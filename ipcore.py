"""Core (non-graphical) logic of MyIP Widget.

Kept free of any tkinter / tray imports so it can be tested anywhere.
"""
import ipaddress
import json
import logging
import os
import re
import subprocess
import sys
import tempfile
import threading
from logging.handlers import RotatingFileHandler

import requests

__version__ = "0.2.0"

APP_NAME = "MyIP Widget"
APP_DIR_NAME = "MyIPWidget"
REQUEST_TIMEOUT = 6
OFFLINE_AFTER_FAILURES = 2  # consecutive failed checks before showing "No Internet"

HEADERS = {"User-Agent": f"MyIP-Widget/{__version__}"}

log = logging.getLogger("myip")


# --------------------------------------------------------------------------
# Files / settings
# --------------------------------------------------------------------------
def app_data_dir():
    base = os.environ.get("APPDATA") or os.path.join(os.path.expanduser("~"), ".config")
    path = os.path.join(base, APP_DIR_NAME)
    try:
        os.makedirs(path, exist_ok=True)
        return path
    except OSError:
        fallback = os.path.join(tempfile.gettempdir(), APP_DIR_NAME)
        os.makedirs(fallback, exist_ok=True)
        return fallback


def setup_logging():
    """Write a small rotating log file (the app has no console window)."""
    if log.handlers:
        return log
    log.setLevel(logging.INFO)
    try:
        handler = RotatingFileHandler(
            os.path.join(app_data_dir(), "myip-widget.log"),
            maxBytes=200_000, backupCount=1, encoding="utf-8",
        )
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        log.addHandler(handler)
    except OSError:
        log.addHandler(logging.NullHandler())
    return log


DEFAULT_SETTINGS = {
    "x": None,
    "y": None,
    "opacity": 0.7,
    "interval": 10,
    "show_details": True,
    "visible": True,
}


def _validate(key, value):
    """Return a safe value for a setting, or the default if it is invalid."""
    default = DEFAULT_SETTINGS[key]
    try:
        if key in ("x", "y"):
            if value is None:
                return None
            if isinstance(value, bool):
                return default
            return int(value)
        if key == "opacity":
            return min(1.0, max(0.2, float(value)))
        if key == "interval":
            return min(300, max(5, int(value)))
        if key in ("show_details", "visible"):
            return value if isinstance(value, bool) else default
    except (TypeError, ValueError):
        pass
    return default


class Settings:
    def __init__(self, path=None):
        self.path = path or os.path.join(app_data_dir(), "settings.json")
        self.data = dict(DEFAULT_SETTINGS)

    def load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as fh:
                raw = json.load(fh)
        except (OSError, ValueError):
            return self
        if isinstance(raw, dict):
            for key in DEFAULT_SETTINGS:
                if key in raw:
                    self.data[key] = _validate(key, raw[key])
        return self

    def save(self):
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(self.data, fh, indent=2)
            os.replace(tmp, self.path)
        except OSError:
            log.exception("Could not save settings")

    def get(self, key):
        return self.data[key]

    def set(self, key, value, save=True):
        self.data[key] = _validate(key, value)
        if save:
            self.save()


# --------------------------------------------------------------------------
# Network lookups
# --------------------------------------------------------------------------
# Step 1: a cheap request that only returns the public IP (tried in order).
IP_ENDPOINTS = [
    "https://api.ipify.org?format=json",
    "https://ipv4.icanhazip.com",
    "https://ifconfig.me/ip",
]

_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]")


def _clean(value, limit=60):
    if value is None:
        return ""
    return _CONTROL_CHARS.sub("", str(value)).strip()[:limit]


def _clean_code(value):
    value = _clean(value, 8)
    return value.upper() if re.fullmatch(r"[A-Za-z]{2}", value) else ""


def _parse_ip(text):
    text = (text or "").strip()
    return str(ipaddress.ip_address(text))  # raises ValueError if not an IP


def fetch_public_ip(http=requests, endpoints=None):
    """Return the current public IP as a string, or None if it cannot be found."""
    for url in endpoints or IP_ENDPOINTS:
        try:
            resp = http.get(url, timeout=REQUEST_TIMEOUT, headers=HEADERS)
            resp.raise_for_status()
            if "format=json" in url:
                return _parse_ip(resp.json().get("ip", ""))
            return _parse_ip(resp.text)
        except Exception as exc:  # network errors, bad data, ...
            log.debug("IP source %s failed: %s", url, exc)
    return None


# Step 2: details (country, city, provider) - only asked when the IP changes.
def _from_ipwho(d):
    if not d.get("success"):
        raise ValueError("ipwho.is returned an error")
    conn = d.get("connection") or {}
    return {
        "country": _clean(d.get("country")),
        "code": _clean_code(d.get("country_code")),
        "city": _clean(d.get("city")),
        "isp": _clean(conn.get("isp") or conn.get("org")),
    }


def _from_ipapi_co(d):
    if d.get("error"):
        raise ValueError("ipapi.co returned an error")
    return {
        "country": _clean(d.get("country_name")),
        "code": _clean_code(d.get("country_code")),
        "city": _clean(d.get("city")),
        "isp": _clean(d.get("org")),
    }


