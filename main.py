"""MyIP Widget - shows your public IP, country and provider from the tray icon.

By default nothing sits on the desktop: click the tray icon and a small pop-up
appears next to the taskbar. "Keep on desktop" in the menu gives the old
always-visible widget back.
"""
import ctypes
import os
import queue
import re
import sys
import threading
import time
import tkinter as tk

from PIL import Image, ImageTk

import ipcore
from ipcore import APP_NAME, IPMonitor, Settings, __version__

try:  # small hint bubble; optional
    from idlelib.tooltip import Hovertip
except Exception:  # pragma: no cover
    Hovertip = None

import pystray
from pystray import MenuItem


def resource_path(relative_path):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, relative_path)


IMG_DIR = resource_path(os.path.join("assets", "images"))
FLAG_DIR = os.path.join(IMG_DIR, "flags")
GLOBE_ICON = os.path.join(IMG_DIR, "globe.png")          # checking / unknown country
OFFLINE_ICON = os.path.join(IMG_DIR, "no_internet.png")  # no connection
ICON_FILE = os.path.join(IMG_DIR, "icon.ico")

FLAG_SIZE = 56
TEXT_WIDTH = 24  # characters; keeps the window a stable size
INFO_WIDTH = 24  # the column next to the flag
OPACITY_CHOICES = (100, 85, 70, 50, 30)
AUTOHIDE_MS = 10000      # pop-up closes by itself after this long if the mouse is not on it
FOCUS_GRACE = 0.4        # seconds after showing in which losing focus is ignored
REOPEN_GUARD = 0.7       # a tray click this soon after the pop-up closed itself only closes it
HINT = "Right-click: menu\nDouble-click: copy IP"
CREDIT_FA = "سازنده: مصطفی ماندگاری"


