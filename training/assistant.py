"""PvP assistant, Python side: the pvppilot client mod (Assistant.java) sends observations, this script answers
with actions from the network.

The observation is the same RAW 249 layout as on the training server, built from the client's world, so the network
input is computed with the training code (features.Sedzia.wejscie_sieci). Differences from training: the target is
already delayed by the client, so only the ping is fed in; positions are relative to a virtual arena center; round time
is capped at 30 s; slots without an item are masked.

  .venv/bin/python -u assistant.py
"""
import argparse
import socket
import struct
import threading
import time
from pathlib import Path

import numpy as np
import torch
import bridge as M
from features import GLOWY, Lag, Sedzia, Stos, akcje_z_indeksow
from exam import wczytaj

KATALOG = Path(__file__).parent
T_MAX = 600                       # 30 s cap on the round-time input
OFFSET_SLOTU = sum(GLOWY[:7])     # first logit of the slot head
NAZWY_SLOTOW = ["weapon/fist", "bow", "golden apples", "food", "fishing rod", "blocks", "lava bucket", "healing", "potion"]


class LagKlienta(Lag):
    """Ping from the real client: the target is already seen delayed (that is how the game shows it), so the opponent view
    is the current observation; only the history of decisions "in flight" and the delays on the network input remain."""

    def widok_przeciwnika(self, obs, swiezi, cel):
        self.hist_obs = obs[None].copy()
        if swiezi.any():
            self.hist_akc[:, swiezi] = 0
        self.widziany = obs[cel]
        return self.widziany

    def widok_gracza(self, j):
        return self.hist_obs[0, j]

    def ustaw_ping(self, ping_ms):
        akc = int(np.clip(np.rint(ping_ms / 100), 0, 2))
        self.akc[:] = 0
        self.widok[:] = 0
        self.akc[0], self.widok[0] = akc, min(akc + 1, 3)


class Wygladzanie:
    """Camera smoothing and "calm mode" for the client.

    Turn speed moves towards the requested one, accelerating by at most PRZYSP deg/tick per tick and braking by HAMUJ;
    the network sees the executed turn on the next tick and corrects the rest. In calm mode (target far or hidden) the
    camera is slower, tiny turns are ignored and jumping is only allowed in front of an obstacle or in lava.
    """

    ZWYKLE = dict(yaw=(20.0, 6.0, 15.0), pitch=(10.0, 5.0, 10.0), strefa=0.0)       # (MAX, ACCEL, BRAKE) °/tick
    SPOKOJ = dict(yaw=(8.0, 3.0, 8.0), pitch=(5.0, 2.0, 5.0), strefa=3.0)
    ODL_SPOKOJU = 6.0
    PRZESZKODA = (M.VOKSELE + 25 + 15 + 2, M.VOKSELE + 25 + 20 + 2)   # foot-level blocks 1 and 2 ahead

    def __init__(self, spokoj=True):
        self.spokoj = spokoj
        self.v = np.zeros(2, np.float32)          # yaw, pitch turn executed on the previous tick
        self.stat = dict(ticki=0, spokoj=0, skoki_wyciete=0)

    def reset(self):
        self.v[:] = 0

    @staticmethod
    def _krok(v, chce, max_, przysp, hamuj):
        cel = float(np.clip(chce, -max_, max_))
        hamowanie = v != 0 and (cel == 0 or np.sign(cel) != np.sign(v) or abs(cel) < abs(v))
        a = hamuj if hamowanie else przysp
        return v + float(np.clip(cel - v, -a, a))

    def __call__(self, akc, obs):
        ja = obs[0]
        spokoj = self.spokoj and (ja[M.ODL] > self.ODL_SPOKOJU or ja[M.WIDZI + 1] < 0.5)
        tryb = self.SPOKOJ if spokoj else self.ZWYKLE
        self.stat["ticki"] += 1
        self.stat["spokoj"] += int(spokoj)
        for k, (i, nazwa) in enumerate(((5, "yaw"), (6, "pitch"))):
            chce = float(akc[i])
            if abs(chce) <= tryb["strefa"]:
                chce = 0.0
            self.v[k] = self._krok(float(self.v[k]), chce, *tryb[nazwa])
            akc[i] = self.v[k]
        if spokoj and akc[2] > 0.5:
            przeszkoda = any(ja[j] > 0.5 for j in self.PRZESZKODA) or ja[M.W_LAWIE] > 0.5
            if not przeszkoda:
                akc[2] = 0.0
                self.stat["skoki_wyciete"] += 1
        return akc


