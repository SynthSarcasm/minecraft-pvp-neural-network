"""One-off training health report: processes, the latest window of updates vs the previous one and the end of
the previous stage, exams, watchdog, swap; ends with a list of warnings.

  python health_check.py --nazwa pvp4 --poprzedni pvp3 --okno 50
"""
import argparse
import csv
import json
import math
import subprocess
import time
from pathlib import Path

KAT = Path(__file__).parent
SERWERY = {"server 1": 25565, "server 2": 25567, "server 3": 25568, "show server": 25566}   # game port


def wiersze(nazwa):
    p = KAT / "runs" / nazwa / "log.csv"
    return list(csv.DictReader(open(p))) if p.exists() else []


def f(x, k):
    v = x.get(k)
    try:
        return float(v) if v not in (None, "") else None
    except ValueError:
        return None


def sr(okno, k):
    v = [f(x, k) for x in okno if f(x, k) is not None]
    return sum(v) / len(v) if v else None


def suma(okno, k):
    return sum(f(x, k) or 0 for x in okno)


def podsumuj(okno):
    if not okno:
        return None
    pw, pp, pr = suma(okno, "pula_wygrane"), suma(okno, "pula_przegrane"), suma(okno, "pula_remisy")
    bw, bp, br = suma(okno, "bot_wygrane"), suma(okno, "bot_przegrane"), suma(okno, "bot_remisy")
    rund = suma(okno, "rund")
    return dict(
        akt=f"{okno[0]['aktualizacja']}-{okno[-1]['aktualizacja']}",
        pula=100 * pw / max(1, pw + pp + pr), pula_n=pw + pp + pr,
        bot=100 * bw / max(1, bw + bp + br), bot_n=bw + bp + br,
        zabic=100 * suma(okno, "zabic") / max(1, rund), remisy=100 * suma(okno, "remisy") / max(1, rund),
        rund_akt=rund / len(okno), dl_s=(sr(okno, "dl_rundy") or 0) / 20, odl=sr(okno, "odl_self"),
        ent=sr(okno, "entropia"), kl=sr(okno, "kl"), kl_max=max(f(x, "kl") or 0 for x in okno),
        clip=sr(okno, "clipfrac"), wart=sr(okno, "strata_wartosci"), krs=sr(okno, "kroki_na_s"),
        skok=sr(okno, "skok_proc"), sprint=sr(okno, "sprint_proc"),
        przedm={k: suma(okno, k) for k in ("strzaly", "traf_strzala", "blokady", "zjedzone", "haki", "bloki_post", "rund_przedm")})


def wiersz(n, s):
    if not s:
        return f"{n:18s} no data"
    return (f"{n:18s} upd {s['akt']:>11s} | pool {s['pula']:4.0f}% ({s['pula_n']:.0f}) | bot {s['bot']:4.0f}% | "
            f"knockouts {s['zabic']:3.0f}% draws {s['remisy']:3.1f}% | round {s['dl_s']:4.1f}s ({s['rund_akt']:.0f}/upd) | "
            f"dist {s['odl']:.2f} | ent {s['ent']:.3f} | kl {s['kl']:.4f} (max {s['kl_max']:.3f}) | clip {s['clip']:.3f} | "
            f"value {s['wart']:.4f} | jump {s['skok'] or 0:.1f}% sprint {s['sprint'] or 0:.1f}% | {s['krs']:.0f} steps/s")


