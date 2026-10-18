"""Do lava and potions work as they should (show server, port 25600, flat, diamond armor).

Arena 0: A hits a standing B down to HP < 10, then B throws a healing II potion at its feet — how much HP B regained, and A (standing next to it).
Arena 1: A walks up and pours lava under the feet of a standing B (B in armor) — B's HP and fire over 15 s (B leaves the lava after 2 s).
Arena 2: A throws a harming II potion (kind 7) at B from 3 blocks — B's HP.

  python test_mechanics.py
"""
import numpy as np
import bridge as M
from bots import BotV2, _najblizszy
from features import PITCH_PROGI, Sedzia, Stos, akcje_z_indeksow
from bridge import Most


def main():
    m = Most(3, 10000, port=25600)
    sed = Sedzia(3, zestawy=[((1, 1), 64 | 32 | 128, 1.0)], teren=None)
    sed.nast_mikstura[:] = 7
    u = sed.u
    stos = Stos(m.n)
    v2 = BotV2(m.n)
    _, obs = m.obserwacje()
    sed.wejscie_sieci(stos, obs)
    faza = [0, 0, 0]
    t_fazy = [0, 0, 0]
    log = {0: [], 1: [], 2: []}
    for t in range(1400):
        idx = v2(obs, sed.cel)
        idx[:, 7] = 0
        idx[:, 8] = 0
        for a in range(3):
            A, B = u.pocz[a], u.pocz[a] + 1
            idx[B] = [4, 0, 0, 0, 0, 7, 4, 0, 0]          # B stands still
            ja = obs[A]
            odl = ja[M.ODL]
            t_fazy[a] += 1
            if a == 0:
                if faza[0] == 0 and obs[B, M.HP] < 10:
                    faza[0], t_fazy[0] = 1, 0
                if faza[0] >= 1:
                    idx[A] = [4, 0, 0, 0, 0, 7, 4, 0, 0]
                    idx[B, 7] = 8                         # slot 7: healing
                    idx[B, 6] = _najblizszy(PITCH_PROGI, np.clip([90 - obs[B, M.PITCH]], -20, 20))[0]
                    if faza[0] == 1 and obs[B, M.PITCH] > 85 and t_fazy[0] > 3:
                        idx[B, 8] = 1
                        faza[0], t_fazy[0] = 2, 0
                        log[0].append(("throw", t, float(obs[B, M.HP]), float(obs[A, M.HP])))
                    elif faza[0] == 2:
                        idx[B, 8] = 2
                        if t_fazy[0] in (2, 5, 10, 20):
                            log[0].append(("after", t_fazy[0], float(obs[B, M.HP]), float(obs[A, M.HP]), float(obs[B, M.LECZENIE])))
            else:
                s_ = 6 if a == 1 else 8
                if faza[a] == 0:
                    idx[A, 3] = 0
                    if odl < 3.0:
                        idx[A, 0] = 4
                        idx[A, 7] = s_ + 1
                        poziom = np.hypot(obs[B, M.X] - ja[M.X], obs[B, M.Z] - ja[M.Z])
                        chce = -np.degrees(np.arctan2(obs[B, M.Y] - (ja[M.Y] + 1.62), poziom)) + (4 if a == 1 else 0)
                        idx[A, 6] = _najblizszy(PITCH_PROGI, np.clip([chce - ja[M.PITCH]], -20, 20))[0]
                        if ja[M.SLOT] == s_ and abs(chce - ja[M.PITCH]) < 5:
                            idx[A, 8] = 1
                            faza[a], t_fazy[a] = 1, 0
                            log[a].append(("use", t, float(obs[B, M.HP]), float(obs[A, M.MIKSTURA_ILE]), float(obs[A, M.TRZYMA]), float(odl)))
                elif faza[a] >= 1:
                    idx[A] = [4, 0, 0, 0, 0, 7, 4, 0, 2]
                    if a == 1 and 40 < t_fazy[a] < 70:
                        idx[B, 0] = 7                     # B walks out of the lava (forward)
                    if t_fazy[a] in (1, 5, 10, 20, 40, 80, 150, 300):
                        log[a].append((t_fazy[a], float(obs[B, M.HP]), float(obs[B, M.OGIEN_T]), float(obs[B, M.W_LAWIE]),
                                       float(obs[A, M.WIADRO]), float(obs[A, M.MIKSTURA_ILE]), float(obs[A, M.TRZYMA]), float(obs[A, M.MIKSTURA])))
        if t > 0:
            sed.smiec[:] = False                          # one round without resets
        sed.wyslij(m, sed.akcje(akcje_z_indeksow(idx)))
        _, po = m.obserwacje()
        sed.krok(obs, po)
        sed.wejscie_sieci(stos, po)
        obs = po
    m.zamknij()
    for a, nazwa in ((0, "healing"), (1, "lava"), (2, "harming")):
        print(nazwa, log[a])


if __name__ == "__main__":
    main()
