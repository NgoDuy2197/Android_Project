'use strict';
/* SentryRoom web client: room join, chat, file transfer (with % progress),
   WebRTC mesh voice/video (perfect negotiation), local motion detection, and
   notifications for room events. */

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);

let socket = null;
let myId = null;
let room = params.get('room') || 'main';
let myName = 'Ẩn danh';
const peers = new Map(); // id -> name

// Browsers only expose getUserMedia / Notification on a secure origin, so a
// phone opening http://192.168.x.x sees navigator.mediaDevices === undefined.
const SECURE = window.isSecureContext ||
  location.protocol === 'https:' ||
  ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);

// ---------- Join ----------
$('roomInput').value = room;
$('nameInput').value = localStorage.getItem('name') || '';
if (params.get('host') === '1') $('nameInput').value ||= 'Host';

$('joinBtn').onclick = join;
$('nameInput').addEventListener('keydown', (e) => e.key === 'Enter' && join());

function join() {
  myName = ($('nameInput').value || 'Ẩn danh').trim();
  room = ($('roomInput').value || 'main').trim();
  localStorage.setItem('name', myName);
  $('roomName').textContent = room;
  $('gate').classList.add('hidden');
  $('app').classList.remove('hidden');
  $('insecureBar').classList.toggle('hidden', SECURE);
  askNotifyPermission(); // we're inside a click, so the prompt is allowed
  connect();
  if (params.get('host') === '1') showInvite();
}

function connect() {
  socket = io({
    reconnection: true,
    reconnectionAttempts: Infinity, // Wi-Fi comes back eventually; keep trying
    reconnectionDelay: 600,
    reconnectionDelayMax: 5000,
    timeout: 8000,
  });
  socket.on('connect', () => {
    setNet('on');
    socket.emit('join', { room, name: myName });
  });
  socket.on('disconnect', (reason) => {
    setNet('off');
    addSystem(`Mất kết nối tới server (${reason}) — đang thử lại…`);
    // Peer ids ARE socket ids, so everyone gets a new one after a reconnect:
    // tear the mesh down but keep mic/camera open so the call resumes without
    // asking for permission again.
    for (const id of [...pcs.keys()]) closePc(id);
    peers.clear();
  });
  socket.io.on('reconnect_attempt', (n) => setNet('wait', n));
  socket.io.on('reconnect', () => addSystem('Đã kết nối lại server.'));
  socket.on('welcome', ({ id, peers: list }) => {
    myId = id;
    list.forEach((p) => peers.set(p.id, p.name));
    // I joined last → I initiate calls to existing peers (if a call is active).
    if (localStream) list.forEach((p) => connectPeer(p.id, true));
  });
  socket.on('peer-joined', ({ id, name }) => {
    peers.set(id, name);
    notify('Vào phòng', `${name} đã vào phòng ${room}`, { tag: 'peer' });
    if (localStream) connectPeer(id, false); // they will offer to me
  });
  socket.on('peer-left', ({ id }) => {
    const name = peers.get(id);
    if (name) notify('Rời phòng', `${name} đã rời phòng`, { tag: 'peer' });
    dropPeer(id);
  });
  socket.on('system', (t) => addSystem(t));
  socket.on('chat', (m) => {
    addChat(m);
    if (m.id !== myId) notify(m.from, m.text, { tag: 'chat' });
  });
  socket.on('file', (m) => {
    addFileMsg(m);
    if (m.id !== myId) {
      const what = m.kind === 'motion' ? '📷 ảnh chuyển động' : `📎 ${m.name}`;
      notify(m.from, what, { tag: 'file' });
    }
  });
  socket.on('alert', (a) => {
    if (!a || a.id === myId) return;
    const hot = a.level === 'alert';
    notify(hot ? '⚠ Có chuyển động' : 'Theo dõi chuyển động',
      `${a.from}: ${a.text}`,
      { tag: 'motion', force: hot, alert: hot, vibrate: hot });
  });
  socket.on('signal', onSignal);
}

// Connection indicator in the header.
function setNet(state, attempt) {
  const d = $('netDot');
  if (!d) return;
  d.className = 'net ' + state;
  d.textContent = state === 'on' ? '●' : '◌';
  d.title = state === 'on' ? 'Đã kết nối server'
    : state === 'wait' ? `Đang kết nối lại… (lần ${attempt || 1})`
    : 'Mất kết nối server';
}

