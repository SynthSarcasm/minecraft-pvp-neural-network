"""Test arena for the assistant on server 3 at 20 tick/s: bot v2 vs a real client that joined with
/walcz siekiera siekiera. Each round is appended to client_arena.csv, labelled with client_arena_label.txt.

  .venv/bin/python -u client_arena.py     # then: ./gradlew runClient -Pserwer=127.0.0.1:25568
"""
import argparse
import time
from pathlib import Path

import numpy as np
import bridge as M
from bots import BotV2
from features import Sedzia, akcje_z_indeksow
from bridge import Most

KATALOG = Path(__file__).parent


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--wyniki", default=str(KATALOG / "client_arena.csv"))
    a.add_argument("--teren", action="store_true", help="random terrain as in stage 6 (bot v2 can get stuck in a pit - 0:0 rounds)")
    args = a.parse_args()
    m = Most(3, 20.0, port=args.port)
    _, obs = m.obserwacje()
    n = m.n
    sedzia = Sedzia(3, teren=(0.2, 0.3) if args.teren else None, aktywne=[False, False, True])   # the weapon in the human's arena is chosen by /walcz
    bot = BotV2(n)
    B, C = 4, 5                                          # bot v2, human (client)
    etyk = KATALOG / "client_arena_label.txt"
    plik = Path(args.wyniki)
    nowy = not plik.exists()
    wyn = open(plik, "a", buffering=1)
    if nowy:
        wyn.write("time,label,result,how,ticks,balance,dealt,taken\n")
    zad = otrz = 0.0
    licz = {}
    print("arena: ready - client: /walcz siekiera siekiera", flush=True)
    while True:
        czlowiek = obs[C, M.CZLOWIEK] > 0.5
        idx = bot(obs)
        wys = sedzia.akcje(akcje_z_indeksow(idx))
        sedzia.wyslij(m, wys)
        _, obs_po = m.obserwacje()
        if czlowiek:                                     # velocity from the position delta, as in show.py
            krok = obs_po[C, [M.X, M.Y, M.Z]] - obs[C, [M.X, M.Y, M.Z]]
            if np.abs(krok).max() < 1.5:
                obs_po = obs_po.copy()
                obs_po[C, [M.VX, M.VY, M.VZ]] = krok
        w = sedzia.krok(obs, obs_po)
        if czlowiek and not w["swieze"][2]:
            zad += float(w["stracone"][B])
            otrz += float(w["stracone"][C])
        if w["koniec"][2] and czlowiek:
            r = -int(w["wynik0"][2])                     # wynik0 is from the view of the arena's player 0 (the bot)
            zg = w["zginal"]
            jak = "knockout" if zg[B] or zg[C] else "draw" if w["remis"][2] else "points"
            e = etyk.read_text().strip() if etyk.exists() else "?"
            wyn.write(f"{time.strftime('%H:%M:%S')},{e},{r},{jak},{int(sedzia.t_ep[2])},{-float(sedzia.bilans[B]):.1f},"
                      f"{zad:.1f},{otrz:.1f}\n")
            k = licz.setdefault(e, [0, 0, 0])
            k[0 if r > 0 else 1 if r < 0 else 2] += 1
            print(f"arena {time.strftime('%H:%M:%S')} [{e}] {'WIN' if r > 0 else 'LOSS' if r < 0 else 'draw'} "
                  f"({jak}, {sedzia.t_ep[2] / 20:.1f} s, dealt {zad:.0f} / taken {otrz:.0f}) - total W/L/D {k}", flush=True)
        if w["swieze"][2] or (w["koniec"][2]):
            zad = otrz = 0.0
        bot.nowa_walka(np.repeat(w["swieze"], 2))
        obs = obs_po


if __name__ == "__main__":
    main()
