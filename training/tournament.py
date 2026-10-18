"""Our network (even players, siec_pokaz.pt) vs third-party opponents (odd players): FlyPvP, the pvp-bot-fabric
port, or our bot v2. Our side plays with ping; the opponent without, unless --obcy_lag.

  python tournament.py --przeciwnik pvpbot --sekundy 200 --bronie 2,2 --maska 31
  python tournament.py --przeciwnik fly --sekundy 600      # needs FlyPvP in ../third_party/flypvp

FlyPvP gets the sense vector of its own simulator, rebuilt from our observations (as its pvp/live.py does).
"""
import argparse
import json
import queue
import socket
import struct
import sys
import threading
import time
from pathlib import Path

import numpy as np
import torch
import bridge as M
from features import Sedzia, Stos, akcje_z_indeksow
from exam import wczytaj
from bridge import Most

KAT = Path(__file__).parent
OBCE = KAT.parent / "third_party"
SCIANA = 7.5            # arena interior from -7 to +7 blocks around the center (+0.5) -> wall 7.5 from the center


class FlyPvP:
    """FlyPvP brain driving the odd players."""

    def __init__(self, n, gracze):
        sys.path.insert(0, str(OBCE / "flypvp"))
        from pvp import sim
        from pvp.model import PvPFly, load_circuit, one_hot_actions
        self.sim, self.one_hot = sim, one_hot_actions
        circuit, _ = load_circuit()
        blob = torch.load(OBCE / "flypvp/brain/pvp/checkpoint.pt", map_location="cpu", weights_only=True)
        self.model = PvPFly(circuit, blob.get("variant", "fly"))
        self.model.load_state_dict(blob["state"])
        self.model.eval()
        self.W = self.model.weight_matrix().detach()
        self.gracze = gracze
        k = len(gracze)
        self.duel = sim.Duel(k, 0)
        self.hidden = self.model.initial(k)
        self.prev = torch.zeros(k, sum(sim.HEADS))
        self.jego_mach = np.full(k, 100.0)            # ticks since the opponent's swing (drop of its attack bar)
        self.od_mojego = np.full(k, 100.0)
        self.od_jego = np.full(k, 100.0)
        self.ost_nietyk = np.zeros((k, 2))
        self.ost_pasek_on = np.ones(k)

    def nowa_walka(self, maska_graczy):
        m = maska_graczy[self.gracze]
        if m.any():
            mt = torch.as_tensor(m)
            self.hidden[mt] = 0
            self.prev[mt] = 0
            self.jego_mach[m] = self.od_mojego[m] = self.od_jego[m] = 100.0
            self.ost_nietyk[m] = 0
            self.ost_pasek_on[m] = 1

    @torch.no_grad()
    def __call__(self, obs, t_ep, widziany=None):
        sim, d, g = self.sim, self.duel, self.gracze
        ja, on = obs[g], (obs[g ^ 1] if widziany is None else widziany[g])   # with ping: the opponent as it was a moment ago
        k = len(g)
        poz = lambda o: torch.tensor(o[:, [M.X, M.Y, M.Z]], dtype=torch.float32)
        d.pos[:, 0], d.pos[:, 1] = poz(ja), poz(on)
        d.vel[:, 0] = torch.tensor(ja[:, [M.VX, M.VY, M.VZ]], dtype=torch.float32)
        d.vel[:, 1] = torch.tensor(on[:, [M.VX, M.VY, M.VZ]], dtype=torch.float32)
        d.yaw[:, 0], d.yaw[:, 1] = torch.tensor(ja[:, M.YAW]), torch.tensor(on[:, M.YAW])
        d.health[:, 0], d.health[:, 1] = torch.tensor(ja[:, M.HP]), torch.tensor(on[:, M.HP])
        d.on_ground[:, 0], d.on_ground[:, 1] = torch.tensor(ja[:, M.ZIEMIA] > .5), torch.tensor(on[:, M.ZIEMIA] > .5)
        d.sprinting[:, 0], d.sprinting[:, 1] = torch.tensor(ja[:, M.SPRINT] > .5), torch.tensor(on[:, M.SPRINT] > .5)
        # the opponent's swing shows up as a reset of its attack bar
        mach = on[:, M.PASEK] < self.ost_pasek_on - 0.05
        self.jego_mach = np.where(mach, 0.0, self.jego_mach + 1)
        self.ost_pasek_on = on[:, M.PASEK].copy()
        d.swing[:, 0] = torch.tensor(ja[:, M.PASEK] * sim.COOLDOWN - .5, dtype=torch.float32)
        d.swing[:, 1] = torch.tensor(self.jego_mach, dtype=torch.float32)
        nietyk = np.stack([ja[:, M.NIETYK], on[:, M.NIETYK]], 1)
        self.od_mojego = np.where(nietyk[:, 1] > self.ost_nietyk[:, 1], 0.0, self.od_mojego)
        self.od_jego = np.where(nietyk[:, 0] > self.ost_nietyk[:, 0], 0.0, self.od_jego)
        self.ost_nietyk = nietyk
        d.hurt[:, 0], d.hurt[:, 1] = torch.tensor(nietyk[:, 0]), torch.tensor(nietyk[:, 1])
        d.since_hit[:, 0], d.since_hit[:, 1] = torch.tensor(self.od_mojego), torch.tensor(self.od_jego)
        d.tick[:] = torch.tensor(t_ep, dtype=torch.float32)
        zmysly = d.senses(0)
        # walls: real distance in blocks (its feature = distance / 12)
        x, z = ja[:, M.X], ja[:, M.Z]
        for kol, wart in zip((30, 31, 32, 33), (SCIANA - x, SCIANA + x, SCIANA - z, SCIANA + z)):
            zmysly[:, kol] = torch.tensor(wart / sim.ARENA, dtype=torch.float32)
        self.od_mojego += 1
        self.od_jego += 1
        akcja, _, _, _, self.hidden, _ = self.model(zmysly, self.prev, self.hidden, greedy=True, W=self.W)
        self.prev = self.one_hot(akcja)
        a = akcja.numpy()
        out = np.zeros((k, 10), np.float32)
        out[:, 0] = np.array(sim.FORWARD, np.float32)[a[:, 0]]
        out[:, 1] = np.array(sim.STRAFE, np.float32)[a[:, 1]]         # +1 = left, same as ours (Carpet)
        out[:, 3] = a[:, 2]
        out[:, 2] = a[:, 3]
        out[:, 4] = a[:, 4]                                            # attack (the server computes the crosshair)
        out[:, 5] = np.array(sim.TURNS, np.float32)[a[:, 5]]
        poziom = np.hypot(on[:, M.X] - ja[:, M.X], on[:, M.Z] - ja[:, M.Z])
        chce = -np.degrees(np.arctan2(on[:, M.Y] + sim.HEIGHT / 2 - (ja[:, M.Y] + sim.EYE), np.maximum(poziom, 1e-3)))
        out[:, 6] = np.clip(chce - ja[:, M.PITCH], -90, 90)
        return out


