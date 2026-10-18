"""PPO self-play training on real Minecraft servers.

One collector thread per server drives the bots with a CPU copy of the network and queues batches of ticks; the main
thread learns on the GPU. Arena roles per server: self-play, pool (vs an older version), bot (vs bot v2, a fixed
yardstick), and optionally 1v3 vs bot v2 or pvp-bot. Rules and rewards: features.Sedzia. The file PAUZA pauses learning.

  python train.py --nazwa pvp2 --wznow --porty 25599,25602
"""
import argparse
import csv
import math
import os
import queue
import random
import threading
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from bridge import WYPADL as WYPADL_

from bots import BotV2
from features import GLOWY, KOL_W_RECE, WEJSCIE, ZESTAWY_ETAP3, ZESTAWY_ETAP5, ZESTAWY_ETAP6, Sedzia, Stos, akcje_z_indeksow
from bridge import HAK, JABLKA, LECZENIE, MIKSTURA_ILE, POSTAWIONE, STEKI, STRZALY, TRAF_STRZALA, WIADRO, ZABLOKOWANE, ZESTAW
from bridge import ODL, Most
from network import Siec, dopasuj

KOLUMNY = ["aktualizacja", "kroki", "kroki_na_s", "zbieranie_s", "nauka_s", "bot_wygrane", "bot_przegrane",
           "bot_remisy", "bot_zabicia", "bot_smierci", "bot_zadane", "bot_otrzymane", "rund", "zabic", "na_punkty",
           "remisy", "dl_rundy", "odl_self", "entropia", "strata_wartosci", "kl", "clipfrac", "pula", "czas",
           "serwer", "opoznienie_wersji", "pula_wygrane", "pula_przegrane", "pula_remisy",
           "lekcje_czlowieka", "strata_czlowieka", "ozywione", "skok_proc", "sprint_proc",
           "strzaly", "traf_strzala", "blokady", "zjedzone", "haki", "bloki_post", "rund_przedm",
           "lawa", "leczenie", "mikstury", "v3_rund", "v3_w", "v3_p", "v3_r", "v3_zab", "pb3_rund", "pb3_w", "pb3_zab"]
PRZEDM = ["strzaly", "traf_strzala", "blokady", "zjedzone", "haki", "bloki_post", "rund_przedm",
          "lawa", "leczenie", "mikstury", "v3_rund", "v3_w", "v3_p", "v3_r", "v3_zab", "pb3_rund", "pb3_w", "pb3_zab"]


@torch.no_grad()
def ozyw_neurony(siec, opt, X, prog=0.01):
    """ReDo (Sokar et al. 2023): block neurons that barely fire on fresh data get new input weights and zero output
    weights, so the network's output barely changes. Also resets their Adam state. Returns revived counts per block.
    """
    ile = []
    h = F.relu(siec.wejscie(siec.norm(X)))
    for b in siec.bloki:
        wew = F.relu(b.w1(b.ln(h)))
        wynik = wew.abs().mean(0)
        uspione = wynik <= prog * wynik.mean().clamp_min(1e-12)
        n = int(uspione.sum())
        ile.append(n)
        if n:
            wej = b.w1.weight.shape[1]
            granica = 1 / math.sqrt(wej)                 # like the default nn.Linear initialization
            b.w1.weight[uspione] = torch.empty(n, wej, device=X.device).uniform_(-granica, granica)
            b.w1.bias[uspione] = torch.empty(n, device=X.device).uniform_(-granica, granica)
            b.w2.weight[:, uspione] = 0
            for par, maska in ((b.w1.weight, uspione), (b.w1.bias, uspione), (b.w2.weight, (slice(None), uspione))):
                stan = opt.state.get(par, {})
                for klucz in ("exp_avg", "exp_avg_sq"):
                    if klucz in stan:
                        stan[klucz][maska] = 0
        h = h + b.w2(wew)
    return ile


def zapisz_atomowo(obiekt, sciezka):
    tmp = sciezka.with_suffix(".tmp")
    torch.save(obiekt, tmp)
    os.replace(tmp, sciezka)


def wagi_fp16(siec):
    # scalars (e.g. the normalization counter, ~1e8) stay in fp32 - in fp16 they would overflow to inf
    return {k: (v.half() if v.is_floating_point() and v.dim() > 0 else v).cpu() for k, v in siec.state_dict().items()}


class Wspolne:
    """Latest weights for the collectors (CPU copy) + pool of older versions."""

    def __init__(self):
        self.zamek = threading.Lock()
        self.wagi, self.wersja = None, 0
        self.pula = []
        self.stop = False

    def opublikuj(self, siec, wersja):
        w = {k: v.detach().to("cpu", copy=True) for k, v in siec.state_dict().items()}
        with self.zamek:
            self.wagi, self.wersja = w, wersja


