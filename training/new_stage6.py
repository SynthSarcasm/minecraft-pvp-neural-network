"""Create the stage-6 run from the end of stage 5: append the new inputs (zero weights) and slots 6-8, whose bias
is set by bisection so each is picked ~--szansa of the time; set up the fixed opponent pool.

  python new_stage6.py --z pvp6 --do pvp7
"""
import argparse
import shutil
from pathlib import Path

import numpy as np
import torch
from features import GLOWY, GLOWY_ETAP5, WEJSCIE
from network import Siec, dopasuj_wejscie, wstaw_sloty

KAT = Path(__file__).parent


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--z", default="pvp6")
    a.add_argument("--do", default="pvp7")
    a.add_argument("--szansa", type=float, default=0.01, help="mean probability of picking each of the new slots")
    args = a.parse_args()
    zrodlo, cel = KAT / "runs" / args.z, KAT / "runs" / args.do
    (cel / "pula_stala").mkdir(parents=True, exist_ok=True)
    z = torch.load(zrodlo / "siec.pt", map_location="cpu", weights_only=False)
    stan = dopasuj_wejscie(z["siec"], WEJSCIE)
    # typical situations: stage-5 lesson inputs
    xs = [np.load(f)["X"].astype(np.float32) for f in sorted((KAT / "recordings").glob("lekcje_przedm_*.npz"))]
    X = np.concatenate(xs)[:20000]
    X = torch.as_tensor(np.concatenate([X, np.zeros((len(X), WEJSCIE - X.shape[1]), np.float32)], 1))
    s = Siec(WEJSCIE, GLOWY).eval()
    s.load_state_dict(wstaw_sloty(stan, -30.0))
    with torch.no_grad():
        logity = s(X)[0]
    p0 = sum(GLOWY[:7])
    stare = logity[:, p0:p0 + 7].double()                    # "no change" + slots 0-5
    lo, hi = -40.0, 20.0
    for _ in range(60):
        b = (lo + hi) / 2
        p = (np.exp(b) / (torch.exp(stare).sum(1) + 3 * np.exp(b))).mean().item()
        lo, hi = (b, hi) if p < args.szansa else (lo, b)
    b = (lo + hi) / 2
    print(f"bias of the new slots: {b:.2f} (on average {args.szansa:.1%} per slot); old: no change "
          f"{torch.softmax(stare, 1)[:, 0].mean():.1%}")
    stan = wstaw_sloty(stan, b)
    s.load_state_dict(stan)
    torch.save({"siec": stan, "opt": None, "aktualizacja": z["aktualizacja"], "kroki": z["kroki"]}, cel / "siec.pt")
    wagi = lambda st: {k: (v.half() if v.is_floating_point() and v.dim() > 0 else v) for k, v in st.items()}
    torch.save({"wagi": wagi(z["siec"]), "aktualizacja": z["aktualizacja"]}, cel / "przed_etapem.pt")
    torch.save({"wagi": wagi(stan), "ustawienia": s.ustawienia, "aktualizacja": z["aktualizacja"]}, cel / "siec_pokaz.pt")
    torch.save(wagi(z["siec"]), cel / "pula_stala" / f"s{z['aktualizacja']:06d}_koniec_pvp6.pt")
    m = torch.load(zrodlo / "mistrz_przedmiotow.pt", map_location="cpu", weights_only=False)
    torch.save(m["wagi"], cel / "pula_stala" / f"s{m['aktualizacja']:06d}_mistrz_przedm.pt")
    shutil.copy(zrodlo / "pula_stala" / "s012150_ping.pt", cel / "pula_stala" / "s012150_ping.pt")
    print(f"saved {cel}/siec.pt (update {z['aktualizacja']}, input {WEJSCIE}, heads {GLOWY})")


if __name__ == "__main__":
    main()
