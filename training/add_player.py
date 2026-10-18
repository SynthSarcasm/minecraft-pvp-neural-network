"""Adds a player to the whitelist of the show server (offline mode, i.e. without account verification).

  python add_player.py FriendsNick [OtherNick ...]

The nick must match the launcher exactly (case-sensitive). Takes effect after a server restart
(show_for_friend.sh does that by itself).
"""
import hashlib
import json
import sys
import uuid
from pathlib import Path

PLIK = Path(__file__).parent.parent / "server-show" / "whitelist.json"


def offline_uuid(nick):
    """Offline-mode player UUID: as in Minecraft, a v3 UUID of "OfflinePlayer:<nick>"."""
    h = bytearray(hashlib.md5(("OfflinePlayer:" + nick).encode()).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


def main():
    lista = json.loads(PLIK.read_text()) if PLIK.exists() else []
    for nick in sys.argv[1:]:
        if any(x["name"] == nick for x in lista):
            print(f"{nick}: already on the list")
            continue
        lista.append({"uuid": offline_uuid(nick), "name": nick})
        print(f"{nick}: added")
    PLIK.write_text(json.dumps(lista, indent=2))
    print("whitelist:", ", ".join(x["name"] for x in lista))


if __name__ == "__main__":
    main()
