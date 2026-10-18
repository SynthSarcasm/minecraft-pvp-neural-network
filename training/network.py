"""Large PPO network: input normalization, input layer, residual blocks with LayerNorm, two heads.

Residual block: x + W2(ReLU(W1(LN(x)))); the last layer of each block starts at zero, so initially
every block passes the signal through unchanged — large networks train more stably this way.
"""
import torch
import torch.nn as nn
import torch.nn.functional as F


class Normalizacja(nn.Module):
    def __init__(self, wym):
        super().__init__()
        self.register_buffer("srednia", torch.zeros(wym))
        self.register_buffer("war", torch.ones(wym))
        self.register_buffer("n", torch.tensor(1e-4))

    @torch.no_grad()
    def aktualizuj(self, x):
        b_sr, b_war, b_n = x.mean(0), x.var(0, unbiased=False), x.shape[0]
        delta, n = b_sr - self.srednia, self.n + b_n
        self.srednia += delta * b_n / n
        self.war = (self.war * self.n + b_war * b_n + delta ** 2 * self.n * b_n / n) / n
        self.n = n

    def forward(self, x):
        return torch.clamp((x - self.srednia) / torch.sqrt(self.war + 1e-8), -5.0, 5.0)


class Blok(nn.Module):
    def __init__(self, szer):
        super().__init__()
        self.ln = nn.LayerNorm(szer)
        self.w1 = nn.Linear(szer, szer)
        self.w2 = nn.Linear(szer, szer)
        nn.init.zeros_(self.w2.weight)
        nn.init.zeros_(self.w2.bias)

    def forward(self, x):
        return x + self.w2(F.relu(self.w1(self.ln(x))))


class Siec(nn.Module):
    def __init__(self, wejscie, glowy, szer=2048, bloki=3):
        super().__init__()
        self.glowy = list(glowy)
        self.ustawienia = dict(wejscie=wejscie, glowy=self.glowy, szer=szer, bloki=bloki)
        self.norm = Normalizacja(wejscie)
        self.wejscie = nn.Linear(wejscie, szer)
        self.bloki = nn.ModuleList(Blok(szer) for _ in range(bloki))
        self.koniec = nn.LayerNorm(szer)
        self.polityka = nn.Linear(szer, sum(self.glowy))
        self.wartosc = nn.Linear(szer, 1)
        nn.init.orthogonal_(self.polityka.weight, 0.01)    # start: almost equal odds for all actions
        nn.init.zeros_(self.polityka.bias)

    def ukryte(self, x):
        """Last hidden layer (2048 neurons) — both heads read from it."""
        h = F.relu(self.wejscie(self.norm(x)))
        for b in self.bloki:
            h = b(h)
        return F.relu(self.koniec(h))

    def forward(self, x):
        h = self.ukryte(x)
        return self.polityka(h), self.wartosc(h).squeeze(-1)

    def rozklad(self, logity, akcje=None, na_glowy=False):
        """-> (sampled actions [n, heads] if akcje=None, log-probability, entropy)
        na_glowy=True: entropy per head [n, heads] instead of the sum."""
        czesci = torch.split(logity, self.glowy, dim=-1)
        logp, ent, wybrane, ent_g = 0, 0, [], []
        for g, l in enumerate(czesci):
            lp = F.log_softmax(l.float(), -1)
            if akcje is None:
                a = torch.multinomial(lp.exp(), 1).squeeze(-1)
                wybrane.append(a)
            else:
                a = akcje[:, g]
            logp = logp + lp.gather(-1, a[:, None]).squeeze(-1)
            e = -(lp.exp() * lp).sum(-1)
            ent = ent + e
            ent_g.append(e)
        return (torch.stack(wybrane, 1) if akcje is None else None), logp, (torch.stack(ent_g, 1) if na_glowy else ent)

    def liczba_parametrow(self):
        return sum(p.numel() for p in self.parameters())


