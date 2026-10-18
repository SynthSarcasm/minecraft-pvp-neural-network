"""Item mastery evaluation: the current network vs itself from before the item stage (which does not use items),
separately for each loadout, both with ping. > 50% = the item gives the network an edge (it knows how to use it).

  python eval_items.py --nazwa pvp6 --sekundy 60
"""
import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch
import bridge as M
from features import Sedzia, Stos, akcje_z_indeksow
from exam import wczytaj
from bridge import Most

KAT = Path(__file__).parent
B4 = [(1, 2), (2, 1), (1, 1), (2, 2)]
ZESTAWY = {
    "fists + bow": [((0, 0), 1, 1.0)],
    "weapon + shield": [(b, 4, 1.0) for b in B4],
    "weapon + bow": [(b, 1, 1.0) for b in B4],
    "weapon + food": [(b, 2, 1.0) for b in B4],
    "weapon + fishing rod": [(b, 8, 1.0) for b in B4],
    "weapon + blocks": [(b, 16, 1.0) for b in B4],
    "full loadout": [(b, 31, 1.0) for b in B4],
}


@torch.no_grad()
def walki(port, nowa, stara, zestawy, sekundy, areny=16):
    m = Most(areny, 10000, port=port)
    n = m.n
    _, obs = m.obserwacje()
    sed, stos = Sedzia(areny, zestawy=zestawy, lag=True), Stos(n)
    X = sed.wejscie_sieci(stos, obs)
    nasi = np.arange(n) % 2 == 0
    W = P = R = 0
    st = dict(strz=0, traf=0, blok=0, jedz=0, hak=0, post=0)
    t0 = time.time()
    while time.time() - t0 < sekundy:
        xt = torch.as_tensor(X)
        idx = np.zeros((n, 9), np.int64)
        idx[nasi] = nowa.rozklad(nowa(xt[nasi])[0])[0].numpy()
        idx[~nasi] = stara.rozklad(stara(xt[~nasi])[0])[0].numpy()
        m.akcje(sed.akcje(akcje_z_indeksow(idx)), sed.resety(), sed.bronie(), sed.przedmioty())
        _, po = m.obserwacje()
        w = sed.krok(obs, po)
        g = nasi & ~np.repeat(w["swieze"], 2)
        st["strz"] += float(np.maximum(0, obs[g, M.STRZALY] - po[g, M.STRZALY]).sum())
        st["traf"] += float(np.maximum(0, po[g, M.TRAF_STRZALA] - obs[g, M.TRAF_STRZALA]).sum())
        st["blok"] += float(np.maximum(0, po[g, M.ZABLOKOWANE] - obs[g, M.ZABLOKOWANE]).sum())
        st["jedz"] += float(np.maximum(0, obs[g, M.JABLKA] + obs[g, M.STEKI] - po[g, M.JABLKA] - po[g, M.STEKI]).sum())
        st["hak"] += float(((po[g, M.HAK] > 1.5) & (obs[g, M.HAK] < 1.5)).sum())
        st["post"] += float(np.maximum(0, po[g, M.POSTAWIONE] - obs[g, M.POSTAWIONE]).sum())
        k, zg, b = w["koniec"], w["zginal"], sed.bilans[0::2]
        W += int((k & (zg[1::2] | (w["na_punkty"] & (b > 0)))).sum())
        P += int((k & (zg[0::2] | (w["na_punkty"] & (b < 0)))).sum())
        R += int((k & w["remis"]).sum())
        X = sed.wejscie_sieci(stos, po, w["swieze"])
        obs = po
    m.zamknij()
    rund = max(1, W + P + R)
    return dict(wygrane=W, przegrane=P, remisy=R, proc=round(100 * W / rund, 1),
                **{k: round(v / rund, 2) for k, v in st.items()})


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp6")
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--sekundy", type=float, default=60)
    args = a.parse_args()
    torch.set_num_threads(3)
    nowa, akt = wczytaj(KAT / "runs" / args.nazwa / "siec_pokaz.pt")
    stara, akt_s = wczytaj(KAT / "runs" / args.nazwa / "przed_etapem.pt")
    wynik = {"czas": time.time(), "akt": akt, "wzgledem": akt_s, "zestawy": {}}
    print(f"item evaluation: version {akt} vs {akt_s} (from before items), with ping", flush=True)
    for nazwa, z in ZESTAWY.items():
        r = walki(args.port, nowa, stara, z, args.sekundy)
        wynik["zestawy"][nazwa] = r
        print(f"  {nazwa:20s}: {r['proc']:5.1f}% ({r['wygrane']}:{r['przegrane']}, draws {r['remisy']}) | per round: "
              f"arrows {r['strz']}, hits {r['traf']}, shield {r['blok']}, food {r['jedz']}, rod {r['hak']}, blocks {r['post']}",
              flush=True)
    plik = KAT / "runs" / args.nazwa / "ocena_przedmiotow.json"
    wszystkie = json.loads(plik.read_text()) if plik.exists() else []
    wszystkie.append(wynik)
    plik.write_text(json.dumps(wszystkie, ensure_ascii=False))


if __name__ == "__main__":
    main()
