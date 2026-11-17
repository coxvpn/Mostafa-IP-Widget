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

## نسخه ۰٫۵٫۰
- سرعت لحظه‌ای در نوار اعلان بالای گوشی (عدد سرعت دانلود روی آیکون، جزئیات و دما و حجم در متن اعلان).
- کشور (دو حرف، مثلاً IR) در نوار بالا و پرچم رنگی در متن اعلان.
- با فیلترشکن روشن، سرعت تقریبی است.

## نسخه ۰٫۶٫۰
- تاریخ امروز (شمسی) روی ویجت و در برنامه، با گزینه‌ی روشن و خاموش.
- دکمه‌ی «درباره‌ی من و کد برنامه» با پیوند به صفحه‌ی گیت‌هاب.

## نسخه ۱٫۲٫۰
- دکمه‌ی افزودن ویجت به صفحه‌ی اصلی، منوهای مرتب‌تر در چند بخش، آیکون تازه.
- گزینه‌ی «فشرده» برای اعلان نوار بالا و دکمه‌ی تنظیمات اعلان.
- حجم مصرف امروز، وای‌فای و سیم‌کارت جدا (دانلود و آپلود با هم جمع).

## نسخه ۱٫۳٫۰
- ویجت واکنش‌گرا: نوشته‌ها با اندازه‌ی ویجت بزرگ و کوچک می‌شوند.
- تاریخ میلادی زیر تاریخ شمسی، نرخ دلار، حجم وای‌فای و سیم‌کارت در ردیف پایین.
- ثابت شدن جای آیکون سرعت در نوار بالا، عدد بزرگ‌تر.
- جلوگیری از انباشته شدن اتصال‌ها (CLOSE_WAIT) هنگام ضعیف بودن اینترنت.

## نسخه ۱٫۳٫۲
- خواندن نرخ دلار از خط «دلار: ... تومان» پست کانال.
- هر تغییر شبکه یا فیلترشکن، بررسی آی‌پی تازه‌ای شروع می‌کند و بررسی قبلی را کنار می‌گذارد.
- وقتی اعلان سرعت روشن است، خود برنامه هم تغییر شبکه را مستقیم می‌شنود.