class Walka:
    """State of one fight (like a training round): a referee with one 1v1 "arena" - player 0 = me, player 1 = the target."""

    def __init__(self, rng):
        self.sedzia = Sedzia(1, lag=True, rng=rng)
        self.sedzia.lag = LagKlienta(2, rng)
        self.stos = Stos(2)
        self.poprz = None
        self.ticki = 0
        self.start = time.time()
        self.zrzut = []

    def krok(self, obs, nowa):
        s = self.sedzia
        if nowa or self.poprz is None:
            s.t_ep[:] = 0
            s.bilans[:] = 0
            s.straty[:] = 0
            swieze = np.array([True])
        else:
            stracone = np.maximum(0, self.poprz[:, M.HP] - obs[:, M.HP])
            s.straty += stracone
            s.bilans += stracone[[1, 0]] - stracone
            s.t_ep[0] = min(s.t_ep[0] + 1, T_MAX)
            swieze = np.array([False])
        self.poprz = obs.copy()
        self.ticki += 1
        return s.wejscie_sieci(self.stos, obs, swieze)


def czytaj(s, n):
    buf = bytearray()
    while len(buf) < n:
        k = s.recv(n - len(buf))
        if not k:
            raise ConnectionError("client closed the connection")
        buf += k
    return bytes(buf)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--siec", default=str(KATALOG / "runs" / "pvp7" / "champion_stage6.pt"))
    a.add_argument("--port", type=int, default=8790)
    a.add_argument("--najpewniejsze", action="store_true", help="no sampling (in training the network played with sampling - better)")
    a.add_argument("--dziennik", default=str(KATALOG / "assistant_fights.csv"), help="one line per fight")
    a.add_argument("--surowo", action="store_true", help="no camera smoothing and no calm mode (as in training)")
    a.add_argument("--bez_spokoju", action="store_true", help="camera smoothing on, but no calm mode")
    a.add_argument("--nagrywaj", default="", help="directory: every fight is recorded from the network's eyes to mp4 (pvppilot mod, ffmpeg)")
    a.add_argument("--zrzut", action="store_true", help="save observations and actions of every fight (client_dumps/*.npz)")
    a.add_argument("--scenariusz", default="",
                   help="test without a keyboard: 'seconds:what,...'; what = R / V / B (assistant keys), F2 (screenshot), ODRODZ (respawn after death) "
                        "or /command (chat), e.g. '5:/walcz siekiera siekiera,12:R,14:F2,16:V'")
    args = a.parse_args()
    torch.set_num_threads(2)
    siec, wersja = wczytaj(args.siec)
    opis = f"champion v{wersja}"
    print(f"assistant: loaded network {opis} ({sum(p.numel() for p in siec.parameters()):,} parameters)", flush=True)
    rng = np.random.default_rng()
    dziennik = open(args.dziennik, "a", buffering=1)
    if Path(args.dziennik).stat().st_size == 0:
        dziennik.write("time,seconds,ticks,dealt,taken,my_hp,target_hp,ping_ms,mean_decision_ms\n")
    rozmiar = 4 + 1 + 4 + 2 + 2 * M.RAW * 4
    while True:
        try:
            s = socket.create_connection(("127.0.0.1", args.port), timeout=2)
        except OSError:
            time.sleep(2)
            continue
        s.settimeout(None)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        b = opis.encode()
        zamek = threading.Lock()
        s.sendall(bytes([6]) + struct.pack(">H", len(b)) + b)
        if args.scenariusz:
            threading.Thread(target=scenariusz, args=(s, zamek, args.scenariusz), daemon=True).start()
        print("assistant: connected to Minecraft - waiting for a fight (R = network on, V = fight)", flush=True)
        walka, czasy, ping, wyg = None, [], 0.0, None
        try:
            while True:
                typ = czytaj(s, 1)[0]
                if typ == 11:
                    if args.nagrywaj and walka is not None:
                        with zamek:
                            s.sendall(b"\x03")
                    if walka is not None:
                        zakoncz(walka, czasy, ping, dziennik, args.zrzut)
                    walka = None
                    continue
                if typ != 10:
                    raise ConnectionError(f"unknown message {typ}")
                d = czytaj(s, rozmiar)
                _tick, nowa, ping, maska = struct.unpack("<iBfH", d[:11])
                obs = np.frombuffer(d[11:], dtype="<f4").reshape(2, M.RAW).copy()
                t0 = time.perf_counter()
                if nowa or walka is None:
                    poczatek = walka is None                         # not a mid-fight teleport
                    if walka is not None:
                        zakoncz(walka, czasy, ping, dziennik, args.zrzut)
                    walka, czasy = Walka(rng), []
                    if args.nagrywaj and poczatek:
                        Path(args.nagrywaj).mkdir(parents=True, exist_ok=True)
                        film = str(Path(args.nagrywaj).resolve() / (time.strftime("walka_%H%M%S")
                                   + ("_surowo" if args.surowo else "_gladko") + ".mp4"))
                        b = film.encode()
                        with zamek:
                            s.sendall(b"\x03" + bytes([2]) + struct.pack(">H", len(b)) + b)
                        print(f"assistant: recording -> {film}", flush=True)
                    wyg = None if args.surowo else Wygladzanie(spokoj=not args.bez_spokoju)
                    sloty = ", ".join(n for v, n in enumerate(NAZWY_SLOTOW) if (maska >> v) & 1)
                    print(f"assistant: new fight (ping {ping:.0f} ms; network slots on the hotbar: {sloty})", flush=True)
                walka.sedzia.lag.ustaw_ping(ping)
                X = walka.krok(obs, nowa)
                with torch.no_grad():
                    logity = siec(torch.as_tensor(X[:1]))[0]
                    zakaz = [v for v in range(9) if not (maska >> v) & 1]
                    for v in zakaz:
                        logity[0, OFFSET_SLOTU + 1 + v] = -1e9
                    if args.najpewniejsze:
                        idx = torch.stack([l.argmax(-1) for l in torch.split(logity, siec.glowy, dim=-1)], 1)
                    else:
                        idx = siec.rozklad(logity)[0]
                idx2 = np.zeros((2, len(GLOWY)), np.int64)
                idx2[0] = idx[0].numpy()
                surowe = akcje_z_indeksow(idx2)
                prosi = surowe[0, [2, 5, 6]].copy()                 # before smoothing
                if wyg is not None:
                    if nowa:
                        wyg.reset()
                    surowe[0] = wyg(surowe[0], obs)              # lag history must hold what was actually executed
                walka.sedzia.akcje(surowe)
                akc = surowe[0].copy()
                akc[9] = walka.sedzia.prawy[0]
                with zamek:
                    s.sendall(b"\x01" + struct.pack("<10f", *[float(v) for v in akc]))
                czasy.append(time.perf_counter() - t0)
                if args.zrzut:
                    walka.zrzut.append((obs, X[0].astype(np.float32), akc, ping, prosi))
        except (ConnectionError, OSError) as e:
            print(f"assistant: disconnected ({e}) - waiting for Minecraft", flush=True)
            if walka is not None:
                zakoncz(walka, czasy, ping, dziennik, args.zrzut)
            walka = None
            s.close()
            time.sleep(2)


