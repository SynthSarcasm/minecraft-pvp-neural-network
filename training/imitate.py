"""Behaviour cloning + DAgger of bot v2 as the starting point for PPO. Also fits the value head on discounted
referee rewards. Saves runs/<nazwa>/siec.pt, which train.py --wznow resumes from.

  python imitate.py --nazwa pvp2
"""
import argparse
import os
import time
from pathlib import Path

import numpy as np
import torch
from bots import BotV2
from features import GLOWY, WEJSCIE, Sedzia, Stos, akcje_z_indeksow
from bridge import Most
from network import Siec, najpewniejsze


class Sesja:
    """Server connection together with the game state. The server always waits for our actions for the last
    observation, so the subsequent phases (collection, DAgger, check) must continue from it instead of
    reading a new one - otherwise both sides wait for each other."""

    def __init__(self, areny, rng):
        self.most = Most(areny, 10000)
        self.n = self.most.n
        _, self.obs = self.most.obserwacje()
        self.sedzia, self.bot, self.stos = Sedzia(areny), BotV2(self.n, rng), Stos(self.n)
        self.X = self.sedzia.wejscie_sieci(self.stos, self.obs)

    def krok(self, idx):
        self.most.akcje(akcje_z_indeksow(idx), self.sedzia.resety(), self.sedzia.bronie())
        _, obs_po = self.most.obserwacje()
        w = self.sedzia.krok(self.obs, obs_po)
        self.bot.nowa_walka(np.repeat(w["swieze"], 2))
        self.X = self.sedzia.wejscie_sieci(self.stos, obs_po, w["swieze"])
        self.obs = obs_po
        return w


def zbierz(ses, siec, dev, ticki, udzial_sieci, rng, gamma):
    """Returns (X fp16 [N, WEJSCIE], bot labels [N, heads], returns [N]); the network controls udzial_sieci of the players."""
    n = ses.n
    bX = np.zeros((ticki, n, WEJSCIE), np.float16)
    bA = np.zeros((ticki, n, len(GLOWY)), np.int8)
    bR, bD = np.zeros((ticki, n), np.float32), np.zeros((ticki, n), np.float32)
    bM = np.zeros((ticki, n), bool)
    steruje_siec = np.zeros(n, bool)
    for t in range(ticki):
        if t % 600 == 0:                                   # every 30 s of game time draw which players the network controls
            steruje_siec = rng.random(n) < udzial_sieci
        idx_bota = ses.bot(ses.obs)
        idx = idx_bota.copy()
        if siec is not None and steruje_siec.any():
            with torch.no_grad():
                l = siec(torch.as_tensor(ses.X[steruje_siec], device=dev))[0]
                idx[steruje_siec] = siec.rozklad(l)[0].cpu().numpy()
        bX[t], bA[t] = ses.X, idx_bota
        bM[t] = ~np.repeat(ses.sedzia.smiec, 2)
        w = ses.krok(idx)
        bR[t], bD[t] = w["r"], np.repeat(w["koniec"], 2)
    # value targets; the last 300 ticks have no bootstrap, so drop them
    G = np.zeros((ticki, n), np.float32)
    g = np.zeros(n, np.float32)
    for t in reversed(range(ticki)):
        g = bR[t] + gamma * g * (1 - bD[t])
        G[t] = g
    koniec = ticki - 300
    m = bM[:koniec].reshape(-1)
    return (bX[:koniec].reshape(-1, WEJSCIE)[m], bA[:koniec].reshape(-1, len(GLOWY))[m], G[:koniec].reshape(-1)[m])


