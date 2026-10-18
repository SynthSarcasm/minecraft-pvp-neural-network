"""Markdown table from runs/pvp7/ocena_koncowa*.json - for the results document."""
import json
from pathlib import Path

NAZWY = [("v3_pelny", "1v3 pvp-bot, full loadout"), ("v3_tarcza", "1v3 pvp-bot: weapon+shield+food+healing"),
         ("v1_pvpbot", "1v1 pvp-bot"), ("teren", "1v1 terrain, full loadout (vs pre-stage)"), ("bron", "weapons only"),
         ("mikstura", "weapon + potion"), ("leczenie", "weapon + healing"), ("lawa", "weapon + lava"), ("wedka", "weapon + fishing rod"),
         ("p_bron", "fists"), ("p_mikst", "fists + offensive potion"), ("p_lecz", "fists + healing"),
         ("p_lawa", "fists + lava"), ("piesci", "fists vs fists champion (forgetting)")]
w = []
for f in sorted((Path(__file__).parent / "runs/pvp7").glob("ocena_koncowa*.json")):
    w += json.loads(f.read_text())
w.sort(key=lambda r: r["akt"])
print("| Test | " + " | ".join(f"v{r['akt']}" for r in w) + " |")
print("|---|" + "---|" * len(w))
for k, opis in NAZWY:
    kom = []
    for r in w:
        t = r.get(k)
        if not t:
            kom.append("-")
            continue
        uz = {"mikstura": t["mikstury"], "p_mikst": t["mikstury"], "leczenie": t["leczenie"], "p_lecz": t["leczenie"],
              "lawa": t["lawa"], "p_lawa": t["lawa"], "wedka": t["haki"]}.get(k)
        dod = f" ({t['czyste_proc']}% all)" if k.startswith("v3") else f" (uses {uz})" if uz is not None else ""
        kom.append(f"{t['proc']}%{dod}")
    print(f"| {opis} | " + " | ".join(kom) + " |")
