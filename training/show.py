"""Fights to watch at normal speed, on a separate server (bridge port 25600, game 127.0.0.1:25566).

Arena 0: the latest network vs itself. Arena 1: the network vs the hand-written bot v2.
Arena 2: the network vs bot v2, and after typing /walcz in game — the network vs YOU (1v1).
While a human is in arena 2: the PAUZA file pauses training (smoother play), and the fight is recorded
to recordings/ (state of both players every tick).
Every 30 s it checks whether training saved a newer version (siec_pokaz.pt) and swaps it in. Runs on CPU.

  python show.py --nazwa pvp2 --z_losowaniem
"""
import argparse
import json
import socket
import struct
import threading
import time
from pathlib import Path

import numpy as np
import torch
from bots import BotV2
from features import GLOWY, WEJSCIE, ZESTAWY_ETAP3, ZESTAWY_ETAP5, ZESTAWY_ETAP6, Sedzia, Stos, akcje_z_indeksow
from features import MAX_EPIZOD
import bridge as M
from bridge import BRON, CELUJE, CZLOWIEK, HP, HURT, KUCA, PASEK, PITCH, SPRINT, X, Y, YAW, Z, ZIEMIA, Most
from pad import Pad
from network import Siec, dopasuj, najpewniejsze

AREN = 3
KLATKA_CZLOWIEKA = 2
SLOT_CZLOWIEKA = 2 * KLATKA_CZLOWIEKA + 1               # player from /walcz or the gamepad (instead of bot v2)
ROZSTAW = 48                                            # like PvpBridge.ROZSTAW: arena centers every 48 blocks


def _varint(x):
    b = b""
    while True:
        c = x & 0x7F
        x >>= 7
        b += bytes([c | (0x80 if x else 0)])
        if not x:
            return b


def _czytaj_varint(f):
    w = p = 0
    while True:
        c = f.read(1)[0]
        w |= (c & 0x7F) << p
        p += 7
        if not c & 0x80:
            return w


def ludzie_na_serwerze(port_gry=25566):
    """How many real players are on the server (status query like the client's server list; bots are bot###)."""
    with socket.create_connection(("127.0.0.1", port_gry), timeout=2) as s:
        host = b"127.0.0.1"
        dane = _varint(0) + _varint(777) + _varint(len(host)) + host + struct.pack(">H", port_gry) + _varint(1)
        s.sendall(_varint(len(dane)) + dane + _varint(1) + _varint(0))
        f = s.makefile("rb")
        _czytaj_varint(f), _czytaj_varint(f)
        tekst = f.read(_czytaj_varint(f)).decode()
    gracze = json.loads(tekst).get("players", {})
    # names in the status may be hidden ("Anonymous Player"), so count: everyone online minus the arena bots
    # (a bot replaced by a human in /walcz is still online, "parked" above the arena)
    return max(0, gracze.get("online", 0) - 2 * AREN)


class Obserwator(threading.Thread):
    """Every 5 s checks in the background whether a human is on the server (the game loop does not wait for the query)."""

    def __init__(self, port_gry):
        super().__init__(daemon=True)
        self.port_gry, self.ludzie = port_gry, 0

    def run(self):
        while True:
            try:
                self.ludzie = ludzie_na_serwerze(self.port_gry)
            except Exception:
                pass
            time.sleep(5)


