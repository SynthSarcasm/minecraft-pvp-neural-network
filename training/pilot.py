"""Connection to the "pvppilot" client mod: the network drives a real Minecraft client and records the screen.

Messages (see Pilot.java): 1 = action (10 × float32), 2 = record to file, 3 = stop recording, 4 = chat command, 5 = camera.
"""
import select
import socket
import struct
import threading
import time
class PilotKlient:
    def __init__(self, komendy=(), port=8790):
        self.port, self.komendy = port, list(komendy)
        self.s = None
        self.zamek = threading.Lock()
        threading.Thread(target=self._lacz, daemon=True).start()

    @property
    def polaczony(self):
        return self.s is not None

    def _lacz(self):
        while True:
            if self.s is not None:                     # client closed? (socket readable and empty)
                try:
                    r, _, _ = select.select([self.s], [], [], 0)
                    if r and self.s.recv(1, socket.MSG_PEEK) == b"":
                        raise OSError("closed")
                except OSError:
                    print("pilot: client disconnected, waiting for a new one", flush=True)
                    self.s = None
            if self.s is None:
                try:
                    s = socket.create_connection(("127.0.0.1", self.port), timeout=2)
                    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    self.s = s
                    print("pilot: connected to the client", flush=True)
                    time.sleep(3)                      # the client must already be on the server
                    for k in self.komendy:
                        self._tekst(4, k)
                        time.sleep(1.5)
                except OSError:
                    pass
            time.sleep(3)

    def _wyslij(self, dane):
        if self.s is None:
            return
        try:
            with self.zamek:
                self.s.sendall(dane)
        except OSError:
            print("pilot: connection dropped", flush=True)
            self.s = None

    def _tekst(self, typ, tekst):
        b = tekst.encode()
        self._wyslij(bytes([typ]) + struct.pack(">H", len(b)) + b)

    def akcja(self, a):
        self._wyslij(b"\x01" + struct.pack("<10f", *[float(v) for v in a]))

    def nagrywaj(self, plik):
        self._tekst(2, str(plik))

    def kamera(self, nazwa):
        self._tekst(5, nazwa)

    def komenda(self, tekst):
        self._tekst(4, tekst)

    def stop(self):
        self._wyslij(b"\x03")
