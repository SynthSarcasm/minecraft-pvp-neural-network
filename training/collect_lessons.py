"""Item lessons: the teacher bot (bots.BotPrzedmiotyy) fights and records (network input, its decisions).

Player 2a = teacher, 2a+1 = plain bot v2 (this is also a tournament: do the items pay off at all).
Mostly saves ticks in which the teacher does something with items (+ a share of ordinary ticks) to recordings/<prefix>_*.npz.
The trainer reads them with --lekcje 'lekcje6_*.npz' (stage-6 lessons teach heads 6–8) or
--lekcje 'lekcje_przedm_*.npz' --glowy_lekcji 7,8 (only the slot and right-button heads).

  python collect_lessons.py --port 25603 --sekundy 300
"""
import argparse
import time
from pathlib import Path

import numpy as np
from bots import BotPrzedmioty, BotV2
from features import ZESTAWY_ETAP6, Sedzia, Stos, akcje_z_indeksow
import bridge as M
from bridge import Most


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--areny", type=int, default=16)
    a.add_argument("--sekundy", type=float, default=300)
    a.add_argument("--umie", default="lawa,leczenie,mikstura", help="teacher skills")
    a.add_argument("--maska", type=int, default=-1, help="only rounds with this item mask (-1 = loadouts with lava/potions)")
    a.add_argument("--rodzaje", default="", help="only these potion kinds in slot 8, e.g. 7,8,9,10")
    a.add_argument("--bronie", default="", help="only these weapons, e.g. 1,1 (sword-sword); empty = from the loadouts")
    a.add_argument("--przedrostek", default="lekcje6", help="file name: <prefix>_<date>.npz")
    a.add_argument("--bez_zapisu", action="store_true")
    a.add_argument("--zwykle", type=float, default=0.0, help="fraction of ticks without item actions to save as well")
    args = a.parse_args()
    rng = np.random.default_rng()
    m = Most(args.areny, 10000, port=args.port)
    n = m.n
    _, obs = m.obserwacje()
    zestawy = [z for z in ZESTAWY_ETAP6 if (z[1] & (32 | 64 | 128)) and (args.maska < 0 or z[1] == args.maska)]
    if args.bronie or args.rodzaje:
        b = tuple(int(x) for x in args.bronie.split(",")) if args.bronie else (1, 1)
        rodz = [int(x) for x in args.rodzaje.split(",")] if args.rodzaje else list(range(1, 11))
        zestawy = [(b, args.maska if args.maska >= 0 else 255, 1.0, rodz)]
    sed, stos = Sedzia(args.areny, zestawy=zestawy, rng=rng, lag=True, teren=(0.2, 0.3)), Stos(n)
    sed.lag.wylacz[:] = False
    naucz, v2 = BotPrzedmioty(n, rng, args.umie.split(",")), BotV2(n, rng)
    A_ = np.arange(n) % 2 == 0
    X = sed.wejscie_sieci(stos, obs)
    xs, as_ = [], []
    W = P = R = 0
    st = dict(strz=0, traf=0, blok=0, jedz=0, hak=0, post=0, rund=0, lawa=0, lecz=0, mikst=0)
    t0 = time.time()
    while time.time() - t0 < args.sekundy:
        idx = v2(obs)
        idx_n = naucz(obs, sed.prawy)
        idx[A_] = idx_n[A_]
        robi = A_ & ((idx[:, 7] > 0) | (idx[:, 8] > 0) | (naucz.tryb > 0) | (sed.prawy > 0.5))
        zapis = robi | (A_ & (rng.random(n) < args.zwykle))
        zapis &= ~np.repeat(sed.smiec, 2) & ~(obs[:, M.WYPADL] > 0.5)
        if zapis.any():
            xs.append(X[zapis].astype(np.float16))
            as_.append(idx[zapis].astype(np.int8))
        sed.wyslij(m, sed.akcje(akcje_z_indeksow(idx)))
        _, po = m.obserwacje()
        w = sed.krok(obs, po)
        g = A_ & ~np.repeat(w["swieze"], 2) & ~(po[:, M.WYPADL] > 0.5)
        st["strz"] += float(np.maximum(0, obs[g, M.STRZALY] - po[g, M.STRZALY]).sum())
        st["traf"] += float(np.maximum(0, po[g, M.TRAF_STRZALA] - obs[g, M.TRAF_STRZALA]).sum())
        st["blok"] += float(np.maximum(0, po[g, M.ZABLOKOWANE] - obs[g, M.ZABLOKOWANE]).sum())
        st["jedz"] += float(np.maximum(0, obs[g, M.JABLKA] + obs[g, M.STEKI] - po[g, M.JABLKA] - po[g, M.STEKI]).sum())
        st["hak"] += float(((po[g, M.HAK] > 1.5) & (obs[g, M.HAK] < 1.5)).sum())
        st["post"] += float(np.maximum(0, po[g, M.POSTAWIONE] - obs[g, M.POSTAWIONE]).sum())
        st["lawa"] += int(((obs[g, M.WIADRO] == 1) & (po[g, M.WIADRO] == 2)).sum())
        st["lecz"] += float(np.maximum(0, obs[g, M.LECZENIE] - po[g, M.LECZENIE]).sum())
        st["mikst"] += float(np.maximum(0, obs[g, M.MIKSTURA_ILE] - po[g, M.MIKSTURA_ILE]).sum())
        k = w["koniec"]
        W += int((k & (w["wynik0"] > 0)).sum())
        P += int((k & (w["wynik0"] < 0)).sum())
        R += int((k & (w["wynik0"] == 0)).sum())
        st["rund"] += int(k.sum())
        sw = np.repeat(w["swieze"], 2)
        naucz.nowa_walka(sw)
        v2.nowa_walka(sw)
        X = sed.wejscie_sieci(stos, po, w["swieze"])
        obs = po
    m.zamknij()
    Xa = np.concatenate(xs) if xs else np.zeros((0, 1), np.float16)
    Aa = np.concatenate(as_) if as_ else np.zeros((0, 9), np.int8)
    plik = Path(__file__).parent / "recordings" / time.strftime(f"{args.przedrostek}_%Y%m%d_%H%M%S.npz")
    if not args.bez_zapisu:
        np.savez_compressed(plik, X=Xa, A=Aa)
    r = max(1, st["rund"])
    print(f"teacher vs bot v2 (rounds with items): {W}:{P} (draws {R}), {100 * W / max(1, W + P + R):.1f}% wins")
    print(f"per round: lava {st['lawa'] / r:.2f}, healing {st['lecz'] / r:.2f}, potions {st['mikst'] / r:.2f} | arrows {st['strz'] / r:.2f}, hits {st['traf'] / r:.2f}, shield {st['blok'] / r:.2f}, "
          f"eaten {st['jedz'] / r:.2f}, hooks {st['hak'] / r:.2f}, blocks {st['post'] / r:.2f}")
    print(f"saved {len(Xa):,} examples -> {plik.name} | slot changed in {100 * (Aa[:, 7] > 0).mean():.1f}%, "
          f"right button pressed/released in {100 * (Aa[:, 8] > 0).mean():.1f}%")


if __name__ == "__main__":
    main()