def _dopisz_glowy(stan, glowy, bias_nowych):
    """Heads appended at the end: zero weights (initially the decision does not depend on the situation) and bias from `bias_nowych`."""
    w = stan["polityka.weight"]
    jest, trzeba = w.shape[0], sum(glowy)
    if jest == trzeba:
        return stan
    assert jest < trzeba, (jest, trzeba)
    bias = []
    for g in range(len(glowy)):
        poczatek = sum(glowy[:g])
        if poczatek >= jest:
            bias += list(bias_nowych.get(g, [0.0] * glowy[g]))
    stan = dict(stan)
    stan["polityka.weight"] = torch.cat([w, w.new_zeros(trzeba - jest, w.shape[1])], 0)
    stan["polityka.bias"] = torch.cat([stan["polityka.bias"], torch.tensor(bias, dtype=w.dtype, device=w.device)])
    return stan


def wstaw_sloty(stan, bias_slotow):
    """Stage 5 -> 6: the slot head grows from 7 (no change + slots 0–5) to 10 (+ slots 6–8: lava, healing, potion).
    New policy rows are inserted after the old rows of that head: zero weights, bias `bias_slotow` (a number or a list of 3)."""
    from features import GLOWY_ETAP5
    w, b = stan["polityka.weight"], stan["polityka.bias"]
    assert w.shape[0] == sum(GLOWY_ETAP5), w.shape
    gdzie = sum(GLOWY_ETAP5[:8])                       # end of the old slot head
    bias = torch.as_tensor(bias_slotow if isinstance(bias_slotow, (list, tuple)) else [bias_slotow] * 3,
                           dtype=b.dtype, device=b.device)
    stan = dict(stan)
    stan["polityka.weight"] = torch.cat([w[:gdzie], w.new_zeros(3, w.shape[1]), w[gdzie:]], 0)
    stan["polityka.bias"] = torch.cat([b[:gdzie], bias, b[gdzie:]])
    return stan


def dopasuj_wyjscie(stan, glowy, bias_nowych):
    """Older checkpoint -> current action heads. Stages 1–4 (no slot and right-button heads): heads 7–8 appended with a "never" bias;
    stage 5 (slots 0–5): slots 6–8 inserted with a "never" bias — old versions play exactly as in their own stage."""
    from features import BIAS_NOWYCH_SLOTOW, GLOWY_ETAP5
    jest = stan["polityka.weight"].shape[0]
    if jest == sum(glowy):
        return stan
    if jest < sum(GLOWY_ETAP5):
        stan = _dopisz_glowy(stan, GLOWY_ETAP5, {7: [0.0] + [-30.0] * 6, 8: [0.0, -30.0, -30.0]})
    if sum(glowy) == sum(GLOWY_ETAP5):
        return stan
    return wstaw_sloty(stan, BIAS_NOWYCH_SLOTOW)


def dopasuj_wejscie(stan, wejscie):
    """Older checkpoint with fewer inputs -> new inputs appended at the end with zero weights
    (initially the network behaves exactly as before, and over time it learns to use them)."""
    w = stan["wejscie.weight"]
    stare = w.shape[1]
    if stare == wejscie:
        return stan
    stan = dict(stan)
    stan["norm.n"] = torch.as_tensor(min(float(stan["norm.n"]), 1e7))   # new inputs: normalization has to keep up
    assert stare < wejscie, (stare, wejscie)
    stan = dict(stan)
    stan["wejscie.weight"] = torch.cat([w, w.new_zeros(w.shape[0], wejscie - stare)], 1)
    stan["norm.srednia"] = torch.cat([stan["norm.srednia"], stan["norm.srednia"].new_zeros(wejscie - stare)])
    stan["norm.war"] = torch.cat([stan["norm.war"], stan["norm.war"].new_ones(wejscie - stare)])
    return stan


def dopasuj(stan, wejscie):
    """Input and output of an older checkpoint adapted to the current network shape (features.WEJSCIE, features.GLOWY)."""
    from features import BIAS_NOWYCH_GLOW, GLOWY
    return dopasuj_wyjscie(dopasuj_wejscie(stan, wejscie), GLOWY, BIAS_NOWYCH_GLOW)


def najpewniejsze(siec, logity):
    """No sampling: in every head the most likely action (what the network "really" picks)."""
    return torch.stack([l.argmax(-1) for l in torch.split(logity, siec.glowy, dim=-1)], 1)