def draw_globe(offline=False):
    """Draws the globe icons in code - used only if the PNG files are not there."""
    from PIL import ImageDraw
    size, out = 1024, 128
    body, line = ((100, 108, 120, 255), (225, 228, 234, 235)) if offline else \
                 ((37, 99, 235, 255), (255, 255, 255, 235))
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    margin, width, c = 40, 30, size // 2
    r = c - margin
    box = (margin, margin, size - margin, size - margin)
    d.ellipse(box, fill=body)
    d.ellipse(box, outline=line, width=width)
    d.line((margin, c, size - margin, c), fill=line, width=width)
    d.line((c, margin, c, size - margin), fill=line, width=width)
    d.ellipse((c - r // 2, margin, c + r // 2, size - margin), outline=line, width=width)
    half = int((r * r - (r // 2) ** 2) ** 0.5)
    for dy in (-r // 2, r // 2):
        d.line((c - half, c + dy, c + half, c + dy), fill=line, width=width)
    if offline:
        a, b = (175, 175), (size - 175, size - 175)
        d.line((a, b), fill=(255, 255, 255, 255), width=170)
        d.line((a, b), fill=(220, 38, 38, 255), width=100)
    return img.resize((out, out), Image.LANCZOS)


def load_icon(path):
    try:
        return Image.open(path).convert("RGBA")
    except Exception:
        return draw_globe(offline=(path == OFFLINE_ICON))


def load_flag_image(code):
    """Return a PIL image of the country flag (a plain globe if unknown)."""
    if re.fullmatch(r"[A-Z]{2}", code or ""):
        path = os.path.join(FLAG_DIR, f"{code}.png")
        if os.path.isfile(path):
            try:
                return load_icon(path)
            except Exception:
                pass
    return load_icon(GLOBE_ICON)


def virtual_screen(root):
    """(left, top, width, height) of the area made of all monitors."""
    if ipcore.IS_WINDOWS:
        try:
            gm = ctypes.windll.user32.GetSystemMetrics
            return gm(76), gm(77), gm(78), gm(79)
        except Exception:
            pass
    return 0, 0, root.winfo_screenwidth(), root.winfo_screenheight()


def work_area(root):
    """(left, top, right, bottom) of the main screen without the taskbar."""
    if ipcore.IS_WINDOWS:
        try:
            from ctypes import wintypes
            rect = wintypes.RECT()
            if ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(rect), 0):
                return rect.left, rect.top, rect.right, rect.bottom
        except Exception:
            pass
    return 0, 0, root.winfo_screenwidth(), root.winfo_screenheight()


class App:
    def __init__(self):
        self.settings = Settings().load()
        self.ui_queue = queue.Queue()
        self.state = ipcore.make_state()
        self.checking = True
        self.closing = False
        self._flash_job = None
        self._autohide_job = None
        self._menu_open = False
        self._shown_at = 0.0
        self._last_focus_hide = 0.0
        self._drag = (0, 0)
        self.tray = None
        self.speed = ipcore.SpeedMeter()
        self._tray_base_title = APP_NAME
        self._tray_title_shown = None
        self._speed_line = self._speed_text(0, 0)
        self.usage = ipcore.DailyUsage()
        self._usage_line = self._usage_text()
        self.weather = None          # {'temp': .., 'code': ..} once known
        self._city_dlg = None

        self.root = tk.Tk()
        self.root.withdraw()
        self.root.overrideredirect(True)
        self.root.title(APP_NAME)
        try:
            self.root.iconbitmap(ICON_FILE)
        except Exception:
            pass
        self.root.report_callback_exception = self._report_error

        self._build_colors()
        self._build_widgets()
        self._build_menu()

        self.root.attributes("-topmost", True)
        self.root.attributes("-alpha", self.settings.get("opacity"))
        self.root.bind("<FocusOut>", self._on_focus_out)

        self._render()
        self._start_tray()

        if self.pinned:          # normally the widget stays hidden until the tray icon is clicked
            self.show_window()

        self.monitor = IPMonitor(
            on_update=lambda state: self.post("data", state),
            interval_fn=lambda: self.settings.get("interval"),
        )
        self.monitor.start()
        self.weather_monitor = ipcore.WeatherMonitor(
            on_update=lambda result: self.post("weather", result),
            location_fn=self._weather_location,
        )
        self.weather_monitor.start()
        self.root.after(100, self._process_queue)
        self.speed.update()  # first reading; real speeds appear a second later
        self.root.after(1000, self._speed_tick)

    @property
    def pinned(self):
        return bool(self.settings.get("pinned"))

    @staticmethod
    def _speed_text(down, up):
        return f"\u2193 {ipcore.format_speed(down)}    \u2191 {ipcore.format_speed(up)}"

    @staticmethod
    def _speed_label_text(down, up):
        return f"\u2193 {ipcore.format_speed(down)}\n\u2191 {ipcore.format_speed(up)}"

    def _usage_text(self):
        return (f"Today  \u2193 {ipcore.format_bytes(self.usage.down)}"
                f"  \u2191 {ipcore.format_bytes(self.usage.up)}")

    def _weather_location(self):
        """(latitude, longitude) of the chosen city, or None when weather is off or no city is set."""
        if not self.settings.get("show_weather"):
            return None
        lat, lon = self.settings.get("city_lat"), self.settings.get("city_lon")
        return (lat, lon) if lat is not None and lon is not None else None

    def _weather_wanted(self):
        return self._weather_location() is not None

    def _speed_tick(self):
        if self.closing:
            return
        try:
            down, up = self.speed.update()
            self.usage.add(self.speed.down_bytes, self.speed.up_bytes)
            self.usage.save()
            self._speed_line = self._speed_text(down, up)
            self._usage_line = self._usage_text()
            self.speed_lbl.config(text=self._speed_label_text(down, up))
            self.usage_lbl.config(text=self._usage_line)
            self._apply_tray_title()
            self._ticks = getattr(self, "_ticks", 0) + 1
            if self._ticks % 60 == 0 and self.tray and getattr(self, "_tray_image", None) is not None:
                self.tray.icon = self._tray_image  # draw it again, in case Windows lost it
        except Exception:
            ipcore.log.exception("Could not update the network speed")
        self.root.after(1000, self._speed_tick)

    def _refresh_info(self):
        """Shows or hides the weather / speed / usage next to the flag, as chosen in the menu."""
        show_weather = self._weather_wanted()
        show_speed = bool(self.settings.get("show_speed"))
        show_usage = bool(self.settings.get("show_usage"))

        if show_weather:
            if self.weather:
                text = f"{ipcore.weather_symbol(self.weather['code'])} {ipcore.format_temp(self.weather['temp'])}"
            else:
                text = "..."
            self.weather_lbl.config(text=text)
            self.weather_city_lbl.config(text=self.settings.get("city_name"))
            self.weather_lbl.grid()
            self.weather_city_lbl.grid()
        else:
            self.weather_lbl.grid_remove()
            self.weather_city_lbl.grid_remove()
        if show_speed:
            self.speed_lbl.grid()
        else:
            self.speed_lbl.grid_remove()
        if show_usage:
            self.usage_lbl.grid()
        else:
            self.usage_lbl.grid_remove()

        if show_weather or show_speed or show_usage:
            self.info.grid()
        else:
            self.info.grid_remove()
        if self._is_visible() and not self.pinned:
            self._place_window()

    # ------------------------------------------------------------------ UI
    def _build_colors(self):
        if ipcore.system_is_dark():
            self.bg, self.fg, self.dim, self.border = "#101010", "#f2f2f2", "#a8a8a8", "#3a3a3a"
        else:
            self.bg, self.fg, self.dim, self.border = "#ffffff", "#111111", "#5c5c5c", "#c8c8c8"
        self.root.configure(bg=self.bg)

    def _build_widgets(self):
        scale = max(1.0, self.root.winfo_fpixels("1i") / 96.0)
        self.flag_px = int(FLAG_SIZE * scale)

        self.frame = tk.Frame(self.root, bg=self.bg, highlightthickness=1,
                              highlightbackground=self.border, padx=10, pady=8)
        self.frame.pack()

        # top row: the flag, and next to it the weather, the speed and today's volume
        self.top = tk.Frame(self.frame, bg=self.bg)
        self.flag_lbl = tk.Label(self.top, bg=self.bg, bd=0)
        self.info = tk.Frame(self.top, bg=self.bg)
        info_args = dict(bg=self.bg, bd=0, highlightthickness=0, anchor="w", justify="left",
                         width=INFO_WIDTH)
        self.weather_lbl = tk.Label(self.info, fg=self.fg, font=("Segoe UI", 13, "bold"), **info_args)
        self.weather_city_lbl = tk.Label(self.info, fg=self.dim, font=("Segoe UI", 8), **info_args)
        self.speed_lbl = tk.Label(self.info, fg=self.fg, font=("Segoe UI", 9),
                                  text=self._speed_label_text(0, 0), **info_args)
        self.usage_lbl = tk.Label(self.info, fg=self.dim, font=("Segoe UI", 8),
                                  text=self._usage_line, **info_args)
        self.weather_lbl.grid(row=0, column=0, sticky="w")
        self.weather_city_lbl.grid(row=1, column=0, sticky="w")
        self.speed_lbl.grid(row=2, column=0, sticky="w", pady=(4, 0))
        self.usage_lbl.grid(row=3, column=0, sticky="w", pady=(2, 0))
        self.flag_lbl.grid(row=0, column=0)
        self.info.grid(row=0, column=1, padx=(10, 0))

        label_args = dict(bg=self.bg, bd=0, highlightthickness=0, width=TEXT_WIDTH)
        self.country_lbl = tk.Label(self.frame, fg=self.fg, font=("Segoe UI", 11, "bold"), **label_args)
        self.ip_lbl = tk.Label(self.frame, fg=self.fg, font=("Segoe UI", 10), **label_args)
        self.detail_lbl = tk.Label(self.frame, fg=self.dim, font=("Segoe UI", 8),
                                   wraplength=int(190 * scale), justify="center", **label_args)
        self.credit_lbl = tk.Label(self.frame, fg=self.dim, font=("Segoe UI", 8),
                                   text=CREDIT_FA, **label_args)

        self.top.grid(row=0, column=0)
        self.country_lbl.grid(row=1, column=0)
        self.ip_lbl.grid(row=2, column=0)
        self.detail_lbl.grid(row=3, column=0)
        self.credit_lbl.grid(row=4, column=0, pady=(4, 0))

        # Not bound on the root window itself: its events would fire twice.
        for widget in (self.frame, self.top, self.info, self.flag_lbl, self.weather_lbl,
                       self.weather_city_lbl, self.speed_lbl, self.usage_lbl, self.country_lbl,
                       self.ip_lbl, self.detail_lbl, self.credit_lbl):
            widget.bind("<ButtonPress-1>", self._drag_start)
            widget.bind("<B1-Motion>", self._drag_move)
            widget.bind("<ButtonRelease-1>", self._drag_end)
            widget.bind("<Button-3>", self._show_menu)
            widget.bind("<Double-Button-1>", lambda e: self.copy_ip())

        if Hovertip:
            try:
                Hovertip(self.flag_lbl, HINT, hover_delay=700)
            except Exception:
                pass
        self._refresh_info()

    def _build_menu(self):
        self.details_var = tk.BooleanVar(value=self.settings.get("show_details"))
        self.weather_var = tk.BooleanVar(value=self.settings.get("show_weather"))
        self.speed_var = tk.BooleanVar(value=self.settings.get("show_speed"))
        self.usage_var = tk.BooleanVar(value=self.settings.get("show_usage"))
        self.pin_var = tk.BooleanVar(value=self.pinned)
        self.startup_var = tk.BooleanVar(value=ipcore.is_startup_enabled())
        self.opacity_var = tk.IntVar(value=self._nearest_opacity())

        self.menu = tk.Menu(self.root, tearoff=0)
        self.menu.add_command(label="Copy IP address", command=self.copy_ip)
        self.menu.add_command(label="Refresh now", command=lambda: self.monitor.refresh())
        self.menu.add_separator()
        self.menu.add_checkbutton(label="Show city and provider", variable=self.details_var,
                                  command=self._toggle_details)
        self.menu.add_checkbutton(label="Show weather", variable=self.weather_var,
                                  command=lambda: self._toggle_option("show_weather", self.weather_var))
        self.menu.add_checkbutton(label="Show speed", variable=self.speed_var,
                                  command=lambda: self._toggle_option("show_speed", self.speed_var))
        self.menu.add_checkbutton(label="Show today's data usage", variable=self.usage_var,
                                  command=lambda: self._toggle_option("show_usage", self.usage_var))
        self.menu.add_command(label="Choose city for weather...", command=self.choose_city)
        opacity_menu = tk.Menu(self.menu, tearoff=0)
        for pct in OPACITY_CHOICES:
            opacity_menu.add_radiobutton(label=f"{pct}%", value=pct, variable=self.opacity_var,
                                         command=self._apply_opacity)
        self.menu.add_cascade(label="Opacity", menu=opacity_menu)
        self.menu.add_checkbutton(label="Keep on desktop", variable=self.pin_var,
                                  command=self._toggle_pin)
        if ipcore.IS_WINDOWS:
            self.menu.add_checkbutton(label="Start with Windows", variable=self.startup_var,
                                      command=self._toggle_startup)
        self.menu.add_separator()
        self.menu.add_command(label="Close", command=self.hide_window)
        self.menu.add_command(label="Quit", command=self.quit)

    def _nearest_opacity(self):
        current = self.settings.get("opacity") * 100
        return min(OPACITY_CHOICES, key=lambda pct: abs(pct - current))

    def _show_menu(self, event):
        self._menu_open = True
        try:
            self.menu.tk_popup(event.x_root, event.y_root)
        finally:
            self.menu.grab_release()
            self._menu_open = False

    # -------------------------------------------------------------- window
    def _is_visible(self):
        return self.root.state() != "withdrawn"

    def _place_window(self):
        self.root.update_idletasks()
        width, height = self.root.winfo_reqwidth(), self.root.winfo_reqheight()
        if self.pinned:
            left, top, vw, vh = virtual_screen(self.root)
            x, y = self.settings.get("x"), self.settings.get("y")
            if x is None or y is None:
                x, y = left + vw - width - 24, top + 60
            x = max(left, min(x, left + vw - width))
            y = max(top, min(y, top + vh - height))
        else:  # pop-up: bottom-right corner, just above the taskbar / tray
            _, _, right, bottom = work_area(self.root)
            x, y = right - width - 12, bottom - height - 12
        self.root.geometry(f"+{x}+{y}")

    def _drag_start(self, event):
        self._drag = (event.x_root - self.root.winfo_x(), event.y_root - self.root.winfo_y())

    def _drag_move(self, event):
        if not self.pinned:
            return  # the pop-up always stays next to the taskbar
        dx, dy = self._drag
        self.root.geometry(f"+{event.x_root - dx}+{event.y_root - dy}")

    def _drag_end(self, event):
        if not self.pinned:
            return
        self.settings.set("x", self.root.winfo_x(), save=False)
        self.settings.set("y", self.root.winfo_y())

    def show_window(self):
        self._place_window()
        self.root.deiconify()
        self.root.attributes("-topmost", True)
        self.root.lift()
        self._shown_at = time.time()
        if not self.pinned:
            try:
                self.root.focus_force()  # so it can notice when you click somewhere else
            except tk.TclError:
                pass
            self._schedule_autohide()

    def hide_window(self, from_focus=False):
        self._cancel_autohide()
        self.root.withdraw()
        if from_focus:
            self._last_focus_hide = time.time()

    def toggle_window(self):
        if self._is_visible():
            self.hide_window()
        elif not self.pinned and time.time() - self._last_focus_hide < REOPEN_GUARD:
            return  # this click is what just closed the pop-up; don't reopen it
        else:
            self.show_window()

    def _on_focus_out(self, event):
        if event.widget is not self.root:
            return
        if self.pinned or self._menu_open or not self._is_visible():
            return
        if time.time() - self._shown_at < FOCUS_GRACE:
            return
        self.hide_window(from_focus=True)

    def _schedule_autohide(self):
        self._cancel_autohide()
        self._autohide_job = self.root.after(AUTOHIDE_MS, self._autohide_tick)

    def _cancel_autohide(self):
        if self._autohide_job:
            try:
                self.root.after_cancel(self._autohide_job)
            except tk.TclError:
                pass
            self._autohide_job = None

    def _autohide_tick(self):
        self._autohide_job = None
        if self.pinned or not self._is_visible():
            return
        px, py = self.root.winfo_pointerxy()
        x, y = self.root.winfo_rootx(), self.root.winfo_rooty()
        inside = x <= px < x + self.root.winfo_width() and y <= py < y + self.root.winfo_height()
        if inside or self._menu_open:
            self._schedule_autohide()  # still being used
        else:
            self.hide_window()

    # ------------------------------------------------------------ settings
    def _toggle_details(self):
        self.settings.set("show_details", bool(self.details_var.get()))
        self._render()

    def _toggle_option(self, key, var):
        self.settings.set(key, bool(var.get()))
        if key == "show_weather":
            self.weather_monitor.refresh()
        self._refresh_info()
        self._apply_tray_title()

    # ---------------------------------------------------------- city dialog
    def choose_city(self):
        """A small window: type a city name, search, pick one from the list."""
        if self._city_dlg:
            try:
                self._city_dlg["win"].lift()
                self._city_dlg["win"].focus_force()
            except tk.TclError:
                pass
            return
        win = tk.Toplevel(self.root)
        win.title("Choose city for weather")
        win.configure(bg=self.bg, padx=14, pady=12)
        win.resizable(False, False)
        win.attributes("-topmost", True)
        try:
            win.iconbitmap(ICON_FILE)
        except Exception:
            pass

        tk.Label(win, text="City name (Persian or English):", bg=self.bg, fg=self.fg,
                 font=("Segoe UI", 10)).grid(row=0, column=0, columnspan=2, sticky="w")
        entry = tk.Entry(win, width=34, font=("Segoe UI", 11))
        entry.grid(row=1, column=0, sticky="we", pady=(4, 6))
        tk.Button(win, text="Search", command=self._city_search).grid(row=1, column=1, padx=(6, 0))
        listbox = tk.Listbox(win, height=7, width=44, font=("Segoe UI", 10), exportselection=False)
        listbox.grid(row=2, column=0, columnspan=2, sticky="we")
        status = tk.Label(win, text="Type a name and press Enter.", bg=self.bg, fg=self.dim,
                          font=("Segoe UI", 9), anchor="w")
        status.grid(row=3, column=0, columnspan=2, sticky="we", pady=(6, 6))
        buttons = tk.Frame(win, bg=self.bg)
        buttons.grid(row=4, column=0, columnspan=2, sticky="e")
        tk.Button(buttons, text="Use this city", command=self._city_accept).pack(side="left", padx=(0, 6))
        tk.Button(buttons, text="Remove city", command=self._city_clear).pack(side="left", padx=(0, 6))
        tk.Button(buttons, text="Cancel", command=self._city_close).pack(side="left")
        entry.bind("<Return>", lambda e: self._city_search())
        listbox.bind("<Double-Button-1>", lambda e: self._city_accept())
        win.protocol("WM_DELETE_WINDOW", self._city_close)

        self._city_dlg = {"win": win, "entry": entry, "list": listbox, "status": status,
                          "results": [], "token": 0}
        win.update_idletasks()
        left, top, right, bottom = work_area(self.root)
        x = left + (right - left - win.winfo_reqwidth()) // 2
        y = top + (bottom - top - win.winfo_reqheight()) // 3
        win.geometry(f"+{x}+{y}")
        entry.focus_force()

    def _city_search(self):
        dlg = self._city_dlg
        if not dlg:
            return
        query = dlg["entry"].get().strip()
        if len(query) < 2:
            dlg["status"].config(text="Type at least two letters.")
            return
        dlg["token"] += 1
        token = dlg["token"]
        dlg["status"].config(text="Searching...")

        def work():
            self.post("cities", (token, ipcore.search_cities(query)))

        threading.Thread(target=work, daemon=True).start()

    def _city_results(self, token, results):
        dlg = self._city_dlg
        if not dlg or token != dlg["token"]:
            return  # an older search, or the window was closed
        dlg["list"].delete(0, "end")
        if results is None:
            dlg["results"] = []
            dlg["status"].config(text="Could not search. Check the internet connection.")
            return
        dlg["results"] = results
        for item in results:
            dlg["list"].insert("end", " - ".join(p for p in (item["name"], item["region"], item["country"]) if p))
        if results:
            dlg["list"].selection_set(0)
            dlg["status"].config(text="Choose your city, then press \"Use this city\".")
        else:
            dlg["status"].config(text="No city found. Try another spelling.")

    def _city_accept(self):
        dlg = self._city_dlg
        if not dlg:
            return
        selection = dlg["list"].curselection()
        if not selection or selection[0] >= len(dlg["results"]):
            dlg["status"].config(text="Select a city from the list first.")
            return
        self._set_city(dlg["results"][selection[0]])

    def _set_city(self, item):
        self.settings.set("city_name", item["name"], save=False)
        self.settings.set("city_lat", item["lat"], save=False)
        self.settings.set("city_lon", item["lon"], save=False)
        self.settings.set("show_weather", True)
        self.weather_var.set(True)
        self.weather = None
        self._city_close()
        self.weather_monitor.refresh()
        self._refresh_info()
        self._apply_tray_title()
        self.show_window()

    def _city_clear(self):
        self.settings.set("city_name", "", save=False)
        self.settings.set("city_lat", None, save=False)
        self.settings.set("city_lon", None)
        self.weather = None
        self._city_close()
        self.weather_monitor.refresh()
        self._refresh_info()
        self._apply_tray_title()

    def _city_close(self):
        dlg, self._city_dlg = self._city_dlg, None
        if dlg:
            try:
                dlg["win"].destroy()
            except tk.TclError:
                pass

    def _toggle_pin(self):
        self.settings.set("pinned", bool(self.pin_var.get()))
        if self.pinned:
            self.show_window()
        else:
            self.hide_window()

    def _apply_opacity(self):
        value = self.opacity_var.get() / 100.0
        self.settings.set("opacity", value)
        self.root.attributes("-alpha", value)

    def _toggle_startup(self):
        wanted = bool(self.startup_var.get())
        if not ipcore.set_startup(wanted):
            self.startup_var.set(not wanted)  # it failed: undo the tick mark

    def _on_tray_startup(self):
        self.startup_var.set(not ipcore.is_startup_enabled())
        self._toggle_startup()

    def _on_tray_pin(self):
        self.pin_var.set(not self.pinned)
        self._toggle_pin()

    # ------------------------------------------------------------- content
    def _render(self):
        state = self.state
        if self.checking:
            country, ip, detail = "Checking...", "", ""
            image = load_icon(GLOBE_ICON)
        elif not state["online"]:
            country, ip, detail = "No Internet", "", ""
            image = load_icon(OFFLINE_ICON)
        else:
            country = state["country"] or "Unknown location"
            ip = state["ip"]
            detail = " - ".join(p for p in (state["city"], state["isp"]) if p)
            image = load_flag_image(state["code"])

        self._update_tray(image, ip, country)  # first: the tray must follow even if the pop-up fails
        self._flag_photo = ImageTk.PhotoImage(image.resize((self.flag_px, self.flag_px), Image.LANCZOS))
        self.flag_lbl.config(image=self._flag_photo)
        self.country_lbl.config(text=country)
        self.ip_lbl.config(text=ip)
        self.detail_lbl.config(text=detail)
        if detail and self.settings.get("show_details"):
            self.detail_lbl.grid()
        else:
            self.detail_lbl.grid_remove()

        if self._is_visible() and not self.pinned:
            self._place_window()  # the height may have changed

    def copy_ip(self):
        ip = self.state.get("ip")
        if not ip:
            return
        if not ipcore.copy_to_clipboard(ip):
            self.root.clipboard_clear()
            self.root.clipboard_append(ip)
            self.root.update()
        self._flash("Copied!")

    def _flash(self, text):
        if self._flash_job:
            self.root.after_cancel(self._flash_job)
        self.ip_lbl.config(text=text)
        self._flash_job = self.root.after(1000, self._end_flash)

    def _end_flash(self):
        self._flash_job = None
        self.ip_lbl.config(text=self.state.get("ip", ""))

    # ---------------------------------------------------------------- tray
    def _start_tray(self):
        menu = pystray.Menu(
            MenuItem("Show / Hide", lambda icon, item: self.post("toggle"), default=True),
            MenuItem("Copy IP address", lambda icon, item: self.post("copy")),
            MenuItem("Refresh now", lambda icon, item: self.post("refresh")),
            MenuItem("Choose city for weather...", lambda icon, item: self.post("city")),
            MenuItem("Keep on desktop", lambda icon, item: self.post("pin"),
                     checked=lambda item: self.pinned),
            MenuItem("Start with Windows", lambda icon, item: self.post("startup"),
                     checked=lambda item: ipcore.is_startup_enabled(),
                     visible=ipcore.IS_WINDOWS),
            pystray.Menu.SEPARATOR,
            MenuItem(f"{APP_NAME} {__version__}", lambda icon, item: None, enabled=False),
            MenuItem(CREDIT_FA, lambda icon, item: None, enabled=False),
            MenuItem("Quit", lambda icon, item: self.post("quit")),
        )
        try:
            self.tray = pystray.Icon("MyIPWidget", load_icon(GLOBE_ICON), APP_NAME, menu)
            self.tray.run_detached()
        except Exception:
            ipcore.log.exception("Could not start the tray icon")
            self.tray = None

    def _update_tray(self, image, ip, country):
        self._tray_base_title = f"{ip}  ({country})" if ip else country
        self._tray_image = image
        if not self.tray:
            return
        try:
            self.tray.icon = image
        except Exception:
            ipcore.log.exception("Could not update the tray icon")
        self._apply_tray_title()

    def _apply_tray_title(self):
        """Tooltip = IP and country, plus weather, live speed and today's volume (if shown)."""
        if not self.tray:
            return
        lines = [self._tray_base_title]
        if self._weather_wanted() and self.weather:
            lines.append(f"{ipcore.weather_symbol(self.weather['code'])} "
                         f"{ipcore.format_temp(self.weather['temp'])}  {self.settings.get('city_name')}")
        if self.settings.get("show_speed"):
            lines.append(self._speed_line)
        if self.settings.get("show_usage"):
            lines.append(self._usage_line)
        title = "\n".join(lines)[:127]  # Windows allows at most 127 characters
        if title == self._tray_title_shown:
            return
        try:
            self.tray.title = title
            self._tray_title_shown = title
        except Exception:
            ipcore.log.exception("Could not update the tray tooltip")

    # -------------------------------------------------------------- events
    def post(self, kind, payload=None):
        """Thread-safe way for other threads to ask the window to do something."""
        self.ui_queue.put((kind, payload))

    def _process_queue(self):
        # One bad message must never stop this loop - otherwise the flag would freeze for good.
        try:
            while True:
                try:
                    kind, payload = self.ui_queue.get_nowait()
                except queue.Empty:
                    break
                try:
                    if self._handle_message(kind, payload):
                        return
                except Exception:
                    ipcore.log.exception("Could not handle the message %r", kind)
        finally:
            if not self.closing:
                self.root.after(100, self._process_queue)

    def _handle_message(self, kind, payload):
        """Handles one message from another thread. Returns True when the program is quitting."""
        if kind == "data":
            self.state, self.checking = payload, False
            self._render()
        elif kind == "weather":
            # a failed lookup keeps the old temperature; "no city" clears it
            if payload is not None or not self._weather_wanted():
                self.weather = payload
            self._refresh_info()
            self._apply_tray_title()
        elif kind == "city":
            self.choose_city()
        elif kind == "cities":
            self._city_results(*payload)
        elif kind == "toggle":
            self.toggle_window()
        elif kind == "copy":
            self.copy_ip()
        elif kind == "refresh":
            self.monitor.refresh()
        elif kind == "pin":
            self._on_tray_pin()
        elif kind == "startup":
            self._on_tray_startup()
        elif kind == "quit":
            self.quit()
            return True
        return False

    def _report_error(self, exc, val, tb):
        import traceback
        ipcore.log.error("Interface error:\n%s", "".join(traceback.format_exception(exc, val, tb)))

    def quit(self):
        if self.closing:
            return
        self.closing = True
        self.usage.save(force=True)
        self.monitor.stop()
        self.weather_monitor.stop()
        if self.tray:
            try:
                self.tray.stop()
            except Exception:
                pass
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def selftest():
    """Used by the build: checks that the packaged program has everything it needs.

    Writes what it finds to the file named in MYIP_SELFTEST_REPORT (the build prints it).
    """
    problems, notes = [], []
    for path in (ICON_FILE, os.path.join(FLAG_DIR, "US.png")):
        if not os.path.isfile(path):
            problems.append(f"missing file: {path}")
    for path in (GLOBE_ICON, OFFLINE_ICON):
        if not os.path.isfile(path):
            notes.append(f"note: {os.path.basename(path)} not found, the built-in drawing is used")
    for module in ("requests", "PIL.ImageTk", "pystray", "psutil"):
        try:
            __import__(module)
        except Exception as exc:
            problems.append(f"cannot import {module}: {exc!r}")
    try:
        load_flag_image("US")
        load_icon(GLOBE_ICON)
        load_icon(OFFLINE_ICON)
    except Exception as exc:
        problems.append(f"cannot load icons: {exc!r}")
    try:
        import psutil
        psutil.net_io_counters(pernic=True)
    except Exception as exc:
        problems.append(f"cannot read network counters: {exc!r}")

    report = os.environ.get("MYIP_SELFTEST_REPORT")
    if report:
        try:
            with open(report, "w", encoding="utf-8") as fh:
                fh.write("\n".join(problems + notes + ["RESULT: " + ("FAILED" if problems else "OK")]))
        except OSError:
            pass
    return 1 if problems else 0


def enable_dpi_awareness():
    if not ipcore.IS_WINDOWS:
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    ipcore.setup_logging()
    if not ipcore.acquire_single_instance():
        return  # already running; its tray icon is the way to reach it
    enable_dpi_awareness()
    try:
        App().run()
    except Exception:
        ipcore.log.exception("The widget crashed")
        raise
    os._exit(0)  # make sure no background thread keeps the process alive


if __name__ == "__main__":
    main()
