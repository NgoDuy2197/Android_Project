"""SentryRoom server (pure-Python): serves the HTML client, handles file
upload/download, generates the invite QR, and relays chat + WebRTC signaling +
motion alerts over Socket.IO. Packaged to a single .exe with PyInstaller.

Listens on plain HTTP and — when `cryptography` is installed — also on HTTPS
with a self-signed cert covering every local IP. Phones need the https link
because browsers only expose camera/mic (getUserMedia) in a secure context."""
import asyncio
import base64
import datetime
import io
import ipaddress
import os
import socket
import ssl
import sys
import tempfile
import time
import uuid
import webbrowser

import segno
import socketio
from aiohttp import web

# Windows consoles default to cp1252, which can't encode Vietnamese; make our
# banner safe instead of crashing startup.
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

PORT = int(os.environ.get("PORT", "3000"))
HTTPS_PORT = int(os.environ.get("HTTPS_PORT", str(PORT + 443)))

# Where the bundled web assets live (works both from source and inside the
# PyInstaller one-file bundle, which unpacks to sys._MEIPASS).
BASE = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
WEB_DIR = os.path.join(BASE, "web")
UPLOAD_DIR = os.path.join(tempfile.gettempdir(), "sentryroom_uploads")
CERT_DIR = os.path.join(tempfile.gettempdir(), "sentryroom_cert")
os.makedirs(UPLOAD_DIR, exist_ok=True)

# Set once at startup: True when the HTTPS listener actually came up, so the
# invite link/QR can point phones at the secure origin.
HTTPS_ON = False

# id -> {"path","name","mime","size"}
FILES = {}
# room -> {sid: name} ; sid -> room
ROOMS = {}
SID_ROOM = {}


