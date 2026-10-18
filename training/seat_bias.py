"""Seat-bias check: bot v2 against itself in 1v1 arenas, results per seat (player 0 / 1). Fair setups give ~50%.

  python seat_bias.py --port 25604 --areny 16 --sekundy 120 [--bron 0]
"""
import argparse
import json
import time
import numpy as np
import bridge as M
from bots import BotV2
from features import Sedzia, akcje_z_indeksow
from bridge import Most


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--port", type=int, default=25604)
    a.add_argument("--areny", type=int, default=16)
    a.add_argument("--sekundy", type=float, default=120)
    a.add_argument("--bron", type=int, default=0, help="0 fists, 1 sword, 2 axe (same for both)")
    a.add_argument("--tempo", type=float, default=10000)
    a.add_argument("--zapisz", default="")
    args = a.parse_args()
    rng = np.random.default_rng()
    m = Most(args.areny, args.tempo, port=args.port)
    n = m.n
    sed = Sedzia(args.areny, zestawy=[((args.bron, args.bron), 0, 1.0)], rng=rng)
    u = sed.u
    bot = BotV2(n, rng)
    _, obs = m.obserwacje()
    k0 = u.k == 0
    st = {f"{p}{k}": 0.0 for p in ("zab", "pkt", "zad", "traf", "cios", "cios_cel", "celuje", "ticki") for k in (0, 1)}
    st.update(remis=0, rund=0, oba_traf=0)
    wa = np.zeros((args.areny, 2), np.int64)            # wins of player 0 / 1 in every arena
    ticki, t0 = 0, time.time()
    while time.time() - t0 < args.sekundy:
        idx = bot(obs, sed.cel)
        akc = sed.akcje(akcje_z_indeksow(idx))
        sed.wyslij(m, akc)
        _, po = m.obserwacje()
        ticki += 1
        w = sed.krok(obs, po)
        gra = ~w["swieze"][u.klatka]
        cios = idx[:, 3] > 0
        cel = obs[:, M.CELUJE] > 0.5
        traf = w["zadane"] > 0
        for k, mk in ((0, k0), (1, ~k0)):
            g = gra & mk
            st[f"zad{k}"] += float(w["zadane"][g].sum())
            st[f"traf{k}"] += int(traf[g].sum())
            st[f"cios{k}"] += int((cios & g).sum())
            st[f"cios_cel{k}"] += int((cios & cel & g).sum())
            st[f"celuje{k}"] += int((cel & g).sum())
            st[f"ticki{k}"] += int(g.sum())
        st["oba_traf"] += int((traf[u.pocz] & traf[u.pocz + 1] & gra[u.pocz]).sum())
        kon = w["koniec"]
        p0 = u.pocz
        z0, z1 = w["zginal"][p0], w["zginal"][p0 + 1]
        st["zab0"] += int((kon & z1 & ~z0).sum())
        st["zab1"] += int((kon & z0 & ~z1).sum())
        np_ = kon & w["na_punkty"]
        st["pkt0"] += int((np_ & (w["wynik0"] > 0)).sum())
        st["pkt1"] += int((np_ & (w["wynik0"] < 0)).sum())
        st["remis"] += int((kon & w["remis"]).sum())
        wa[:, 0] += kon & ((z1 & ~z0) | (np_ & (w["wynik0"] > 0)))
        wa[:, 1] += kon & ((z0 & ~z1) | (np_ & (w["wynik0"] < 0)))
        st["rund"] += int(kon.sum())
        bot.nowa_walka(w["swieze"][u.klatka])
        obs = po
    m.zamknij()
    dt = time.time() - t0
    w0, w1 = st["zab0"] + st["pkt0"], st["zab1"] + st["pkt1"]
    print(f"{ticki} ticks in {dt:.0f} s ({ticki / dt:.0f}/s), rounds {st['rund']}")
    print(f"WINS player0 {w0} : {w1} player1  ({100 * w0 / max(1, w0 + w1):.1f}% for player 0), draws {st['remis']}")
    print(f"  kills {st['zab0']} : {st['zab1']}, on points {st['pkt0']} : {st['pkt1']}")
    print(f"  damage {st['zad0']:.0f} : {st['zad1']:.0f}, hits {st['traf0']} : {st['traf1']}, both in the same tick {st['oba_traf']}")
    print(f"  attacks {st['cios0']} : {st['cios1']}, with crosshair on target {st['cios_cel0']} : {st['cios_cel1']}")
    print(f"  crosshair on the opponent {100 * st['celuje0'] / max(1, st['ticki0']):.2f}% : {100 * st['celuje1'] / max(1, st['ticki1']):.2f}%")
    print("  arenas (player0:player1):", " ".join(f"{x}:{y}" for x, y in wa))
    if args.zapisz:
        st["klatki"] = wa.tolist()
        with open(args.zapisz, "w") as f:
            json.dump(st, f)


if __name__ == "__main__":
    main()
