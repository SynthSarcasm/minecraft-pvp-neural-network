"""Auto-start for the assistant (LaunchAgent pl.pvp.assistant): every 3 s, if a Minecraft process with the
game directory `minecraft-siec` is running, keep assistant.py running; stop it when the game closes.

  python game_watcher.py
"""
import subprocess
import sys
import time
from pathlib import Path

KAT = Path(__file__).parent
ZNAK_GRY = "minecraft-siec"            # game directory of the assistant installation (--gameDir in the Java command line)
CO_ILE = 3.0


def gra_dziala():
    out = subprocess.run(["ps", "-axo", "pid=,command="], capture_output=True, text=True).stdout
    for linia in out.splitlines():
        if "java" in linia and ZNAK_GRY in linia and "--gameDir" in linia:
            return True
    return False


def log(tekst):
    print(time.strftime("%Y-%m-%d %H:%M:%S ") + tekst, flush=True)


def main():
    siec = None
    log("assistant watcher: started, waiting for the installation with game directory " + ZNAK_GRY)
    while True:
        gra = gra_dziala()
        if gra and (siec is None or siec.poll() is not None):
            if siec is not None:
                log(f"the network exited by itself (code {siec.returncode}) - restarting")
            plik_logu = open(KAT / "assistant_auto.log", "a", buffering=1)
            siec = subprocess.Popen([sys.executable, "-u", str(KAT / "assistant.py")], cwd=KAT,
                                    stdout=plik_logu, stderr=subprocess.STDOUT)
            log(f"game detected - network started (pid {siec.pid}), log: training/assistant_auto.log")
        elif not gra and siec is not None and siec.poll() is None:
            siec.terminate()
            try:
                siec.wait(10)
            except subprocess.TimeoutExpired:
                siec.kill()
            log("game closed - network stopped")
            siec = None
        time.sleep(CO_ILE)


if __name__ == "__main__":
    main()
