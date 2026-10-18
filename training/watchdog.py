"""Stage watchdog: waits until the network has "mastered" the stage and then exits (so whoever started it gets notified).

Mastery = all conditions at once, on two checks in a row:
  1. plateau: the last three progress tests (with a weapon vs its own version from >= 500 updates earlier) <= 56%
     (two tests gave false alarms),
  2. >= 95% wins vs the armed bot v2 (last 300 updates),
  3. no forgetting: the last fists exam vs the champion (exam --wzor) >= 45%,
  4. minimum training: >= --min_akt updates since the stage started,
  5. a real stage gain: with a weapon it wins >= --prog_zysku % vs its own version from before the stage (a mere lack of
     progress is not mastery),
  6. fists reached their ceiling: the last 3 fists exams lie within 8 points.

  python watchdog.py --nazwa pvp4 --start 8640 --prog_zysku 55
"""
import argparse
import csv
import json
import time
from pathlib import Path

KAT = Path(__file__).parent


def proc(w, p, r):
    return 100 * w / (w + p + r) if w + p + r else None


def sprawdz(nazwa, start, min_akt=1500, prog_zysku=60.0, prog_bota=95.0, prog_przedm=0.0):
    r = list(csv.DictReader(open(KAT / "runs" / nazwa / "log.csv")))
    f = lambda x, k: float(x[k]) if x.get(k) not in (None, "") else 0.0
    akt = int(r[-1]["aktualizacja"])
    okna = []
    for koniec in (akt, akt - 200, akt - 400):
        w = [x for x in r if koniec - 200 < int(x["aktualizacja"]) <= koniec]
        okna.append(proc(sum(f(x, "pula_wygrane") for x in w), sum(f(x, "pula_przegrane") for x in w),
                         sum(f(x, "pula_remisy") for x in w)))
    ost = [x for x in r if int(x["aktualizacja"]) > akt - 300]
    bot = proc(sum(f(x, "bot_wygrane") for x in ost), sum(f(x, "bot_przegrane") for x in ost),
               sum(f(x, "bot_remisy") for x in ost))
    skok = sum(f(x, "skok_proc") for x in ost) / max(1, len(ost))
    egz_plik = KAT / "runs" / nazwa / "egzamin.json"
    wszystkie = json.loads(egz_plik.read_text()) if egz_plik.exists() else []
    egz = wszystkie[-1]["proc"] if wszystkie else None
    piesci = [e["proc"] for e in wszystkie][-3:]                # fists must also reach their ceiling, not just not drop
    postep = [e["postep_proc"] for e in wszystkie if "postep_proc" in e][-3:]
    zysk = ([e["zysk_proc"] for e in wszystkie if "zysk_proc" in e] or [None])[-1]
    warunki = {
        "plateau (3 progress tests <= 56%)": len(postep) == 3 and all(p <= 56 for p in postep),
        f"bot v2 >= {prog_bota:g}%": bot is not None and bot >= prog_bota,
        "fists exam >= 45%": egz is not None and egz >= 45,
        f"minimum training (>= {min_akt} upd.)": akt - start >= min_akt,
        f"stage gain >= {prog_zysku:g}%": zysk is not None and zysk >= prog_zysku,
        "fists stable (3 exams within 8 pts)": len(piesci) == 3 and max(piesci) - min(piesci) <= 8,
    }
    if prog_przedm:                                    # stage 5: the items that pay off must really be mastered
        luk = ([e["luk_proc"] for e in wszystkie if "luk_proc" in e] or [None])[-1]
        tarcza = ([e["tarcza_proc"] for e in wszystkie if "tarcza_proc" in e] or [None])[-1]
        warunki[f"bow with fists >= {prog_przedm:g}%"] = luk is not None and luk >= prog_przedm
        warunki[f"shield with weapon >= {prog_przedm:g}%"] = tarcza is not None and tarcza >= prog_przedm
    warunki = {**warunki,
    }
    opis = (f"upd {akt} | pool in windows {[None if o is None else round(o) for o in okna]} | bot {bot and round(bot)}% | "
            f"exam {egz}% | progress {postep} | stage gain {zysk}% | jump {skok:.1f}% | met {sum(warunki.values())}/{len(warunki)}")
    return all(warunki.values()), opis, warunki


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp3")
    a.add_argument("--start", type=int, default=3000, help="update at which the stage started")
    a.add_argument("--co_ile", type=float, default=300)
    a.add_argument("--min_akt", type=int, default=1500)
    a.add_argument("--prog_bota", type=float, default=95.0, help="with ping the bot plays without lag — use a lower value")
    a.add_argument("--prog_przedm", type=float, default=0.0, help="stage 5: bow (fists) and shield (weapon) >= this %% vs the pre-stage version")
    a.add_argument("--prog_zysku", type=float, default=60.0)
    args = a.parse_args()
    z_rzedu = 0
    while True:
        try:
            ok, opis, warunki = sprawdz(args.nazwa, args.start, args.min_akt, args.prog_zysku, args.prog_bota, args.prog_przedm)
        except Exception as e:                         # log being written etc. — try again shortly
            print(f"{time.strftime('%H:%M')} read error: {e!r}", flush=True)
            time.sleep(60)
            continue
        z_rzedu = z_rzedu + 1 if ok else 0
        print(f"{time.strftime('%H:%M')} {opis} | in a row {z_rzedu}", flush=True)
        if z_rzedu >= 2:
            print("MASTERED: " + opis, flush=True)
            return
        time.sleep(args.co_ile)


if __name__ == "__main__":
    main()
