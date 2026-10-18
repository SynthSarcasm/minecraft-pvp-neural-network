"""Artificial ping for tests: a TCP proxy that delays each direction by --ms milliseconds (ping ≈ 2 × ms).

    .venv/bin/python -u delay_proxy.py --z 25590 --do 25568 --ms 100     # the client connects to 127.0.0.1:25590
"""
import argparse
import asyncio
async def przepusc(czyt, pisz, opozn):
    kolejka = asyncio.Queue()
    petla = asyncio.get_running_loop()

    async def czytaj():
        while True:
            dane = await czyt.read(65536)
            await kolejka.put((petla.time() + opozn, dane))
            if not dane:
                return

    async def wysylaj():
        while True:
            t, dane = await kolejka.get()
            if t > petla.time():
                await asyncio.sleep(t - petla.time())
            if not dane:
                pisz.close()
                return
            pisz.write(dane)
            await pisz.drain()

    try:
        await asyncio.gather(czytaj(), wysylaj())
    except (ConnectionError, OSError):
        pisz.close()


async def main():
    a = argparse.ArgumentParser()
    a.add_argument("--z", type=int, default=25590)
    a.add_argument("--do", type=int, default=25568)
    a.add_argument("--ms", type=float, default=100.0, help="one-way delay")
    args = a.parse_args()

    async def klient(c_czyt, c_pisz):
        s_czyt, s_pisz = await asyncio.open_connection("127.0.0.1", args.do)
        print(f"delay_proxy: connection, {args.ms:.0f} ms each way", flush=True)
        await asyncio.gather(przepusc(c_czyt, s_pisz, args.ms / 1000), przepusc(s_czyt, c_pisz, args.ms / 1000))

    serwer = await asyncio.start_server(klient, "127.0.0.1", args.z)
    print(f"delay_proxy: 127.0.0.1:{args.z} -> 127.0.0.1:{args.do}, +{args.ms:.0f} ms each way", flush=True)
    async with serwer:
        await serwer.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
