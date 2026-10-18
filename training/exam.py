"""Exams for stages 1-5 on a separate server (never used for learning): fists vs the fists champion
(forgetting), weapons vs a version >= 500 updates older (progress) and vs the pre-stage version (stage gain).
Results go to runs/<nazwa>/egzamin.json.

  python exam.py --nazwa pvp3 --wzor runs/pvp2/siec.pt --port 25603
"""
import argparse
import json
import shutil
import time
from pathlib import Path

import numpy as np
import torch
from features import GLOWY, WEJSCIE, ZESTAWY_ETAP5, Sedzia, Stos, akcje_z_indeksow
from bridge import Most
from network import Siec, dopasuj

KAT = Path(__file__).parent


def wczytaj(sciezka):
    z = torch.load(sciezka, map_location="cpu", weights_only=False)
    wagi = z["wagi"] if "wagi" in z else z["siec"]
    s = Siec(WEJSCIE, GLOWY).eval()
    s.load_state_dict(dopasuj({k: v.float() for k, v in wagi.items()}, WEJSCIE))
    return s, z.get("aktualizacja")


ZESTAWY_POSTEPU = [((1, 2), 1.0), ((2, 1), 1.0), ((1, 1), 1.0), ((2, 2), 1.0)]
# stage 5: progress on item loadouts without fists, stage gain on the full loadout
ZESTAWY_PRZEDM = [z for z in ZESTAWY_ETAP5 if z[0] != (0, 0)]
ZESTAWY_PELNE = [(b, 31, 1.0) for b in [(1, 2), (2, 1), (1, 1), (2, 2)]]


@torch.no_grad()
def egzamin(port, biezaca, mistrz, sekundy, areny, zestawy=None, lag=False):
    m = Most(areny, 10000, port=port)
    n = m.n
    _, obs = m.obserwacje()
    sedzia, stos = Sedzia(areny, zestawy=zestawy, lag=lag), Stos(n)   # zestawy=None: fists only
    X = sedzia.wejscie_sieci(stos, obs)
    nasi = np.arange(n) % 2 == 0                         # player 2a = current network, 2a+1 = fists champion
    W = P = R = 0
    zadane = otrzymane = 0.0
    t0 = time.time()
    while time.time() - t0 < sekundy:
        xt = torch.as_tensor(X)
        idx = np.zeros((n, len(GLOWY)), np.int64)
        idx[nasi] = biezaca.rozklad(biezaca(xt[nasi])[0])[0].numpy()
        idx[~nasi] = mistrz.rozklad(mistrz(xt[~nasi])[0])[0].numpy()
        m.akcje(sedzia.akcje(akcje_z_indeksow(idx)), sedzia.resety(), sedzia.bronie(), sedzia.przedmioty())
        _, obs_po = m.obserwacje()
        w = sedzia.krok(obs, obs_po)
        k, zg, b = w["koniec"], w["zginal"], sedzia.bilans[0::2]
        W += int((k & (zg[1::2] | (w["na_punkty"] & (b > 0)))).sum())
        P += int((k & (zg[0::2] | (w["na_punkty"] & (b < 0)))).sum())
        R += int((k & w["remis"]).sum())
        wazne = ~np.repeat(w["swieze"], 2)
        zadane += float(w["zadane"][nasi & wazne].sum())
        otrzymane += float(w["stracone"][nasi & wazne].sum())
        X = sedzia.wejscie_sieci(stos, obs_po, w["swieze"])
        obs = obs_po
    m.zamknij()
    return W, P, R, zadane, otrzymane


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp3")
    a.add_argument("--wzor", default="runs/pvp2/siec.pt", help="frozen champion of the previous stage")
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--areny", type=int, default=16)
    a.add_argument("--sekundy", type=float, default=120)
    a.add_argument("--co_ile", type=float, default=900)
    a.add_argument("--sekundy_postepu", type=float, default=180)
    a.add_argument("--przedmioty", action="store_true", help="stage 5: progress on item loadouts, gain on the full loadout")
    a.add_argument("--lag", action="store_true", help="both networks with ping as in training (features.Lag)")
    a.add_argument("--wzor_etapu", default="", help="pre-stage version (stage gain: with a weapon against it)")
    args = a.parse_args()
    torch.set_num_threads(2)
    plik_wynikow = KAT / "runs" / args.nazwa / "egzamin.json"
    wyniki = json.loads(plik_wynikow.read_text()) if plik_wynikow.exists() else []
    mistrz, akt_mistrza = wczytaj(KAT / args.wzor)
    wzor_etapu = (KAT / args.wzor_etapu).resolve() if args.wzor_etapu else None
    print(f"exam: fists champion = {args.wzor} (update {akt_mistrza})", flush=True)
    while True:
        try:
            cykl(args, mistrz, wzor_etapu, wyniki, plik_wynikow)
        except (ConnectionError, OSError) as e:
            print(f"exam: connection lost ({e!r}), retrying in 30 s", flush=True)
            time.sleep(30)
            continue
        time.sleep(args.co_ile)


