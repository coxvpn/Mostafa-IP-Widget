# Mostafa-IP-Widget - Android

Home-screen widget (and small app) that shows your public IP address, country flag,
city and provider. Android 8.0 or newer.

- Button (the circular arrow) on the widget refreshes it at once; tapping the rest opens the app.
- The flag changes within a second or two when the connection changes (Wi-Fi / mobile data /
  VPN on, off or another server): Android wakes the widget for it, so nothing runs in the
  background otherwise and no notification is needed.
- Also refreshes by itself about every 30 minutes (Android's minimum for widgets).

Build: run the "Build Android app" workflow on GitHub (Actions tab), then download the
`Mostafa-IP-Widget-Android` artifact. The app is signed with the key in `app/debug.keystore`, so every
new version installs over the previous one (the first time after a key change, uninstall the old one).

## نسخه ۰٫۴٫۰
- دمای هوا کنار پرچم (شهر را خودتان در برنامه انتخاب می‌کنید).
- حجم دانلود و آپلود امروز کنار پرچم (یک بار باید در تنظیمات گوشی اجازه‌ی دسترسی به آمار مصرف را بدهید).
- هر کدام را در برنامه می‌توانید روشن یا خاموش کنید.
