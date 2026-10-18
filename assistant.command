#!/bin/zsh
# PvP assistant: starts the network (stage-6 champion) for the pvppilot client mod. Double-click it in Finder.
# Keep the window open while you play. Closing the window = network off (the game keeps running normally).
cd "$(dirname "$0")/training"
# if the auto-start service (pl.pvp.assistant) is installed, a manual start would conflict with it
if launchctl print gui/$(id -u)/pl.pvp.assistant >/dev/null 2>&1; then
  echo "The assistant already starts together with the game (service pl.pvp.assistant). Nothing to do — you can close this window."
  exit 0
fi
echo "PvP assistant — waiting for Minecraft (port 8790). Close this window to turn the network off."
exec .venv/bin/python -u assistant.py "$@"
