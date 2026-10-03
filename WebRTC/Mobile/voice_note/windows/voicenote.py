"""VoiceNote for Windows: tray app, microphone -> Vosk (offline) -> daily transcript.

Output format (one file per day, UTF-8):

    08:15:32:
    - Nội dung câu nói

    08:15:40:
    - Câu tiếp theo

Every failure (mic unplugged, model broken, disk error...) is logged, shown in the
tray tooltip and retried with exponential backoff; the app never exits on its own.
"""
import ctypes
import json
import logging
import os
import queue
import shutil
import sys
import threading
import time
import urllib.request
import zipfile
from datetime import datetime
from logging.handlers import RotatingFileHandler

APP = "VoiceNote"
APP_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), APP)
CONFIG_PATH = os.path.join(APP_DIR, "config.json")
MODELS_DIR = os.path.join(APP_DIR, "models")
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
LANGS = {"vi": "Tiếng Việt", "en": "English"}

DEFAULTS = {
    "language": "vi",           # "vi" | "en"
    "engine": "whisper",        # "whisper" (accurate) | "vosk" (light, weaker Vietnamese)
    "whisper_model": "small",   # tiny | base | small | medium | large-v3-turbo
    "whisper_threads": 0,       # 0 = half of the CPU cores
    "model_url_vi": "https://alphacephei.com/vosk/models/vosk-model-vn-0.4.zip",
    "model_url_en": "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
    "model_dir_vi": "",         # optional: already-unpacked model (skips download)
    "model_dir_en": "",
    "output_dir": os.path.join(os.path.expanduser("~"), "Documents", APP),
    "time_format": "%H:%M:%S",  # strftime syntax
    "pause_ms": 800,            # whisper: silence that ends an entry; vosk: merge gap
    "min_db": -50,              # quieter than this (dBFS) = silence, even if it "sounds like" speech
    "batch_ms": 3000,           # whisper: recognise every N ms while talking (0 = only at pauses)
    "min_confidence": 0.6,      # drop utterances whose mean word confidence is lower (noise)
    "input_device": None,       # None = system default, or device name / index
    "autostart": False,
    "start_paused": False,
    "show_window_on_start": False,
}

# Settings the running capture reads live (changing them must not restart the mic).
LIVE_KEYS = {"min_db", "batch_ms", "autostart", "show_window_on_start", "start_paused"}

log = logging.getLogger(APP)


# ---- config / autostart ------------------------------------------------------

def load_config():
    os.makedirs(APP_DIR, exist_ok=True)
    cfg = dict(DEFAULTS)
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, encoding="utf-8") as f:
                cfg.update({k: v for k, v in json.load(f).items() if k in DEFAULTS})
        except Exception:
            log.exception("config broken, backing up and using defaults")
            shutil.copyfile(CONFIG_PATH, CONFIG_PATH + ".bad")
    if cfg["language"] not in LANGS:
        cfg["language"] = DEFAULTS["language"]
    if cfg["engine"] not in ("whisper", "vosk"):
        cfg["engine"] = DEFAULTS["engine"]
    try:
        cfg["pause_ms"] = max(0, min(60000, int(cfg["pause_ms"])))
    except (TypeError, ValueError):
        cfg["pause_ms"] = DEFAULTS["pause_ms"]
    for key, lo, hi in (("min_db", -90, 0), ("batch_ms", 0, 30000)):
        try:
            cfg[key] = max(lo, min(hi, int(cfg[key])))
        except (TypeError, ValueError):
            cfg[key] = DEFAULTS[key]
    try:
        cfg["min_confidence"] = max(0.0, min(1.0, float(cfg["min_confidence"])))
    except (TypeError, ValueError):
        cfg["min_confidence"] = DEFAULTS["min_confidence"]
    save_config(cfg)  # writes missing keys so the user sees every option
    return cfg


def save_config(cfg):
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    os.replace(tmp, CONFIG_PATH)


def launch_command():
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    return f'"{pyw if os.path.exists(pyw) else sys.executable}" "{os.path.abspath(__file__)}"'


def set_autostart(on):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, launch_command())
        else:
            try:
                winreg.DeleteValue(k, APP)
            except FileNotFoundError:
                pass


# ---- transcript ----------------------------------------------------------------

class TranscriptWriter:
    def __init__(self, out_dir, time_format):
        self.out_dir = out_dir
        self.time_format = time_format

    def path_for(self, dt):
        return os.path.join(self.out_dir, f"transcript_{dt:%Y-%m-%d}.txt")

    def format(self, start_ts, text):
        dt = datetime.fromtimestamp(start_ts)
        try:
            stamp = dt.strftime(self.time_format)
        except ValueError:
            stamp = dt.strftime(DEFAULTS["time_format"])
        return f"{stamp}:\n- {text}\n\n"

    def write(self, start_ts, text):
        os.makedirs(self.out_dir, exist_ok=True)
        path = self.path_for(datetime.fromtimestamp(start_ts))
        with open(path, "a", encoding="utf-8", newline="\n") as f:
            f.write(self.format(start_ts, text))
            f.flush()
            os.fsync(f.fileno())


