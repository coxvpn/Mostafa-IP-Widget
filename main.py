"""MyIP Widget - shows your public IP, country and provider on the desktop and in the tray."""
import ctypes
import os
import queue
import re
import sys
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
PIRATE_FLAG = os.path.join(IMG_DIR, "pirate_flag.png")
ICON_FILE = os.path.join(IMG_DIR, "icon.ico")

FLAG_SIZE = 56
TEXT_WIDTH = 24  # characters; keeps the window a stable size
OPACITY_CHOICES = (100, 85, 70, 50, 30)
HINT = "Right-click: menu\nDouble-click: copy IP"


def load_flag_image(code):
    """Return a PIL image of the country flag (the pirate flag if unknown)."""
    if re.fullmatch(r"[A-Z]{2}", code or ""):
        path = os.path.join(FLAG_DIR, f"{code}.png")
        if os.path.isfile(path):
            try:
                return Image.open(path).convert("RGBA")
            except Exception:
                pass
    return Image.open(PIRATE_FLAG).convert("RGBA")


def virtual_screen(root):
    """(left, top, width, height) of the area made of all monitors."""
    if ipcore.IS_WINDOWS:
        try:
            gm = ctypes.windll.user32.GetSystemMetrics
            return gm(76), gm(77), gm(78), gm(79)
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
        self._drag = (0, 0)
        self.tray = None

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
        self._place_window()

        self.root.attributes("-topmost", True)
        self.root.attributes("-alpha", self.settings.get("opacity"))
        if self.settings.get("visible"):
            self.root.deiconify()

        self._render()
        self._start_tray()

        self.monitor = IPMonitor(
            on_update=lambda state: self.post("data", state),
            interval_fn=lambda: self.settings.get("interval"),
        )
        self.monitor.start()
        self.root.after(200, self._process_queue)

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

        label_args = dict(bg=self.bg, bd=0, highlightthickness=0, width=TEXT_WIDTH)
        self.flag_lbl = tk.Label(self.frame, bg=self.bg, bd=0)
        self.country_lbl = tk.Label(self.frame, fg=self.fg, font=("Segoe UI", 11, "bold"), **label_args)
        self.ip_lbl = tk.Label(self.frame, fg=self.fg, font=("Segoe UI", 10), **label_args)
        self.detail_lbl = tk.Label(self.frame, fg=self.dim, font=("Segoe UI", 8),
                                   wraplength=int(190 * scale), justify="center", **label_args)

        self.flag_lbl.grid(row=0, column=0)
        self.country_lbl.grid(row=1, column=0)
        self.ip_lbl.grid(row=2, column=0)
        self.detail_lbl.grid(row=3, column=0)

        # Not bound on the root window itself: its events would fire twice.
        for widget in (self.frame, self.flag_lbl, self.country_lbl,
                       self.ip_lbl, self.detail_lbl):
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

    def _build_menu(self):
        self.details_var = tk.BooleanVar(value=self.settings.get("show_details"))
        self.startup_var = tk.BooleanVar(value=ipcore.is_startup_enabled())
        self.opacity_var = tk.IntVar(value=self._nearest_opacity())

        self.menu = tk.Menu(self.root, tearoff=0)
        self.menu.add_command(label="Copy IP address", command=self.copy_ip)
        self.menu.add_command(label="Refresh now", command=lambda: self.monitor.refresh())
        self.menu.add_separator()
        self.menu.add_checkbutton(label="Show city and provider", variable=self.details_var,
                                  command=self._toggle_details)
        opacity_menu = tk.Menu(self.menu, tearoff=0)
        for pct in OPACITY_CHOICES:
            opacity_menu.add_radiobutton(label=f"{pct}%", value=pct, variable=self.opacity_var,
                                         command=self._apply_opacity)
        self.menu.add_cascade(label="Opacity", menu=opacity_menu)
        if ipcore.IS_WINDOWS:
            self.menu.add_checkbutton(label="Start with Windows", variable=self.startup_var,
                                      command=self._toggle_startup)
        self.menu.add_separator()
        self.menu.add_command(label="Hide to tray", command=self.hide_window)
        self.menu.add_command(label="Quit", command=self.quit)

    def _nearest_opacity(self):
        current = self.settings.get("opacity") * 100
        return min(OPACITY_CHOICES, key=lambda pct: abs(pct - current))

    def _show_menu(self, event):
        try:
            self.menu.tk_popup(event.x_root, event.y_root)
        finally:
            self.menu.grab_release()

    # -------------------------------------------------------------- window
    def _place_window(self):
        self.root.update_idletasks()
        width, height = self.root.winfo_reqwidth(), self.root.winfo_reqheight()
        left, top, vw, vh = virtual_screen(self.root)
        x, y = self.settings.get("x"), self.settings.get("y")
        if x is None or y is None:
            x, y = left + vw - width - 24, top + 60
        x = max(left, min(x, left + vw - width))
        y = max(top, min(y, top + vh - height))
        self.root.geometry(f"+{x}+{y}")

    def _drag_start(self, event):
        self._drag = (event.x_root - self.root.winfo_x(), event.y_root - self.root.winfo_y())

    def _drag_move(self, event):
        dx, dy = self._drag
        self.root.geometry(f"+{event.x_root - dx}+{event.y_root - dy}")

    def _drag_end(self, event):
        self.settings.set("x", self.root.winfo_x(), save=False)
        self.settings.set("y", self.root.winfo_y())

    def show_window(self):
        self.root.deiconify()
        self.root.attributes("-topmost", True)
        self.settings.set("visible", True)

    def hide_window(self):
        self.root.withdraw()
        self.settings.set("visible", False)

    def toggle_window(self):
        if self.root.state() == "withdrawn":
            self.show_window()
        else:
            self.hide_window()

    # ------------------------------------------------------------ settings
    def _toggle_details(self):
        self.settings.set("show_details", bool(self.details_var.get()))
        self._render()

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

    # ------------------------------------------------------------- content
    def _render(self):
        state = self.state
        if self.checking:
            country, ip, detail, code = "Checking...", "", "", ""
        elif not state["online"]:
            country, ip, detail, code = "No Internet", "", "", ""
        else:
            country = state["country"] or "Unknown location"
            ip = state["ip"]
            detail = " - ".join(p for p in (state["city"], state["isp"]) if p)
            code = state["code"]

        image = load_flag_image(code).resize((self.flag_px, self.flag_px), Image.LANCZOS)
        self._flag_photo = ImageTk.PhotoImage(image)  # keep a reference
        self.flag_lbl.config(image=self._flag_photo)
        self.country_lbl.config(text=country)
        self.ip_lbl.config(text=ip)
        self.detail_lbl.config(text=detail)
        if detail and self.settings.get("show_details"):
            self.detail_lbl.grid()
        else:
            self.detail_lbl.grid_remove()

        self._update_tray(code, ip, country)

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
            MenuItem("Start with Windows", lambda icon, item: self.post("startup"),
                     checked=lambda item: ipcore.is_startup_enabled(),
                     visible=ipcore.IS_WINDOWS),
            pystray.Menu.SEPARATOR,
            MenuItem(f"{APP_NAME} {__version__}", lambda icon, item: None, enabled=False),
            MenuItem("Quit", lambda icon, item: self.post("quit")),
        )
        try:
            self.tray = pystray.Icon("MyIPWidget", load_flag_image(""), APP_NAME, menu)
            self.tray.run_detached()
        except Exception:
            ipcore.log.exception("Could not start the tray icon")
            self.tray = None

    def _update_tray(self, code, ip, country):
        if not self.tray:
            return
        try:
            self.tray.icon = load_flag_image(code)
            self.tray.title = f"{ip}  ({country})" if ip else country
        except Exception:
            ipcore.log.exception("Could not update the tray icon")

    # -------------------------------------------------------------- events
    def post(self, kind, payload=None):
        """Thread-safe way for other threads to ask the window to do something."""
        self.ui_queue.put((kind, payload))

    def _process_queue(self):
        try:
            while True:
                kind, payload = self.ui_queue.get_nowait()
                if kind == "data":
                    self.state, self.checking = payload, False
                    self._render()
                elif kind == "toggle":
                    self.toggle_window()
                elif kind == "copy":
                    self.copy_ip()
                elif kind == "refresh":
                    self.monitor.refresh()
                elif kind == "startup":
                    self._on_tray_startup()
                elif kind == "quit":
                    self.quit()
                    return
        except queue.Empty:
            pass
        if not self.closing:
            self.root.after(200, self._process_queue)

    def _report_error(self, exc, val, tb):
        import traceback
        ipcore.log.error("Interface error:\n%s", "".join(traceback.format_exception(exc, val, tb)))

    def quit(self):
        if self.closing:
            return
        self.closing = True
        self.monitor.stop()
        if self.tray:
            try:
                self.tray.stop()
            except Exception:
                pass
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def selftest():
    """Used by the build: checks that every needed file and module is packaged."""
    needed = [ICON_FILE, PIRATE_FLAG, os.path.join(FLAG_DIR, "US.png")]
    missing = [path for path in needed if not os.path.isfile(path)]
    load_flag_image("US")
    return 1 if missing else 0


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
