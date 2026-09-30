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

DEFAULTS = {
    "model_url": "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip",
    "model_dir": "",            # optional: path to an already-unpacked model (skips download)
    "output_dir": os.path.join(os.path.expanduser("~"), "Documents", APP),
    "time_format": "%H:%M:%S",  # strftime syntax
    "pause_ms": 1200,           # results closer than this are merged; 0 = never merge
    "input_device": None,       # None = system default, or device name / index
    "autostart": False,
    "start_paused": False,
}

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
    try:
        cfg["pause_ms"] = max(0, min(60000, int(cfg["pause_ms"])))
    except (TypeError, ValueError):
        cfg["pause_ms"] = DEFAULTS["pause_ms"]
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

    def write(self, start_ts, text):
        os.makedirs(self.out_dir, exist_ok=True)
        dt = datetime.fromtimestamp(start_ts)
        try:
            stamp = dt.strftime(self.time_format)
        except ValueError:
            stamp = dt.strftime(DEFAULTS["time_format"])
        with open(self.path_for(dt), "a", encoding="utf-8", newline="\n") as f:
            f.write(f"{stamp}:\n- {text}\n\n")
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


def ensure_model(url, progress):
    name = os.path.splitext(os.path.basename(url.split("?")[0]))[0] or "model"
    root = os.path.join(MODELS_DIR, name)
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


# ---- engine --------------------------------------------------------------------

class Engine(threading.Thread):
    def __init__(self, cfg, on_status=None):
        super().__init__(name="engine", daemon=True)
        self.cfg = cfg
        self.on_status = on_status or (lambda s: None)
        self.status = "Đang khởi động…"
        self.last_text = ""
        self.paused = bool(cfg.get("start_paused"))
        self.state = "loading"  # loading | listening | paused | error
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._reload = False
        self._model = None
        self._model_path = None

    # control (called from tray thread)
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
        try:
            self.on_status(status)
        except Exception:
            log.exception("status callback")

    def _sleep(self, seconds):
        self._wake.clear()
        self._wake.wait(seconds)

    def run(self):
        backoff = 2.0
        while not self._stop.is_set():
            try:
                self._reload = False
                if self.paused:
                    self._set("Tạm dừng", "paused")
                    self._sleep(3600)
                    continue
                cfg = self.cfg
                self._recognize(self._load_model(cfg), cfg)
                backoff = 2.0
            except Exception as e:
                if self._stop.is_set():
                    break
                log.exception("engine error")
                self._set(f"Lỗi: {e}. Thử lại sau {int(backoff)}s", "error")
                self._sleep(backoff)
                backoff = min(backoff * 2, 60.0)

    def _load_model(self, cfg):
        import vosk
        vosk.SetLogLevel(-1)
        path = cfg.get("model_dir") or ""
        if path:
            path = find_model_dir(path) or path
        else:
            path = ensure_model(cfg["model_url"], lambda s: self._set(s, "loading"))
        if self._model is not None and self._model_path == path:
            return self._model
        self._set("Đang nạp model…", "loading")
        try:
            self._model = vosk.Model(path)
        except Exception:
            # corrupted download -> force re-download next attempt
            if not cfg.get("model_dir"):
                marker = os.path.join(MODELS_DIR, os.path.relpath(path, MODELS_DIR).split(os.sep)[0], ".ready")
                if os.path.exists(marker):
                    os.remove(marker)
            raise
        self._model_path = path
        return self._model

    def _recognize(self, model, cfg):
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

        seg = Segmenter(cfg["pause_ms"], sink)
        rec = vosk.KaldiRecognizer(model, rate)
        cfg_mtime = _mtime(CONFIG_PATH)
        next_cfg_check = time.time() + 2
        try:
            with sd.RawInputStream(samplerate=rate, blocksize=rate // 5, device=device,
                                   dtype="int16", channels=1, callback=callback):
                self._set("Đang nghe…", "listening")
                while not (self._stop.is_set() or self._reload or self.paused):
                    try:
                        data = audio.get(timeout=3)
                    except queue.Empty:
                        raise IOError("Micro không trả dữ liệu")
                    now = time.time()
                    if rec.AcceptWaveform(data):
                        self._safe(seg.on_final, _field(rec.Result(), "text"), now)
                    else:
                        seg.on_partial(_field(rec.PartialResult(), "partial"), now)
                    self._safe(seg.tick, now)
                    if now >= next_cfg_check:  # auto-apply edits to config.json
                        next_cfg_check = now + 2
                        m = _mtime(CONFIG_PATH)
                        if m != cfg_mtime:
                            cfg_mtime = m
                            self.cfg = load_config()
                            self._reload = True
                self._safe(seg.on_final, _field(rec.FinalResult(), "text"), time.time())
        finally:
            self._safe(seg.flush)

    def _safe(self, fn, *args):
        """Disk errors must not kill recognition: log, show, keep the text pending."""
        try:
            fn(*args)
        except Exception as e:
            log.exception("write failed")
            self._set(f"Lỗi ghi file: {e}", "error")


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


