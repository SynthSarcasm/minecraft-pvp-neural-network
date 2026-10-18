"""Final evaluation of stage-6 candidates (longer tests than egzamin6, same rules).

  python eval_final.py --port 25603 --rcon 25577 --serwer server3 --sekundy 120 runs/pvp7/a.pt runs/pvp7/b.pt

For every file: 1v3 vs pvp-bot (full loadout / enemies with shield, food and healing), 1v1 vs pvp-bot, terrain and
weapons only vs the pre-stage network, items (weapon + item, fists + item), fists vs the fists champion.
Results are appended to runs/pvp7/ocena_koncowa.json.
"""
import argparse
import json
import time
from pathlib import Path

import torch
from features import OFENSYWNE, PELNY
from exam import wczytaj
from exam6 import BRONIE4, walki

KAT = Path(__file__).parent


def main():
    a = argparse.ArgumentParser()
    a.add_argument("pliki", nargs="+")
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--rcon", type=int, default=25577)
    a.add_argument("--serwer", default="server3")
    a.add_argument("--sekundy", type=float, default=120)
    a.add_argument("--tylko", default="", help="only these tests, e.g. v3_pelny,v3_tarcza")
    a.add_argument("--wyjscie", default="ocena_koncowa.json", help="results file in runs/pvp7/ (a separate one per parallel run)")
    args = a.parse_args()
    torch.set_num_threads(2)
    haslo = KAT.parent / args.serwer / "rcon_haslo.txt"
    przed, _ = wczytaj(KAT / "runs/pvp7/przed_etapem.pt")
    mistrz, _ = wczytaj(KAT / "runs/pvp2/siec.pt")
    S = args.sekundy
    plik_w = KAT / "runs/pvp7" / args.wyjscie
    for plik in args.pliki:
        siec, akt = wczytaj(KAT / plik)
        w = dict(plik=plik, akt=akt, czas=time.time())
        testy = [
            ("v3_pelny", dict(multi=True, pvpbot=True, zestawy_multi=[(0, 0, PELNY, PELNY, 1.0)], rozklad=(0, 0, 1)), 2.0),
            ("v3_tarcza", dict(multi=True, pvpbot=True, zestawy_multi=[(0, 0, PELNY, 4 | 2 | 64, 1.0)], rozklad=(0, 0, 1)), 1.0),
            ("v1_pvpbot", dict(multi=True, pvpbot=True, zestawy_multi=[(0, 0, PELNY, PELNY, 1.0)], rozklad=(1, 0, 0)), 1.0),
            ("teren", dict(przeciwnik=przed, zestawy=[(b, PELNY, 1.0) for b in BRONIE4], teren=(0.0, 0.5)), 1.0),
            ("bron", dict(przeciwnik=przed, zestawy=[(b, 0, 1.0) for b in BRONIE4]), 0.8),
            ("mikstura", dict(przeciwnik=przed, zestawy=[(b, 128, 1.0) for b in BRONIE4]), 0.8),
            ("leczenie", dict(przeciwnik=przed, zestawy=[(b, 64, 1.0) for b in BRONIE4]), 0.8),
            ("lawa", dict(przeciwnik=przed, zestawy=[(b, 32, 1.0) for b in BRONIE4]), 0.8),
            ("wedka", dict(przeciwnik=przed, zestawy=[(b, 8, 1.0) for b in BRONIE4]), 0.8),
            ("p_bron", dict(przeciwnik=przed, zestawy=[((0, 0), 0, 1.0)]), 0.6),
            ("p_mikst", dict(przeciwnik=przed, zestawy=[((0, 0), 128, 1.0, OFENSYWNE)]), 0.6),
            ("p_lecz", dict(przeciwnik=przed, zestawy=[((0, 0), 64, 1.0)]), 0.6),
            ("p_lawa", dict(przeciwnik=przed, zestawy=[((0, 0), 32, 1.0)]), 0.6),
            ("piesci", dict(przeciwnik=mistrz, zestawy=[((0, 0), 0, 1.0)], teren=None), 0.6),
        ]
        if args.tylko:
            testy = [t for t in testy if t[0] in args.tylko.split(",")]
        for nazwa, kw, mn in testy:
            r = walki(args.port, siec, kw.pop("przeciwnik", None), S * mn, rcon=args.rcon, haslo_z=haslo, **kw)
            w[nazwa] = r
            print(f"{plik} v{akt} {nazwa:9s}: {r['wygrane']}:{r['przegrane']} (draws {r['remisy']}) {r['proc']}% | "
                  f"down {r['zab_na_runde']} all {r['czyste_proc']}% | lava {r['lawa']} heal {r['leczenie']} "
                  f"potion {r['mikstury']} hooks {r['haki']} shield {r['tarcza']}", flush=True)
        wsz = json.loads(plik_w.read_text()) if plik_w.exists() else []
        wsz.append(w)
        plik_w.write_text(json.dumps(wsz))


if __name__ == "__main__":
    main()
