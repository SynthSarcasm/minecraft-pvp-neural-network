"""Watch report (stage 6): training health, results of the latest updates and exams, comparison with the previous
window and with the start.

  python watch_report.py --nazwa pvp7 --okno 40
"""
import argparse
import csv
import json
import subprocess
import time
from pathlib import Path

import numpy as np
KAT = Path(__file__).parent


def procesy():
    wzorce = {"training": "train.py --nazwa", "exam": "exam6.py", "servers": "fabric-server-launch.jar nogui",
              }
    out = subprocess.run(["ps", "-axo", "pid,rss,%cpu,command"], capture_output=True, text=True).stdout.splitlines()
    wynik = []
    for nazwa, w in wzorce.items():
        linie = [l for l in out if w in l and "grep" not in l]
        if nazwa == "servers":
            wynik.append(f"MC servers: {len(linie)} ({', '.join(str(round(int(l.split()[1]) / 1e6, 1)) + ' GB' for l in linie)})")
        else:
            wynik.append(f"{nazwa}: {'running' if linie else 'NOT RUNNING'}" + (f" ({int(linie[0].split()[1]) / 1e6:.1f} GB, CPU {linie[0].split()[2]}%)" if linie else ""))
    return wynik


def _f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return np.nan


def okno(w, kol):
    if not w:
        return {}
    a = {k: np.array([_f(r.get(k)) for r in w]) for k in kol}
    s = lambda k: float(np.nansum(a[k]))
    m = lambda k: float(np.nanmean(a[k]))
    rund = max(1, s("rund"))
    bot = max(1, s("bot_wygrane") + s("bot_przegrane") + s("bot_remisy"))
    pula = max(1, s("pula_wygrane") + s("pula_przegrane") + s("pula_remisy"))
    v3 = max(1, s("v3_rund"))
    pb3 = max(1, s("pb3_rund"))
    rp = max(1, s("rund") + s("v3_rund"))
    return dict(akt=f"{int(a['aktualizacja'][0])}–{int(a['aktualizacja'][-1])}", kps=round(m("kroki_na_s")),
                ent=round(m("entropia"), 2), kl=round(m("kl"), 3), clip=round(m("clipfrac"), 2),
                bot_v2=round(100 * s("bot_wygrane") / bot, 1), pula=round(100 * s("pula_wygrane") / pula, 1),
                remisy=round(100 * s("remisy") / rund, 1), zabic=round(100 * s("zabic") / rund, 1), odl=round(m("odl_self"), 1),
                dl_rundy=round(m("dl_rundy")), v3_w=round(100 * s("v3_w") / v3, 1), v3_p=round(100 * s("v3_p") / v3, 1),
                v3_zab=round(s("v3_zab") / v3, 2), pb3_w=round(100 * s("pb3_w") / pb3, 1), pb3_zab=round(s("pb3_zab") / pb3, 2),
                pb3_rund=int(s("pb3_rund")),
                lawa=round(s("lawa") / rp, 2), leczenie=round(s("leczenie") / rp, 2), mikstury=round(s("mikstury") / rp, 2),
                haki=round(s("haki") / rp, 2), strzaly=round(s("strzaly") / rp, 2), tarcza=round(s("blokady") / rp, 2))


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp7")
    a.add_argument("--okno", type=int, default=40)
    args = a.parse_args()
    kat = KAT / "runs" / args.nazwa
    print(time.strftime("=== watch %H:%M ==="))
    for l in procesy():
        print(" ", l)
    with open(kat / "log.csv") as f:
        w = list(csv.DictReader(f))
    w = [r for r in w if r.get("v3_rund") not in (None, "")]
    kol = list(w[0].keys()) if w else []
    teraz, przed = okno(w[-args.okno:], kol), okno(w[-2 * args.okno:-args.okno], kol)
    start = okno(w[:args.okno], kol)
    print(f"  stage updates: {len(w)}, latest {w[-1]['aktualizacja'] if w else '-'}, "
          f"age of the latest entry {time.time() - (kat / 'log.csv').stat().st_mtime:.0f} s")
    print(f"  {'':10s} {'start':>10s} {'previous':>10s} {'now':>10s}")
    for k in teraz:
        print(f"  {k:10s} {str(start.get(k, '')):>10s} {str(przed.get(k, '')):>10s} {str(teraz[k]):>10s}")
    pe = kat / "egzamin6.json"
    if pe.exists():
        e = json.loads(pe.read_text())
        print(f"  exams ({len(e)}):")
        testy = ["v3_pelny", "v3_bron", "v1_pvpbot", "teren", "bron", "lawa", "leczenie", "mikstura", "wedka", "p_bron", "p_lawa", "p_lecz", "p_mikst", "piesci", "postep"]
        for r in e[-4:]:
            print(f"   {time.strftime('%H:%M', time.localtime(r['czas']))} v{r['akt']}: " + " | ".join(
                f"{t} {r[t]['proc']}%" + (f" ({r[t]['zab_na_runde']} down, all {r[t].get('czyste_proc', '?')}%)" if t.startswith("v") else "")
                for t in testy if t in r))
        if e:
            r = e[-1]
            print("   use (latest): " + " | ".join(f"{t}: lava {r[t]['lawa']} heal {r[t]['leczenie']} potion {r[t]['mikstury']} "
                                                    f"hooks {r[t]['haki']}" for t in ("lawa", "leczenie", "mikstura", "wedka", "p_lawa", "p_lecz", "p_mikst") if t in r))


if __name__ == "__main__":
    main()