class V2:
    """Our rule-based bot v2 (reference)."""

    def __init__(self, n, gracze):
        from bots import BotV2
        self.bot, self.gracze = BotV2(n), gracze

    def nowa_walka(self, maska_graczy):
        self.bot.nowa_walka(maska_graczy)

    def __call__(self, obs, t_ep, widziany=None):
        idx = self.bot(obs)
        return akcje_z_indeksow(idx)[self.gracze]


class Rcon:
    """Server console (RCON, 127.0.0.1 only). Commands go from a separate thread: the server runs them between ticks,
    and in lockstep mode a tick waits for our actions - waiting for the reply in the main loop would deadlock both."""

    def __init__(self, port, haslo):
        self.s = socket.create_connection(("127.0.0.1", port))
        self.id = 0
        self._wyslij(3, haslo)
        if self._odbierz()[0] == -1:
            raise RuntimeError("RCON: wrong password")
        self.kolejka = queue.Queue()
        self.odpowiedzi = []
        threading.Thread(target=self._petla, daemon=True).start()

    def _wyslij(self, typ, tekst):
        self.id += 1
        dane = struct.pack("<ii", self.id, typ) + tekst.encode() + b"\0\0"
        self.s.sendall(struct.pack("<i", len(dane)) + dane)

    def _odbierz(self):
        dl = struct.unpack("<i", self._pelne(4))[0]
        dane = self._pelne(dl)
        rid, typ = struct.unpack("<ii", dane[:8])
        return rid, dane[8:-2].decode(errors="replace")

    def _pelne(self, k):
        b = b""
        while len(b) < k:
            c = self.s.recv(k - len(b))
            if not c:
                raise ConnectionError("RCON closed")
            b += c
        return b

    def _petla(self):
        while True:
            cmd = self.kolejka.get()
            self._wyslij(2, cmd)
            self.odpowiedzi.append((cmd, self._odbierz()[1]))

    def komenda(self, tekst):
        self.kolejka.put(tekst)


class PvpBot:
    """pvp-bot-fabric (port in ../third_party/pvpbot26) driving the given players server-side over RCON.
    Python sends NaN actions for them. Settings are the author's defaults except ranged-retreat and target picking;
    the target is ordered after every arena reset. cele() returns each player's target index.
    """

    def __init__(self, n, gracze, port_rcon=25577, haslo_z=None, cele=None):
        haslo = Path(haslo_z or KAT.parent / "server3" / "rcon_haslo.txt").read_text().strip()
        self.rcon, self.gracze = Rcon(port_rcon, haslo), np.asarray(gracze)
        self.cele = cele or (lambda: np.arange(n) ^ 1)
        for k, v in [("bot-leave-on-death", "false"), ("clear-on-remove", "false"), ("auto-mend", "false"),
                     ("idle", "false"), ("drop-armor", "false"), ("drop-weapon", "false"),
                     # the default ranged-retreat makes it back off with a bow forever in a small arena (0 damage)
                     ("ranged-retreat", "false"),
                     # 1v3: enemies do not pick targets themselves and do not take revenge on each other (stray lava/arrow)
                     ("auto-target", "false"), ("revenge", "false"), ("target-bots", "false"), ("friendly-fire", "false")]:
            self.rcon.komenda(f"pvpbot settings {k} {v}")
        c = self.cele()
        for i in self.gracze:
            self.rcon.komenda(f"pvpbot adopt bot{i:03d}")
            self.rcon.komenda(f"pvpbot bot-management attack bot{i:03d} bot{c[i]:03d}")

    def nowa_walka(self, maska_graczy):
        c = self.cele()
        for i in self.gracze[maska_graczy[self.gracze]]:
            self.rcon.komenda(f"pvpbot bot-management attack bot{i:03d} bot{c[i]:03d}")

    def __call__(self, obs, t_ep, widziany=None):
        return np.full((len(self.gracze), 10), np.nan, np.float32)