class Zbieracz(threading.Thread):
    def __init__(self, nr, port, args, wspolne, kolejka, rcon_port=None, rcon_haslo=None):
        super().__init__(daemon=True, name=f"zbieracz-{nr}")
        self.nr, self.port, self.args, self.wspolne, self.kolejka = nr, port, args, wspolne, kolejka
        self.rcon_port, self.rcon_haslo = rcon_port, rcon_haslo
        self.blad = None

    def run(self):
        bledy = 0
        while not self.wspolne.stop:
            try:
                self.petla()
            except Exception as e:                     # server hung / connection dropped: retry, do not kill training
                bledy += 1
                print(f"collector {self.nr} (port {self.port}): {e!r} - retrying in 30 s (error #{bledy})", flush=True)
                if bledy >= 20:                        # something is permanently broken: report to the main thread
                    self.blad = e
                    self.kolejka.put(None)
                    return
                time.sleep(30)

    def petla(self):
        args, T = self.args, self.args.dlugosc
        rng = np.random.default_rng()
        siec = Siec(WEJSCIE, GLOWY, args.szer, args.bloki).eval()
        przeciwnik = Siec(WEJSCIE, GLOWY, args.szer, args.bloki).eval()
        wersja = -1
        nA, n4 = args.areny, args.multi
        n1 = nA - n4                                       # 1v1 arenas (first), then 1v3
        n_bot = max(1, round(n1 * args.udzial_bot))
        n_pula = round(n1 * args.udzial_pula)
        rola = np.array(["self"] * nA, dtype=object)
        rola[n1 - n_bot:n1] = "bot"
        rola[n1 - n_bot - n_pula:n1 - n_bot] = "pula"
        rola[:args.naucz_klatki] = "naucz"                 # self-play with the item teacher in the loop (DAgger)
        rola[n1:] = "v3"                                   # 1v3: enemies are bot v2 (Python) or pvp-bot (server side)
        n_pb = min(n4, args.multi_pvpbot) if self.rcon_port else 0
        rola[nA - n_pb:] = "pb3"
        most = Most(nA, args.tempo, port=self.port, n4=n4)
        n = most.n
        zestawy = ZESTAWY_ETAP6 if args.etap6 else ZESTAWY_ETAP5 if args.przedmioty else ZESTAWY_ETAP3 if args.bronie else None
        rozklad = tuple(float(x) for x in args.rozklad_botow.split(","))
        sedzia = Sedzia(nA, zestawy=zestawy, rng=rng, lag=args.lag, kara_remis=args.kara_remis, n4=n4,
                        rozklad_botow=rozklad, teren=(args.teren_plasko, args.teren_nowy) if args.etap6 else None,
                        start_przedmiot=args.start_przedmiot)
        u = sedzia.u
        rola_g = rola[u.klatka]
        steruje = np.array(["siec"] * n, dtype=object)
        for a in range(nA):
            if rola[a] in ("pula", "bot"):
                steruje[u.pocz[a] + 1] = rola[a]
            elif rola[a] == "v3":
                steruje[u.pocz[a] + 1:u.pocz[a] + 4] = "bot"
            elif rola[a] == "pb3":
                steruje[u.pocz[a] + 1:u.pocz[a] + 4] = "pvpbot"
        uczy = steruje == "siec"
        m_pula, m_bot, m_pb = steruje == "pula", steruje == "bot", steruje == "pvpbot"
        m_self = (rola_g == "self") | (rola_g == "naucz")
        m_naucz = rola_g == "naucz"
        naucz = None
        if m_naucz.any():
            from bots import BotPrzedmioty
            naucz = BotPrzedmioty(n, rng, args.naucz_umie.split(","), tylko_rzucane=True)
        k_bot, k_pula = rola == "bot", rola == "pula"
        k_v3, k_pb = rola == "v3", rola == "pb3"
        p0 = u.pocz
        pvpbot = None
        if n_pb:
            from tournament import PvpBot
            pvpbot = PvpBot(n, np.where(m_pb)[0], port_rcon=self.rcon_port, haslo_z=self.rcon_haslo,
                            cele=lambda: sedzia.cel)
        _, obs = most.obserwacje()
        bot, stos = BotV2(n, rng), Stos(n)
        X = sedzia.wejscie_sieci(stos, obs)
        while not self.wspolne.stop:
            with self.wspolne.zamek:
                if self.wspolne.wersja != wersja:
                    siec.load_state_dict(self.wspolne.wagi)
                    wersja = self.wspolne.wersja
                pula = list(self.wspolne.pula)
            z_puli = bool(pula)
            if z_puli:
                try:
                    przeciwnik.load_state_dict(dopasuj(
                        {k: v.float() for k, v in torch.load(random.choice(pula), map_location="cpu").items()}, WEJSCIE))
                except FileNotFoundError:                  # the oldest copy has just been dropped from the pool
                    z_puli = False
            steruje_bot = m_bot | (m_pula & (not z_puli))
            if sedzia.lag is not None:
                sedzia.lag.wylacz = steruje_bot | m_pb          # bots (gauge and 1v3) play without lag
            bX = np.zeros((T, n, WEJSCIE), np.float32)
            bA = np.zeros((T, n, len(GLOWY)), np.int64)
            bLP, bV, bR, bD = (np.zeros((T, n), np.float32) for _ in range(4))
            bM = np.zeros((T, n), bool)
            lX, lA = [], []
            st = {k: 0.0 for k in ["bw", "bp", "br", "bzab", "bsm", "bz", "bo", "rund", "zab", "pkt", "rem", "dl",
                                   "odl", "odl_n", "pw", "pp", "pr"] + PRZEDM}
            t0 = time.time()
            for k in range(T):
                with torch.no_grad():
                    xt = torch.as_tensor(X)
                    logity, v = siec(xt)
                    idx, logp, _ = siec.rozklad(logity)
                    if z_puli:
                        mp = torch.as_tensor(m_pula)
                        idx[mp] = przeciwnik.rozklad(przeciwnik(xt[mp])[0])[0]
                idx, logp, v = idx.numpy(), logp.numpy(), v.numpy()
                idx_bota = bot(obs, sedzia.cel)
                idx[steruje_bot] = idx_bota[steruje_bot]
                nad = np.zeros(n, bool)
                if naucz is not None:                      # the teacher takes over pitch, slot and right button when it wants to use an item;
                    idx_n = naucz(obs, sedzia.prawy, sedzia.cel)   # those ticks become lessons, not PPO samples
                    nad = m_naucz & (naucz.tryb > 0) & ~sedzia.smiec[u.klatka]
                    if nad.any():
                        w_ = np.where(nad)[0]
                        idx[np.ix_(w_, [6, 7, 8])] = idx_n[np.ix_(w_, [6, 7, 8])]
                        lX.append(X[nad].astype(np.float16))
                        lA.append(idx[nad].astype(np.int8))
                bX[k], bA[k], bLP[k], bV[k] = X, idx, logp, v
                poza = obs[:, WYPADL_] > 0.5
                bM[k] = uczy & ~sedzia.smiec[u.klatka] & ~poza & ~nad
                akc = sedzia.akcje(akcje_z_indeksow(idx))
                akc[m_pb] = np.nan                         # pvp-bot is driven server-side by itself
                sedzia.wyslij(most, akc)
                _, obs_po = most.obserwacje()
                w = sedzia.krok(obs, obs_po)
                bR[k] = w["r"]
                bD[k] = w["koniec"][u.klatka]
                wazne = ~w["swieze"][u.klatka]
                kon, zg, w0 = w["koniec"], w["zginal"], w["wynik0"]
                kb = kon & k_bot
                st["bzab"] += int((kb & zg[np.minimum(p0 + 1, n - 1)]).sum())
                st["bsm"] += int((kb & zg[p0]).sum())
                st["bw"] += int((kb & (w0 > 0)).sum())
                st["bp"] += int((kb & (w0 < 0)).sum())
                st["br"] += int((kb & w["remis"]).sum())
                if z_puli:
                    kp = kon & k_pula
                    st["pw"] += int((kp & (w0 > 0)).sum())
                    st["pp"] += int((kp & (w0 < 0)).sum())
                    st["pr"] += int((kp & w["remis"]).sum())
                for kl, pre in ((k_v3 | k_pb, "v3"), (k_pb & (sedzia.ile_botow_runda == 3), "pb3")):
                    kk = kon & kl
                    st[pre + "_rund"] += int(kk.sum())
                    st[pre + "_w"] += int((kk & (w0 > 0)).sum())
                    if pre == "v3":
                        st["v3_p"] += int((kk & (w0 < 0)).sum())
                        st["v3_r"] += int((kk & (w0 == 0)).sum())
                    st[pre + "_zab"] += int((w["zabici"] * kl).sum())
                gb = k_bot[u.klatka] & (u.k == 0) & wazne
                st["bz"] += float(w["zadane"][gb].sum())
                st["bo"] += float(w["stracone"][gb].sum())
                jeden = kon & ~u.multi
                st["rund"] += int(jeden.sum())
                st["zab"] += int((jeden & (zg[p0] | zg[np.minimum(p0 + 1, n - 1)])).sum())
                st["pkt"] += int((w["na_punkty"] & ~u.multi).sum())
                st["rem"] += int((w["remis"] & ~u.multi).sum())
                st["dl"] += int(sedzia.t_ep[jeden].sum())
                st["odl"] += float(obs_po[m_self & wazne, ODL].sum())
                st["odl_n"] += int((m_self & wazne).sum())
                # items: uses by the learning network (counter differences within the same round)
                g = uczy & wazne & ~poza
                if g.any():
                    st["strzaly"] += float(np.maximum(0, obs[g, STRZALY] - obs_po[g, STRZALY]).sum())
                    st["traf_strzala"] += float(np.maximum(0, obs_po[g, TRAF_STRZALA] - obs[g, TRAF_STRZALA]).sum())
                    st["blokady"] += float(np.maximum(0, obs_po[g, ZABLOKOWANE] - obs[g, ZABLOKOWANE]).sum())
                    st["zjedzone"] += float(np.maximum(0, obs[g, JABLKA] + obs[g, STEKI] - obs_po[g, JABLKA] - obs_po[g, STEKI]).sum())
                    st["haki"] += float(((obs_po[g, HAK] > 1.5) & (obs[g, HAK] < 1.5)).sum())
                    st["bloki_post"] += float(np.maximum(0, obs_po[g, POSTAWIONE] - obs[g, POSTAWIONE]).sum())
                    st["lawa"] += float(((obs[g, WIADRO] == 1) & (obs_po[g, WIADRO] == 2)).sum())
                    st["leczenie"] += float(np.maximum(0, obs[g, LECZENIE] - obs_po[g, LECZENIE]).sum())
                    st["mikstury"] += float(np.maximum(0, obs[g, MIKSTURA_ILE] - obs_po[g, MIKSTURA_ILE]).sum())
                st["rund_przedm"] += int((jeden & (obs[p0, ZESTAW] > 0)).sum())
                sw = w["swieze"][u.klatka]
                bot.nowa_walka(sw)
                if naucz is not None:
                    naucz.nowa_walka(sw)
                if pvpbot is not None:
                    pvpbot.nowa_walka(sw)
                X = sedzia.wejscie_sieci(stos, obs_po, w["swieze"])
                obs = obs_po
            with torch.no_grad():
                v_dalej = siec(torch.as_tensor(X))[1].numpy()
            st["naucz_ticki"] = sum(len(x) for x in lX)
            self.kolejka.put(dict(serwer=self.nr, wersja=wersja, X=bX, A=bA, LP=bLP, V=bV, R=bR, D=bD, M=bM,
                                  LX=np.concatenate(lX) if lX else None, LA=np.concatenate(lA) if lA else None,
                                  v_dalej=v_dalej, st=st, czas=time.time() - t0, len_pula=len(pula)))


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp2")
    a.add_argument("--wznow", action="store_true")
    a.add_argument("--porty", default="25599", help="bridge ports of the training servers, comma-separated")
    a.add_argument("--areny", type=int, default=32, help="arenas per server")
    a.add_argument("--tempo", type=float, default=10000)
    a.add_argument("--dlugosc", type=int, default=256, help="ticks collected per batch")
    a.add_argument("--epoki", type=int, default=3)
    a.add_argument("--minibatch", type=int, default=4096)
    a.add_argument("--lr", type=float, default=1e-4)
    a.add_argument("--gamma", type=float, default=0.995)
    a.add_argument("--lam", type=float, default=0.95)
    a.add_argument("--clip", type=float, default=0.2)
    a.add_argument("--entropia", type=float, default=0.003)
    a.add_argument("--szer", type=int, default=2048)
    a.add_argument("--bloki", type=int, default=3)
    a.add_argument("--udzial_pula", type=float, default=0.15)
    a.add_argument("--udzial_bot", type=float, default=0.15)
    a.add_argument("--co_ile_pula", type=int, default=20, help="copy to the pool every N updates")
    a.add_argument("--max_pula", type=int, default=10)
    a.add_argument("--watki_cpu", type=int, default=3, help="PyTorch threads (collectors' network on CPU)")
    a.add_argument("--waga_czlowieka", type=float, default=0.05,
                   help="weight of the imitation (lesson) loss for recorded demonstrations (recordings/<pattern>)")
    a.add_argument("--batch_czlowieka", type=int, default=512)
    a.add_argument("--lekcje", default="pad_*.npz", help="lesson files in recordings/ (glob pattern), e.g. lekcje_przedm_*.npz")
    a.add_argument("--glowy_lekcji", default="", help="lessons only for these heads, e.g. 7,8 (empty = all)")
    a.add_argument("--co_ile_ozywiac", type=int, default=100, help="revive dormant neurons every N updates (0 = never)")
    a.add_argument("--bronie", action="store_true", help="stages 2-3: random sword/axe/fists loadouts every round (armor with weapons)")
    a.add_argument("--kara_remis", type=float, default=0.5, help="draw penalty for both sides (0.8 from stage 3 on)")
    a.add_argument("--co_ile_archiwum", type=int, default=200, help="permanent weight archive every N updates")
    a.add_argument("--przedmioty", action="store_true", help="stage 5: bow, food, shield, fishing rod, blocks (ZESTAWY_ETAP5)")
    a.add_argument("--lag", action="store_true", help="\"fair network\" stage: ping 0-200 ms (features.Lag)")
    a.add_argument("--etap6", action="store_true", help="stage 6: terrain, lava, potions (ZESTAWY_ETAP6)")
    a.add_argument("--multi", type=int, default=0, help="number of 1v3 arenas per server (at the end of the arena list)")
    a.add_argument("--multi_pvpbot", type=int, default=0, help="how many of the 1v3 arenas have pvp-bot enemies (the rest: bot v2)")
    a.add_argument("--rcony", default="", help="RCON ports of the training servers (comma-separated, in --porty order)")
    a.add_argument("--rozklad_botow", default="0.25,0.35,0.4", help="probability of 1, 2, 3 enemies in a 1v3 arena")
    a.add_argument("--teren_plasko", type=float, default=0.2, help="probability of a flat round")
    a.add_argument("--naucz_klatki", type=int, default=0, help="self-play arenas per server with the item teacher in the loop")
    a.add_argument("--naucz_umie", default="mikstura", help="skills of the in-loop teacher")
    a.add_argument("--waga_online", type=float, default=0.3, help="weight of lessons from the in-loop teacher")
    a.add_argument("--waga_rzut", type=float, default=1.0, help="relative weight of lekcje6_rzut_* lessons (potion throws)")
    a.add_argument("--zacheta_prawy", type=float, default=0.0,
                   help="entropy bonus for the right button while holding a lava bucket or a potion")
    a.add_argument("--start_przedmiot", type=float, default=0.0, help="fraction of item rounds that start with the item in hand")
    a.add_argument("--teren_nowy", type=float, default=0.3, help="probability of new random terrain (otherwise: same terrain)")
    a.add_argument("--ozyw_na_start", action="store_true", help="revive dormant neurons on the first update")
    a.add_argument("--entropia_skok", type=float, default=0.0,
                   help="extra incentive to try jump and sprint (heads 1 and 2), decays linearly")
    a.add_argument("--glowy_zachety", default="1,2", help="heads with extra entropy (1,2 = jump, sprint; 7,8 = slot, right button)")
    a.add_argument("--wygaszanie", type=int, default=1500, help="number of updates after which the extra incentive is gone")
    a.add_argument("--godziny", type=float, default=0, help="0 = no limit")
    a.add_argument("--urzadzenie", default="mps")
    args = a.parse_args()

    dev = torch.device(args.urzadzenie)
    os.nice(10)                                          # the show and human play take priority
    torch.set_num_threads(args.watki_cpu)
    pauza = Path(__file__).parent / "PAUZA"
    kat = Path(__file__).parent / "runs" / args.nazwa
    (kat / "pula").mkdir(parents=True, exist_ok=True)

    glowy_zachety = [int(g) for g in args.glowy_zachety.split(",")]
    glowy_lekcji = [int(g) for g in args.glowy_lekcji.split(",")] if args.glowy_lekcji else []
    siec = Siec(WEJSCIE, GLOWY, args.szer, args.bloki).to(dev)
    opt = torch.optim.Adam(siec.parameters(), lr=args.lr, eps=1e-5)
    aktualizacja, kroki_razem = 0, 0
    if args.wznow and (kat / "siec.pt").exists():
        z = torch.load(kat / "siec.pt", map_location=dev, weights_only=False)
        stary_wej = z["siec"]["wejscie.weight"].shape[1]
        stare_wyj = z["siec"]["polityka.weight"].shape[0]
        siec.load_state_dict(dopasuj(z["siec"], WEJSCIE))
        if z.get("opt") is not None and stary_wej == WEJSCIE and stare_wyj == sum(GLOWY):
            opt.load_state_dict(z["opt"])
            for g in opt.param_groups:
                g["lr"] = args.lr
        else:
            print("optimizer reset")
        aktualizacja, kroki_razem = z["aktualizacja"], z["kroki"]
        print(f"resumed from update {aktualizacja} ({kroki_razem:,} steps)")

    wspolne = Wspolne()
    # pula_stala/: permanent opponents (healthy older versions, an anchor against self-play drift); pula/: latest copies
    stala = sorted((kat / "pula_stala").glob("*.pt"))
    wspolne.pula = stala + sorted((kat / "pula").glob("u*.pt"))[-args.max_pula:]
    (kat / "archiwum").mkdir(exist_ok=True)
    if stala:
        print(f"fixed pool: {[p.name for p in stala]}", flush=True)
    wspolne.opublikuj(siec, aktualizacja)
    porty = [int(p) for p in args.porty.split(",")]
    kolejka = queue.Queue(maxsize=len(porty))
    rcony = [int(p) for p in args.rcony.split(",")] if args.rcony else [None] * len(porty)
    katalogi = {25599: "server1", 25602: "server2", 25603: "server3"}
    zbieracze = [Zbieracz(i, p, args, wspolne, kolejka, rcony[i],
                          (Path(__file__).parent.parent / katalogi.get(p, "server1") / "rcon_haslo.txt"))
                 for i, p in enumerate(porty)]
    for z_ in zbieracze:
        z_.start()
    print(f"network {siec.liczba_parametrow():,} parameters, learning on {dev}, collecting on CPU | servers {porty} with "
          f"{args.areny} arenas | lr {args.lr} entropy {args.entropia}", flush=True)

    plik_log = kat / "log.csv"
    if plik_log.exists():                                  # old header: add the new columns
        wiersze = plik_log.read_text().splitlines()
        if wiersze and wiersze[0] != ",".join(KOLUMNY):
            wiersze[0] = ",".join(KOLUMNY)
            plik_log.write_text("\n".join(wiersze) + "\n")
    nowy_log = not plik_log.exists()
    log = open(plik_log, "a", newline="")
    pisarz = csv.writer(log)
    if nowy_log:
        pisarz.writerow(KOLUMNY)
    start = time.time()
    ostatnia = time.time()
    akt_startu = aktualizacja
    nagrania = Path(__file__).parent / "recordings"
    lekcje_pliki, Xc, Ac, Gc, Wc = set(), None, None, None, None

    def wczytaj_lekcje():
        """Lessons (demonstrations): network input + the demonstrator's actions (same action heads as the network)."""
        nonlocal lekcje_pliki, Xc, Ac, Gc, Wc
        pliki = set().union(*[nagrania.glob(w) for w in args.lekcje.split(",")])
        if pliki == lekcje_pliki:
            return
        xs, as_, gs, ws = [], [], [], []
        for f in sorted(pliki):
            try:
                d = np.load(f)
                x = d["X"].astype(np.float32)
                if x.shape[1] < WEJSCIE:                   # lessons from an earlier stage: new features = zeros
                    x = np.concatenate([x, np.zeros((len(x), WEJSCIE - x.shape[1]), np.float32)], 1)
                xs.append(x)
                as_.append(d["A"].astype(np.int64))
                # stage-6 lessons (lava, potions: look at your feet or the enemy's feet) also teach pitch
                gl = [6, 7, 8] if f.name.startswith("lekcje6") else (glowy_lekcji or list(range(len(GLOWY))))
                gm = np.zeros((len(x), len(GLOWY)), bool)
                gm[:, gl] = True
                gs.append(gm)
                # throw lessons (validated: +9 pts for the teacher) can be weighted up
                ws.append(np.full(len(x), args.waga_rzut if f.name.startswith("lekcje6_rzut") else 1.0, np.float32))
            except Exception as e:                         # file being written or corrupted: skip
                print(f"lessons: skipping {f.name} ({e})", flush=True)
        lekcje_pliki = pliki
        if not xs:
            Xc = Ac = None
        if xs:
            Xc = torch.as_tensor(np.concatenate(xs), device=dev)
            Ac = torch.as_tensor(np.concatenate(as_), device=dev)
            Gc = torch.as_tensor(np.concatenate(gs), device=dev)
            Wc = torch.as_tensor(np.concatenate(ws), device=dev)
            print(f"lessons: {len(Xc):,} ticks from {len(xs)} files", flush=True)

    wczytaj_lekcje()
    N_ON = 60000                                           # lessons from the in-loop teacher (most recent)
    Xo = torch.zeros((N_ON, WEJSCIE), device=dev)
    Ao = torch.zeros((N_ON, len(GLOWY)), dtype=torch.int64, device=dev)
    n_on, wsk_on = 0, 0
    while True:
        if aktualizacja % 20 == 0:
            wczytaj_lekcje()
        if pauza.exists():
            print("paused: a human is fighting the network in the show", flush=True)
            if dev.type == "mps":
                torch.mps.empty_cache()
            while pauza.exists():
                time.sleep(2)
            print("pause over", flush=True)
            ostatnia = time.time()
        p_ = kolejka.get()
        if p_ is None:
            bledy = [z_.blad for z_ in zbieracze if z_.blad is not None]
            raise RuntimeError(f"collector died: {bledy[0]!r}") from bledy[0]
        if p_.get("LX") is not None:
            lx = torch.as_tensor(p_["LX"].astype(np.float32), device=dev)[-N_ON:]
            la = torch.as_tensor(p_["LA"].astype(np.int64), device=dev)[-N_ON:]
            k_ = len(lx)
            gdzie = (torch.arange(k_, device=dev) + wsk_on) % N_ON
            Xo[gdzie], Ao[gdzie] = lx, la
            wsk_on, n_on = (wsk_on + k_) % N_ON, min(N_ON, n_on + k_)
        T, n = p_["R"].shape
        bR, bD, bV = p_["R"], p_["D"], p_["V"]
        adv = np.zeros((T, n), np.float32)
        ost = np.zeros(n, np.float32)
        for k in reversed(range(T)):
            nv = p_["v_dalej"] if k == T - 1 else bV[k + 1]
            delta = bR[k] + args.gamma * nv * (1 - bD[k]) - bV[k]
            ost = delta + args.gamma * args.lam * (1 - bD[k]) * ost
            adv[k] = ost
        ret = adv + bV
        m = p_["M"].reshape(-1)
        Xt = torch.as_tensor(p_["X"].reshape(-1, WEJSCIE)[m], device=dev)
        At = torch.as_tensor(p_["A"].reshape(-1, len(GLOWY))[m], device=dev)
        LPt = torch.as_tensor(p_["LP"].reshape(-1)[m], device=dev)
        ADVt = torch.as_tensor(adv.reshape(-1)[m], device=dev)
        ADVt = (ADVt - ADVt.mean()) / (ADVt.std() + 1e-8)
        RETt = torch.as_tensor(ret.reshape(-1)[m], device=dev)

        t1 = time.time()
        N = len(Xt)
        ent_s = vl_s = kl_s = cf_s = cz_s = 0.0
        dodatek_skok = args.entropia_skok * max(0.0, 1 - (aktualizacja - akt_startu) / max(1, args.wygaszanie))
        skok_proc = float((At[:, 1] == 1).float().mean()) * 100
        sprint_proc = float((At[:, 2] == 1).float().mean()) * 100
        jest_czlowiek = Xc is not None and len(Xc) >= 200 and args.waga_czlowieka > 0
        licz = 0
        for _ in range(args.epoki):
            perm = torch.randperm(N, device=dev)
            for q in range(0, N, args.minibatch):
                i = perm[q:q + args.minibatch]
                logity, v = siec(Xt[i])
                _, logp, ent_g = siec.rozklad(logity, At[i], na_glowy=True)
                ent = ent_g.sum(1)
                ratio = torch.exp(logp - LPt[i])
                pg = -torch.min(ratio * ADVt[i], torch.clamp(ratio, 1 - args.clip, 1 + args.clip) * ADVt[i]).mean()
                vl = 0.5 * ((v - RETt[i]) ** 2).mean()
                loss = pg + 0.5 * vl - args.entropia * ent.mean()
                if dodatek_skok > 0:                       # heads 1 (jump) and 2 (sprint): keep trying them
                    loss = loss - dodatek_skok * ent_g[:, glowy_zachety].sum(1).mean()
                if args.zacheta_prawy > 0:                 # with lava / potion in hand: try the right button
                    xr = Xt[i]
                    w_rece = (xr[:, KOL_W_RECE] + xr[:, KOL_W_RECE + 2] + xr[:, KOL_W_RECE + 3]) > 0.5
                    loss = loss - args.zacheta_prawy * (ent_g[:, 8] * w_rece).mean()
                if jest_czlowiek:                          # small weight, so the demonstrator's weaknesses are not copied
                    ic = torch.randint(0, len(Xc), (args.batch_czlowieka,), device=dev)
                    # lessons only for selected decisions (e.g. slot and right button) - the head mask depends on the file
                    czesci = torch.split(siec(Xc[ic])[0], GLOWY, dim=-1)
                    logp_c = sum(F.log_softmax(czesci[g].float(), -1).gather(-1, Ac[ic][:, g:g + 1]).squeeze(-1) * Gc[ic][:, g]
                                 for g in range(len(GLOWY)))
                    strata_c = -(logp_c * Wc[ic]).mean()
                    loss = loss + args.waga_czlowieka * strata_c
                    cz_s += float(strata_c.detach())
                if n_on >= 500 and args.waga_online > 0:   # in-loop teacher: pitch, slot, right button
                    io = torch.randint(0, n_on, (args.batch_czlowieka,), device=dev)
                    czesci = torch.split(siec(Xo[io])[0], GLOWY, dim=-1)
                    logp_o = sum(F.log_softmax(czesci[g].float(), -1).gather(-1, Ao[io][:, g:g + 1]).squeeze(-1) for g in (6, 7, 8))
                    loss = loss - args.waga_online * logp_o.mean()
                opt.zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(siec.parameters(), 0.5)
                opt.step()
                with torch.no_grad():
                    ent_s += float(ent.mean())
                    vl_s += float(vl)
                    kl_s += float((LPt[i] - logp).mean())
                    cf_s += float(((ratio - 1).abs() > args.clip).float().mean())
                licz += 1
        siec.norm.aktualizuj(Xt)
        ozywione = ""
        if (args.co_ile_ozywiac and (aktualizacja + 1) % args.co_ile_ozywiac == 0) or (args.ozyw_na_start and aktualizacja == akt_startu):
            ile = ozyw_neurony(siec, opt, Xt[:8192])
            ozywione = "/".join(map(str, ile))
            print(f"revived dormant neurons in blocks: {ozywione} (of {siec.ustawienia['szer']} in each)", flush=True)
        aktualizacja += 1
        wspolne.opublikuj(siec, aktualizacja)
        t_nauki = time.time() - t1

        kroki_razem += T * n
        teraz = time.time()
        kps = T * n / (teraz - ostatnia)                   # total throughput (all servers together)
        ostatnia = teraz
        st = p_["st"]
        rund = max(1, st["rund"])
        opozn = aktualizacja - 1 - p_["wersja"]
        pisarz.writerow([aktualizacja, kroki_razem, round(kps), round(p_["czas"], 2), round(t_nauki, 2),
                         int(st["bw"]), int(st["bp"]), int(st["br"]), int(st["bzab"]), int(st["bsm"]),
                         round(st["bz"], 1), round(st["bo"], 1), int(st["rund"]), int(st["zab"]), int(st["pkt"]),
                         int(st["rem"]), round(st["dl"] / rund), round(st["odl"] / max(1, st["odl_n"]), 2),
                         round(ent_s / licz, 3), round(vl_s / licz, 4), round(kl_s / licz, 4), round(cf_s / licz, 3),
                         p_["len_pula"], round(time.time() - start), p_["serwer"], opozn,
                         int(st["pw"]), int(st["pp"]), int(st["pr"]),
                         len(Xc) if Xc is not None else 0, round(cz_s / licz, 4) if jest_czlowiek else "", ozywione,
                         round(skok_proc, 2), round(sprint_proc, 2)] + [int(st[k]) for k in PRZEDM])
        log.flush()
        print(f"upd {aktualizacja:5d} [s{p_['serwer']}] | {kroki_razem / 1e6:7.2f} M | {kps:5.0f} steps/s | vs bot v2: "
              f"W {int(st['bw'])} L {int(st['bp'])} D {int(st['br'])} | vs pool: W {int(st['pw'])} L {int(st['pp'])} (dealt {st['bz']:.0f} / taken {st['bo']:.0f}) | "
              f"rounds {int(st['rund'])}: kills {int(st['zab'])}, on points {int(st['pkt'])}, draws {int(st['rem'])} | "
              f"dist {st['odl'] / max(1, st['odl_n']):.1f} | ent {ent_s / licz:.2f} kl {kl_s / licz:.4f} | "
              f"collect {p_['czas']:.1f}s learn {t_nauki:.1f}s lag {opozn}"
              + (f" | in-loop teacher {int(st.get('naucz_ticki', 0))} ticks (buffer {n_on})" if args.naucz_klatki else ""), flush=True)

        if aktualizacja % 5 == 0:
            zapisz_atomowo({"wagi": wagi_fp16(siec), "ustawienia": siec.ustawienia, "aktualizacja": aktualizacja},
                           kat / "siec_pokaz.pt")
        if aktualizacja % 10 == 0:
            zapisz_atomowo({"siec": siec.state_dict(), "opt": opt.state_dict(), "aktualizacja": aktualizacja,
                            "kroki": kroki_razem, "args": vars(args)}, kat / "siec.pt")
        if aktualizacja % args.co_ile_pula == 0:
            p = kat / "pula" / f"u{aktualizacja:06d}.pt"
            zapisz_atomowo(wagi_fp16(siec), p)
            with wspolne.zamek:
                wspolne.pula.append(p)
                biezace = [q for q in wspolne.pula if q.parent.name == "pula"]
                while len(biezace) > args.max_pula:          # the fixed pool stays
                    stary = biezace.pop(0)
                    wspolne.pula.remove(stary)
                    stary.unlink(missing_ok=True)
        if args.co_ile_archiwum and aktualizacja % args.co_ile_archiwum == 0:   # never deleted
            zapisz_atomowo({"wagi": wagi_fp16(siec), "ustawienia": siec.ustawienia, "aktualizacja": aktualizacja},
                           kat / "archiwum" / f"u{aktualizacja:06d}.pt")
        if args.godziny and time.time() - start > args.godziny * 3600:
            break

    wspolne.stop = True
    zapisz_atomowo({"siec": siec.state_dict(), "opt": opt.state_dict(), "aktualizacja": aktualizacja,
                    "kroki": kroki_razem, "args": vars(args)}, kat / "siec.pt")


if __name__ == "__main__":
    main()