def scenariusz(s, zamek, tekst):
    t0 = time.time()
    for krok in tekst.split(","):
        t, co = krok.split(":", 1)
        while time.time() - t0 < float(t):
            time.sleep(0.05)
        if co.startswith("/"):
            b = co[1:].encode()
            dane = bytes([4]) + struct.pack(">H", len(b)) + b
        else:
            dane = bytes([7, {"R": 1, "V": 2, "B": 3, "F2": 4, "ODRODZ": 5}[co]])
        with zamek:
            s.sendall(dane)
        print(f"assistant: scenario {float(t):.0f} s: {co}", flush=True)


def zakoncz(walka, czasy, ping, dziennik, zrzut=False):
    s = walka.sedzia
    otrzymane, zadane = float(s.straty[0]), float(s.straty[1])
    sek = time.time() - walka.start
    hp = walka.poprz[:, M.HP] if walka.poprz is not None else [0, 0]
    ms = 1000 * float(np.mean(czasy)) if czasy else 0.0
    print(f"assistant: fight over after {sek:.1f} s ({walka.ticki} ticks): dealt {zadane:.1f}, taken {otrzymane:.1f}, "
          f"HP me {hp[0]:.1f} / target {hp[1]:.1f}, mean decision {ms:.1f} ms", flush=True)
    dziennik.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')},{sek:.1f},{walka.ticki},{zadane:.1f},{otrzymane:.1f},"
                   f"{hp[0]:.1f},{hp[1]:.1f},{ping:.0f},{ms:.1f}\n")
    if zrzut and walka.zrzut:
        kat = KATALOG / "client_dumps"
        kat.mkdir(exist_ok=True)
        o, x, a, p, pr = zip(*walka.zrzut)
        plik = kat / (time.strftime("walka_%Y%m%d_%H%M%S") + f"_{int(time.time() * 1000) % 1000:03d}.npz")
        np.savez_compressed(plik, obs=np.stack(o), X=np.stack(x), akc=np.stack(a), ping=np.array(p), prosi=np.stack(pr))
        print(f"assistant: fight dump -> {plik.name}", flush=True)


if __name__ == "__main__":
    main()