// The browser knows before we do that the network is back.
window.addEventListener('online', () => {
  if (socket && !socket.connected) socket.connect();
});

// ---------- Notifications ----------
// OS notification when granted, in-page toast otherwise; unread count in the
// tab title either way. Motion alerts are forced through even when focused.
const notif = { on: localStorage.getItem('notify') !== '0' };
const canNotify = () => 'Notification' in window;
const notifyGranted = () => canNotify() && Notification.permission === 'granted';
let unread = 0;
const callNotified = new Set();

$('bellBtn').onclick = async () => {
  notif.on = !notif.on;
  localStorage.setItem('notify', notif.on ? '1' : '0');
  if (notif.on) await askNotifyPermission();
  paintBell();
};

async function askNotifyPermission() {
  if (!notif.on || !canNotify() || Notification.permission !== 'default') return paintBell();
  try { await Notification.requestPermission(); } catch (_) {}
  paintBell();
}

function paintBell() {
  const b = $('bellBtn');
  b.textContent = notif.on ? '🔔' : '🔕';
  b.classList.toggle('off', !notif.on);
  b.title = !notif.on ? 'Thông báo: tắt'
    : notifyGranted() ? 'Thông báo: bật'
    : !canNotify() ? (SECURE ? 'Thông báo: trình duyệt không hỗ trợ → hiện trong trang'
                             : 'Thông báo hệ thống cần https → hiện trong trang')
    : 'Thông báo: chưa cấp quyền → hiện trong trang';
}
paintBell();

function notify(title, body, opts) {
  if (!notif.on) return;
  const o = opts || {};
  const away = document.hidden || !document.hasFocus();
  if (away) bumpBadge();
  if (o.vibrate) { try { navigator.vibrate && navigator.vibrate([200, 100, 200]); } catch (_) {} }
  if (!away && !o.force) return; // don't nag while they're reading the room
  if (notifyGranted()) {
    try {
      new Notification(title, { body: String(body || ''), tag: o.tag, icon: 'icon.png' });
      return;
    } catch (_) {}
  }
  toast(title, body, o.alert);
}

function bumpBadge() {
  unread++;
  document.title = `(${unread}) SentryRoom`;
}
function clearBadge() {
  unread = 0;
  document.title = 'SentryRoom';
}
window.addEventListener('focus', clearBadge);
document.addEventListener('visibilitychange', () => { if (!document.hidden) clearBadge(); });

function toast(title, body, isAlert) {
  const t = el('toast' + (isAlert ? ' alert' : ''),
    `<b>${esc(title)}</b>${body ? ' — ' + esc(body) : ''}`);
  $('toasts').appendChild(t);
  setTimeout(() => t.remove(), 5000);
}