PRZECIWNICY = {"fly": FlyPvP, "v2": V2, "pvpbot": PvpBot}


@torch.no_grad()
def turniej(args):
    m = Most(args.areny, 10000, port=args.port)
    n = m.n
    _, obs = m.obserwacje()
    nasi = np.arange(n) % 2 == 0
    obcy_idx = np.where(~nasi)[0]
    bronie = tuple(int(b) for b in args.bronie.split(","))
    sed, stos = Sedzia(args.areny, zestawy=[(bronie, args.maska, 1.0)], lag=True), Stos(n)
    sed.lag.wylacz[~nasi] = not args.obcy_lag        # by default the opponent has no ping
    if args.bez_lagu:
        sed.lag.wylacz[:] = True
    siec, akt = wczytaj(KAT / "runs" / args.nazwa / "siec_pokaz.pt")
    obcy = PRZECIWNICY[args.przeciwnik](n, obcy_idx)
    X = sed.wejscie_sieci(stos, obs)
    W = P = R = 0
    zadane = otrzymane = 0.0
    czasy = []
    t0 = time.time()
    while time.time() - t0 < args.sekundy:
        xt = torch.as_tensor(X[nasi])
        idx = np.zeros((n, 9), np.int64)
        idx[nasi] = siec.rozklad(siec(xt)[0])[0].numpy()
        akc = akcje_z_indeksow(idx)
        akc[~nasi] = obcy(obs, np.repeat(sed.t_ep, 2)[~nasi], sed.lag.widziany if args.obcy_lag else None)
        m.akcje(sed.akcje(akc), sed.resety(), sed.bronie(), sed.przedmioty())
        _, po = m.obserwacje()
        w = sed.krok(obs, po)
        sw = np.repeat(w["swieze"], 2)
        straty = np.maximum(0, obs[:, M.HP] - po[:, M.HP]) * ~sw
        zadane += float(straty[~nasi].sum())
        otrzymane += float(straty[nasi].sum())
        k, zg, b = w["koniec"], w["zginal"], sed.bilans[0::2]
        W += int((k & (zg[1::2] | (w["na_punkty"] & (b > 0)))).sum())
        P += int((k & (zg[0::2] | (w["na_punkty"] & (b < 0)))).sum())
        R += int((k & w["remis"]).sum())
        czasy += list(sed.t_ep[k] / 20) if k.any() else []
        obcy.nowa_walka(sw)
        X = sed.wejscie_sieci(stos, po, w["swieze"])
        obs = po
    m.zamknij()
    rund = max(1, W + P + R)
    return dict(przeciwnik=args.przeciwnik, wersja=akt, bronie=bronie, maska=args.maska, nasz_ping=not args.bez_lagu,
                obcy_ping=args.obcy_lag and not args.bez_lagu,
                wygrane=W, przegrane=P, remisy=R, proc=round(100 * W / rund, 1),
                zadane_na_runde=round(zadane / rund, 1), otrzymane_na_runde=round(otrzymane / rund, 1),
                sredni_czas_s=round(float(np.mean(czasy)), 1) if czasy else None)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--przeciwnik", default="fly", choices=list(PRZECIWNICY))
    a.add_argument("--nazwa", default="pvp6")
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--areny", type=int, default=16)
    a.add_argument("--sekundy", type=float, default=300)
    a.add_argument("--bronie", default="1,1", help="ours,opponent's: 0 fists, 1 sword, 2 axe")
    a.add_argument("--maska", type=int, default=0, help="items: 0 none, 31 full loadout")
    a.add_argument("--bez_lagu", action="store_true", help="nobody has ping")
    a.add_argument("--obcy_lag", action="store_true", help="opponent also with ping (same distribution as our network)")
    args = a.parse_args()
    torch.set_num_threads(3)
    r = turniej(args)
    print(json.dumps(r, ensure_ascii=False), flush=True)
    plik = KAT / "runs" / args.nazwa / "turniej_obcy.json"
    wszystkie = json.loads(plik.read_text()) if plik.exists() else []
    wszystkie.append({"czas": time.time(), **r})
    plik.write_text(json.dumps(wszystkie, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
