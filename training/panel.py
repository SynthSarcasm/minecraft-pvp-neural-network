"""Training dashboard backend (http://<host>:8780).

GET /           -> panel_web/index.html (live data from the show: ws://<host>:8771)
GET /api/log    -> training log averaged to ~120 points + current numbers
GET /api/xray   -> analysis of the latest checkpoint on a sample from the show, recomputed every 2 minutes
GET /api/egzamin, /api/straznik -> exams, mastery conditions

  python panel.py --nazwa pvp2
"""
import argparse
import csv
import json
import math
import subprocess
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

import watchdog
from features import GLOWY, N_CECH, STOS, WEJSCIE
from network import Siec, dopasuj

KAT = Path(__file__).parent
ALARM_SILA = 40.0          # % wins vs older versions (over >= 150 rounds) - below: alarm
ALARM_ZAPOMINANIE = 40.0   # % wins vs the fists champion in the last exam - below: alarm
ALARM_PAT = 8.0            # % draws over 50 updates; shields alone give ~4-7%
ALARM_KL = 0.12            # mean KL per update, ~2x the normal level
OST = (STOS - 1) * N_CECH                # start of the newest tick in the network input
PRZOD, PRAWO, ODL, CELUJE, PASEK = 18, 19, 21, 26, 3


def liczba(w, k):
    try:
        v = w.get(k, "")
        return float(v) if v not in ("", None) else None
    except ValueError:
        return None


def dane_logu(nazwa):
    plik = KAT / "runs" / nazwa / "log.csv"
    if not plik.exists():
        return {"jest": False}
    r = list(csv.DictReader(open(plik)))
    if not r:
        return {"jest": False}
    kubel = max(10, math.ceil(len(r) / 120))
    pkt = []
    for i in range(0, len(r), kubel):
        w = r[i:i + kubel]
        suma = lambda k: sum(liczba(x, k) or 0 for x in w)
        srednia = lambda k: (lambda v: sum(v) / len(v) if v else None)([liczba(x, k) for x in w if liczba(x, k) is not None])
        bw, bp, br = suma("bot_wygrane"), suma("bot_przegrane"), suma("bot_remisy")
        z_pula = [x for x in w if liczba(x, "pula_wygrane") is not None]
        pw = sum(liczba(x, "pula_wygrane") for x in z_pula)
        pp = sum(liczba(x, "pula_przegrane") for x in z_pula)
        pr = sum(liczba(x, "pula_remisy") for x in z_pula)
        rund, zab, npk, rem = suma("rund"), suma("zabic"), suma("na_punkty"), suma("remisy")
        pkt.append(dict(
            akt=int(liczba(w[-1], "aktualizacja")), kroki=round((liczba(w[-1], "kroki") or 0) / 1e6, 2),
            kps=round(srednia("kroki_na_s") or 0),
            bot=round(100 * bw / (bw + bp + br), 1) if bw + bp + br else None,
            pula=round(100 * pw / (pw + pp + pr), 1) if z_pula and pw + pp + pr else None,
            nokaut=round(100 * zab / rund, 1) if rund else None, punkty=round(100 * npk / rund, 1) if rund else None,
            remis=round(100 * rem / rund, 1) if rund else None,
            dl=round((srednia("dl_rundy") or 0) / 20, 1), odl=round(srednia("odl_self") or 0, 2),
            ent=round(srednia("entropia") or 0, 3), kl=round(srednia("kl") or 0, 4),
            clip=round(srednia("clipfrac") or 0, 3), wart=round(srednia("strata_wartosci") or 0, 4),
            skok=srednia("skok_proc"), sprint=srednia("sprint_proc"),
            **{k: (round(suma(k) / suma("rund_przedm"), 2) if suma("rund_przedm") else None)
               for k in ("strzaly", "traf_strzala", "blokady", "zjedzone", "haki", "bloki_post")}))
    ost = r[-100:]
    s = lambda k: sum(liczba(x, k) or 0 for x in ost)
    bw, bp, br = s("bot_wygrane"), s("bot_przegrane"), s("bot_remisy")
    pw, pp, pr = s("pula_wygrane"), s("pula_przegrane"), s("pula_remisy")
    rund, zab = s("rund"), s("zabic")
    dziala = subprocess.run(["pgrep", "-f", f"train.py --nazwa {nazwa}"], capture_output=True).returncode == 0
    teraz = dict(akt=int(liczba(r[-1], "aktualizacja")), kroki=liczba(r[-1], "kroki"),
                 kps=round(sum(liczba(x, "kroki_na_s") or 0 for x in r[-20:]) / len(r[-20:])),
                 bot=round(100 * bw / (bw + bp + br), 1) if bw + bp + br else None,
                 pula=round(100 * pw / (pw + pp + pr), 1) if pw + pp + pr else None,
                 nokaut=round(100 * zab / rund, 1) if rund else None, dziala=dziala,
                 zmiana=plik.stat().st_mtime)
    teraz["alarmy"] = alarmy(r, dziala, egzaminy(nazwa))
    return {"jest": True, "punkty": pkt, "teraz": teraz, "kubel": kubel}


