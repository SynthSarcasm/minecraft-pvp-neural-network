"""Stage-6 exams on server 3 (12 1v1 arenas + 8 1v3 arenas with pvp-bot over RCON). Never used for learning.

Tests: v3_pelny / v3_bron (1v3 vs pvp-bot, full loadout / weapon only), v1_pvpbot, teren (vs the pre-stage version on
terrain), bron / lawa / leczenie / mikstura / wedka (weapon + one item vs pre-stage), p_* (fists + item), piesci
(fists vs the fists champion), postep (vs a version >= 300 updates older). Results: runs/<nazwa>/egzamin6.json.

  python exam6.py --nazwa pvp7 --co_ile 600
"""
import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch
import bridge as M
from features import GLOWY, PELNY, ZESTAWY_ETAP6, Sedzia, Stos, akcje_z_indeksow
from exam import wczytaj
from bridge import Most

KAT = Path(__file__).parent
AREN, N4 = 20, 8
BRONIE4 = [(1, 2), (2, 1), (1, 1), (2, 2)]


@torch.no_grad()
def walki(port, nasza, przeciwnik, sekundy, zestawy=None, multi=False, zestawy_multi=None, rozklad=(0, 0, 1),
          teren=(0.2, 0.4), pvpbot=False, rcon=25577, haslo_z=None):
    """-> results dict for player 0 of the arenas (our network). przeciwnik: a network or None (pvp-bot)."""
    m = Most(AREN, 10000, port=port, n4=N4)
    n = m.n
    sed_tmp = Sedzia(AREN, n4=N4)
    u = sed_tmp.u
    aktywne = u.multi.copy() if multi else ~u.multi
    sed = Sedzia(AREN, zestawy=zestawy, lag=True, n4=N4, zestawy_multi=zestawy_multi, rozklad_botow=rozklad,
                 teren=teren, aktywne=aktywne, kara_remis=0.8)
    stos = Stos(n)
    akt_g = aktywne[u.klatka]
    # 1v1: alternate seats to cancel any seat bias; 1v3: always player 0
    zamiana = ~u.multi & (np.arange(AREN) % 2 == 1)
    nasz_k = np.where(zamiana, 1, 0)[u.klatka]
    nasi = (u.k == nasz_k) & akt_g
    obcy = (u.k != nasz_k) & akt_g
    znak = np.where(zamiana, -1, 1)
    pvp = None
    if pvpbot:
        from tournament import PvpBot
        pvp = PvpBot(n, np.where(u.wrog_multi)[0], port_rcon=rcon, haslo_z=haslo_z, cele=lambda: sed.cel)
        sed.lag.wylacz[u.wrog_multi] = True
    _, obs = m.obserwacje()
    X = sed.wejscie_sieci(stos, obs)
    st = dict(rund=0, w=0, p=0, r=0, zab=0, czyste=0, lawa=0, lecz=0, mikst=0, haki=0, strz=0, blok=0, zad=0.0, otrz=0.0, czas=0)
    t0 = time.time()
    while time.time() - t0 < sekundy:
        xt = torch.as_tensor(X)
        idx = np.zeros((n, len(GLOWY)), np.int64)
        idx[nasi] = nasza.rozklad(nasza(xt[nasi])[0])[0].numpy()
        if przeciwnik is not None and obcy.any():
            idx[obcy] = przeciwnik.rozklad(przeciwnik(xt[obcy])[0])[0].numpy()
        akc = sed.akcje(akcje_z_indeksow(idx))
        if pvpbot:
            akc[u.wrog_multi] = np.nan
        sed.wyslij(m, akc)
        _, po = m.obserwacje()
        w = sed.krok(obs, po)
        g = nasi & ~w["swieze"][u.klatka] & ~(po[:, M.WYPADL] > 0.5)
        st["lawa"] += int(((obs[g, M.WIADRO] == 1) & (po[g, M.WIADRO] == 2)).sum())
        st["lecz"] += float(np.maximum(0, obs[g, M.LECZENIE] - po[g, M.LECZENIE]).sum())
        st["mikst"] += float(np.maximum(0, obs[g, M.MIKSTURA_ILE] - po[g, M.MIKSTURA_ILE]).sum())
        st["haki"] += int(((po[g, M.HAK] > 1.5) & (obs[g, M.HAK] < 1.5)).sum())
        st["strz"] += float(np.maximum(0, obs[g, M.STRZALY] - po[g, M.STRZALY]).sum())
        st["blok"] += float(np.maximum(0, po[g, M.ZABLOKOWANE] - obs[g, M.ZABLOKOWANE]).sum())
        st["zad"] += float(w["zadane"][g].sum())
        st["otrz"] += float(w["stracone"][g].sum())
        k = w["koniec"] & aktywne
        st["rund"] += int(k.sum())
        wn = w["wynik0"] * znak
        st["w"] += int((k & (wn > 0)).sum())
        st["p"] += int((k & (wn < 0)).sum())
        st["r"] += int((k & (wn == 0)).sum())
        st["zab"] += int((w["zabici"] * aktywne).sum())
        st["czyste"] += int((w["czyste"] & aktywne).sum())
        st["czas"] += int(sed.t_ep[k].sum())
        if pvp is not None:
            pvp.nowa_walka(w["swieze"][u.klatka])
        X = sed.wejscie_sieci(stos, po, w["swieze"])
        obs = po
    m.zamknij()
    r = max(1, st["rund"])
    return dict(rund=st["rund"], wygrane=st["w"], przegrane=st["p"], remisy=st["r"], proc=round(100 * st["w"] / r, 1),
                przegr_proc=round(100 * st["p"] / r, 1), zab_na_runde=round(st["zab"] / r, 2),
                czyste_proc=round(100 * st["czyste"] / r, 1),
                lawa=round(st["lawa"] / r, 2), leczenie=round(st["lecz"] / r, 2), mikstury=round(st["mikst"] / r, 2),
                haki=round(st["haki"] / r, 2), strzaly=round(st["strz"] / r, 2), tarcza=round(st["blok"] / r, 2),
                zadane=round(st["zad"] / r, 1), otrzymane=round(st["otrz"] / r, 1), czas_s=round(st["czas"] / r / 20, 1))


