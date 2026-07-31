// WebRTC signaling server + static web viewer.
//
// Roles:
//   - "broadcaster": the Android app. It owns the camera and creates one
//     RTCPeerConnection per viewer, sending an offer to each.
//   - "viewer": the browser page served from /. It receives the stream and
//     can snapshot / record / go fullscreen locally, and ask the phone to
//     switch camera via a "command" message.
//
// The server never touches media - it only relays signaling messages by id.

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { WebSocketServer } = require('ws');
const QRCode = require('qrcode');

const PORT = process.env.PORT || 8080;
const PUBLIC_DIR = path.join(__dirname, 'public');
const DATA_DIR = path.join(__dirname, '__data'); // captured photos/videos land here

function twoDigit(n) {
  return String(n).padStart(2, '0');
}

// Timestamped base name, e.g. photo_20260721_233045
function stampName(kind) {
  const d = new Date();
  const s = `${d.getFullYear()}${twoDigit(d.getMonth() + 1)}${twoDigit(d.getDate())}_` +
    `${twoDigit(d.getHours())}${twoDigit(d.getMinutes())}${twoDigit(d.getSeconds())}`;
  return `${kind}_${s}`;
}

// All non-internal IPv4 addresses of this machine (LAN addresses the phone
// can reach).
function localIPs() {
  const nets = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) ips.push(net.address);
    }
  }
  return ips;
}

// ---- Static file server (serves the viewer page) --------------------------
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.ico': 'image/x-icon',
};

const httpServer = http.createServer((req, res) => {
  const parsed = new URL(req.url, `http://${req.headers.host}`);
  const urlPathRaw = parsed.pathname;

  // --- API: list of LAN addresses (ip:port) the phone can connect to ---
  if (urlPathRaw === '/api/info') {
    const addresses = localIPs().map((ip) => `${ip}:${PORT}`);
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({ port: PORT, addresses }));
  }

  // --- API: save a captured photo/video into the __data folder ---
  // Body is the raw binary (image/png or video/webm). No download prompt on
  // the client - the file is written straight to disk here.
  if (urlPathRaw === '/api/save' && req.method === 'POST') {
    const kind = parsed.searchParams.get('kind') === 'video' ? 'video' : 'photo';
    const ext = kind === 'video' ? 'webm' : 'png';
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true });
    } catch {}
    const filename = `${stampName(kind)}.${ext}`;
    const fullPath = path.join(DATA_DIR, filename);
    const out = fs.createWriteStream(fullPath);
    req.pipe(out); // stream to disk (handles large videos without buffering)
    out.on('finish', () => {
      console.log(`[SAVED] __data/${filename}`);
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: true, file: filename }));
    });
    out.on('error', (e) => {
      res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, error: String(e) }));
    });
    return;
  }

  // --- API: QR code (SVG) encoding an address; scanned by the phone ---
  if (urlPathRaw === '/api/qr') {
    const text = parsed.searchParams.get('text') || '';
    QRCode.toString(text, { type: 'svg', margin: 1, width: 260 }, (err, svg) => {
      if (err) {
        res.writeHead(500);
        return res.end('QR error');
      }
      res.writeHead(200, { 'Content-Type': 'image/svg+xml; charset=utf-8' });
      res.end(svg);
    });
    return;
  }

  let urlPath = decodeURIComponent(urlPathRaw);
  if (urlPath === '/') urlPath = '/index.html';
  const filePath = path.join(PUBLIC_DIR, path.normalize(urlPath));

  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    return res.end('Forbidden');
  }
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      return res.end('Not found');
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream' });
    res.end(data);
  });
});

// ---- Signaling ------------------------------------------------------------
const wss = new WebSocketServer({ server: httpServer });

let broadcaster = null;          // single broadcaster socket (the phone)
const viewers = new Map();       // viewerId -> socket
let nextViewerId = 1;

function send(sock, obj) {
  if (sock && sock.readyState === sock.OPEN) sock.send(JSON.stringify(obj));
}

function broadcastToViewers(obj) {
  for (const v of viewers.values()) send(v, obj);
}

wss.on('connection', (sock) => {
  sock.role = null;
  sock.viewerId = null;

  sock.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    switch (msg.type) {
      // --- registration ---
      case 'role': {
        if (msg.role === 'broadcaster') {
          broadcaster = sock;
          sock.role = 'broadcaster';
          console.log('[+] Broadcaster (phone) connected');
          // Tell every waiting viewer we're live, and ask the phone to offer
          // to each of them.
          broadcastToViewers({ type: 'broadcaster-status', online: true });
          for (const id of viewers.keys()) send(sock, { type: 'viewer-join', viewerId: id });
        } else {
          const id = nextViewerId++;
          sock.role = 'viewer';
          sock.viewerId = id;
          viewers.set(id, sock);
          console.log(`[+] Viewer #${id} connected (${viewers.size} total)`);
          send(sock, { type: 'welcome', viewerId: id });
          send(sock, { type: 'broadcaster-status', online: !!broadcaster });
          if (broadcaster) send(broadcaster, { type: 'viewer-join', viewerId: id });
        }
        break;
      }

      // --- broadcaster -> viewer ---
      case 'offer':
        send(viewers.get(msg.viewerId), { type: 'offer', sdp: msg.sdp });
        break;

      // --- viewer -> broadcaster ---
      case 'answer':
        send(broadcaster, { type: 'answer', viewerId: sock.viewerId, sdp: msg.sdp });
        break;

      // --- ICE candidates (both directions) ---
      case 'candidate':
        if (sock.role === 'broadcaster') {
          send(viewers.get(msg.viewerId), { type: 'candidate', candidate: msg.candidate });
        } else {
          send(broadcaster, { type: 'candidate', viewerId: sock.viewerId, candidate: msg.candidate });
        }
        break;

      // --- viewer -> broadcaster commands (e.g. switch camera) ---
      case 'command':
        send(broadcaster, { type: 'command', action: msg.action, viewerId: sock.viewerId });
        break;
    }
  });

  sock.on('close', () => {
    if (sock.role === 'broadcaster') {
      broadcaster = null;
      console.log('[-] Broadcaster disconnected');
      broadcastToViewers({ type: 'broadcaster-status', online: false });
    } else if (sock.role === 'viewer') {
      viewers.delete(sock.viewerId);
      console.log(`[-] Viewer #${sock.viewerId} disconnected (${viewers.size} left)`);
      send(broadcaster, { type: 'viewer-leave', viewerId: sock.viewerId });
    }
  });
});

// ---- Start ----------------------------------------------------------------
httpServer.listen(PORT, '0.0.0.0', () => {
  console.log('==============================================');
  console.log('  WebRTC Camera Server is running');
  console.log('==============================================');
  console.log(`  Viewer (open in browser):  http://localhost:${PORT}`);
  for (const ip of localIPs()) {
    console.log(`                             http://${ip}:${PORT}`);
    console.log(`  Phone server address:      ${ip}:${PORT}`);
  }
  console.log('==============================================');
  console.log('  Nhap dia chi "IP:PORT" o tren vao app dien thoai roi bam Ket noi.');
  console.log('  (Dien thoai va may tinh phai cung mang Wi-Fi/LAN)');
  console.log('==============================================');
  console.log(`  Anh/video chup se luu vao: ${DATA_DIR}`);
  console.log('==============================================');
});
