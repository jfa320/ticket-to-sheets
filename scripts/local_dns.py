"""Publica un nombre .local mientras la aplicación Windows está ejecutándose."""

import argparse
import ctypes
from ctypes import wintypes
import re
import socket


def local_address():
    # connect de UDP selecciona la interfaz de salida sin enviar paquetes.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
        connection.connect(("192.0.2.1", 9))
        return connection.getsockname()[0]


def local_name(value):
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", value):
        raise argparse.ArgumentTypeError("Usá entre 1 y 63 letras minúsculas, números o guiones.")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", type=local_name, default="facturas")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--app-pid", type=int, required=True)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or args.app_pid <= 0:
        parser.error("El puerto debe estar entre 1 y 65535 y el PID debe ser positivo.")

    try:
        from zeroconf import IPVersion, ServiceInfo, Zeroconf
    except ImportError:
        parser.exit(1, "Instalá la dependencia opcional con python -m pip install -r scripts/requirements-local-dns.txt\n")

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel.WaitForSingleObject.restype = wintypes.DWORD
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.CloseHandle.restype = wintypes.BOOL
    process = kernel.OpenProcess(0x00100000, False, args.app_pid)  # SYNCHRONIZE
    if not process:
        raise ctypes.WinError(ctypes.get_last_error())

    def app_running(timeout_ms=0):
        status = kernel.WaitForSingleObject(process, timeout_ms)
        if status == 0xFFFFFFFF:  # WAIT_FAILED
            raise ctypes.WinError(ctypes.get_last_error())
        return status == 258  # WAIT_TIMEOUT

    try:
        while app_running():
            try:
                address = local_address()
            except OSError:
                # Recuperar el anuncio cuando vuelve el Wi-Fi.
                app_running(5000)
                continue
            info = ServiceInfo(
                "_http._tcp.local.",
                f"{args.name}._http._tcp.local.",
                server=f"{args.name}.local.",
                addresses=[socket.inet_aton(address)],
                port=args.port,
                properties={"path": "/"},
            )
            with Zeroconf(interfaces=[address], ip_version=IPVersion.V4Only) as publisher:
                publisher.register_service(info)
                try:
                    print(f"DNS local listo en http://{args.name}.local:{args.port} ({address})", flush=True)
                    while app_running(5000):
                        try:
                            if local_address() != address:
                                break
                        except OSError:
                            break
                finally:
                    publisher.unregister_service(info)
    finally:
        kernel.CloseHandle(process)


if __name__ == "__main__":
    main()