// ---------- Camera / mic access ----------
function mediaDev() {
  return (navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
    ? navigator.mediaDevices : null;
}

/** getUserMedia with a message that explains the real cause on http origins. */
async function getMedia(constraints) {
  const md = mediaDev();
  if (!md) {
    throw new Error(SECURE
      ? 'Trình duyệt này không hỗ trợ camera/micro.'
      : 'Trình duyệt chặn camera/micro khi trang mở bằng http:// — cần https hoặc localhost. Bấm "Mở link https" ở đầu trang.');
  }
  return md.getUserMedia(constraints);
}

// ---------- Shared camera ----------
// Most webcams (especially on Windows) can only be opened once; a second
// getUserMedia while motion detection holds the device yields a black frame.
// So the camera is opened ONCE and shared: motion detection and the video call
// use the same track, and it's only stopped when nobody needs it anymore.
const cam = { stream: null, users: new Set() }; // users: 'motion' | 'call'

const trackDead = (t) => !t || t.readyState !== 'live';

// Older WebViews/Safari return undefined from play() instead of a Promise.
function playSafely(el) {
  try {
    const p = el.play();
    if (p && typeof p.catch === 'function') p.catch(() => {});
  } catch (_) {}
}

function camLive() {
  return cam.stream && cam.stream.getVideoTracks().some((t) => t.readyState === 'live');
}

async function openCamera() {
  cam.stream = await getMedia({
    video: { width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false,
  });
  cam.stream.getVideoTracks().forEach((t) => watchTrack(t, 'video'));
  return cam.stream;
}

async function acquireCamera(user) {
  if (!camLive()) await openCamera();
  cam.users.add(user);
  return cam.stream;
}

function releaseCamera(user) {
  cam.users.delete(user);
  if (cam.users.size || !cam.stream) return;
  cam.stream.getVideoTracks().forEach((t) => t.stop());
  cam.stream = null;
}

// ---------- Media watchdog (mic / camera) ----------
// Capture tracks die on their own: USB hiccup, phone screen-lock, another app
// grabbing the device, driver reset. The call then goes silent or black while
// everything else looks fine. So watch every track and, when one dies,
// re-acquire the device and swap the new track into every peer connection.
const media = { audioTries: 0, videoTries: 0, busy: '', timers: {} };

function watchTrack(t, kind) {
  t.addEventListener('ended', () => recoverMedia(kind));
  t.addEventListener('mute', () => {
    // 'mute' is often a brief stall — only act if it's still muted after 3s.
    setTimeout(() => { if (t.muted && !trackDead(t)) recoverMedia(kind); }, 3000);
  });
}

/** Point every peer connection at a replacement track. */
function swapSenders(kind, track) {
  for (const entry of pcs.values()) {
    const s = entry.pc.getSenders().find((x) => x.track && x.track.kind === kind);
    if (s) s.replaceTrack(track).catch((e) => console.error(e));
    else if (localStream) { try { entry.pc.addTrack(track, localStream); } catch (_) {} }
  }
}

function mediaNeeded(kind) {
  if (kind === 'audio') return !!(localStream && localStream.getAudioTracks().length);
  return !!(motion.on || (localStream && localStream.getVideoTracks().length));
}

async function recoverMedia(kind) {
  if (media.busy || !mediaNeeded(kind)) return;
  const triesKey = kind === 'audio' ? 'audioTries' : 'videoTries';
  media.busy = kind;
  try {
    if (kind === 'audio') {
      const old = localStream.getAudioTracks()[0];
      if (old && !trackDead(old) && !old.muted) return;
      const t = (await getMedia({ audio: true })).getAudioTracks()[0];
      if (old) { try { old.stop(); } catch (_) {} localStream.removeTrack(old); }
      localStream.addTrack(t);
      watchTrack(t, 'audio');
      swapSenders('audio', t);
      addSystem('Đã kết nối lại micro.');
    } else {
      if (camLive() && !cam.stream.getVideoTracks()[0].muted) return;
      if (cam.stream) cam.stream.getVideoTracks().forEach((t) => { try { t.stop(); } catch (_) {} });
      cam.stream = null;
      const s = await openCamera(); // one device, both consumers re-pointed below
      const t = s.getVideoTracks()[0];
      if (localStream && cam.users.has('call')) {
        localStream.getVideoTracks().forEach((o) => localStream.removeTrack(o));
        localStream.addTrack(t);
        swapSenders('video', t);
        showLocal();
      }
      if (motion.on) {
        motion.stream = s;
        motion.prev = null;
        $('motionVideo').srcObject = s;
        playSafely($('motionVideo'));
      }
      addSystem('Đã kết nối lại camera.');
    }
    media[triesKey] = 0;
  } catch (e) {
    media[triesKey]++;
    const wait = Math.min(15000, 1000 * Math.pow(2, media[triesKey] - 1));
    const what = kind === 'audio' ? 'micro' : 'camera';
    addSystem(`Chưa lấy lại được ${what} (${e.message}) — thử lại sau ${Math.round(wait / 1000)}s.`);
    if (media[triesKey] === 1) toast(`Mất ${what}`, 'Đang tự thử kết nối lại…', true);
    clearTimeout(media.timers[kind]);
    media.timers[kind] = setTimeout(() => recoverMedia(kind), wait);
  } finally {
    media.busy = '';
  }
}

// Belt-and-braces: some devices kill a track without firing 'ended' at all, and
// a stalled peer connection can sit in 'disconnected' silently.
setInterval(() => {
  if (media.busy) return;
  if (localStream) {
    const a = localStream.getAudioTracks()[0];
    if (a && trackDead(a)) recoverMedia('audio');
    const v = localStream.getVideoTracks()[0];
    if (v && trackDead(v)) recoverMedia('video');
  }
  if (motion.on && (!motion.stream || trackDead(motion.stream.getVideoTracks()[0]))) {
    recoverMedia('video');
  }
  for (const [id, entry] of pcs) {
    const st = entry.pc.connectionState;
    if ((st === 'disconnected' || st === 'failed') && !entry.retryTimer) {
      scheduleRecover(id, 500);
    }
  }
}, 5000);

// Coming back from a locked screen / background tab is the usual moment a
// suspended camera turns out to be dead.
document.addEventListener('visibilitychange', () => {
  if (document.hidden || media.busy) return;
  if (motion.on || localStream) {
    if (localStream && trackDead(localStream.getAudioTracks()[0]) && localStream.getAudioTracks().length) recoverMedia('audio');
    if (mediaNeeded('video') && !camLive()) recoverMedia('video');
  }
});

$('secureBtn').onclick = async () => {
  try {
    const info = await fetch('/api/ips').then((x) => x.json());
    if (!info.https) {
      addSystem('Server chưa bật HTTPS. Trên máy chủ chạy: pip install cryptography rồi mở lại SentryRoom.');
      return;
    }
    const here = location.hostname;
    const ip = info.ips.some((e) => e.ip === here) ? here : info.best;
    location.href = `https://${ip}:${info.port}/?room=${encodeURIComponent(room)}`;
  } catch (_) {}
};

// ---------- Chat ----------
$('sendBtn').onclick = sendChat;
$('msgInput').addEventListener('keydown', (e) => e.key === 'Enter' && sendChat());
function sendChat() {
  const text = $('msgInput').value.trim();
  if (!text) return;
  socket.emit('chat', { text });
  $('msgInput').value = '';
}

function el(cls, html) {
  const d = document.createElement('div');
  d.className = cls;
  if (html != null) d.innerHTML = html;
  return d;
}
function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
// Keep the view pinned to the newest message, but don't yank the user back down
// while they're reading history — show a jump button instead. Images arrive with
// zero height, so re-scroll once each one loads.
const STICK_PX = 90;
const atBottom = () => {
  const c = $('chat');
  return c.scrollHeight - c.scrollTop - c.clientHeight <= STICK_PX;
};
function scrollToBottom() {
  const c = $('chat');
  requestAnimationFrame(() => { c.scrollTop = c.scrollHeight; });
}
function showJump(on) {
  $('jumpBtn').classList.toggle('hidden', !on);
}
$('jumpBtn').onclick = () => { scrollToBottom(); showJump(false); };
$('chat').addEventListener('scroll', () => { if (atBottom()) showJump(false); });

function push(node) {
  const c = $('chat');
  const stick = atBottom();
  c.appendChild(node);
  if (stick) scrollToBottom(); else showJump(true);
  node.querySelectorAll('img').forEach((img) =>
    img.addEventListener('load', () => { if (stick || atBottom()) scrollToBottom(); },
      { once: true }));
}
function addChat(m) {
  const mine = m.id === myId;
  const node = el('msg ' + (mine ? 'me' : ''));
  node.innerHTML = `<div class="who">${esc(m.from)}</div>${esc(m.text)}`;
  push(node);
}
function addSystem(t) { push(el('msg sys', esc(t))); }
function addFileMsg(m) {
  const mine = m.id === myId;
  const node = el('msg ' + (mine ? 'me' : ''));
  const isImg = (m.mime || '').startsWith('image/');
  const kb = m.size > 1024 * 1024 ? (m.size / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(m.size / 1024)) + ' KB';
  const body = isImg
    ? `<a href="${m.url}" target="_blank"><img src="${m.url}" alt="${esc(m.name)}"/></a>`
    : `<div class="filechip">📄 <a href="${m.url}" target="_blank" download>${esc(m.name)}</a> <span class="muted small">${kb}</span></div>`;
  node.innerHTML = `<div class="who">${esc(m.from)}${m.kind === 'motion' ? ' • 📷 chuyển động' : ''}</div>${body}`;
  push(node);
}

// ---------- File transfer (upload with % progress) ----------
$('fileBtn').onclick = () => $('fileInput').click();
$('fileInput').onchange = () => {
  [...$('fileInput').files].forEach((f) => sendFile(f));
  $('fileInput').value = '';
};

function sendFile(file, kind) {
  const name = file.name || `file_${Date.now()}`;
  const row = el('up', `<div>${kind === 'motion' ? '📷 ' : '📎 '}${esc(name)} <span class="pct">0%</span></div><div class="bar"><div></div></div>`);
  $('uploads').appendChild(row);
  const bar = row.querySelector('.bar > div');
  const pct = row.querySelector('.pct');

  const fd = new FormData();
  fd.append('room', room);
  fd.append('name', name);
  fd.append('file', file, name);

  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload');
  xhr.upload.onprogress = (e) => {
    if (!e.lengthComputable) return;
    const p = Math.round((e.loaded / e.total) * 100);
    bar.style.width = p + '%';
    pct.textContent = p + '%';
  };
  xhr.onload = () => {
    row.remove();
    try {
      const res = JSON.parse(xhr.responseText);
      socket.emit('file', { ...res, kind: kind || 'file' });
    } catch (_) {}
  };
  xhr.onerror = () => { pct.textContent = 'lỗi'; };
  xhr.send(fd);
}

// ---------- WebRTC mesh (voice / video) ----------
const RTC = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };
const pcs = new Map(); // peerId -> { pc, polite, makingOffer, ignoreOffer }
let localStream = null;

$('voiceBtn').onclick = () => startCall(false);
$('videoBtn').onclick = () => startCall(true);
$('hangBtn').onclick = hangUp;

async function startCall(video) {
  try {
    if (!localStream) localStream = new MediaStream();
    if (!localStream.getAudioTracks().length) {
      const a = await getMedia({ audio: true }); // mic is call-only
      a.getAudioTracks().forEach((t) => { localStream.addTrack(t); watchTrack(t, 'audio'); });
    }
    if (video && !localStream.getVideoTracks().length) {
      // Reuses the motion-detection camera instead of opening it twice.
      const s = await acquireCamera('call');
      s.getVideoTracks().forEach((t) => localStream.addTrack(t));
      if (motion.on) addSystem('Đang dùng chung 1 camera cho cả gọi video và phát hiện chuyển động.');
    }
  } catch (e) {
    addSystem('Không mở được micro/camera: ' + e.message);
    toast('Không mở được micro/camera', e.message, true);
    if (localStream && !localStream.getTracks().length) localStream = null;
    return;
  }
  showLocal();
  $('videos').classList.remove('hidden');
  $('hangBtn').classList.remove('hidden');
  // Connect to everyone currently in the room.
  for (const id of peers.keys()) connectPeer(id, true);
}

function hangUp() {
  // Close the media connections; everyone stays in the room for chat.
  for (const id of [...pcs.keys()]) closePc(id);
  clearTimeout(media.timers.audio);
  clearTimeout(media.timers.video);
  if (localStream) {
    localStream.getAudioTracks().forEach((t) => t.stop());
    // Video track is the shared camera — detach, then let release decide.
    localStream.getVideoTracks().forEach((t) => localStream.removeTrack(t));
  }
  localStream = null;
  releaseCamera('call'); // stays on if motion detection still needs it
  $('videos').innerHTML = '';
  $('videos').classList.add('hidden');
  $('hangBtn').classList.add('hidden');
}

function connectPeer(peerId, initiate) {
  const entry = ensurePc(peerId);
  if (localStream) {
    localStream.getTracks().forEach((t) => {
      if (!entry.pc.getSenders().find((s) => s.track === t)) entry.pc.addTrack(t, localStream);
    });
  }
  return entry;
}

function ensurePc(peerId) {
  let entry = pcs.get(peerId);
  if (entry) return entry;
  const pc = new RTCPeerConnection(RTC);
  entry = { pc, polite: myId < peerId, makingOffer: false, ignoreOffer: false,
            tries: 0, retryTimer: null };
  pcs.set(peerId, entry);

  pc.onicecandidate = ({ candidate }) => {
    if (candidate) socket.emit('signal', { to: peerId, data: { candidate } });
  };
  pc.ontrack = ({ streams }) => attachRemote(peerId, streams[0]);
  pc.onnegotiationneeded = async () => {
    try {
      entry.makingOffer = true;
      await pc.setLocalDescription();
      socket.emit('signal', { to: peerId, data: { sdp: pc.localDescription } });
    } catch (e) {
      console.error(e);
    } finally {
      entry.makingOffer = false;
    }
  };
  // A dropped media path is recoverable — don't throw the peer away for it.
  pc.onconnectionstatechange = () => {
    const st = pc.connectionState;
    if (st === 'connected') {
      entry.tries = 0;
      clearTimeout(entry.retryTimer);
      entry.retryTimer = null;
      setPeerStatus(peerId, '');
    } else if (st === 'disconnected') {
      scheduleRecover(peerId, 2500); // usually heals by itself first
    } else if (st === 'failed') {
      scheduleRecover(peerId, 300);
    }
  };
  pc.oniceconnectionstatechange = () => {
    if (pc.iceConnectionState === 'failed') scheduleRecover(peerId, 300);
  };
  return entry;
}

// ---------- Per-peer reconnect ladder ----------
// wait (self-heal) → ICE restart with exponential backoff → rebuild the
// RTCPeerConnection from scratch. Both sides may restart: the perfect-negotiation
// collision handling in onSignal already resolves a simultaneous restart.
const ICE_MAX_TRIES = 5;

function scheduleRecover(peerId, delay) {
  const entry = pcs.get(peerId);
  if (!entry || entry.retryTimer || !localStream) return;
  entry.retryTimer = setTimeout(async () => {
    entry.retryTimer = null;
    if (pcs.get(peerId) !== entry) return; // rebuilt or closed meanwhile
    const st = entry.pc.connectionState;
    if (st === 'connected' || st === 'closed' || !localStream) return;
    if (!socket || !socket.connected) return scheduleRecover(peerId, 2000); // no signaling yet
    if (!peers.has(peerId)) return closePc(peerId); // they really left

    if (++entry.tries > ICE_MAX_TRIES) {
      addSystem(`Dựng lại kết nối với ${peers.get(peerId) || 'peer'}…`);
      closePc(peerId);
      connectPeer(peerId, true); // brand-new pc + fresh offer
      return;
    }
    setPeerStatus(peerId, `kết nối lại ${entry.tries}/${ICE_MAX_TRIES}`);
    await restartIce(peerId);
    scheduleRecover(peerId, Math.min(8000, 1000 * Math.pow(2, entry.tries - 1)));
  }, delay);
}

async function restartIce(peerId) {
  const entry = pcs.get(peerId);
  if (!entry) return;
  try {
    if (typeof entry.pc.restartIce === 'function') {
      entry.pc.restartIce(); // fires negotiationneeded → offer with fresh ICE
    } else {
      entry.makingOffer = true;
      try {
        await entry.pc.setLocalDescription(await entry.pc.createOffer({ iceRestart: true }));
        socket.emit('signal', { to: peerId, data: { sdp: entry.pc.localDescription } });
      } finally {
        entry.makingOffer = false;
      }
    }
  } catch (e) {
    console.error(e);
  }
}

function setPeerStatus(peerId, status) {
  const tile = document.getElementById('v_' + peerId);
  if (!tile) return;
  const tag = tile.querySelector('.tag');
  if (tag) tag.textContent = (peers.get(peerId) || '') + (status ? ' • ' + status : '');
  tile.classList.toggle('reconnecting', !!status);
}

async function onSignal({ from, data }) {
  const entry = ensurePc(from);
  const pc = entry.pc;
  try {
    if (data.sdp) {
      // Someone started a call and is offering to me while I'm not in one yet.
      if (data.sdp.type === 'offer' && !localStream && !callNotified.has(from)) {
        callNotified.add(from);
        notify('📞 Cuộc gọi đến', `${peers.get(from) || 'Ai đó'} đang gọi trong phòng ${room}`,
          { tag: 'call', force: true, alert: true, vibrate: true });
      }
      const collision = data.sdp.type === 'offer' && (entry.makingOffer || pc.signalingState !== 'stable');
      entry.ignoreOffer = !entry.polite && collision;
      if (entry.ignoreOffer) return;
      await pc.setRemoteDescription(data.sdp);
      if (data.sdp.type === 'offer') {
        await pc.setLocalDescription();
        socket.emit('signal', { to: from, data: { sdp: pc.localDescription } });
      }
    } else if (data.candidate) {
      try { await pc.addIceCandidate(data.candidate); } catch (e) { if (!entry.ignoreOffer) throw e; }
    }
  } catch (e) {
    console.error(e);
  }
}

/** Tear down the connection but keep the peer on the roster (retry may rebuild). */
function closePc(peerId) {
  const entry = pcs.get(peerId);
  callNotified.delete(peerId);
  if (entry) {
    clearTimeout(entry.retryTimer);
    try { entry.pc.close(); } catch (_) {}
    pcs.delete(peerId);
  }
  const tile = document.getElementById('v_' + peerId);
  if (tile) tile.remove();
}

/** The peer is gone for good. */
function dropPeer(peerId) {
  closePc(peerId);
  peers.delete(peerId);
}

function showLocal() {
  let tile = document.getElementById('v_local');
  if (!tile) {
    tile = el('vtile');
    tile.id = 'v_local';
    tile.innerHTML = `<video autoplay playsinline muted></video><span class="tag">Bạn</span>`;
    $('videos').appendChild(tile);
  }
  tile.querySelector('video').srcObject = localStream;
}
function attachRemote(peerId, stream) {
  let tile = document.getElementById('v_' + peerId);
  if (!tile) {
    tile = el('vtile');
    tile.id = 'v_' + peerId;
    tile.innerHTML = `<video autoplay playsinline></video><span class="tag">${esc(peers.get(peerId) || '')}</span>`;
    $('videos').appendChild(tile);
    $('videos').classList.remove('hidden');
  }
  tile.querySelector('video').srcObject = stream;
}

// ---------- Motion detection (this device only) ----------
const motion = { on: false, stream: null, timer: null, prev: null, last: 0 };
$('sens').oninput = () => ($('sensVal').textContent = $('sens').value);
$('motionToggle').onchange = (e) => toggleMotion(e.target.checked);

// What to do on a hit: 'photo' (chỉ gửi ảnh), 'notify' (chỉ báo), 'both'.
const MOTION_MODES = ['photo', 'notify', 'both'];
const stored = localStorage.getItem('motionAction');
$('motionAction').value = MOTION_MODES.includes(stored) ? stored : 'photo';
$('motionAction').onchange = () => localStorage.setItem('motionAction', $('motionAction').value);

async function toggleMotion(on) {
  $('motionCfg').classList.toggle('hidden', !on);
  if (on) {
    try {
      motion.stream = await acquireCamera('motion'); // shared with the video call
    } catch (e) {
      addSystem('Không mở được camera: ' + e.message);
      toast('Không mở được camera', e.message, true);
      $('motionToggle').checked = false;
      $('motionCfg').classList.add('hidden');
      return;
    }
    const v = $('motionVideo');
    v.srcObject = motion.stream;
    playSafely(v);
    motion.on = true;
    motion.prev = null;
    motion.timer = setInterval(scanMotion, 350);
    socket.emit('motion', { text: 'BẬT theo dõi chuyển động', level: 'info' });
    $('statusDot').className = 'dot on';
  } else {
    motion.on = false;
    clearInterval(motion.timer);
    $('motionVideo').srcObject = null;
    motion.stream = null;
    releaseCamera('motion'); // stays on if a video call still needs it
    $('statusDot').className = 'dot off';
    $('meterBar').style.width = '0';
    if (socket) socket.emit('motion', { text: 'TẮT theo dõi chuyển động', level: 'info' });
  }
}

function scanMotion() {
  const v = $('motionVideo');
  if (!v.videoWidth) return;
  const c = $('motionCanvas');
  const w = (c.width = 160), h = (c.height = 120);
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(v, 0, 0, w, h);
  const cur = ctx.getImageData(0, 0, w, h).data;

  let score = 0;
  if (motion.prev) {
    let changed = 0;
    for (let i = 0; i < cur.length; i += 4) {
      const g = (cur[i] + cur[i + 1] + cur[i + 2]) / 3;
      const pg = (motion.prev[i] + motion.prev[i + 1] + motion.prev[i + 2]) / 3;
      if (Math.abs(g - pg) > 24) changed++;
    }
    score = (changed / (w * h)) * 100; // % of pixels that moved
  }
  motion.prev = cur;

  $('meterBar').style.width = Math.min(100, score * 4) + '%';

  // Higher sensitivity → lower threshold. sens 1..100 → thr ~12%..0.5%.
  const sens = Number($('sens').value);
  const threshold = Math.max(0.5, 12 * (1 - sens / 100));
  const gap = Math.max(1, Number($('interval').value) || 5) * 1000;

  if (score >= threshold && Date.now() - motion.last > gap) {
    motion.last = Date.now();
    onMotionHit(score);
  }
}

function onMotionHit(score) {
  const mode = $('motionAction').value;
  if (mode === 'notify' || mode === 'both') {
    const text = `phát hiện chuyển động (${score.toFixed(1)}%)`;
    if (socket) socket.emit('motion', { text, level: 'alert' });
    notify('⚠ Có chuyển động', `Máy này: ${text}`,
      { tag: 'motion-local', force: true, alert: true, vibrate: true });
  }
  if (mode === 'photo' || mode === 'both') captureShot();
}

function captureShot() {
  const v = $('motionVideo');
  const c = $('shotCanvas');
  c.width = v.videoWidth || 640;
  c.height = v.videoHeight || 480;
  c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
  c.toBlob((blob) => {
    if (blob) sendFile(new File([blob], `motion_${Date.now()}.jpg`, { type: 'image/jpeg' }), 'motion');
  }, 'image/jpeg', 0.7);
}

// ---------- Invite (QR) ----------
// The host may sit on several networks (Wi-Fi, Ethernet, VPN, Hyper-V...), so
// let them pick which address the QR points at. The server orders the list with
// internal LAN addresses first and preselects the best one.
$('inviteBtn').onclick = showInvite;
$('closeQr').onclick = () => $('qrModal').classList.add('hidden');
$('copyLink').onclick = () => navigator.clipboard?.writeText($('qrLink').value);
$('ipSelect').onchange = () => {
  localStorage.setItem('inviteIp', $('ipSelect').value);
  refreshQr($('ipSelect').value);
};

async function showInvite() {
  $('qrModal').classList.remove('hidden');
  try {
    const info = await fetch('/api/ips').then((x) => x.json());
    const saved = localStorage.getItem('inviteIp');
    const use = info.ips.some((e) => e.ip === saved) ? saved : info.best;
    fillIpSelect(info.ips, use);
    await refreshQr(use);
  } catch (_) {
    $('qrNote').textContent = 'Không lấy được danh sách IP từ server.';
  }
}

function fillIpSelect(ips, selected) {
  const sel = $('ipSelect');
  sel.innerHTML = '';
  ips.forEach((e, i) => {
    const o = document.createElement('option');
    o.value = e.ip;
    o.textContent = `${e.ip} · ${e.label}` +
      (e.iface ? ` · ${e.iface}` : '') + (i === 0 ? ' · ưu tiên' : '');
    sel.appendChild(o);
  });
  sel.value = selected;
}

async function refreshQr(ip) {
  const url = `/api/qr?room=${encodeURIComponent(room)}&ip=${encodeURIComponent(ip || '')}`;
  const r = await fetch(url).then((x) => x.json());
  $('qrImg').src = r.qr;
  $('qrLink').value = r.url;
  $('ipSelect').value = r.ip; // server falls back if that IP disappeared
  localStorage.setItem('inviteIp', r.ip);
  $('qrNote').textContent = r.https
    ? 'Link https — điện thoại sẽ cảnh báo chứng chỉ tự ký: chọn "Nâng cao → Tiếp tục". Vào bằng http thì không dùng được camera/micro.'
    : 'Server chưa bật HTTPS → điện thoại vào bằng http sẽ không gọi thoại/video được (trên máy chủ: pip install cryptography).';
}