def stan_dla_strony(obs, ja, wynik, czas, komunikat, gra_mozliwa, mysli=None, czasy=None, ping=None):
    gracze = []
    for i, o in enumerate(obs):
        gracze.append([round(float(o[X]) + ROZSTAW * (i // 2), 3), round(float(o[Y]), 3), round(float(o[Z]), 3),
                       round(float(o[YAW]), 2), round(float(o[PITCH]), 2), round(float(o[HP]), 2),
                       round(float(o[PASEK]), 2), int(o[HURT] > 0), int(o[ZIEMIA] > 0.5), int(o[KUCA] > 0.5),
                       int(o[SPRINT] > 0.5), int(o[CELUJE] > 0.5), int(o[BRON]),
                       # 13+: items (panel: icons, shield, bow, arrows, bobber, play-by-play)
                       int(o[M.TRZYMA]), int(o[M.UZYWA] > 0.5), int(o[M.UZYCIE]), int(o[M.BLOKUJE] > 0.5), int(o[M.HAK]),
                       round(float(o[M.SPLAWIK_X]) + ROZSTAW * (i // 2), 2), round(float(o[M.SPLAWIK_Y]), 2), round(float(o[M.SPLAWIK_Z]), 2),
                       round(float(o[M.STRZALA_X]) + ROZSTAW * (i // 2), 2), round(float(o[M.STRZALA_Y]), 2), round(float(o[M.STRZALA_Z]), 2),
                       int(o[M.STRZALA_JEST] > 0.5), int(o[M.STRZALY]), int(o[M.JABLKA] + o[M.STEKI]), int(o[M.TRAF_STRZALA]),
                       int(o[M.ZABLOKOWANE]), int(o[M.POSTAWIONE]), round(float(o[M.ABSORPCJA]), 1), int(o[M.ZESTAW])])
    role = ["NET", "NET", "NET", "BOT", "NET", "YOU" if ja >= 0 else "BOT"]
    return dict(typ="stan", gracze=gracze, role=role, ja=ja, wynik=wynik, czas=czas, komunikat=komunikat,
                gra_mozliwa=gra_mozliwa, klatki=AREN, rozstaw=ROZSTAW, mysli=mysli,
                czasy=czasy, ping=ping)       # czasy: round fraction in every arena; ping: [actions, view] in ticks per player


def mysli_sieci(siec, logity, wartosc, h):
    """Live x-ray for arena 0 (network vs network): action probabilities in every head, value estimate
    and activity of the last layer (2048 neurons averaged down to 256) of player 0."""
    glowy = []
    for i in (0, 1):
        glowy.append([[round(float(v), 3) for v in torch.softmax(l[i], -1)]
                      for l in torch.split(logity, siec.glowy, dim=-1)])
    neurony = h[0].reshape(256, -1).mean(1)
    return dict(glowy=glowy, wartosc=[round(float(wartosc[0]), 3), round(float(wartosc[1]), 3)],
                neurony=[round(float(v), 3) for v in neurony])


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp2")
    a.add_argument("--port", type=int, default=25600)
    a.add_argument("--port_gry", type=int, default=25566, help="game port of the show server (to check whether someone is watching)")
    a.add_argument("--kamera", action="store_true", help="a client with the pvppilot mod watches through player A's eyes in arena 0 (full loadout) and records rounds")
    a.add_argument("--pilot_lag", default="1,1", help="pilot delay as seen by the network: actions,view (measured ~1 tick)")
    a.add_argument("--pilot", default="", help="the network drives a client with the pvppilot mod; value = weapons for /walcz, e.g. 'siekiera siekiera'")
    a.add_argument("--przedmioty", action="store_true", help="stage-5 loadouts (bow, food, shield, fishing rod, blocks)")
    a.add_argument("--lag", action="store_true", help="network with ping as in training (features.Lag)")
    a.add_argument("--etap6", action="store_true", help="stage 6: terrain, lava, potions (ZESTAWY_ETAP6)")
    a.add_argument("--z_losowaniem", dest="bez_losowania", action="store_false",
                   help="sample moves as in training (default: always the most likely move)")
    args = a.parse_args()
    torch.set_num_threads(1)

    katalog = Path(__file__).parent
    plik = katalog / "runs" / args.nazwa / "siec_pokaz.pt"
    pauza = katalog / "PAUZA"
    nagrania = katalog / "recordings"
    nagrania.mkdir(exist_ok=True)
    siec, mtime, ostatnie_spr = None, 0.0, 0.0
    m = Most(AREN, 20.0, port=args.port)
    _, obs = m.obserwacje()
    n = m.n
    jest_bot = np.zeros(n, bool)
    jest_bot[[3, 5]] = True                              # second players in arenas 1 and 2 are bot v2
    zestawy = ZESTAWY_ETAP6 if args.etap6 else ZESTAWY_ETAP5 if args.przedmioty else ZESTAWY_ETAP3
    sedzia = Sedzia(AREN, zestawy=zestawy, lag=args.lag, teren=(0.2, 0.3) if args.etap6 else None)
    bot, stos = BotV2(n), Stos(n)
    if sedzia.lag is not None:
        sedzia.lag.wylacz = jest_bot.copy()
        sedzia.lag.staly = {2 * KLATKA_CZLOWIEKA: (0, 1)}   # network vs human: like a local player
        if args.pilot:                                       # pilot: real client delay ~2 ticks
            sedzia.lag.wylacz[SLOT_CZLOWIEKA] = False
            sedzia.lag.staly[SLOT_CZLOWIEKA] = tuple(int(v) for v in args.pilot_lag.split(","))
            # fair: the pilot's opponent (network on the server) gets the same delay as the real client
            sedzia.lag.staly[2 * KLATKA_CZLOWIEKA] = sedzia.lag.staly[SLOT_CZLOWIEKA]
    X = sedzia.wejscie_sieci(stos, obs)
    nagranie, plik_nagrania = [], None
    pad = Pad(port_http=None, sterowanie=False)          # gamepad play disabled; WS 8771 stays for the panel
    pilot, nagr, nr, ost_walcz, zrzut_X = None, None, 0, 0.0, []
    katalog_wideo = katalog / "videos"
    kam_nagr, kam_ost, kam_nr = None, 0.0, 0
    if args.kamera:
        from pilot import PilotKlient
        katalog_wideo.mkdir(exist_ok=True)
        pilot_kam = PilotKlient([])
        sedzia_ustaw_klatke0 = True
        nazwy_botow = [f"bot{i:03d}" for i in range(2 * AREN)]
    if args.pilot:
        from pilot import PilotKlient
        katalog_wideo.mkdir(exist_ok=True)
        haslo = (katalog.parent / "server-show" / "pvpmost_haslo.txt").read_text().strip()
        pilot = PilotKlient([f"walcz {args.pilot}"])          # the pilot types the password itself after joining
        dziennik_pilota = open(katalog / "videos" / "dziennik_pilota.csv", "a", buffering=1)
    probka, ostatnia_probka = [], time.time()           # situations from arenas 0–1 for the panel's x-ray
    pad_X, pad_A, plik_pad = [], [], None
    wynik = dict(w=0, p=0, r=0)
    komunikat = dict(id=0, tekst="")
    print("show: live data for the panel on port 8771 (no control)", flush=True)
    obserwator = Obserwator(args.port_gry)
    obserwator.start()
    t_pomiaru, ticki = time.time(), 0
    ostatni_tick, najdluzsza, zaciecia = time.time(), 0.0, 0
    print("show: connected, waiting for the first saved network version", flush=True)

    while True:
        czlowiek_jest = obs[2 * KLATKA_CZLOWIEKA + 1, CZLOWIEK] > 0.5
        ktos_patrzy = obserwator.ludzie > 0
        # load a new network version only when nobody is fighting (loading with memory under pressure can
        # stall everything for a while); the network is built once, later only the weights are swapped
        # with a spectator on the server no new versions are loaded (loading with full memory causes stutter)
        if not czlowiek_jest and not (ktos_patrzy and siec is not None) and time.time() - ostatnie_spr > 30:
            ostatnie_spr = time.time()
            if plik.exists() and plik.stat().st_mtime != mtime:
                mtime = plik.stat().st_mtime
                z = torch.load(plik, map_location="cpu", weights_only=False)
                if siec is None:
                    u = z["ustawienia"]
                    siec = Siec(WEJSCIE, GLOWY, u["szer"], u["bloki"]).eval()
                with torch.no_grad():
                    siec.load_state_dict(dopasuj({k: v.float() for k, v in z["wagi"].items()}, WEJSCIE))
                print(f"show: loaded the version from update {z['aktualizacja']}", flush=True)
        if czlowiek_jest and not pauza.exists():
            pauza.touch()
            plik_nagrania = nagrania / time.strftime("walka_%Y%m%d_%H%M%S.npy")
            print(f"show: human in the arena — training paused, recording to {plik_nagrania.name}", flush=True)
        if not czlowiek_jest and pauza.exists():
            pauza.unlink(missing_ok=True)
            if nagranie:
                np.save(plik_nagrania, np.stack(nagranie))
                print(f"show: human left — training resumes, saved {len(nagranie)} ticks", flush=True)
            nagranie = []
        if czlowiek_jest:
            k = 2 * KLATKA_CZLOWIEKA
            nagranie.append(obs[k:k + 2].copy())        # state of the network and the human this tick
            if len(nagranie) % 1200 == 0:
                np.save(plik_nagrania, np.stack(nagranie))

        idx = bot(obs)
        mysli = None
        if siec is not None:
            with torch.no_grad():
                h = siec.ukryte(torch.as_tensor(X))
                logity, wartosc = siec.polityka(h), siec.wartosc(h).squeeze(-1)
                idx_s = (najpewniejsze(siec, logity) if args.bez_losowania else siec.rozklad(logity)[0]).numpy()
                mysli = mysli_sieci(siec, logity, wartosc, h)
            idx[~jest_bot] = idx_s[~jest_bot]
            if pilot is not None and pilot.polaczony and czlowiek_jest:   # the network drives a real client in arena 2
                idx[SLOT_CZLOWIEKA] = idx_s[SLOT_CZLOWIEKA]
            probka.extend(X[:3].astype(np.float16))         # network players in arenas 0 and 1
            del probka[:-6000]
            if time.time() - ostatnia_probka > 60:
                ostatnia_probka = time.time()
                np.save(katalog / "probka_X.npy", np.stack(probka))
        gra_padem = pad.aktywny() and not czlowiek_jest
        if gra_padem:
            if plik_pad is None:
                plik_pad = nagrania / time.strftime("pad_%Y%m%d_%H%M%S.npz")
                wynik = dict(w=0, p=0, r=0)
                print(f"show: gamepad player joined, recording to {plik_pad.name}", flush=True)
            idx[SLOT_CZLOWIEKA] = pad.akcja(obs[SLOT_CZLOWIEKA])
            if not sedzia.smiec[KLATKA_CZLOWIEKA]:      # lesson: network input in this situation + the human's move
                pad_X.append(X[SLOT_CZLOWIEKA].astype(np.float16))
                pad_A.append(idx[SLOT_CZLOWIEKA].astype(np.int8))
        if (not gra_padem and plik_pad is not None and pad_X) or (pad_X and len(pad_X) % 2400 == 0):
            np.savez_compressed(plik_pad, X=np.stack(pad_X), A=np.stack(pad_A))
            if not gra_padem:
                print(f"show: gamepad player left, saved {len(pad_X)} lesson ticks", flush=True)
        if not gra_padem and plik_pad is not None:
            plik_pad, pad_X, pad_A = None, [], []
        surowe = akcje_z_indeksow(idx)
        wys = sedzia.akcje(surowe)
        if pilot is not None and pilot.polaczony and czlowiek_jest:
            # the decision goes to the client immediately (the real client -> server path adds the ~2 tick delay); the
            # network sees this as ping (lag.staly), so it knows its last turns are still "in flight"
            a = surowe[SLOT_CZLOWIEKA].copy()
            a[9] = sedzia.prawy[SLOT_CZLOWIEKA]
            pilot.akcja(a)
            zrzut_X.append(X[[SLOT_CZLOWIEKA - 1, SLOT_CZLOWIEKA]].astype(np.float32))   # diagnostics: inputs of the network and the pilot
            if len(zrzut_X) % 600 == 0:
                np.save(katalog / "videos" / "X_pilot.npy", np.stack(zrzut_X))
            ps = SLOT_CZLOWIEKA                              # diagnostic log: what the pilot network sees and picks
            on_ = obs[ps ^ 1]
            blad_ = ((np.degrees(np.arctan2(-(on_[M.X] - obs[ps, M.X]), on_[M.Z] - obs[ps, M.Z])) - obs[ps, M.YAW] + 180) % 360) - 180
            dziennik_pilota.write(f"{int(sedzia.t_ep[KLATKA_CZLOWIEKA])},{obs[ps, M.YAW]:.1f},{blad_:.1f},{a[5]:.0f},{a[4]:.0f},"
                                  f"{obs[ps, M.CELUJE]:.0f},{obs[ps, M.PASEK]:.2f}\n")
        sedzia.wyslij(m, wys)
        _, obs_po = m.obserwacje()
        if czlowiek_jest:
            # real player: the server does not know its velocity (the client computes movement) -> velocity from the
            # position difference, like for fake players (otherwise the observed velocity was ~4× too small)
            ps = SLOT_CZLOWIEKA
            krok_ = obs_po[ps, [M.X, M.Y, M.Z]] - obs[ps, [M.X, M.Y, M.Z]]
            if np.abs(krok_).max() < 1.5:                    # no teleport at round start
                obs_po = obs_po.copy()
                obs_po[ps, [M.VX, M.VY, M.VZ]] = krok_
        w = sedzia.krok(obs, obs_po)
        if args.kamera:                                      # arena 0: full loadout, camera through player A's eyes
            if w["koniec"][0] or sedzia_ustaw_klatke0:
                sedzia_ustaw_klatke0 = False
                sedzia.nast_bron[0:2] = [(1, 2), (2, 1), (1, 1), (2, 2)][np.random.randint(4)]
                sedzia.nast_maska[0:2] = 31
            if pilot_kam.polaczony and time.time() - kam_ost > 10:
                kam_ost = time.time()
                pilot_kam.kamera(nazwy_botow[0])
            if pilot_kam.polaczony:
                if kam_nagr is not None and not w["swieze"][0]:
                    for kl, pole in (("strzaly", M.STRZALY),):
                        kam_nagr[kl] += float(max(0.0, obs[0, pole] - obs_po[0, pole]))
                    kam_nagr["jedzenie"] += float(max(0.0, obs[0, M.JABLKA] + obs[0, M.STEKI] - obs_po[0, M.JABLKA] - obs_po[0, M.STEKI]))
                    kam_nagr["trafienia_luk"] = int(obs_po[0, M.TRAF_STRZALA])
                    kam_nagr["zablokowane"] = int(obs_po[0, M.ZABLOKOWANE])
                    kam_nagr["bloki"] = int(obs_po[0, M.POSTAWIONE])
                    kam_nagr["haki"] += int(obs_po[0, M.HAK] > 1.5 and obs[0, M.HAK] < 1.5)
                    kam_nagr["ticki"] += 1
                if kam_nagr is not None and w["koniec"][0]:
                    pilot_kam.stop()
                    zg, b = w["zginal"], float(sedzia.bilans[0])
                    kam_nagr["wynik"] = ("win (knockout)" if zg[1] else "loss (knockout)" if zg[0] else
                                         "draw" if w["remis"][0] else "win on points" if b > 0 else "loss on points")
                    kam_nagr["bilans"] = round(b, 1)
                    (katalog_wideo / f"{kam_nagr['plik']}.json").write_text(json.dumps(kam_nagr, ensure_ascii=False))
                    print(f"camera: round {kam_nagr['plik']}: {kam_nagr}", flush=True)
                    kam_nagr = None
                if kam_nagr is None and w["swieze"][0]:
                    kam_nr += 1
                    nazwa = f"kamera_{time.strftime('%H%M%S')}_{kam_nr:03d}"
                    pilot_kam.nagrywaj(katalog_wideo / f"{nazwa}.mp4")
                    kam_nagr = dict(plik=nazwa, strzaly=0.0, jedzenie=0.0, haki=0, ticki=0,
                                    bron_A=int(obs_po[0, M.BRON]), bron_B=int(obs_po[1, M.BRON]))
        k = KLATKA_CZLOWIEKA
        if pilot is not None and pilot.polaczony and not czlowiek_jest and time.time() - ost_walcz > 8:
            ost_walcz = time.time()                          # pilot not in the arena yet: repeat /walcz
            pilot.komenda(f"walcz {args.pilot}")
        if pilot is not None and pilot.polaczony and czlowiek_jest:  # recording the pilot's rounds (from round start)
            ps = SLOT_CZLOWIEKA
            if nagr is not None and not w["swieze"][k]:
                for kl, pole in (("strzaly", M.STRZALY), ("jedzenie", M.JABLKA), ("steki", M.STEKI)):
                    nagr[kl] += float(max(0.0, obs[ps, pole] - obs_po[ps, pole]))
                nagr["trafienia_luk"] = int(obs_po[ps, M.TRAF_STRZALA])
                nagr["zablokowane"] = int(obs_po[ps, M.ZABLOKOWANE])
                nagr["bloki"] = int(obs_po[ps, M.POSTAWIONE])
                nagr["ticki"] += 1
            if nagr is not None and w["koniec"][k]:
                pilot.stop()
                zg, b = w["zginal"], float(sedzia.bilans[ps])
                nagr["wynik"] = ("win (knockout)" if zg[ps ^ 1] else "loss (knockout)" if zg[ps] else
                                 "draw" if w["remis"][k] else "win on points" if b > 0 else "loss on points")
                nagr["bilans"] = round(b, 1)
                (katalog_wideo / f"{nagr['plik']}.json").write_text(json.dumps(nagr, ensure_ascii=False))
                print(f"pilot: round {nagr['plik']}: {nagr['wynik']}, {nagr}", flush=True)
                nagr = None
            if nagr is None and w["swieze"][k]:
                nr += 1
                nazwa = f"runda_{time.strftime('%H%M%S')}_{nr:03d}"
                pilot.nagrywaj(katalog_wideo / f"{nazwa}.mp4")
                nagr = dict(plik=nazwa, strzaly=0.0, jedzenie=0.0, steki=0.0, ticki=0, bron_pilota=int(obs_po[ps, M.BRON]))
        if gra_padem and w["koniec"][k]:                  # round result from the human's view (player 2k+1)
            zg, b = w["zginal"], sedzia.bilans[2 * k]
            if zg[2 * k + 1] or (w["na_punkty"][k] and b > 0):
                wynik["p"] += 1
                tekst = "Loss — the network took you down" if zg[2 * k + 1] else "Loss on points"
            elif zg[2 * k] or (w["na_punkty"][k] and b < 0):
                wynik["w"] += 1
                tekst = "WIN! You took the network down" if zg[2 * k] else "Win on points"
            else:
                wynik["r"] += 1
                tekst = "Draw — time is up"
            komunikat = dict(id=komunikat["id"] + 1, tekst=tekst)
        bot.nowa_walka(np.repeat(w["swieze"], 2))
        X = sedzia.wejscie_sieci(stos, obs_po, w["swieze"])
        obs = obs_po
        pad.rozeslij(stan_dla_strony(obs, SLOT_CZLOWIEKA if gra_padem else -1, wynik,
                                     float(sedzia.t_ep[k]) / MAX_EPIZOD, komunikat, not czlowiek_jest, mysli,
                                     [round(float(t) / MAX_EPIZOD, 4) for t in sedzia.t_ep],
                                     None if sedzia.lag is None else [[int(a), int(v)] for a, v in
                                                                      zip(sedzia.lag.akc, sedzia.lag.widok)]))

        ticki += 1
        teraz = time.time()
        przerwa = teraz - ostatni_tick
        ostatni_tick = teraz
        najdluzsza = max(najdluzsza, przerwa)
        zaciecia += przerwa > 0.1                      # a tick late by more than 2 normal intervals
        if teraz - t_pomiaru > 30:
            print(f"show {time.strftime('%H:%M:%S')}: {ticki / (teraz - t_pomiaru):.1f} ticks/s (target 20), "
                  f"longest gap {najdluzsza * 1000:.0f} ms, stalls >100 ms: {zaciecia}"
                  f"{' [training paused]' if pauza.exists() else ''}", flush=True)
            t_pomiaru, ticki, najdluzsza, zaciecia = teraz, 0, 0.0, 0


if __name__ == "__main__":
    try:
        main()
    finally:                                             # the show died: training must not stay paused
        (Path(__file__).parent / "PAUZA").unlink(missing_ok=True)