def ucz(siec, opt, dane, dev, epoki, batch, tytul):
    X, A, G = dane
    N = len(X)
    perm = np.random.permutation(N)
    test, tren = perm[: N // 20], perm[N // 20:]
    Xd = torch.as_tensor(X, device=dev)
    Ad = torch.as_tensor(A.astype(np.int64), device=dev)
    Gd = torch.as_tensor(G, device=dev)
    for e in range(epoki):
        t0 = time.time()
        siec.train()
        np.random.shuffle(tren)
        s_ce = s_v = 0.0
        k = 0
        for p in range(0, len(tren), batch):
            i = torch.as_tensor(tren[p:p + batch], device=dev)
            logity, v = siec(Xd[i].float())
            _, logp, _ = siec.rozklad(logity, Ad[i])
            ce = -logp.mean()
            vl = ((v - Gd[i]) ** 2).mean()
            loss = ce + 0.5 * vl
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(siec.parameters(), 1.0)
            opt.step()
            s_ce += float(ce.detach())
            s_v += float(vl.detach())
            k += 1
        siec.eval()
        with torch.no_grad():
            trafne = np.zeros(len(GLOWY))
            for p in range(0, len(test), 8192):
                i = torch.as_tensor(test[p:p + 8192], device=dev)
                pred = najpewniejsze(siec, siec(Xd[i].float())[0])
                trafne += (pred == Ad[i]).float().sum(0).cpu().numpy()
        traf = trafne / len(test) * 100
        print(f"  {tytul} epoch {e + 1}/{epoki}: imitation loss {s_ce / k:.3f}, value {s_v / k:.4f} | accuracy % "
              f"(move {traf[0]:.0f}, jump {traf[1]:.0f}, sprint {traf[2]:.0f}, attack {traf[3]:.0f}, "
              f"sneak {traf[4]:.0f}, yaw {traf[5]:.0f}, pitch {traf[6]:.0f}) | {time.time() - t0:.0f} s", flush=True)
    del Xd, Ad, Gd


def sprawdzian(ses, siec, dev, ticki, losowo):
    """Network (player 2a) vs bot v2 (player 2a+1). -> (wins, losses, draws)"""
    n = ses.n
    nasi = np.arange(n) % 2 == 0
    W = P = R = 0
    for _ in range(ticki):
        idx = ses.bot(ses.obs)
        with torch.no_grad():
            l = siec(torch.as_tensor(ses.X[nasi], device=dev))[0]
            idx[nasi] = (siec.rozklad(l)[0] if losowo else najpewniejsze(siec, l)).cpu().numpy()
        w = ses.krok(idx)
        k, zg, b = w["koniec"], w["zginal"], ses.sedzia.bilans[0::2]
        W += int((k & (zg[1::2] | (w["na_punkty"] & (b > 0)))).sum())
        P += int((k & (zg[0::2] | (w["na_punkty"] & (b < 0)))).sum())
        R += int((k & w["remis"]).sum())
    return W, P, R


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--nazwa", default="pvp2")
    a.add_argument("--areny", type=int, default=32)
    a.add_argument("--ticki", type=int, default=16000, help="collection ticks per data round (x 64 players)")
    a.add_argument("--dagger", type=int, default=2, help="number of DAgger rounds after plain imitation")
    a.add_argument("--epoki", type=int, default=3)
    a.add_argument("--batch", type=int, default=4096)
    a.add_argument("--lr", type=float, default=2e-4)
    a.add_argument("--gamma", type=float, default=0.995)
    a.add_argument("--urzadzenie", default="mps")
    args = a.parse_args()

    dev = torch.device(args.urzadzenie)
    kat = Path(__file__).parent / "runs" / args.nazwa
    kat.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(0)
    torch.manual_seed(0)
    siec = Siec(WEJSCIE, GLOWY).to(dev)
    opt = torch.optim.Adam(siec.parameters(), lr=args.lr, eps=1e-5)
    print(f"network {siec.liczba_parametrow():,} parameters", flush=True)

    t0 = time.time()
    ses = Sesja(args.areny, rng)
    dane = zbierz(ses, None, dev, args.ticki, 0.0, rng, args.gamma)
    print(f"collected {len(dane[0]):,} bot moves in {time.time() - t0:.0f} s", flush=True)
    siec.norm.aktualizuj(torch.as_tensor(dane[0][::4], device=dev).float())
    ucz(siec, opt, dane, dev, args.epoki, args.batch, "imitation")

    for r in range(args.dagger):
        t0 = time.time()
        nowe = zbierz(ses, siec, dev, args.ticki // 2, 0.5 if r == 0 else 0.8, rng, args.gamma)
        dane = tuple(np.concatenate([d, nd]) for d, nd in zip(dane, nowe))
        print(f"DAgger {r + 1}: added {len(nowe[0]):,} bot labels ({time.time() - t0:.0f} s), total {len(dane[0]):,}",
              flush=True)
        ucz(siec, opt, dane, dev, max(1, args.epoki - 1), args.batch, f"DAgger {r + 1}")

    for losowo in (False, True):
        W, P, R = sprawdzian(ses, siec, dev, 8000, losowo)
        print(f"check ({'sampled' if losowo else 'most likely move'}): network vs bot v2 - "
              f"wins {W}, losses {P}, draws {R} ({100 * W / max(1, W + P + R):.0f}% wins)", flush=True)
    ses.most.zamknij()

    tmp = kat / "siec.tmp"
    torch.save({"siec": siec.state_dict(), "opt": None, "aktualizacja": 0, "kroki": 0, "args": vars(args)}, tmp)
    os.replace(tmp, kat / "siec.pt")
    torch.save({"wagi": {k: (v.half() if v.is_floating_point() else v).cpu() for k, v in siec.state_dict().items()},
                "ustawienia": siec.ustawienia, "aktualizacja": 0}, kat / "siec_pokaz.pt")
    print(f"saved {kat / 'siec.pt'}", flush=True)


if __name__ == "__main__":
    main()
