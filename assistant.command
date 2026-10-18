#!/bin/zsh
# Start the assistant's network (double-click in Finder). Keep the window open while you play.
cd "$(dirname "$0")/training"
# the auto-start service already runs it
if launchctl print gui/$(id -u)/pl.pvp.assistant >/dev/null 2>&1; then
  echo "The assistant already starts together with the game (service pl.pvp.assistant). Nothing to do - you can close this window."
  exit 0
fi
echo "PvP assistant - waiting for Minecraft (port 8790). Close this window to turn the network off."
exec .venv/bin/python -u assistant.py "$@"