class Segmenter:
    """Each final result (recognizer ends one on silence) becomes one entry, unless
    the next utterance starts within pause_ms, in which case they are joined."""

    def __init__(self, pause_ms, sink):
        self.pause = pause_ms / 1000.0
        self.sink = sink
        self.pending = []
        self.pending_start = 0.0
        self.last_final = 0.0
        self.utter_start = 0.0

    def on_partial(self, text, now):
        if text.strip() and not self.utter_start:
            self.utter_start = now

    def on_final(self, text, now):
        start = self.utter_start or now
        self.utter_start = 0.0
        text = text.strip()
        if not text:
            return
        if self.pending and start - self.last_final > self.pause:
            self.flush()
        if not self.pending:
            self.pending_start = start
        self.pending.append(text)
        self.last_final = now

    def tick(self, now):
        if self.pending and not self.utter_start and now - self.last_final > self.pause:
            self.flush()

    def flush(self):
        """If sink raises, the text stays pending and is retried on the next tick."""
        if not self.pending:
            return
        text = " ".join(self.pending)
        self.sink(self.pending_start, text[:1].upper() + text[1:])
        self.pending = []


def final_text(js, min_conf):
    """Text of a final result, or "" when the words look like noise (low confidence)."""
    try:
        d = json.loads(js)
    except Exception:
        return ""
    text = d.get("text", "")
    words = d.get("result") or []
    if text and words:
        mean = sum(w.get("conf", 1.0) for w in words) / len(words)
        # Background noise mostly decodes to 1-2 stray words: demand more for those.
        need = min(0.95, min_conf + 0.25) if len(words) <= 2 else min_conf
        if mean < need:
            log.info("dropped (conf %.2f): %s", mean, text)
            return ""
    return text


# ---- model ---------------------------------------------------------------------

def find_model_dir(path, depth=0):
    if not os.path.isdir(path):
        return None
    if os.path.isdir(os.path.join(path, "am")) or os.path.isdir(os.path.join(path, "conf")):
        return path
    if depth >= 2:
        return None
    for name in sorted(os.listdir(path)):
        found = find_model_dir(os.path.join(path, name), depth + 1)
        if found:
            return found
    return None


def model_root(url):
    name = os.path.splitext(os.path.basename(url.split("?")[0]))[0] or "model"
    return os.path.join(MODELS_DIR, name)


