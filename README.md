# Mostafa-IP Info Widget

Created by Mostafa Mandegari (مصطفی ماندگاری).

See your public IP address, country, city and internet provider in real time —
on the desktop and in the system tray (Windows).

Mostafa-IP Widget

نسخه جدید برنامه Mostafa-IP Widget برای سیستم‌عامل‌های ویندوز و اندروید منتشر شد.

این برنامه برای نمایش و بررسی اطلاعات IP طراحی شده است.

📥 فایل‌های دانلود

نسخه ویندوز: فایل نصب برنامه برای Windows

نسخه اندروید: فایل APK برای نصب روی گوشی‌های Android

⚙️ راهنمای نصب

ویندوز: فایل نصب را دانلود و اجرا کنید.

اندروید: فایل APK را دانلود و نصب کنید. در صورت نیاز، اجازه نصب از منابع ناشناس را فعال کنید.

از تنظیمات ویجت را به صفحه اصلی اضافه کنین

🔄 بروزرسانی

برای دریافت آخرین نسخه برنامه، همین صفحه را دنبال کنید.

توجه: فایل مناسب سیستم‌عامل خود را دانلود کنید.


<img width="591" height="1280" alt="1" src="https://github.com/user-attachments/assets/9e0a3683-810f-4308-b577-d415711659c8" />

<img width="591" height="1280" alt="1" src="https://github.com/user-attachments/assets/e110e7d7-6aff-4c63-8d88-8ef82e39e355" />



## Features (v0.2.2)

- Country flag, IP address, and (optionally) city and provider
- Lives in the tray: nothing sits on the desktop. Click the tray icon and a small pop-up
  appears next to the taskbar; it closes when you click elsewhere
- Live download / upload speed in the pop-up and in the tray tooltip
  (with a VPN the traffic is counted once, not twice)
- A globe with a red slash shows when there is no internet
- Right-click the pop-up for a menu: copy IP, refresh, opacity, keep on desktop, start with Windows, quit
- "Keep on desktop" brings back the always-visible, draggable widget
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

## About antivirus warnings

Programs packaged from Python are sometimes flagged by mistake. The build therefore avoids
packers (no UPX), embeds proper version information, and builds PyInstaller's launcher from
source. This reduces false alarms but cannot guarantee zero; a code-signing certificate is
the only complete fix.
