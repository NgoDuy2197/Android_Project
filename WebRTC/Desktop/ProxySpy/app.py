"""
ProxySpy — a HTTPS man-in-the-middle debugging proxy (Dsoft).

Another device points its Wi-Fi proxy at this PC; ProxySpy decrypts the traffic
(after the device trusts the generated CA) and shows every request, header and
payload in a local web dashboard.

Run:  python app.py            (proxy :8080, dashboard http://127.0.0.1:8081)
      python app.py --port 9090 --dash-port 9091

Engine: mitmproxy (TLS interception, HTTP/2, WebSocket). UI: a self-contained
dashboard served from dashboard.html.
"""

import argparse
import asyncio
import os
import socket
import subprocess
import sys
import webbrowser

from mitmproxy import options
from mitmproxy.tools.dump import DumpMaster

from capture import Capture, FlowStore
from rules import RuleStore, Rules
from server import start_dashboard

# When frozen (PyInstaller), keep the CA/config next to the .exe so it persists across runs;
# otherwise next to this script.
if getattr(sys, "frozen", False):
    APP_DIR = os.path.dirname(os.path.abspath(sys.executable))
else:
    APP_DIR = os.path.dirname(os.path.abspath(__file__))
HERE = APP_DIR


def free_port(port: int):
    """Windows: kill whatever process is LISTENING on [port] so a restart never hits 'port in use'."""
    if os.name != "nt" or not port:
        return
    try:
        out = subprocess.check_output(["netstat", "-ano", "-p", "tcp"],
                                      text=True, stderr=subprocess.DEVNULL)
    except Exception:
        return
    me = os.getpid()
    pids = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[0].upper() == "TCP" and parts[3].upper() == "LISTENING":
            local = parts[1]
            if local.rsplit(":", 1)[-1] == str(port):
                pid = parts[-1]
                if pid.isdigit() and int(pid) not in (0, me):
                    pids.add(pid)
    for pid in pids:
        try:
            subprocess.run(["taskkill", "/F", "/PID", pid],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass


def lan_ips():
    """Best-effort list of this PC's LAN IPv4 addresses (for the phone to point at)."""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        host = socket.gethostname()
        for info in socket.getaddrinfo(host, None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips


def banner(args, ips):
    line = "=" * 64
    print(line)
    print("  ProxySpy — HTTPS debugging proxy (Dsoft)")
    print(line)
    if ips:
        print("  Trên điện thoại, đặt Proxy Wi-Fi thủ công:")
        for ip in ips:
            print(f"      Host {ip}    Port {args.port}")
    else:
        print(f"  Proxy đang nghe ở cổng {args.port} (chưa thấy IP LAN — kiểm tra Wi-Fi).")
    dash_ip = ips[0] if (ips and args.dash_host == "0.0.0.0") else args.dash_host
    print(f"  Dashboard:  http://{dash_ip}:{args.dash_port}  (bấm nút QR để quét bằng điện thoại)")
    if ips and args.dash_host == "0.0.0.0":
        print(f"  Cài CA cho máy khác:  http://{ips[0]}:{args.dash_port}/ca/cert.cer")
        print("  (Dashboard đang mở cho mạng LAN — dữ liệu bắt được lộ trong mạng. Dùng --dash-host 127.0.0.1 để khoá.)")
    print(f"  Thư mục CA: {args.confdir}")
    print(line)
    print("  Ctrl+C để dừng.")
    print(line, flush=True)


async def amain(args, store, info, rules):
    opts = options.Options(listen_host=args.host, listen_port=args.port)
    # Keep the config (CA + settings) inside the app folder so it's self-contained.
    for name, value in (("confdir", args.confdir), ("ssl_insecure", True)):
        try:
            setattr(opts, name, value)
        except Exception:
            try:
                opts.update(**{name: value})
            except Exception:
                pass
    master = DumpMaster(opts, with_termlog=True, with_dumper=False)
    master.addons.add(Rules(rules))      # before Capture: rules modify, then capture records final
    master.addons.add(Capture(store))
    info["master"] = master
    await master.run()


def main():
    # Windows consoles default to a legacy codepage (cp1252) that can't print Vietnamese.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    ap = argparse.ArgumentParser(description="ProxySpy — HTTPS debugging proxy")
    ap.add_argument("--host", default="0.0.0.0", help="địa chỉ proxy lắng nghe (mặc định mọi IP)")
    ap.add_argument("--port", type=int, default=8080, help="cổng proxy cho điện thoại (mặc định 8080)")
    ap.add_argument("--dash-host", default="0.0.0.0",
                    help="địa chỉ dashboard (mặc định 0.0.0.0 để máy cùng mạng xem/quét QR; dùng 127.0.0.1 để khoá về máy này)")
    ap.add_argument("--dash-port", type=int, default=8081, help="cổng dashboard (mặc định 8081)")
    ap.add_argument("--confdir", default=os.path.join(HERE, "ca"), help="thư mục chứa CA/cấu hình")
    ap.add_argument("--max-body", type=int, default=2_000_000, help="giới hạn byte body lưu mỗi chiều")
    ap.add_argument("--no-open", action="store_true", help="không tự mở trình duyệt")
    args = ap.parse_args()

    # Free the ports from a previous instance before binding (so "đang chạy" can be replaced cleanly).
    free_port(args.port)
    free_port(args.dash_port)

    os.makedirs(args.confdir, exist_ok=True)
    store = FlowStore(max_body=args.max_body)
    rules = RuleStore(os.path.join(args.confdir, "rules.json"))
    ips = lan_ips()
    info = {
        "proxyHost": ips[0] if ips else args.host, "proxyPort": args.port, "ips": ips,
        "dashPort": args.dash_port, "dashLan": args.dash_host == "0.0.0.0",
    }

    try:
        start_dashboard(args.dash_host, args.dash_port, store, info, args.confdir, rules)
    except OSError as e:
        print(f"[!] Không mở được dashboard ở cổng {args.dash_port}: {e}", file=sys.stderr)
        sys.exit(1)

    banner(args, ips)
    if not args.no_open:
        local = "127.0.0.1" if args.dash_host in ("0.0.0.0", "") else args.dash_host
        try:
            webbrowser.open(f"http://{local}:{args.dash_port}")
        except Exception:
            pass

    try:
        asyncio.run(amain(args, store, info, rules))
    except KeyboardInterrupt:
        print("\nĐã dừng ProxySpy.")
    except OSError as e:
        print(f"[!] Không mở được proxy ở cổng {args.port}: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