def egzaminy(nazwa):
    plik = KAT / "runs" / nazwa / "egzamin.json"
    return json.loads(plik.read_text()) if plik.exists() else []


def alarmy(r, dziala, egz=()):
    """Warnings based on many fights at once (a single dip in a chart is usually noise)."""
    wynik = []
    if egz:
        e = egz[-1]
        rund = e["wygrane"] + e["przegrane"] + e["remisy"]
        if rund >= 100 and e["proc"] < ALARM_ZAPOMINANIE:
            wynik.append(dict(poziom="zle", tekst=f"The network is forgetting fists: in the last exam it beat the fists champion in only "
                                                    f"{e['proc']:.0f}% of {rund} fights (threshold {ALARM_ZAPOMINANIE:.0f}%)."))
    okno = r[-150:]
    pw, pp, pr = (sum(liczba(x, k) or 0 for x in okno) for k in ("pula_wygrane", "pula_przegrane", "pula_remisy"))
    rund = pw + pp + pr
    if rund >= 150 and 100 * pw / rund < ALARM_SILA:
        wynik.append(dict(poziom="zle", tekst=f"The network is getting weaker: only {100 * pw / rund:.0f}% wins vs older versions "
                                                f"in the last {int(rund)} fights (threshold {ALARM_SILA:.0f}%)."))
    rr = sum(liczba(x, "rund") or 0 for x in r[-50:])
    rem = sum(liczba(x, "remisy") or 0 for x in r[-50:])
    if rr >= 200 and 100 * rem / rr > ALARM_PAT:
        wynik.append(dict(poziom="zle", tekst=f"Stalemate: {100 * rem / rr:.0f}% of rounds end in a draw - the networks circle instead of fighting "
                                                f"(threshold {ALARM_PAT:.0f}%)."))
    kl = [liczba(x, "kl") for x in r[-50:] if liczba(x, "kl") is not None]
    if kl and sum(kl) / len(kl) > ALARM_KL:
        wynik.append(dict(poziom="zle", tekst=f"The network changes too abruptly: on average {sum(kl) / len(kl):.3f} per "
                                                f"update (threshold {ALARM_KL}). Consider a lower learning rate."))
    if not dziala:
        wynik.append(dict(poziom="zle", tekst="Training is not running."))
    return wynik