def dziala(wzor):
    r = subprocess.run(["pgrep", "-f", wzor], capture_output=True, text=True)
    return bool(r.stdout.strip())


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp6")
    a.add_argument("--poprzedni", default="pvp5")
    a.add_argument("--okno", type=int, default=50)
    a.add_argument("--prog_bota", type=float, default=40, help="warn below this (stages with ping: the bot plays without lag)")
    args = a.parse_args()
    ostrzezenia = []
    print(time.strftime("check %H:%M:%S"))

    stan = {}
    for n, w in [("training", "train.py --nazwa " + args.nazwa), ("show", "show.py --nazwa"), ("panel", "panel.py --nazwa"),
                 ("exam", "exam.py --nazwa"), ("watchdog", "watchdog.py --nazwa"), ("caffeinate", "caffeinate -i -w"),
                 ("bore", "bore local")]:
        stan[n] = dziala(w)
    for n, port in SERWERY.items():
        stan[n] = bool(subprocess.run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN"], capture_output=True, text=True).stdout.strip())
    print("processes:", ", ".join(f"{n} {'OK' if d else 'NOT RUNNING'}" for n, d in stan.items()))
    log_s = KAT / f"watchdog_{args.nazwa}.log"
    opanowane = log_s.exists() and log_s.read_text().strip().splitlines()[-1:][0].startswith("MASTERED")
    for n in ("training", "exam", "watchdog", "server 1", "server 2", "server 3"):
        if n == "watchdog" and opanowane:              # the watchdog exits by itself after announcing mastery
            continue
        if not stan[n]:
            ostrzezenia.append(f"{n} is not running")

    r = wiersze(args.nazwa)
    if not r:
        print("no training log"); return
    log = KAT / "runs" / args.nazwa / "log.csv"
    wiek = time.time() - log.stat().st_mtime
    print(f"last log entry {wiek:.0f} s ago, update {r[-1]['aktualizacja']}, steps {f(r[-1], 'kroki') / 1e6:.1f} M")
    if wiek > 180 and not (KAT / "PAUZA").exists():
        ostrzezenia.append(f"training log has not moved for {wiek:.0f} s")
    if (KAT / "PAUZA").exists():
        print("PAUZA: a human is fighting in the show (training waits)")

    k = args.okno
    teraz, przed = podsumuj(r[-k:]), podsumuj(r[-2 * k:-k])
    stary = wiersze(args.poprzedni)
    print(wiersz("now", teraz))
    print(wiersz("previous window", przed))
    print(wiersz(f"end of {args.poprzedni}", podsumuj(stary[-200:])))
    print(wiersz(f"start of {args.nazwa}", podsumuj(r[:k])))
    for nazwa_okna, s_ in (("items now", teraz), ("items at start", podsumuj(r[:k]))):
        p_ = s_ and s_["przedm"]
        if p_ and p_["rund_przedm"]:
            rr = p_["rund_przedm"]
            print(f"{nazwa_okna:18s} per round with items: arrows {p_['strzaly'] / rr:.2f} (hits {p_['traf_strzala'] / rr:.2f}, "
                  f"accuracy {100 * p_['traf_strzala'] / max(1, p_['strzaly']):.0f}%) | hits on shield {p_['blokady'] / rr:.2f} | "
                  f"eaten {p_['zjedzone'] / rr:.2f} | rod hooks {p_['haki'] / rr:.2f} | blocks {p_['bloki_post'] / rr:.2f} | rounds {rr:.0f}")

    for x in r[-k:]:
        for kol in ("entropia", "kl", "strata_wartosci"):
            v = f(x, kol)
            if v is not None and (math.isnan(v) or math.isinf(v)):
                ostrzezenia.append(f"{kol} = {v} at update {x['aktualizacja']} (NaN/inf!)")
    if teraz["kl"] > 0.05:
        ostrzezenia.append(f"high KL: mean {teraz['kl']:.3f} (normal ~0.01-0.03; panel alarm 0.12)")
    if teraz["ent"] < 0.25:
        ostrzezenia.append(f"low entropy {teraz['ent']:.3f} - possible policy collapse")
    if teraz["ent"] > 2.5:                               # slot and right-button heads add entropy
        ostrzezenia.append(f"high entropy {teraz['ent']:.3f} - the network plays more and more randomly")
    if teraz["bot_n"] >= 50 and teraz["bot"] < args.prog_bota:
        ostrzezenia.append(f"only {teraz['bot']:.0f}% vs bot v2")
    if teraz["pula_n"] >= 100 and teraz["pula"] < 40:
        ostrzezenia.append(f"only {teraz['pula']:.0f}% vs the pool - getting weaker")
    if teraz["remisy"] > 5:
        ostrzezenia.append(f"draws {teraz['remisy']:.1f}% - possible stalemate")
    if teraz["odl"] and teraz["odl"] > 6:
        ostrzezenia.append(f"mean distance {teraz['odl']:.1f} - possible running away/stalemate")

    p = KAT / "runs" / args.nazwa / "egzamin.json"
    egz = json.loads(p.read_text()) if p.exists() else []
    print("exams (version: fists vs reference | progress | stage gain):")
    for e in egz[-5:]:
        print(f"   {time.strftime('%H:%M', time.localtime(e['czas']))} v{e['akt']}: {e['proc']}% "
              f"| {e.get('postep_proc', '-')}{'' if 'postep_wzgledem' not in e else ' vs ' + str(e['postep_wzgledem'])} "
              f"| {e.get('zysk_proc', '-')}")
    if egz:
        e = egz[-1]
        if e["proc"] < 45 and e["wygrane"] + e["przegrane"] + e["remisy"] >= 100:
            ostrzezenia.append(f"fists: {e['proc']}% vs the reference version - forgetting")
        if time.time() - e["czas"] > 40 * 60:          # exam cycle ~10 min + 15 min break
            ostrzezenia.append(f"last exam {(time.time() - e['czas']) / 60:.0f} min ago")

    s = KAT / f"watchdog_{args.nazwa}.log"
    if s.exists():
        print("watchdog:", s.read_text().strip().splitlines()[-1])

    vm = subprocess.run(["sysctl", "-n", "vm.swapusage"], capture_output=True, text=True).stdout.strip()
    print("swap:", vm)
    print("WARNINGS:" if ostrzezenia else "WARNINGS: none")
    for o in ostrzezenia:
        print("  !", o)


if __name__ == "__main__":
    main()