# ---- tray UI -------------------------------------------------------------------

COLORS = {"listening": (229, 57, 53), "loading": (251, 140, 0), "error": (251, 140, 0), "paused": (120, 120, 120)}


def make_icon(state):
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    color = COLORS.get(state, COLORS["loading"])
    d.ellipse((4, 4, 60, 60), fill=color)
    d.rounded_rectangle((24, 12, 40, 38), radius=8, fill="white")          # mic head
    d.arc((16, 20, 48, 46), start=0, end=180, fill="white", width=4)       # mic cradle
    d.rectangle((30, 46, 34, 52), fill="white")                            # stand
    return img


def open_path(path):
    try:
        os.makedirs(path if not os.path.splitext(path)[1] else os.path.dirname(path), exist_ok=True)
        os.startfile(path)
    except Exception:
        log.exception("open %s", path)


def run_tray(engine):
    import pystray
    from pystray import Menu, MenuItem as Item

    icon = pystray.Icon(APP, make_icon(engine.state), APP)
    shown = {"state": None}

    def on_status(status):
        tip = f"{APP}: {status}"
        if engine.last_text:
            tip += f"\n{engine.last_text}"
        icon.title = tip[:127]
        if engine.state != shown["state"]:
            shown["state"] = engine.state
            icon.icon = make_icon(engine.state)
        icon.update_menu()

    def toggle_pause(_icon, _item):
        engine.set_paused(not engine.paused)

    def toggle_autostart(_icon, _item):
        cfg = load_config()
        cfg["autostart"] = not cfg["autostart"]
        try:
            set_autostart(cfg["autostart"])
            save_config(cfg)
        except Exception:
            log.exception("autostart")

    def open_today(_icon, _item):
        writer = TranscriptWriter(engine.cfg["output_dir"], engine.cfg["time_format"])
        path = writer.path_for(datetime.now())
        if os.path.exists(path):
            open_path(path)
        else:
            open_path(engine.cfg["output_dir"])

    def reload_cfg(_icon, _item):
        engine.reload(load_config())

    def quit_app(_icon, _item):
        engine.stop()
        icon.stop()

    icon.menu = Menu(
        Item(lambda item: engine.status, None, enabled=False),
        Menu.SEPARATOR,
        Item(lambda item: "Tiếp tục ghi" if engine.paused else "Tạm dừng", toggle_pause, default=True),
        Item("Mở file hôm nay", open_today),
        Item("Mở thư mục transcript", lambda i, it: open_path(engine.cfg["output_dir"])),
        Menu.SEPARATOR,
        Item("Cấu hình (config.json)", lambda i, it: open_path(CONFIG_PATH)),
        Item("Tải lại cấu hình", reload_cfg),
        Item("Chạy khi khởi động Windows", toggle_autostart, checked=lambda item: engine.cfg.get("autostart")),
        Item("Mở log", lambda i, it: open_path(os.path.join(APP_DIR, "voicenote.log"))),
        Menu.SEPARATOR,
        Item("Thoát", quit_app),
    )
    engine.on_status = on_status
    icon.run(setup=lambda ic: (setattr(ic, "visible", True), engine.start()))


def run_console(engine):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    engine.on_status = lambda s: print(f"[{datetime.now():%H:%M:%S}] {s}", flush=True)
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
        run_tray(engine)
    except ImportError:
        log.exception("tray unavailable, running headless")
        run_console(engine)


if __name__ == "__main__":
    main()