def ensure_model(url, progress):
    root = model_root(url)
    name = os.path.basename(root)
    marker = os.path.join(root, ".ready")
    if os.path.exists(marker):
        found = find_model_dir(root)
        if found:
            return found
    os.makedirs(MODELS_DIR, exist_ok=True)
    part = os.path.join(MODELS_DIR, name + ".zip.part")
    tmp = os.path.join(MODELS_DIR, name + ".tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    try:
        progress("Đang tải model…")
        with urllib.request.urlopen(url, timeout=30) as resp, open(part, "wb") as out:
            total = int(resp.headers.get("Content-Length") or 0)
            done, last = 0, -1
            while True:
                chunk = resp.read(64 * 1024)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                pct = done * 100 // total if total else done >> 20
                if pct != last:
                    last = pct
                    progress(f"Đang tải model… {pct}{'%' if total else ' MB'}")
        progress("Đang giải nén model…")
        with zipfile.ZipFile(part) as z:
            base = os.path.realpath(tmp) + os.sep
            for member in z.namelist():
                if not os.path.realpath(os.path.join(tmp, member)).startswith(base):
                    raise IOError(f"Zip entry ngoài thư mục: {member}")
            z.extractall(tmp)
        if not find_model_dir(tmp):
            raise IOError("File zip không chứa model Vosk")
        shutil.rmtree(root, ignore_errors=True)
        os.replace(tmp, root)
        with open(marker, "w", encoding="utf-8") as f:
            f.write(url)
        return find_model_dir(root)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        if os.path.exists(part):
            os.remove(part)


# ---- whisper: pause-based utterance cutting ------------------------------------

WHISPER_RATE = 16000
# Phrases Whisper is known to invent on silence/noise (from subtitle training data).
HALLUCINATIONS = (
    "subscribe", "đăng ký kênh", "đăng kí kênh", "ghiền mì gõ", "theo dõi kênh",
    "cảm ơn các bạn đã theo dõi", "hẹn gặp lại các bạn", "like và share",
    "thanks for watching", "thank you for watching", "please subscribe",
)


class SpeechProb:
    """Streaming Silero VAD (the ONNX model bundled with faster-whisper):
    probability that a 32 ms frame (512 samples @16 kHz) contains human speech.
    Unlike loudness it ignores fans, typing, traffic, music-free background noise."""
    FRAME = 512
    CONTEXT = 64

    def __init__(self):
        import numpy as np
        import onnxruntime
        from faster_whisper.utils import get_assets_path
        self.np = np
        opts = onnxruntime.SessionOptions()
        opts.inter_op_num_threads = 1
        opts.intra_op_num_threads = 1
        opts.log_severity_level = 4
        self.sess = onnxruntime.InferenceSession(
            os.path.join(get_assets_path(), "silero_vad_v6.onnx"),
            providers=["CPUExecutionProvider"], sess_options=opts)
        self.reset()

    def reset(self):
        np = self.np
        self.h = np.zeros((1, 1, 128), np.float32)
        self.c = np.zeros((1, 1, 128), np.float32)
        self.ctx = np.zeros(self.CONTEXT, np.float32)

    def __call__(self, frame):
        x = self.np.concatenate((self.ctx, frame))[None, :].astype(self.np.float32)
        out, self.h, self.c = self.sess.run(None, {"input": x, "h": self.h, "c": self.c})
        self.ctx = frame[-self.CONTEXT:]
        return float(self.np.ravel(out)[0])


class Utterances:
    """Cuts the mic stream into utterances at pauses using Silero speech
    probability (with hysteresis). Noise never opens or extends an utterance,
    so pauses are detected even in a noisy room and Whisper only gets speech.

    While someone keeps talking, a chunk is also cut every batch_ms (at the
    quietest nearby frame, so words are not split) and sent right away, which
    lets text appear while speaking. Frames quieter than min_db count as
    silence whatever the VAD says. Settings are read live through cfg_get()."""

    ON, OFF = 0.5, 0.35          # speech starts above ON, continues while above OFF
    ONSET_FRAMES = 3             # ~100 ms of speech needed to start
    MIN_SPEECH_MS = 300          # shorter blips (cough, click) are ignored
    MIN_CHUNK_VOICED = 8         # a batch needs ~250 ms of speech to be worth sending

    def __init__(self, cfg_get, on_chunk, on_voice, max_sec=20.0):
        import numpy as np
        self.np = np
        self.vad = SpeechProb()
        self.cfg_get = cfg_get
        self.frame = SpeechProb.FRAME
        self.frame_ms = self.frame * 1000 / WHISPER_RATE
        self.max_frames = int(max_sec * 1000 / self.frame_ms)
        self.on_chunk = on_chunk  # (utterance_id, start_ts, audio | None, final)
        self.on_voice = on_voice
        self.carry = np.zeros(0, np.float32)
        self.preroll = []         # (frame, dB) before speech onset
        self.buf, self.dbs = [], []
        self.active = False
        self.run = self.silence = self.voiced = self.chunk_voiced = 0
        self.uid = 0
        self.chunk_start = 0.0
        self.prob = 0.0

    def feed(self, samples, now, can_cut=lambda: True):
        np = self.np
        cfg = self.cfg_get()
        pause_frames = max(1, int(cfg["pause_ms"] / self.frame_ms))
        batch_frames = int(cfg["batch_ms"] / self.frame_ms)
        min_db = cfg["min_db"]
        data = np.concatenate((self.carry, samples))
        n = len(data) // self.frame
        self.carry = data[n * self.frame:]
        for i in range(n):
            f = data[i * self.frame:(i + 1) * self.frame]
            db = _dbfs(f)
            p = self.vad(f)  # always run: keeps the model's state continuous
            if db < min_db:
                p = 0.0
            self.prob = p
            if not self.active:
                self.preroll.append((f, db))
                if len(self.preroll) > 10:  # ~320 ms kept before speech onset
                    self.preroll.pop(0)
                self.run = self.run + 1 if p >= self.ON else 0
                if self.run >= self.ONSET_FRAMES:
                    self.active = True
                    self.buf = [x for x, _ in self.preroll]
                    self.dbs = [d for _, d in self.preroll]
                    self.chunk_start = now - len(self.buf) * self.frame_ms / 1000
                    self.silence, self.voiced, self.chunk_voiced = 0, self.run, self.run
                    self.on_voice(True)
                continue
            self.buf.append(f)
            self.dbs.append(db)
            if p >= self.OFF:
                self.silence = 0
                self.voiced += 1
                self.chunk_voiced += 1
            else:
                self.silence += 1
            if self.silence >= pause_frames:
                self.flush()
            elif len(self.buf) >= self.max_frames or (
                    batch_frames and len(self.buf) >= batch_frames
                    and self.chunk_voiced >= self.MIN_CHUNK_VOICED and can_cut()):
                self._cut()

    def _cut(self):
        """Send the buffer up to the quietest of the last ~640 ms, keep the rest."""
        np = self.np
        window = min(20, len(self.buf) - 1)
        if window > 0:
            idx = len(self.buf) - window + int(np.argmin(self.dbs[-window:]))
        else:
            idx = len(self.buf) - 1
        chunk = self.buf[:idx + 1]
        self.on_chunk(self.uid, self.chunk_start, np.concatenate(chunk), False)
        self.chunk_start += len(chunk) * self.frame_ms / 1000
        self.buf, self.dbs = self.buf[idx + 1:], self.dbs[idx + 1:]
        self.chunk_voiced = 0

    def flush(self):
        if self.active:
            if self.voiced * self.frame_ms >= self.MIN_SPEECH_MS:
                # drop the trailing silence: less audio for Whisper = faster
                keep = len(self.buf) - max(0, self.silence - 6)
                audio = self.np.concatenate(self.buf[:keep]) if keep > 0 and self.chunk_voiced else None
                self.on_chunk(self.uid, self.chunk_start, audio, True)
            self.on_voice(False)
            self.uid += 1
        self.active = False
        self.buf, self.dbs, self.preroll = [], [], []
        self.run = 0


def whisper_text(model, audio, language, prompt=None):
    """prompt = text of the previous batch of the same utterance (keeps sentences coherent)."""
    segments, _ = model.transcribe(
        audio, language=language, beam_size=1, vad_filter=False, without_timestamps=True,
        condition_on_previous_text=False, no_speech_threshold=0.6, initial_prompt=prompt or None,
    )
    parts = []
    for s in segments:
        t = s.text.strip()
        if not t or s.avg_logprob < -1.0 or s.no_speech_prob > 0.7:
            continue
        if any(h in t.lower() for h in HALLUCINATIONS):
            log.info("dropped hallucination: %s", t)
            continue
        parts.append(t)
    return " ".join(parts).strip()


# ---- engine --------------------------------------------------------------------

class Engine(threading.Thread):
    """Callbacks (on_status/on_partial/on_entry) run on this thread; keep them cheap."""

    def __init__(self, cfg):
        super().__init__(name="engine", daemon=True)
        self.cfg = cfg
        self.on_status = lambda s: None
        self.on_partial = lambda text: None
        self.level_db = -90.0  # mic loudness (dBFS) of the latest chunk, read by the UI meter
        self.on_entry = lambda start, text: None
        self.status = "Đang khởi động…"
        self.last_text = ""
        self.paused = bool(cfg.get("start_paused"))
        self.state = "loading"  # loading | listening | paused | error
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._reload = False
        self._model = None
        self._model_path = None
        self._whisper = None
        self._whisper_key = None
        self._jobs = queue.Queue(maxsize=50)  # (utterance_id, start_ts, audio, final)
        self._busy = False
        self._speaking = False
        self._live = ""  # text of the utterance in progress

    # control (thread-safe)
    def stop(self):
        self._stop.set()
        self._wake.set()

    def reload(self, cfg):
        self.cfg = cfg
        self._reload = True
        self._wake.set()

    def set_paused(self, paused):
        self.paused = paused
        self._wake.set()

    def _set(self, status, state):
        if status == self.status and state == self.state:
            return
        self.status, self.state = status, state
        log.info(status)
        self._emit(self.on_status, status)

    def _emit(self, fn, *args):
        try:
            fn(*args)
        except Exception:
            log.exception("callback")

    def _sleep(self, seconds):
        self._wake.clear()
        self._wake.wait(seconds)

    def run(self):
        threading.Thread(target=self._transcribe_loop, name="whisper", daemon=True).start()
        backoff = 2.0
        while not self._stop.is_set():
            try:
                self._reload = False
                if self.paused:
                    self._set("Tạm dừng", "paused")
                    self._sleep(3600)
                    continue
                cfg = self.cfg
                if cfg["engine"] == "whisper":
                    self._load_whisper(cfg)
                    self._capture_whisper(cfg)
                else:
                    self._recognize(self._load_model(cfg), cfg)
                backoff = 2.0
            except Exception as e:
                if self._stop.is_set():
                    break
                log.exception("engine error")
                self._set(f"Lỗi: {e}. Thử lại sau {int(backoff)}s", "error")
                self._sleep(backoff)
                backoff = min(backoff * 2, 60.0)

    # -- whisper --

    def _load_whisper(self, cfg):
        name = cfg["whisper_model"]
        threads = int(cfg.get("whisper_threads") or 0) or max(1, (os.cpu_count() or 2) // 2)
        key = (name, threads)
        if self._whisper is not None and self._whisper_key == key:
            return
        os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
        from faster_whisper import WhisperModel
        root = os.path.join(MODELS_DIR, "whisper")
        self._whisper = None
        opts = dict(device="cpu", compute_type="int8", cpu_threads=threads, download_root=root)
        try:
            self._set(f"Đang nạp Whisper {name}…", "loading")
            self._whisper = WhisperModel(name, local_files_only=True, **opts)
        except Exception:
            self._set(f"Đang tải Whisper {name} (chỉ lần đầu, vài trăm MB)…", "loading")
            self._whisper = WhisperModel(name, **opts)
        self._whisper_key = key

    def _capture_whisper(self, cfg):
        import numpy as np
        import sounddevice as sd
        try:  # re-scan devices so an unplugged/replugged mic is picked up
            sd._terminate()
            sd._initialize()
        except Exception:
            pass
        device = cfg.get("input_device")
        try:  # let the OS resample to 16 kHz when it can
            sd.check_input_settings(device=device, samplerate=WHISPER_RATE, channels=1, dtype="int16")
            rate = WHISPER_RATE
        except Exception:
            rate = int(sd.query_devices(device, "input")["default_samplerate"])
        audio = queue.Queue(maxsize=200)

        def callback(indata, frames, t, status):
            try:
                audio.put_nowait(bytes(indata))
            except queue.Full:
                pass

        def on_chunk(uid, start, samples, final):
            try:
                self._jobs.put_nowait((uid, start, samples, final))
            except queue.Full:
                log.warning("recognition queue full, dropping audio")

        def on_voice(active):
            self._speaking = active
            self._show_live()

        cutter = Utterances(lambda: self.cfg, on_chunk, on_voice)
        can_cut = lambda: not self._busy and self._jobs.empty()  # never build a backlog
        cfg_mtime = _mtime(CONFIG_PATH)
        next_cfg_check = time.time() + 2
        try:
            with sd.RawInputStream(samplerate=rate, blocksize=rate // 10, device=device,
                                   dtype="int16", channels=1, callback=callback):
                self._set(f"Đang nghe ({LANGS[cfg['language']]}, Whisper {cfg['whisper_model']})…", "listening")
                while not (self._stop.is_set() or self._reload or self.paused):
                    try:
                        data = audio.get(timeout=3)
                    except queue.Empty:
                        raise IOError("Micro không trả dữ liệu")
                    now = time.time()
                    x = np.frombuffer(data, np.int16).astype(np.float32) / 32768.0
                    if rate != WHISPER_RATE:
                        n = int(len(x) * WHISPER_RATE / rate)
                        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
                    self.level_db = _dbfs(x)
                    cutter.feed(x, now, can_cut)
                    if now >= next_cfg_check:  # auto-apply edits to config.json
                        next_cfg_check = now + 2
                        cfg_mtime = self._check_config(cfg_mtime)
        finally:
            self.level_db = -90.0
            cutter.flush()

    def _check_config(self, cfg_mtime):
        """Applies config.json edits; restarts capture only when a setting needs it."""
        m = _mtime(CONFIG_PATH)
        if m == cfg_mtime:
            return cfg_mtime
        new = load_config()
        changed = {k for k in new if new[k] != self.cfg.get(k)}
        self.cfg = new
        if changed - LIVE_KEYS:
            self._reload = True
        return m

    def _show_live(self):
        """Live line in the window: text recognised so far + what is happening."""
        busy = self._busy or not self._jobs.empty()
        mark = " ●" if self._speaking else (" …" if busy else "")
        if self._live:
            text = self._live + mark
        else:
            text = "● Đang nói…" if self._speaking else ("… Đang nhận dạng" if busy else "")
        self._emit(self.on_partial, text)

    def _transcribe_loop(self):
        """Runs Whisper on batches/utterances so capture is never blocked. Batches
        of one utterance are joined into a single transcript entry."""
        writer_key, writer = None, None
        current = {}  # utterance id -> {"start": ts, "parts": [text, ...]}
        while not self._stop.is_set():
            try:
                uid, start, samples, final = self._jobs.get(timeout=1)
            except queue.Empty:
                continue
            self._busy = True
            self._show_live()
            try:
                model, cfg = self._whisper, self.cfg
                utt = current.setdefault(uid, {"start": start, "parts": []})
                if model is not None and samples is not None and len(samples) >= WHISPER_RATE // 5:
                    prev = utt["parts"][-1] if utt["parts"] else None
                    text = whisper_text(model, samples, cfg["language"], prev)
                    if text:
                        utt["parts"].append(text)
                        self._live = " ".join(utt["parts"])
                if final:
                    del current[uid]
                    self._live = ""
                    full = " ".join(utt["parts"]).strip()
                    if full:
                        key = (cfg["output_dir"], cfg["time_format"])
                        if key != writer_key:
                            writer_key, writer = key, TranscriptWriter(*key)
                        self._write_retry(writer, utt["start"], full[:1].upper() + full[1:])
            except Exception as e:
                log.exception("whisper failed")
                self._set(f"Lỗi nhận dạng: {e}", "error")
            finally:
                self._busy = False
                self._show_live()

    def _write_retry(self, writer, start, text):
        """Disk errors must not lose text: retry for a while before giving up."""
        for attempt in range(30):
            try:
                writer.write(start, text)
                self.last_text = text
                self._emit(self.on_entry, start, text)
                if self.state == "error":
                    self._set(f"Đang nghe ({LANGS[self.cfg['language']]})…", "listening")
                return
            except Exception as e:
                log.exception("write failed")
                self._set(f"Lỗi ghi file: {e}", "error")
                if self._stop.wait(min(2 ** attempt, 30)):
                    return
        log.error("gave up writing: %s", text)

    # -- vosk --

    def _load_model(self, cfg):
        import vosk
        vosk.SetLogLevel(-1)
        lang = cfg["language"]
        local = cfg.get(f"model_dir_{lang}") or ""
        if local:
            path = find_model_dir(local) or local
        else:
            path = ensure_model(cfg[f"model_url_{lang}"], lambda s: self._set(s, "loading"))
        if self._model is not None and self._model_path == path:
            return self._model
        self._model = None  # free the previous language before loading the next
        self._set(f"Đang nạp model {LANGS[lang]}…", "loading")
        try:
            self._model = vosk.Model(path)
        except Exception:
            if not local:  # corrupted download -> force re-download next attempt
                marker = os.path.join(model_root(cfg[f"model_url_{lang}"]), ".ready")
                if os.path.exists(marker):
                    os.remove(marker)
            raise
        self._model_path = path
        return self._model

    def _recognize(self, model, cfg):
        import numpy as np
        import sounddevice as sd
        import vosk
        try:  # re-scan devices so an unplugged/replugged mic is picked up
            sd._terminate()
            sd._initialize()
        except Exception:
            pass
        device = cfg.get("input_device")
        rate = int(sd.query_devices(device, "input")["default_samplerate"])
        audio = queue.Queue(maxsize=100)

        def callback(indata, frames, t, status):
            try:
                audio.put_nowait(bytes(indata))
            except queue.Full:
                pass

        writer = TranscriptWriter(cfg["output_dir"], cfg["time_format"])

        def sink(start, text):
            writer.write(start, text)
            self.last_text = text
            self._emit(self.on_entry, start, text)

        seg = Segmenter(cfg["pause_ms"], sink)
        min_conf = cfg["min_confidence"]
        rec = vosk.KaldiRecognizer(model, rate)
        rec.SetWords(True)  # per-word confidence for the noise filter
        cfg_mtime = _mtime(CONFIG_PATH)
        next_cfg_check = time.time() + 2
        last_partial = ""
        try:
            with sd.RawInputStream(samplerate=rate, blocksize=rate // 5, device=device,
                                   dtype="int16", channels=1, callback=callback):
                self._set(f"Đang nghe ({LANGS[cfg['language']]})…", "listening")
                while not (self._stop.is_set() or self._reload or self.paused):
                    try:
                        data = audio.get(timeout=3)
                    except queue.Empty:
                        raise IOError("Micro không trả dữ liệu")
                    now = time.time()
                    self.level_db = _dbfs(np.frombuffer(data, np.int16).astype(np.float32) / 32768.0)
                    if self.level_db < self.cfg["min_db"]:
                        data = bytes(len(data))  # below threshold -> feed true silence
                    if rec.AcceptWaveform(data):
                        self._safe(seg.on_final, final_text(rec.Result(), min_conf), now)
                        partial = ""
                    else:
                        partial = _field(rec.PartialResult(), "partial")
                        seg.on_partial(partial, now)
                    if partial != last_partial:
                        last_partial = partial
                        self._emit(self.on_partial, f"… {partial}" if partial else "")
                    self._safe(seg.tick, now)
                    if now >= next_cfg_check:  # auto-apply edits to config.json
                        next_cfg_check = now + 2
                        cfg_mtime = self._check_config(cfg_mtime)
                self._safe(seg.on_final, final_text(rec.FinalResult(), min_conf), time.time())
        finally:
            self.level_db = -90.0
            self._safe(seg.flush)
            self._emit(self.on_partial, "")

    def _safe(self, fn, *args):
        """Disk errors must not kill recognition: log, show, keep the text pending."""
        try:
            fn(*args)
        except Exception as e:
            log.exception("write failed")
            self._set(f"Lỗi ghi file: {e}", "error")


def _dbfs(x):
    """Loudness of a float32 [-1, 1] chunk in dBFS (-90 = silence, 0 = full scale)."""
    import numpy as np
    if not len(x):
        return -90.0
    rms = float(np.sqrt(np.mean(x * x)))
    return max(-90.0, 20 * np.log10(rms + 1e-9))


def _field(js, key):
    try:
        return json.loads(js).get(key, "")
    except Exception:
        return ""


def _mtime(path):
    try:
        return os.stat(path).st_mtime
    except OSError:
        return 0


def set_option(engine, key, value):
    cfg = load_config()
    cfg[key] = value
    save_config(cfg)
    engine.reload(cfg)


def set_language(engine, lang):
    set_option(engine, "language", lang)


def open_path(path):
    try:
        os.makedirs(path if not os.path.splitext(path)[1] else os.path.dirname(path), exist_ok=True)
        os.startfile(path)
    except Exception:
        log.exception("open %s", path)


# ---- GUI: tray icon + live transcript window -----------------------------------

COLORS = {"listening": (229, 57, 53), "loading": (251, 140, 0), "error": (251, 140, 0), "paused": (120, 120, 120)}


def make_icon(state):
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((4, 4, 60, 60), fill=COLORS.get(state, COLORS["loading"]))
    d.rounded_rectangle((24, 12, 40, 38), radius=8, fill="white")          # mic head
    d.arc((16, 20, 48, 46), start=0, end=180, fill="white", width=4)       # mic cradle
    d.rectangle((30, 46, 34, 52), fill="white")                            # stand
    return img


class App:
    """Tk owns the main thread; pystray runs detached; engine/tray events are
    marshalled onto Tk through a queue, because Tk is not thread-safe."""

    def __init__(self, engine):
        import tkinter as tk
        self.tk = tk
        self.engine = engine
        self.root = tk.Tk()
        self.root.withdraw()
        self.events = queue.Queue()
        self.win = None
        self.icon = None
        self.icon_state = None
        engine.on_status = lambda s: self.post(self._on_status)
        engine.on_partial = lambda text: self.post(self._on_partial, text)
        engine.on_entry = lambda start, text: self.post(self._on_entry, start, text)

    def post(self, fn, *args):
        self.events.put((fn, args))

    def _pump(self):
        try:
            while True:
                fn, args = self.events.get_nowait()
                try:
                    fn(*args)
                except Exception:
                    log.exception("ui event")
        except queue.Empty:
            pass
        if self.win is not None and self.win.winfo_viewable():
            self._draw_meter()
        self.root.after(60, self._pump)

    def run(self):
        import pystray
        from pystray import Menu, MenuItem as Item
        e = self.engine

        def lang_item(code):
            return Item(LANGS[code], lambda i, it: self.post(set_language, e, code),
                        checked=lambda item: e.cfg["language"] == code, radio=True)

        self.icon = pystray.Icon(APP, make_icon(e.state), APP, Menu(
            Item(lambda item: e.status, None, enabled=False),
            Menu.SEPARATOR,
            Item("Hiện cửa sổ nội dung", lambda i, it: self.post(self.show_window), default=True),
            Item(lambda item: "Tiếp tục ghi" if e.paused else "Tạm dừng",
                 lambda i, it: e.set_paused(not e.paused)),
            Item("Ngôn ngữ", Menu(lang_item("vi"), lang_item("en"))),
            Item("Engine", Menu(
                Item("Whisper (chính xác)", lambda i, it: self.post(set_option, e, "engine", "whisper"),
                     checked=lambda item: e.cfg["engine"] == "whisper", radio=True),
                Item("Vosk (nhẹ)", lambda i, it: self.post(set_option, e, "engine", "vosk"),
                     checked=lambda item: e.cfg["engine"] == "vosk", radio=True),
            )),
            Item("Mở file hôm nay", lambda i, it: self.post(self.open_today)),
            Item("Mở thư mục transcript", lambda i, it: open_path(e.cfg["output_dir"])),
            Menu.SEPARATOR,
            Item("Cấu hình (config.json)", lambda i, it: open_path(CONFIG_PATH)),
            Item("Tải lại cấu hình", lambda i, it: e.reload(load_config())),
            Item("Chạy khi khởi động Windows", lambda i, it: self.post(self.toggle_autostart),
                 checked=lambda item: e.cfg.get("autostart")),
            Item("Mở log", lambda i, it: open_path(os.path.join(APP_DIR, "voicenote.log"))),
            Menu.SEPARATOR,
            Item("Thoát", lambda i, it: self.post(self.quit)),
        ))
        self.icon.run_detached()
        self.icon.visible = True
        e.start()
        if e.cfg.get("show_window_on_start"):
            self.show_window()
        self.root.after(60, self._pump)
        self.root.mainloop()

    # -- tray actions (Tk thread) --

    def toggle_autostart(self):
        cfg = load_config()
        cfg["autostart"] = not cfg["autostart"]
        set_autostart(cfg["autostart"])
        save_config(cfg)
        self.engine.cfg["autostart"] = cfg["autostart"]
        self.icon.update_menu()

    def open_today(self):
        cfg = self.engine.cfg
        path = TranscriptWriter(cfg["output_dir"], cfg["time_format"]).path_for(datetime.now())
        open_path(path if os.path.exists(path) else cfg["output_dir"])

    def quit(self):
        self.engine.stop()
        try:
            self.icon.stop()
        finally:
            self.root.destroy()

    # -- engine events (Tk thread) --

    def _on_status(self):
        e = self.engine
        tip = f"{APP}: {e.status}" + (f"\n{e.last_text}" if e.last_text else "")
        self.icon.title = tip[:127]
        if e.state != self.icon_state:
            self.icon_state = e.state
            self.icon.icon = make_icon(e.state)
        self.icon.update_menu()
        if self.win:
            self.status_var.set(e.status)
            self.pause_btn.configure(text="Tiếp tục" if e.paused else "Tạm dừng")
            self.lang_var.set(LANGS[e.cfg["language"]])

    def _on_partial(self, text):
        if self.win:
            self.partial_var.set(text)

    def _on_entry(self, start, text):
        self._on_status()
        if not self.win:
            return
        if datetime.fromtimestamp(start).date() != self.shown_day:
            self._load_today()
            return
        cfg = self.engine.cfg
        self._append(TranscriptWriter(cfg["output_dir"], cfg["time_format"]).format(start, text))

    # -- live window --

    def show_window(self):
        if self.win:
            self.win.deiconify()
            self.win.lift()
            self.win.focus_force()
            return
        tk = self.tk
        from tkinter import ttk
        e = self.engine
        w = self.win = tk.Toplevel(self.root)
        w.title(f"{APP} — nội dung ghi nhận")
        w.geometry("640x480")
        w.minsize(360, 240)
        w.protocol("WM_DELETE_WINDOW", w.withdraw)  # closing only hides; app keeps running

        bar = ttk.Frame(w, padding=(8, 6))
        bar.pack(fill="x")
        ttk.Label(bar, text="Ngôn ngữ:").pack(side="left")
        self.lang_var = tk.StringVar(value=LANGS[e.cfg["language"]])
        combo = ttk.Combobox(bar, textvariable=self.lang_var, values=list(LANGS.values()),
                             state="readonly", width=12)
        combo.pack(side="left", padx=(4, 8))
        combo.bind("<<ComboboxSelected>>", lambda ev: set_language(
            e, next(k for k, v in LANGS.items() if v == self.lang_var.get())))
        self.pause_btn = ttk.Button(bar, text="Tiếp tục" if e.paused else "Tạm dừng",
                                    command=lambda: e.set_paused(not e.paused))
        self.pause_btn.pack(side="left")
        ttk.Button(bar, text="Mở file", command=self.open_today).pack(side="left", padx=4)
        self.status_var = tk.StringVar(value=e.status)
        ttk.Label(bar, textvariable=self.status_var, foreground="#666").pack(side="right")

        meter = ttk.Frame(w, padding=(8, 0, 8, 6))
        meter.pack(fill="x")
        ttk.Label(meter, text="Mic:").pack(side="left")
        self.meter = tk.Canvas(meter, height=14, highlightthickness=1, highlightbackground="#bbb",
                               background="#f2f2f2")
        self.meter.pack(side="left", fill="x", expand=True, padx=6)
        self.meter_var = tk.StringVar(value="")
        ttk.Label(meter, textvariable=self.meter_var, width=8).pack(side="left")

        opts = ttk.Frame(w, padding=(8, 0, 8, 6))
        opts.pack(fill="x")
        ttk.Label(opts, text="Im lặng khi nhỏ hơn").pack(side="left")
        self.min_db_var = tk.IntVar(value=e.cfg["min_db"])
        ttk.Spinbox(opts, from_=-80, to=-10, increment=1, width=5, textvariable=self.min_db_var,
                    command=self._apply_opts).pack(side="left", padx=4)
        ttk.Label(opts, text="dB").pack(side="left")
        ttk.Label(opts, text="   Dịch mỗi").pack(side="left")
        self.batch_var = tk.DoubleVar(value=e.cfg["batch_ms"] / 1000)
        ttk.Spinbox(opts, from_=0, to=15, increment=0.5, width=5, textvariable=self.batch_var,
                    command=self._apply_opts).pack(side="left", padx=4)
        ttk.Label(opts, text="giây khi đang nói (0 = chỉ khi nghỉ)").pack(side="left")
        for child in opts.winfo_children():
            child.bind("<Return>", lambda ev: self._apply_opts())
            child.bind("<FocusOut>", lambda ev: self._apply_opts())
        self.meter_level = 0.0
        self.meter_peak = 0.0

        body = ttk.Frame(w)
        body.pack(fill="both", expand=True, padx=8)
        self.text = tk.Text(body, wrap="word", font=("Segoe UI", 11), state="disabled",
                            relief="flat", padx=8, pady=6)
        scroll = ttk.Scrollbar(body, command=self.text.yview)
        self.text.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.text.pack(side="left", fill="both", expand=True)

        self.partial_var = tk.StringVar()
        partial = ttk.Label(w, textvariable=self.partial_var, foreground="#1e88e5", padding=(8, 6),
                            font=("Segoe UI", 11, "italic"), wraplength=600)
        partial.pack(fill="x")
        w.bind("<Configure>", lambda ev: ev.widget is w and partial.configure(
            wraplength=max(200, ev.width - 24)))
        self._load_today()

    def _apply_opts(self):
        """Saves threshold/batch from the window; the capture picks them up live."""
        try:
            min_db = max(-90, min(0, int(self.min_db_var.get())))
            batch_ms = max(0, min(30000, int(float(self.batch_var.get()) * 1000)))
        except (ValueError, self.tk.TclError):
            return
        cfg = self.engine.cfg
        if cfg["min_db"] == min_db and cfg["batch_ms"] == batch_ms:
            return
        disk = load_config()
        disk["min_db"], disk["batch_ms"] = min_db, batch_ms
        save_config(disk)
        cfg["min_db"], cfg["batch_ms"] = min_db, batch_ms

    def _draw_meter(self):
        """-60..0 dBFS bar with fast attack, slow release and a peak marker."""
        db = self.engine.level_db
        target = min(1.0, max(0.0, (db + 60) / 60))
        self.meter_level = target if target > self.meter_level else self.meter_level * 0.85 + target * 0.15
        self.meter_peak = max(target, self.meter_peak - 0.01)
        c = self.meter
        width, height = max(1, c.winfo_width()), max(1, c.winfo_height())
        color = "#e53935" if db > -3 else "#fbc02d" if db > -12 else "#43a047"
        c.delete("all")
        c.create_rectangle(0, 0, int(width * self.meter_level), height, fill=color, width=0)
        x = int(width * self.meter_peak)
        c.create_line(x, 0, x, height, fill="#333")
        t = int(width * min(1.0, max(0.0, (self.engine.cfg["min_db"] + 60) / 60)))
        c.create_line(t, 0, t, height, fill="#1e88e5", width=2)  # silence threshold
        self.meter_var.set(f"{db:5.0f} dB" if db > -90 else "  — ")

    def _load_today(self):
        cfg = self.engine.cfg
        self.shown_day = datetime.now().date()
        path = TranscriptWriter(cfg["output_dir"], cfg["time_format"]).path_for(datetime.now())
        content = ""
        try:
            with open(path, "rb") as f:
                f.seek(0, os.SEEK_END)
                size = f.tell()
                f.seek(max(0, size - 256 * 1024))
                content = f.read().decode("utf-8", errors="ignore")
                if size > 256 * 1024:
                    content = content.split("\n\n", 1)[-1]
        except FileNotFoundError:
            pass
        except Exception:
            log.exception("read transcript")
        self.text.configure(state="normal")
        self.text.delete("1.0", "end")
        self.text.configure(state="disabled")
        self._append(content, force_scroll=True)

    def _append(self, s, force_scroll=False):
        at_bottom = force_scroll or self.text.yview()[1] >= 0.999
        self.text.configure(state="normal")
        self.text.insert("end", s)
        self.text.configure(state="disabled")
        if at_bottom:
            self.text.see("end")


def run_tray_only(engine):
    """Fallback when Tk is unavailable: tray icon without the live window."""
    import pystray
    from pystray import Menu, MenuItem as Item
    icon = pystray.Icon(APP, make_icon(engine.state), APP, Menu(
        Item(lambda item: engine.status, None, enabled=False),
        Item(lambda item: "Tiếp tục ghi" if engine.paused else "Tạm dừng",
             lambda i, it: engine.set_paused(not engine.paused), default=True),
        Item("Mở thư mục transcript", lambda i, it: open_path(engine.cfg["output_dir"])),
        Item("Cấu hình (config.json)", lambda i, it: open_path(CONFIG_PATH)),
        Item("Thoát", lambda i, it: (engine.stop(), icon.stop())),
    ))

    def on_status(status):
        icon.title = f"{APP}: {status}"[:127]
        icon.icon = make_icon(engine.state)
        icon.update_menu()

    engine.on_status = on_status
    icon.run(setup=lambda ic: (setattr(ic, "visible", True), engine.start()))


def run_console(engine):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    stamp = lambda: f"[{datetime.now():%H:%M:%S}]"
    engine.on_status = lambda s: print(stamp(), s, flush=True)
    engine.on_entry = lambda start, text: print(stamp(), "=>", text, flush=True)
    engine.start()
    try:
        while engine.is_alive():
            engine.join(1)
    except KeyboardInterrupt:
        engine.stop()


# ---- entry ---------------------------------------------------------------------

def setup_logging():
    os.makedirs(APP_DIR, exist_ok=True)
    h = RotatingFileHandler(os.path.join(APP_DIR, "voicenote.log"), maxBytes=1_000_000,
                            backupCount=2, encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(threadName)s: %(message)s"))
    log.addHandler(h)
    log.setLevel(logging.INFO)
    sys.excepthook = lambda *a: log.critical("uncaught", exc_info=a)
    threading.excepthook = lambda a: log.critical("uncaught in %s", a.thread, exc_info=(a.exc_type, a.exc_value, a.exc_traceback))


def single_instance():
    """Keeps a named mutex for the process lifetime; False if another copy runs."""
    k32 = ctypes.windll.kernel32
    single_instance.handle = k32.CreateMutexW(None, False, f"Local\\{APP}_single_instance")
    return k32.GetLastError() != 183  # ERROR_ALREADY_EXISTS


def main():
    setup_logging()
    console = "--console" in sys.argv
    if not single_instance():
        if not console:
            ctypes.windll.user32.MessageBoxW(None, f"{APP} đang chạy (xem khay hệ thống).", APP, 0x40)
        return
    cfg = load_config()
    if "--lang" in sys.argv[:-1]:
        lang = sys.argv[sys.argv.index("--lang") + 1]
        if lang in LANGS:
            cfg["language"] = lang
            save_config(cfg)
    try:
        set_autostart(cfg["autostart"])  # keeps the registry path in sync if the exe moved
    except Exception:
        log.exception("autostart sync")
    log.info("start, config=%s", CONFIG_PATH)
    engine = Engine(cfg)
    if console:
        run_console(engine)
        return
    try:
        app = App(engine)
    except Exception:
        log.exception("Tk unavailable, tray only")
        run_tray_only(engine)
        return
    if "--show" in sys.argv:
        engine.cfg["show_window_on_start"] = True
    app.run()


if __name__ == "__main__":
    main()
