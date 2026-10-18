#!/bin/zsh
# Show for a friend outside your home network: show server + network + bore tunnel.
# The friend connects to bore.pub:<port> (a new port on every start),
# types /haslo <password> (if server-show/pvpmost_haslo.txt exists), then /walcz [piesci|miecz|siekiera] [network's weapon].
# Stop: ./stop_show.sh
cd "$(dirname "$0")"
KAT="$PWD"

if ! pgrep -f "pvpmost.port=25600" >/dev/null; then
  (cd server-show && nohup ./start.sh > server.log 2>&1 &)
  echo "starting the show server…"
  until nc -z 127.0.0.1 25600 2>/dev/null; do sleep 1; done   # the mod opens the Python bridge when the server is ready
fi
if ! pgrep -f "show.py --nazwa" >/dev/null; then
  (cd training && nohup .venv/bin/python -u show.py --nazwa pvp7 --z_losowaniem --lag --przedmioty --etap6 > show_pvp7.log 2>&1 &)
fi
pkill -f "bore local 25566" 2>/dev/null
nohup bore local 25566 --to bore.pub > "$KAT/bore.log" 2>&1 &
until grep -q "listening at" "$KAT/bore.log" 2>/dev/null; do sleep 1; done
ADRES=$(sed 's/\x1b\[[0-9;]*m//g' "$KAT/bore.log" | grep -o "bore.pub:[0-9]*" | tail -1)
echo
echo "  Address for your friend:  $ADRES   (Minecraft Java 26.3, Add server)"
[ -f server-show/pvpmost_haslo.txt ] && echo "  After joining:            /haslo $(cat server-show/pvpmost_haslo.txt)"
echo "  Fight:                    /walcz siekiera   (or: /walcz miecz siekiera, /walcz piesci; plain /walcz = leave)"