def cykl(args, mistrz, wzor_etapu, wyniki, plik_wynikow):
    if True:
        plik_sieci = KAT / "runs" / args.nazwa / "siec_pokaz.pt"
        biezaca, akt = wczytaj(plik_sieci)
        W, P, R, zad, otrz = egzamin(args.port, biezaca, mistrz, args.sekundy, args.areny, lag=args.lag)
        rund = W + P + R
        wynik = dict(czas=time.time(), akt=akt, wygrane=W, przegrane=P, remisy=R,
                     proc=round(100 * W / max(1, rund), 1), zadane=round(zad), otrzymane=round(otrz))
        wersje = KAT / "runs" / args.nazwa / "egzamin_wersje"
        wersje.mkdir(exist_ok=True)
        stare = sorted((int(p.stem[1:]), p) for p in wersje.glob("u*.pt"))
        kandydaci = [(a_, p) for a_, p in stare if a_ <= akt - 500 and p.resolve() != wzor_etapu]
        if kandydaci:
            a_st, p_st = kandydaci[-1]
            stara, _ = wczytaj(p_st)
            Wp, Pp, Rp, _, _ = egzamin(args.port, biezaca, stara, args.sekundy_postepu, args.areny,
                                       ZESTAWY_PRZEDM if args.przedmioty else ZESTAWY_POSTEPU, args.lag)
            wynik.update(postep_proc=round(100 * Wp / max(1, Wp + Pp + Rp), 1), postep_rund=Wp + Pp + Rp, postep_wzgledem=a_st)
            print(f"   progress with weapons: version {akt} vs {a_st} - {Wp}:{Pp} (draws {Rp}), {wynik['postep_proc']}% wins",
                  flush=True)
        if args.wzor_etapu:
            przed, a_prz = wczytaj(KAT / args.wzor_etapu)
            Wz, Pz, Rz, _, _ = egzamin(args.port, biezaca, przed, args.sekundy_postepu, args.areny,
                                       ZESTAWY_PELNE if args.przedmioty else ZESTAWY_POSTEPU, args.lag)
            wynik.update(zysk_proc=round(100 * Wz / max(1, Wz + Pz + Rz), 1), zysk_rund=Wz + Pz + Rz)
            print(f"   stage gain with weapons: version {akt} vs pre-stage ({a_prz}) - {Wz}:{Pz}, {wynik['zysk_proc']}%",
                  flush=True)
        if args.przedmioty and args.wzor_etapu:
            from eval_items import ZESTAWY as ZP, walki
            for klucz, nazwa in (("luk", "fists + bow"), ("tarcza", "weapon + shield")):
                r = walki(args.port, biezaca, przed, ZP[nazwa], 60, args.areny)
                wynik[f"{klucz}_proc"] = r["proc"]
                wynik[f"{klucz}_uzycie"] = r["strz"] if klucz == "luk" else r["blok"]
                print(f"   {nazwa}: version {akt} vs pre-stage - {r['wygrane']}:{r['przegrane']}, {r['proc']}% "
                      f"(per round: arrows {r['strz']}, hits {r['traf']}, shield {r['blok']})", flush=True)
        torch.save({"wagi": {k: (v.half() if v.is_floating_point() and v.dim() > 0 else v) for k, v in biezaca.state_dict().items()}, "aktualizacja": akt},
                   wersje / f"u{akt:06d}.pt")
        for _, p in stare[:-11]:                         # keep ~12 latest copies (~50 MB each)
            p.unlink(missing_ok=True)
        wyniki.append(wynik)
        plik_wynikow.write_text(json.dumps(wyniki))
        print(f"exam {time.strftime('%H:%M')}: version {akt} vs fists champion - {W}:{P} (draws {R}), "
              f"{wynik['proc']}% wins, damage {zad:.0f}:{otrz:.0f}", flush=True)


if __name__ == "__main__":
    main()