def _from_ip_api(d):
    if d.get("status") != "success":
        raise ValueError("ip-api returned an error")
    return {
        "country": _clean(d.get("country")),
        "code": _clean_code(d.get("countryCode")),
        "city": _clean(d.get("city")),
        "isp": _clean(d.get("isp")),
    }


DETAIL_SOURCES = [
    ("https://ipwho.is/{ip}", _from_ipwho),
    ("https://ipapi.co/{ip}/json/", _from_ipapi_co),
    ("http://ip-api.com/json/{ip}", _from_ip_api),  # last resort (no https on free plan)
]


def lookup_location(ip, http=requests, sources=None):
    """Return {'country','code','city','isp'} for an IP, or None if all sources fail."""
    for template, parser in sources or DETAIL_SOURCES:
        try:
            resp = http.get(template.format(ip=ip), timeout=REQUEST_TIMEOUT, headers=HEADERS)
            resp.raise_for_status()
            info = parser(resp.json())
            if info["country"] or info["code"]:
                return info
        except Exception as exc:
            log.debug("Details source %s failed: %s", template, exc)
    return None


# --------------------------------------------------------------------------
# Background monitor
# --------------------------------------------------------------------------
def make_state(ip=None, location=None):
    """The dictionary the user interface displays."""
    if not ip:
        return {"online": False, "ip": "", "country": "", "code": "",
                "city": "", "isp": "", "located": False}
    loc = location or {}
    return {
        "online": True,
        "ip": ip,
        "country": loc.get("country", ""),
        "code": loc.get("code", ""),
        "city": loc.get("city", ""),
        "isp": loc.get("isp", ""),
        "located": bool(location),
    }


class IPMonitor(threading.Thread):
    """Checks the public IP regularly and reports changes through on_update(state)."""

    def __init__(self, on_update, interval_fn, fetch_ip=fetch_public_ip, lookup=lookup_location):
        super().__init__(daemon=True, name="ip-monitor")
        self._on_update = on_update
        self._interval_fn = interval_fn
        self._fetch_ip = fetch_ip
        self._lookup = lookup
        self._stop_evt = threading.Event()
        self._wake = threading.Event()
        self._force = False
        self._ip = None
        self._location = None
        self._failures = 0
        self._last_state = None

    # -- control ----------------------------------------------------------
    def refresh(self):
        self._force = True
        self._wake.set()

    def stop(self):
        self._stop_evt.set()
        self._wake.set()

    # -- work -------------------------------------------------------------
    def poll_once(self, force=False):
        ip = self._fetch_ip()
        if ip is None:
            self._failures += 1
            if self._failures >= OFFLINE_AFTER_FAILURES or self._last_state is None or force:
                self._ip = None
                self._location = None
                self._emit(make_state(), force)
            return
        self._failures = 0
        if force or ip != self._ip or self._location is None:
            self._location = self._lookup(ip)
            self._ip = ip
        self._emit(make_state(ip, self._location), force)

    def _emit(self, state, force=False):
        if force or state != self._last_state:
            self._last_state = state
            self._on_update(state)

    def run(self):
        while not self._stop_evt.is_set():
            force, self._force = self._force, False
            try:
                self.poll_once(force)
            except Exception:
                log.exception("Unexpected error while checking the IP")
            try:
                wait = max(5, int(self._interval_fn()))
            except Exception:
                wait = 10
            self._wake.wait(timeout=wait)
            self._wake.clear()


# --------------------------------------------------------------------------
# Windows helpers (safe no-ops elsewhere)
# --------------------------------------------------------------------------
IS_WINDOWS = sys.platform == "win32"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
_mutex_handle = None


def system_is_dark():
    if not IS_WINDOWS:
        return False
    try:
        import winreg
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
        ) as key:
            return winreg.QueryValueEx(key, "AppsUseLightTheme")[0] == 0
    except OSError:
        return False


def startup_command():
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    return f'"{sys.executable}" "{os.path.abspath(sys.argv[0])}"'


def is_startup_enabled():
    if not IS_WINDOWS:
        return False
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            winreg.QueryValueEx(key, APP_NAME)
            return True
    except OSError:
        return False


def set_startup(enabled):
    """Turn 'start with Windows' on or off. Returns True on success."""
    if not IS_WINDOWS:
        return False
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enabled:
                winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, startup_command())
            else:
                try:
                    winreg.DeleteValue(key, APP_NAME)
                except FileNotFoundError:
                    pass
        return True
    except OSError:
        log.exception("Could not change the startup setting")
        return False


def acquire_single_instance():
    """Return False if another copy of the widget is already running."""
    global _mutex_handle
    if not IS_WINDOWS:
        return True
    import ctypes
    _mutex_handle = ctypes.windll.kernel32.CreateMutexW(None, False, "Local\\MyIPWidgetSingleInstance")
    return ctypes.windll.kernel32.GetLastError() != 183  # ERROR_ALREADY_EXISTS


def copy_to_clipboard(text):
    """Copy text with Windows' own 'clip' tool so it stays after the app quits."""
    if not IS_WINDOWS:
        return False
    try:
        subprocess.run(
            ["clip"], input=text.encode("ascii", "ignore"), check=True,
            creationflags=0x08000000,  # CREATE_NO_WINDOW
            timeout=5,
        )
        return True
    except Exception:
        return False
