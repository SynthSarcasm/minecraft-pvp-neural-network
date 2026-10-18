"""The current network with a sword vs the same network with an axe.

  python weapon_duel.py --nazwa pvp3 --port 25603 --sekundy 180
"""
import argparse
import time
import numpy as np
import torch
from features import Sedzia, Stos, akcje_z_indeksow
from exam import KAT, wczytaj
from bridge import Most


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp3")
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--areny", type=int, default=16)
    a.add_argument("--sekundy", type=float, default=180)
    args = a.parse_args()
    torch.set_num_threads(2)
    s, akt = wczytaj(KAT / "runs" / args.nazwa / "siec_pokaz.pt")
    m = Most(args.areny, 10000, port=args.port)
    n = m.n
    _, obs = m.obserwacje()
    sedzia, stos = Sedzia(args.areny, zestawy=[((1, 2), 1.0)]), Stos(n)   # player 2a: sword, 2a+1: axe
    X = sedzia.wejscie_sieci(stos, obs)
    W = dict(miecz=0, siekiera=0, remis=0)
    traf = dict(miecz=[], siekiera=[])
    t0 = time.time()
    with torch.no_grad():
        while time.time() - t0 < args.sekundy:
            idx = s.rozklad(s(torch.as_tensor(X))[0])[0].numpy()
            m.akcje(akcje_z_indeksow(idx), sedzia.resety(), sedzia.bronie())
            _, po = m.obserwacje()
            w = sedzia.krok(obs, po)
            k, zg, b = w["koniec"], w["zginal"], sedzia.bilans[0::2]
            W["miecz"] += int((k & (zg[1::2] | (w["na_punkty"] & (b > 0)))).sum())
            W["siekiera"] += int((k & (zg[0::2] | (w["na_punkty"] & (b < 0)))).sum())
            W["remis"] += int((k & w["remis"]).sum())
            wz = ~np.repeat(w["swieze"], 2)
            for i in np.where((w["zadane"] > 0) & wz)[0]:
                traf["miecz" if i % 2 == 0 else "siekiera"].append(float(w["zadane"][i]))
            X = sedzia.wejscie_sieci(stos, po, w["swieze"])
            obs = po
    m.zamknij()
    r = max(1, sum(W.values()))
    print(f"version {akt}: sword {W['miecz']} ({100 * W['miecz'] / r:.0f}%) | axe {W['siekiera']} "
          f"({100 * W['siekiera'] / r:.0f}%) | draws {W['remis']} | rounds {r}")
    for k, t in traf.items():
        t = np.array(t)
        prog = 7.5 if k == "miecz" else 9.5                  # above a full normal hit = crit
        print(f"  {k}: hits {len(t)}, mean {t.mean():.2f} damage, crits {100 * np.mean(t > prog):.0f}% of hits")


if __name__ == "__main__":
    main()