# ---------------- Local IPv4 discovery ----------------
# Rank 0 wins. Private LAN ranges first: those are the ones a phone on the same
# Wi-Fi can actually reach, and they never leave the building. A private address
# on a *virtual* adapter (Hyper-V, WSL, VMware, Docker…) looks just as internal
# but no phone can route to it, so it ranks below the real NICs.
_PRIVATE_LAN = tuple(ipaddress.ip_network(n) for n in
                     ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
_CGNAT = ipaddress.ip_network("100.64.0.0/10")  # Tailscale / carrier NAT

# Matched against the interface name (lowercased) when psutil is available.
_VIRTUAL_HINTS = (
    "vethernet", "hyper-v", "wsl", "vmware", "virtualbox", "vbox", "docker",
    "loopback pseudo", "bluetooth", "vpn", "tap-", "tap ", "wintun", "tailscale",
    "zerotier", "nordlynx", "hamachi", "radmin", "npcap",
)
# Fallback when the interface name is unknown: well-known virtual subnets.
_VIRTUAL_NETS = tuple(ipaddress.ip_network(n) for n in
                      ("192.168.56.0/24",   # VirtualBox host-only
                       "172.17.0.0/16"))    # Docker default bridge


def _is_virtual(ip, iface):
    if iface and any(h in iface.lower() for h in _VIRTUAL_HINTS):
        return True
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return any(a in n for n in _VIRTUAL_NETS)


def classify(ip, iface=None):
    """(rank, human label) for one IPv4 — lower rank = more preferred."""
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return 9, "Không rõ"
    if a.is_loopback:
        return 5, "Loopback (chỉ máy này)"
    if any(a in n for n in _PRIVATE_LAN):
        if _is_virtual(ip, iface):
            return 1, "LAN nội bộ (card ảo — điện thoại thường không vào được)"
        return 0, "LAN nội bộ"
    if a in _CGNAT:
        return 2, "VPN/CGNAT"
    if a.is_link_local:
        return 3, "Link-local (chưa có DHCP)"
    return 4, "Công khai"


def _default_route_ip():
    """IPv4 the OS would use to reach the outside world, or None."""
    for probe in ("8.8.8.8", "1.1.1.1"):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((probe, 80))
            return s.getsockname()[0]
        except OSError:
            continue
        finally:
            s.close()
    return None


def _candidates():
    """{ip: interface name or None}, plus the default-route ip."""
    found = {}
    try:  # optional: gives friendly names like "Wi-Fi" / "Ethernet"
        import psutil
        for iface, addrs in psutil.net_if_addrs().items():
            for a in addrs:
                if a.family == socket.AF_INET and a.address:
                    found.setdefault(a.address, iface)
    except Exception:
        pass
    try:
        for res in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.setdefault(res[4][0], None)
    except OSError:
        pass
    default = _default_route_ip()
    if default:
        found.setdefault(default, None)
    found.setdefault("127.0.0.1", None)
    return found, default


def list_ips():
    """Every local IPv4, best-first: real internal LAN beats virtual adapters
    beats VPN beats public, loopback last. Inside a rank the address the OS
    routes through wins; the rest sort numerically (not as strings)."""
    found, default = _candidates()
    out = []
    for ip, iface in found.items():
        rank, label = classify(ip, iface)
        out.append({"ip": ip, "iface": iface, "label": label, "rank": rank,
                    "primary": ip == default})
    def key(e):
        try:
            n = int(ipaddress.ip_address(e["ip"]))
        except ValueError:
            n = 0
        return (e["rank"], not e["primary"], n)
    out.sort(key=key)
    return out


def best_ip():
    ips = list_ips()
    return ips[0]["ip"] if ips else "127.0.0.1"


def invite_url(room, ip):
    """Prefer https so phones get camera/mic (getUserMedia needs a secure origin)."""
    if HTTPS_ON:
        return f"https://{ip}:{HTTPS_PORT}/?room={room}"
    return f"http://{ip}:{PORT}/?room={room}"


sio = socketio.AsyncServer(async_mode="aiohttp", cors_allowed_origins="*",
                           max_http_buffer_size=200_000_000)
app = web.Application(client_max_size=1024 ** 3)  # allow big uploads
sio.attach(app)


# ---------------- HTTP routes ----------------
async def upload(request):
    reader = await request.multipart()
    room = name = None
    saved_path = orig_name = mime = None
    size = 0
    async for part in reader:
        if part.name == "room":
            room = (await part.text())
        elif part.name == "name":
            name = (await part.text())
        elif part.name == "file":
            orig_name = part.filename or "file"
            mime = part.headers.get("Content-Type", "application/octet-stream")
            ext = os.path.splitext(orig_name)[1]
            fid = uuid.uuid4().hex + ext
            saved_path = os.path.join(UPLOAD_DIR, fid)
            with open(saved_path, "wb") as f:
                while True:
                    chunk = await part.read_chunk()
                    if not chunk:
                        break
                    size += len(chunk)
                    f.write(chunk)
    if not saved_path:
        return web.json_response({"error": "no file"}, status=400)
    fid = os.path.basename(saved_path)
    FILES[fid] = {"path": saved_path, "name": name or orig_name, "mime": mime, "size": size}
    return web.json_response(
        {"id": fid, "name": FILES[fid]["name"], "size": size, "mime": mime,
         "url": "/download/" + fid})


async def download(request):
    fid = request.match_info["id"]
    m = FILES.get(fid)
    if not m:
        return web.Response(status=404)
    from urllib.parse import quote
    return web.FileResponse(
        m["path"],
        headers={
            "Content-Type": m["mime"] or "application/octet-stream",
            "Content-Disposition": "attachment; filename*=UTF-8''" + quote(m["name"]),
        },
    )


async def api_ips(request):
    """Selectable host addresses, already ordered with internal LAN first."""
    ips = list_ips()
    return web.json_response({
        "ips": ips,
        "best": ips[0]["ip"] if ips else "127.0.0.1",
        "port": HTTPS_PORT if HTTPS_ON else PORT,
        "https": HTTPS_ON,
    })


async def api_qr(request):
    room = request.query.get("room", "main")
    # Only ever encode an address we actually own; anything else falls back.
    wanted = request.query.get("ip")
    ip = wanted if wanted in {e["ip"] for e in list_ips()} else best_ip()
    url = invite_url(room, ip)
    buff = io.BytesIO()
    segno.make(url, error="m").save(buff, kind="png", scale=8, border=2)
    data = "data:image/png;base64," + base64.b64encode(buff.getvalue()).decode()
    return web.json_response({"url": url, "qr": data, "ip": ip, "https": HTTPS_ON})


async def serve_static(request):
    """Serve the web/ client. Defaults to index.html."""
    rel = request.match_info.get("tail", "") or "index.html"
    if rel.endswith("/") or rel == "":
        rel = "index.html"
    path = os.path.normpath(os.path.join(WEB_DIR, rel))
    if not path.startswith(WEB_DIR) or not os.path.isfile(path):
        path = os.path.join(WEB_DIR, "index.html")
    return web.FileResponse(path)


# ---------------- Socket.IO ----------------
@sio.event
async def join(sid, data):
    room = (data or {}).get("room") or "main"
    name = (data or {}).get("name") or "Ẩn danh"
    SID_ROOM[sid] = room
    ROOMS.setdefault(room, {})[sid] = name
    await sio.enter_room(sid, room)
    peers = [{"id": s, "name": n} for s, n in ROOMS[room].items() if s != sid]
    await sio.emit("welcome", {"id": sid, "peers": peers}, to=sid)
    await sio.emit("peer-joined", {"id": sid, "name": name}, room=room, skip_sid=sid)
    await sio.emit("system", f"{name} đã vào phòng", room=room)


def _name(sid):
    room = SID_ROOM.get(sid)
    return ROOMS.get(room, {}).get(sid, "?")


@sio.event
async def chat(sid, msg):
    room = SID_ROOM.get(sid)
    if room:
        await sio.emit("chat", {"from": _name(sid), "id": sid,
                                "text": str((msg or {}).get("text", "")),
                                "ts": time.time() * 1000}, room=room)


@sio.event
async def file(sid, meta):
    room = SID_ROOM.get(sid)
    if room:
        payload = dict(meta or {})
        payload.update({"from": _name(sid), "id": sid, "ts": time.time() * 1000})
        await sio.emit("file", payload, room=room)


@sio.event
async def motion(sid, m):
    """level: 'alert' = movement right now, 'info' = monitoring turned on/off.
    Broadcast as a chat line plus a structured event the client can notify on."""
    room = SID_ROOM.get(sid)
    if not room:
        return
    text = str((m or {}).get("text", ""))
    level = "alert" if (m or {}).get("level") == "alert" else "info"
    await sio.emit("system", f"⚠ {_name(sid)}: {text}", room=room)
    await sio.emit("alert", {"from": _name(sid), "id": sid, "text": text,
                            "level": level, "ts": time.time() * 1000}, room=room)


@sio.event
async def signal(sid, data):
    target = (data or {}).get("to")
    if target:
        await sio.emit("signal", {"from": sid, "name": _name(sid),
                                  "data": (data or {}).get("data")}, to=target)


@sio.event
async def disconnect(sid):
    room = SID_ROOM.pop(sid, None)
    if room:
        name = ROOMS.get(room, {}).pop(sid, "?")
        await sio.emit("peer-left", {"id": sid}, room=room)
        await sio.emit("system", f"{name} rời phòng", room=room)


app.router.add_post("/upload", upload)
app.router.add_get("/download/{id}", download)
app.router.add_get("/api/qr", api_qr)
app.router.add_get("/api/ips", api_ips)
app.router.add_get("/{tail:.*}", serve_static)


# ---------------- HTTPS (self-signed) ----------------
def ensure_cert(ips):
    """Self-signed cert whose SAN covers localhost + every local IP.

    Needed because browsers gate getUserMedia (camera/mic) and the Notification
    API behind a secure context: a phone on http://192.168.x.x sees
    navigator.mediaDevices === undefined. Returns (cert_path, key_path) or None
    when `cryptography` isn't installed.
    """
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID
    except Exception:
        return None

    os.makedirs(CERT_DIR, exist_ok=True)
    crt_path = os.path.join(CERT_DIR, "cert.pem")
    key_path = os.path.join(CERT_DIR, "key.pem")
    san_path = os.path.join(CERT_DIR, "san.txt")
    san_key = "\n".join(sorted(ips))
    # Reuse the cert while the address set is unchanged, so a phone that already
    # trusted it doesn't get a fresh warning on every restart.
    if all(os.path.isfile(p) for p in (crt_path, key_path, san_path)):
        try:
            with open(san_path, encoding="utf-8") as f:
                if f.read() == san_key:
                    return crt_path, key_path
        except OSError:
            pass

    try:
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        alt = [x509.DNSName("localhost")]
        for ip in ips:
            try:
                alt.append(x509.IPAddress(ipaddress.ip_address(ip)))
            except ValueError:
                pass
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "SentryRoom")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=825))
            .add_extension(x509.SubjectAlternativeName(alt), critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256())
        )
        with open(crt_path, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))
        with open(key_path, "wb") as f:
            f.write(key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.TraditionalOpenSSL,
                encryption_algorithm=serialization.NoEncryption()))
        with open(san_path, "w", encoding="utf-8") as f:
            f.write(san_key)
        return crt_path, key_path
    except Exception as e:
        print(f"  (Không tạo được chứng chỉ HTTPS: {e})")
        return None


