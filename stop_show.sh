#!/bin/zsh
# Stops the show for a friend: bore tunnel, the network in the show and the show server.
pkill -f "bore local 25566"
pkill -f "show.py --nazwa"
pkill -f "pvpmost.port=25600"
rm -f "$(dirname "$0")/training/PAUZA"
echo "show stopped, tunnel closed"
