"""Stage-6 smoke test: teacher vs bot v2 on terrain and in 1v3; prints speed, results, item use and sanity checks.

  python test_stage6.py --port 25603 --areny 12 --multi 4 --sekundy 60
"""
import argparse
import time
import numpy as np
import bridge as M
from bots import BotPrzedmioty, BotV2
from features import ZESTAWY_ETAP6, Sedzia, Stos, akcje_z_indeksow
from bridge import Most


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--areny", type=int, default=12)
    a.add_argument("--multi", type=int, default=4)
    a.add_argument("--sekundy", type=float, default=60)
    a.add_argument("--maska", type=int, default=-1, help="only this mask in 1v1 (-1 = stage-6 loadouts)")
    a.add_argument("--pvpbot", type=int, default=0, help="number of 1v3 arenas with pvp-bot (server 3 RCON)")
    args = a.parse_args()
    rng = np.random.default_rng()
    m = Most(args.areny, 10000, port=args.port, n4=args.multi)
    n = m.n
    zestawy = ZESTAWY_ETAP6 if args.maska < 0 else [(b, args.maska, 1.0) for b in [(1, 2), (2, 1), (1, 1), (2, 2)]]
    sed = Sedzia(args.areny, zestawy=zestawy, rng=rng, lag=True, n4=args.multi, teren=(0.2, 0.3))
    u = sed.u
    stos = Stos(n)
    naucz, v2 = BotPrzedmioty(n, rng), BotV2(n, rng)
    pb = np.zeros(n, bool)
    pvp = None
    if args.pvpbot:
        from tournament import PvpBot
        for a_ in range(args.areny - args.pvpbot, args.areny):
            pb[u.pocz[a_] + 1:u.pocz[a_] + 4] = True
        pvp = PvpBot(n, np.where(pb)[0], cele=lambda: sed.cel)
    sed.lag.wylacz[:] = False
    _, obs = m.obserwacje()
    X = sed.wejscie_sieci(stos, obs)
    A_ = u.k == 0
    st = dict(rund=0, w=0, p=0, r=0, lawa=0, lecz=0, mikst=0, strz=0, blok=0, zab=0, v3=0, v3w=0, v3p=0)
    ticki, t0 = 0, time.time()
    zab_r, dzien = np.zeros(args.areny, np.int64), []
    nan = 0
    while time.time() - t0 < args.sekundy:
        idx = v2(obs, sed.cel)
        idx_n = naucz(obs, sed.prawy, sed.cel)
        idx[A_] = idx_n[A_]
        akc = sed.akcje(akcje_z_indeksow(idx))
        akc[pb] = np.nan
        sed.wyslij(m, akc)
        _, po = m.obserwacje()
        ticki += 1
        nan += int(np.isnan(po).sum())
        w = sed.krok(obs, po)
        g = A_ & ~w["swieze"][u.klatka] & ~(po[:, M.WYPADL] > 0.5)
        st["lawa"] += int(((obs[g, M.WIADRO] == 1) & (po[g, M.WIADRO] == 2)).sum())
        st["lecz"] += float(np.maximum(0, obs[g, M.LECZENIE] - po[g, M.LECZENIE]).sum())
        st["mikst"] += float(np.maximum(0, obs[g, M.MIKSTURA_ILE] - po[g, M.MIKSTURA_ILE]).sum())
        st["strz"] += float(np.maximum(0, obs[g, M.STRZALY] - po[g, M.STRZALY]).sum())
        k = w["koniec"]
        st["rund"] += int((k & ~u.multi).sum())
        st["w"] += int((k & ~u.multi & (w["wynik0"] > 0)).sum())
        st["p"] += int((k & ~u.multi & (w["wynik0"] < 0)).sum())
        st["v3"] += int((k & u.multi).sum())
        st["v3w"] += int((k & u.multi & (w["wynik0"] > 0)).sum())
        st["v3p"] += int((k & u.multi & (w["wynik0"] < 0)).sum())
        st["zab"] += int(w["zabici"].sum())
        for a_ in np.where(u.multi)[0]:
            p0 = u.pocz[a_]
            zab_r[a_] += int(w["zabici"][a_])
            if k[a_]:
                if len(dzien) < 25:
                    dzien.append((int(a_), int(sed.ile_botow_runda[a_]), zab_r[a_], int(sed.t_ep[a_]), int(w["wynik0"][a_]),
                                  round(float(sed.bilans[p0]), 1), bool(po[p0, M.ZGINAL] > 0.5)))
                zab_r[a_] = 0
        sw = w["swieze"][u.klatka]
        naucz.nowa_walka(sw)
        v2.nowa_walka(sw)
        if pvp is not None:
            pvp.nowa_walka(sw)
        X = sed.wejscie_sieci(stos, po, w["swieze"])
        assert np.isfinite(X).all(), "NaN in the network input"
        obs = po
    m.zamknij()
    dt = time.time() - t0
    gra = ~(obs[:, M.WYPADL] > 0.5)
    print(f"{ticki} ticks in {dt:.0f} s = {ticki / dt:.0f} ticks/s ({ticki * n / dt:.0f} steps/s), NaN in obs: {nan}")
    print(f"1v1: rounds {st['rund']}, teacher W {st['w']} L {st['p']} | 1v3: rounds {st['v3']}, W {st['v3w']} L {st['v3p']}, "
          f"enemies taken down {st['zab']}")
    r = max(1, st["rund"] + st["v3"])
    print(f"per round: lava {st['lawa'] / r:.2f}, healing {st['lecz'] / r:.2f}, potions {st['mikst'] / r:.2f}, "
          f"arrows {st['strz'] / r:.2f}")
    print(f"positions: |x| max {np.abs(obs[gra, M.X]).max():.1f}, y from {obs[gra, M.Y].min():.1f} to {obs[gra, M.Y].max():.1f}; "
          f"terrain map: from {obs[:, M.MAPA_H:M.MAPA_H + 49].min():.0f} to {obs[:, M.MAPA_H:M.MAPA_H + 49].max():.0f}, "
          f"lava in maps: {int(obs[:, M.MAPA_L:M.MAPA_L + 49].sum())}; target visible: {obs[gra, M.WIDZI:M.WIDZI + 4].sum(1).mean():.2f}")
    print("arena, enemies, taken down, ticks, result, balance, network died:")
    for d in dzien:
        print("  ", d)
    print(f"X: {X.shape}, range {X.min():.1f}..{X.max():.1f}")


if __name__ == "__main__":
    main()
