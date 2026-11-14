# My IP Info Widget

See your public IP address, country, city and internet provider in real time —
on the desktop and in the system tray (Windows).

![](screenshots/MyIPWidget-dark.jpg)
![](screenshots/MyIPWidget-light.jpg)

## Features (v0.2.0)

- Country flag, IP address, and (optionally) city and provider
- Tray icon with the current flag; left-click to show/hide
- Right-click the widget for a menu: copy IP, refresh, opacity, start with Windows, hide, quit
- Double-click the widget to copy the IP address
- Remembers its position and settings
- Follows the Windows dark / light theme
- Uses encrypted (https) lookups with automatic fallbacks, and only asks for
  location details when your IP actually changes
- Shows "No Internet" only after two failed checks, so short hiccups don't flicker
- Only one copy can run at a time

## Install

Download `MyIPWidget-Setup.exe` from the Releases page (or from the *Actions* tab →
latest run → *Artifacts*) and run it. No administrator rights needed.

Settings and a small log file live in `%APPDATA%\MyIPWidget`.

## Run from source

```
pip install -r requirements.txt
python main.py
```

## Build the installer

The workflow in `.github/workflows/build.yml` builds `MyIPWidget-Setup.exe` on GitHub
(PyInstaller + Inno Setup). Run it from the *Actions* tab with *Run workflow*, or push a
tag such as `v0.2.0` to publish a release automatically.

## Tests

```
python -m unittest discover -s . -p "test_*.py" -v
```