def cykl(args, wyniki, plik):
    biezaca, akt = wczytaj(KAT / args.siec if args.siec else KAT / "runs" / args.nazwa / "siec_pokaz.pt")
    przed, a_prz = wczytaj(KAT / "runs" / args.nazwa / "przed_etapem.pt")
    wynik = dict(czas=time.time(), akt=akt)
    S = args.sekundy

    def test(nazwa, **kw):
        r = walki(args.port, biezaca, kw.pop("przeciwnik", None), kw.pop("sek", S), **kw)
        wynik[nazwa] = r
        print(f"   {nazwa:10s}: {r['wygrane']}:{r['przegrane']} (draws {r['remisy']}), {r['proc']}% wins | "
              f"down/round {r['zab_na_runde']} (all: {r['czyste_proc']}%) | lava {r['lawa']} healing {r['leczenie']} potions {r['mikstury']} "
              f"hooks {r['haki']} arrows {r['strzaly']} shield {r['tarcza']} | {r['czas_s']} s/round", flush=True)

    test("v3_pelny", multi=True, pvpbot=True, zestawy_multi=[(0, 0, PELNY, PELNY, 1.0)], rozklad=(0, 0, 1), sek=S * 1.5)
    test("v3_bron", multi=True, pvpbot=True, zestawy_multi=[(0, 0, PELNY, 0, 1.0)], rozklad=(0, 0, 1))
    test("v1_pvpbot", multi=True, pvpbot=True, zestawy_multi=[(0, 0, PELNY, PELNY, 1.0)], rozklad=(1, 0, 0))
    test("teren", przeciwnik=przed, zestawy=[(b, PELNY, 1.0) for b in BRONIE4], teren=(0.0, 0.5))
    for nazwa, maska in (("bron", 0), ("lawa", 32), ("leczenie", 64), ("mikstura", 128), ("wedka", 8)):
        test(nazwa, przeciwnik=przed, zestawy=[(b, maska, 1.0) for b in BRONIE4], sek=S * 0.6)
    from features import OFENSYWNE
    for nazwa, z in (("p_bron", ((0, 0), 0, 1.0)), ("p_lawa", ((0, 0), 32, 1.0)), ("p_lecz", ((0, 0), 64, 1.0)),
                     ("p_mikst", ((0, 0), 128, 1.0, OFENSYWNE))):   # fists (no armor) + item: here the item decides
        test(nazwa, przeciwnik=przed, zestawy=[z], sek=S * 0.5)
    mistrz, _ = wczytaj(KAT / args.wzor)
    test("piesci", przeciwnik=mistrz, zestawy=[((0, 0), 0, 1.0)], teren=None, sek=S * 0.6)
    wersje = KAT / "runs" / args.nazwa / "egzamin_wersje"
    wersje.mkdir(exist_ok=True)
    stare = sorted((int(p.stem[1:]), p) for p in wersje.glob("u*.pt"))
    kand = [(a_, p) for a_, p in stare if a_ <= akt - 300]
    if kand:
        a_st, p_st = kand[-1]
        stara, _ = wczytaj(p_st)
        test("postep", przeciwnik=stara, zestawy=ZESTAWY_ETAP6)
        wynik["postep_wzgledem"] = a_st
    torch.save({"wagi": {k: (v.half() if v.is_floating_point() and v.dim() > 0 else v) for k, v in biezaca.state_dict().items()},
                "aktualizacja": akt}, wersje / f"u{akt:06d}.pt")
    for _, p in stare[:-11]:
        p.unlink(missing_ok=True)
    wyniki.append(wynik)
    plik.write_text(json.dumps(wyniki))
    print(f"exam {time.strftime('%H:%M')}: version {akt} done", flush=True)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp7")
    a.add_argument("--port", type=int, default=25603)
    a.add_argument("--sekundy", type=float, default=90)
    a.add_argument("--co_ile", type=float, default=300, help="pause between cycles (s)")
    a.add_argument("--wzor", default="runs/pvp2/siec.pt", help="fists champion")
    a.add_argument("--raz", action="store_true")
    a.add_argument("--siec", default="", help="instead of the run's siec_pokaz.pt (e.g. to measure the starting version)")
    args = a.parse_args()
    torch.set_num_threads(2)
    plik = KAT / "runs" / args.nazwa / "egzamin6.json"
    wyniki = json.loads(plik.read_text()) if plik.exists() else []
    while True:
        try:
            cykl(args, wyniki, plik)
        except (ConnectionError, OSError) as e:
            print(f"exam: connection lost ({e!r}), retrying in 30 s", flush=True)
            time.sleep(30)
            continue
        if args.raz:
            break
        time.sleep(args.co_ile)


if __name__ == "__main__":
    main()