class Rentgen:
    """Computes the x-ray in the background so the page gets a ready result immediately."""

    def __init__(self, nazwa):
        self.nazwa, self.wynik, self.siec, self.mtime = nazwa, {"jest": False}, None, 0.0
        self.plik_historii = KAT / "runs" / nazwa / "historia_martwych.json"
        self.historia = json.loads(self.plik_historii.read_text()) if self.plik_historii.exists() else []
        threading.Thread(target=self.petla, daemon=True).start()

    def petla(self):
        torch.set_num_threads(2)
        while True:
            try:
                self.wynik = self.licz()
            except Exception as e:
                self.wynik = {"jest": False, "blad": repr(e)}
            time.sleep(120)

    @torch.no_grad()
    def licz(self):
        plik = KAT / "runs" / self.nazwa / "siec_pokaz.pt"
        probka = KAT / "probka_X.npy"
        if not plik.exists() or not probka.exists():
            return {"jest": False, "blad": "no network or sample yet (the show has to run for ~1 min)"}
        if plik.stat().st_mtime != self.mtime:
            z = torch.load(plik, map_location="cpu", weights_only=False)
            if self.siec is None:
                u = z["ustawienia"]
                self.siec = Siec(WEJSCIE, GLOWY, u["szer"], u["bloki"]).eval()
            self.siec.load_state_dict(dopasuj({k: v.float() for k, v in z["wagi"].items()}, WEJSCIE))
            self.mtime, self.akt = plik.stat().st_mtime, z["aktualizacja"]
        s = self.siec
        Xn = np.load(probka).astype(np.float32)
        if Xn.shape[1] < WEJSCIE:                            # sample from before inputs were added: missing = 0
            Xn = np.pad(Xn, ((0, 0), (0, WEJSCIE - Xn.shape[1])))
        X = torch.as_tensor(Xn[:, :WEJSCIE])
        # activations layer by layer (like Siec.ukryte), dead neuron = zero on the whole sample
        warstwy = []
        h = F.relu(s.wejscie(s.norm(X)))
        warstwy.append(("input", h))
        for i, b in enumerate(s.bloki):
            wew = F.relu(b.w1(b.ln(h)))
            warstwy.append((f"block {i + 1}", wew))
            h = h + b.w2(wew)
        h = F.relu(s.koniec(h))
        warstwy.append(("output", h))
        martwe = [dict(nazwa=n, martwe=round(100 * float(((a > 0).sum(0) == 0).float().mean()), 1),
                       aktywne=round(100 * float((a > 0).float().mean()), 1)) for n, a in warstwy]
        wagi = []
        for n, w in [("input", s.wejscie.weight)] + \
                    [(f"block {i + 1} · a", b.w1.weight) for i, b in enumerate(s.bloki)] + \
                    [(f"block {i + 1} · b", b.w2.weight) for i, b in enumerate(s.bloki)] + \
                    [("actions", s.polityka.weight), ("value", s.wartosc.weight)]:
            wagi.append(dict(nazwa=n, rms=round(float(w.pow(2).mean().sqrt()), 5)))
        logity, v = s.polityka(h), s.wartosc(h).squeeze(-1)
        sklonnosci = [[round(float(p), 4) for p in torch.softmax(l, -1).mean(0)]
                      for l in torch.split(logity, list(GLOWY), dim=-1)]
        hv, kraw = np.histogram(v.numpy(), bins=12, range=(-1.5, 1.5))
        # zone map: where the opponent is relative to the bot (forward/right, in blocks)
        przod = X[:, OST + PRZOD].numpy() * 7
        prawo = X[:, OST + PRAWO].numpy() * 7
        mapa, _, _ = np.histogram2d(przod, prawo, bins=24, range=[[-6, 6], [-6, 6]])
        odl = X[:, OST + ODL].numpy() * 10
        ho, ko = np.histogram(odl, bins=24, range=(0, 12))
        if not self.historia or self.historia[-1]["akt"] != self.akt:
            self.historia.append({"akt": self.akt, "czas": time.time(), **{m["nazwa"]: m["martwe"] for m in martwe}})
            self.plik_historii.write_text(json.dumps(self.historia))
        return {"jest": True, "akt": self.akt, "probka": len(X), "czas": time.time(), "martwe": martwe,
                "historia": self.historia[-300:],
                "wagi": wagi, "sklonnosci": sklonnosci, "wartosc_hist": hv.tolist(), "wartosc_kraw": kraw.tolist(),
                "wartosc_srednia": round(float(v.mean()), 3), "mapa": mapa.tolist(), "dystans": ho.tolist(),
                "dystans_kraw": ko.tolist(), "celuje": round(100 * float((X[:, OST + CELUJE] > 0.5).float().mean()), 1)}


def stan_straznika(args):
    """Stage-mastery conditions (same as watchdog.py) - for the achievements in the panel."""
    if args.start is None:
        return {"jest": False}
    try:
        ok, opis, warunki = watchdog.sprawdz(args.nazwa, args.start, args.min_akt, args.prog_zysku, args.prog_bota)
    except Exception as e:
        return {"jest": False, "blad": repr(e)}
    akt = int(opis.split()[1])
    return {"jest": True, "ok": ok, "opis": opis, "warunki": [{"nazwa": k, "ok": bool(v)} for k, v in warunki.items()],
            "akt": akt, "start": args.start, "min_akt": args.min_akt}


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp2")
    a.add_argument("--port", type=int, default=8780)
    a.add_argument("--start", type=int, default=None, help="update at which the stage started (mastery conditions as in the watchdog)")
    a.add_argument("--min_akt", type=int, default=1500)
    a.add_argument("--prog_zysku", type=float, default=60.0)
    a.add_argument("--prog_bota", type=float, default=95.0)
    args = a.parse_args()
    rentgen = Rentgen(args.nazwa)

    class Obsluga(SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def end_headers(self):
            self.send_header("Cache-Control", "no-store")
            super().end_headers()

        def do_GET(self):
            sciezka = self.path.split("?")[0]
            if sciezka.startswith("/api/"):
                dane = (dane_logu(args.nazwa) if sciezka == "/api/log" else rentgen.wynik if sciezka == "/api/xray"
                        else egzaminy(args.nazwa) if sciezka == "/api/egzamin"
                        else stan_straznika(args) if sciezka == "/api/straznik" else None)
                tresc = json.dumps(dane if dane is not None else {"blad": "not found"})
                tresc = tresc.replace("NaN", "null").replace("-Infinity", "null").replace("Infinity", "null").encode()   # JSON has no NaN
                self.send_response(200 if dane is not None else 404)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(tresc)))
                self.end_headers()
                self.wfile.write(tresc)
            else:
                super().do_GET()

    serwer = ThreadingHTTPServer(("0.0.0.0", args.port), partial(Obsluga, directory=str(KAT / "panel_web")))
    print(f"panel on port {args.port}", flush=True)
    serwer.serve_forever()


if __name__ == "__main__":
    main()