def _banner(ips):
    scheme = "https" if HTTPS_ON else "http"
    port = HTTPS_PORT if HTTPS_ON else PORT
    print("")
    print("  SentryRoom đang chạy")
    print(f"  Máy này (host):     http://localhost:{PORT}")
    print("  Máy khác quét/nhập (ưu tiên trên xuống):")
    for e in ips:
        tag = " ← mặc định" if e is ips[0] else ""
        iface = f" [{e['iface']}]" if e.get("iface") else ""
        print(f"    {scheme}://{e['ip']}:{port}   {e['label']}{iface}{tag}")
    if HTTPS_ON:
        print("")
        print("  Điện thoại: dùng link https ở trên (bấm 'Nâng cao → Tiếp tục'")
        print("  khi trình duyệt cảnh báo chứng chỉ tự ký) — nếu vào bằng http")
        print("  thì trình duyệt KHÔNG cho dùng camera/micro.")
    else:
        print("")
        print("  Chưa bật HTTPS (thiếu 'cryptography') → điện thoại vào bằng http")
        print("  sẽ không gọi được thoại/video. Cài: pip install cryptography")
    print('  (Bấm "Mời (QR)" trong app để hiện mã QR. Ctrl+C để tắt.)')
    print("")


async def _main():
    global HTTPS_ON
    ips = list_ips()
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", PORT).start()

    if os.environ.get("NO_HTTPS") != "1":
        pair = ensure_cert([e["ip"] for e in ips])
        if pair:
            try:
                ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                ctx.load_cert_chain(pair[0], pair[1])
                await web.TCPSite(runner, "0.0.0.0", HTTPS_PORT, ssl_context=ctx).start()
                HTTPS_ON = True
            except OSError as e:
                print(f"  (Không mở được cổng HTTPS {HTTPS_PORT}: {e})")

    _banner(ips)
    if os.environ.get("NO_OPEN") != "1":
        try:
            webbrowser.open(f"http://localhost:{PORT}")
        except Exception:
            pass
    while True:  # serve until Ctrl+C
        await asyncio.sleep(3600)


if __name__ == "__main__":
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        pass
