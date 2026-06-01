"""Tiny TCP relay so a phone on the LAN can reach the HUD emulator.
   listens on 0.0.0.0:18080  ->  forwards to  127.0.0.1:18080
   (adb -s emulator-5554 forward tcp:18080 tcp:28080 must already be active).
"""
import socket
import threading

LAN_PORT = 18080
LOCAL_PORT = 18080  # where adb-forward listens


def pipe(src, dst):
    try:
        while True:
            data = src.recv(8192)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try: src.shutdown(socket.SHUT_RDWR)
        except Exception: pass
        try: dst.shutdown(socket.SHUT_RDWR)
        except Exception: pass


def handle(client):
    try:
        upstream = socket.create_connection(("127.0.0.1", LOCAL_PORT), timeout=5)
    except Exception as e:
        print(f"upstream fail: {e}")
        client.close()
        return
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", LAN_PORT))
srv.listen(64)
print(f"relay listening on 0.0.0.0:{LAN_PORT} -> 127.0.0.1:{LOCAL_PORT}")
while True:
    c, addr = srv.accept()
    print(f"accept {addr}")
    threading.Thread(target=handle, args=(c,), daemon=True).start()
