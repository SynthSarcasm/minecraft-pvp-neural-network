"""Do crits (jump + hit while falling) pay off in a fist fight?

Tournament bot v2 vs bot v2 — player A always goes for crits, player B never (sprint + W-tap).
The rest of the style (accuracy, strafe, distance) is drawn as usual. Counts wins and the damage distribution of single
hits (1.0 = normal hit, 1.5 = crit), to check that crits work for fake players at all.

  python test_crits.py --port 25603 --sekundy 120
"""
import argparse
import time
import numpy as np
from bots import BotV2
from features import Sedzia, akcje_z_indeksow
from bridge import Most


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--areny", type=int, default=16)
    a.add_argument("--sekundy", type=float, default=120)
    a.add_argument("--zestaw", default="0,0", help="weapon A,B: 0 fists, 1 sword, 2 axe")
    a.add_argument("--tryb", default="krytyki", help="krytyki: A always crits, B never; losowy: style as usual")
    args = a.parse_args()
    m = Most(args.areny, 10000, port=args.port)
    n = m.n
    _, obs = m.obserwacje()
    ba, bb = map(int, args.zestaw.split(","))
    bot, sedzia = BotV2(n, np.random.default_rng(1)), Sedzia(args.areny, zestawy=[((ba, bb), 1.0)])
    A = np.arange(n) % 2 == 0

    def wymus():
        if args.tryb == "krytyki":
            bot.krytyki[A] = True
            bot.krytyki[~A] = False

    wymus()
    W = dict(a=0, b=0, r=0)
    trafienia = {"a": [], "b": []}
    t0 = time.time()
    while time.time() - t0 < args.sekundy:
        m.akcje(akcje_z_indeksow(bot(obs)), sedzia.resety(), sedzia.bronie())
        _, obs_po = m.obserwacje()
        w = sedzia.krok(obs, obs_po)
        wazne = ~np.repeat(w["swieze"], 2)
        for i in np.where((w["zadane"] > 0) & wazne)[0]:
            trafienia["a" if i % 2 == 0 else "b"].append(round(float(w["zadane"][i]), 2))
        k, zg, b = w["koniec"], w["zginal"], sedzia.bilans[0::2]
        W["a"] += int((k & (zg[1::2] | (w["na_punkty"] & (b > 0)))).sum())
        W["b"] += int((k & (zg[0::2] | (w["na_punkty"] & (b < 0)))).sum())
        W["r"] += int((k & w["remis"]).sum())
        bot.nowa_walka(np.repeat(w["swieze"], 2))
        wymus()
        obs = obs_po
    m.zamknij()
    rund = sum(W.values())
    print(f"loadout {args.zestaw}, mode {args.tryb}")
    print(f"rounds: {rund} | A won {W['a']} ({100 * W['a'] / max(1, rund):.0f}%), "
          f"B {W['b']} ({100 * W['b'] / max(1, rund):.0f}%), draws {W['r']}")
    for s, nazwa in (("a", "A, crits"), ("b", "B, no crits")):
        t = np.array(trafienia[s])
        wart, ile = np.unique(t, return_counts=True)
        top = sorted(zip(ile, wart), reverse=True)[:4]
        print(f"  {nazwa}: hits {len(t)}, mean damage {t.mean():.3f} | most common values: "
              + ", ".join(f"{v} x{c}" for c, v in top))


if __name__ == "__main__":
    main()
