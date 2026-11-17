package app.myipwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.text.format.Formatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps up to two notifications alive:
 *  - the speed one: the status-bar icon shows the download speed; the text shows both speeds,
 *    the weather and today's volume;
 *  - the flag one: the status-bar icon shows the country code (status-bar icons can only be
 *    one-colour, so a coloured flag is not possible there); the text has the flag, country and IP.
 */
class SpeedService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val busy = AtomicBoolean(false)
    private var running = false
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastTime = 0L
    private var down = 0.0
    private var up = 0.0
    private var ticks = 0
    private var flagShown: String? = null
    private var asForeground = false
    private val startTime = System.currentTimeMillis()   // fixed: the system orders notifications by it

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        private fun changed() {
            val app = applicationContext
            if (NetWatch.networkChanged(app)) {
                IpWidget.refresh(app, force = true)
                NetWatch.scheduleLater(app, 8000)
            }
        }

        override fun onAvailable(network: android.net.Network) = changed()
        override fun onLost(network: android.net.Network) = changed()
        override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) = changed()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                measure()
                if (ticks % 20 == 0) refreshExtras()
                ticks++
                render()
            } catch (e: Exception) {
                // never let one bad second stop the service
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notice_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        thread = HandlerThread("speed").also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.anyNotice(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        NetWatch.start(applicationContext)
        if (!running) {
            running = true
            try {
                val request = android.net.NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, netCallback)
            } catch (e: Exception) {
                // the system wake-up through NetWatch still works
            }
            lastRx = -1L
            handler.post { IpWidget.refresh(applicationContext) }   // make sure the IP / flag are current
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
        } catch (e: Exception) {
            // it was not registered
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        getSystemService(NotificationManager::class.java).cancel(ID_FLAG)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ foreground
    private fun enterForeground() {
        val notification = if (Prefs.showSpeedNotice(this)) buildSpeed() else buildFlag()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_MAIN, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_MAIN, notification)
        }
        asForeground = true
    }

    // ------------------------------------------------------------------ measuring
    private fun vpnActive(): Boolean {
        return try {
            val manager = getSystemService(ConnectivityManager::class.java)
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) {
            false
        }
    }

    private fun measure() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = System.nanoTime()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            down = 0.0
            up = 0.0
            return
        }
        if (lastRx >= 0 && now > lastTime) {
            val seconds = (now - lastTime) / 1e9
            // with a VPN every byte passes the phone twice (the real link and the tunnel)
            val factor = if (vpnActive()) 0.5 else 1.0
            down = maxOf(0L, rx - lastRx) / seconds * factor
            up = maxOf(0L, tx - lastTx) / seconds * factor
        }
        lastRx = rx
        lastTx = tx
        lastTime = now
    }

    /** Weather and today's volume change slowly: look them up now and then, off the main loop. */
    private fun refreshExtras() {
        if (!busy.compareAndSet(false, true)) return
        val app = applicationContext
        Thread {
            try {
                Weather.refreshIfStale(app, Weather.FRESH_MS)
                Rate.refreshIfStale(app, Weather.FRESH_MS)
                Usage.update(app)
            } catch (e: Exception) {
                // optional extras
            } finally {
                busy.set(false)
            }
        }.start()
    }

    // ------------------------------------------------------------------ showing
    private fun render() {
        if (!Prefs.anyNotice(this)) {
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val speedOn = Prefs.showSpeedNotice(this)
        val flagOn = Prefs.showFlagNotice(this)
        manager.notify(ID_MAIN, if (speedOn) buildSpeed() else buildFlag())
        if (speedOn && flagOn) {
            val key = describeCached().let { it.flag + it.title + it.ip + it.detail } + codeCached()
            if (key != flagShown) {
                flagShown = key
                manager.notify(ID_FLAG, buildFlag())
            }
        } else {
            flagShown = null
            manager.cancel(ID_FLAG)
        }
    }

    private fun describeCached(): Display {
        val cached = IpLookup.load(this)
        return if (cached != null) IpLookup.describe(this, cached) else IpLookup.checking(this)
    }

    private fun codeCached(): String = IpLookup.load(this)?.code ?: ""

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 7, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun extrasLine(): String {
        val parts = ArrayList<String>()
        if (Prefs.noticeTemp(this) && Prefs.hasCity(this)) {
            val weather = Weather.cached(this)
            if (weather != null) {
                parts.add(Weather.format(weather) + "  " + Weather.range(weather) + "  " + Prefs.cityName(this))
            }
        }
        if (Prefs.noticeRate(this)) {
            val rate = Rate.cached(this)
            if (rate != null) parts.add(Rate.format(rate))
        }
        if (Prefs.noticePrayer(this)) {
            val azan = Prayer.next(this)
            if (azan != null) {
                parts.add(
                    getString(
                        R.string.notice_azan, getString(azan.nameRes), Prayer.clockText(azan.time),
                        Prayer.remainingText(azan.time - System.currentTimeMillis()),
                    ),
                )
            }
        }
        if (Prefs.noticeUsage(this)) {
            val used = Usage.cached(this)
            if (used != null) {
                parts.add(getString(R.string.today_short) + ": " + Usage.oneLine(this, used))
            }
        }
        return parts.joinToString("\n")
    }

    private fun buildSpeed(): Notification {
        val title = "↓ " + Formatter.formatShortFileSize(this, down.toLong()) + "/s    ↑ " +
            Formatter.formatShortFileSize(this, up.toLong()) + "/s"
        val extras = if (Prefs.compactNotice(this)) "" else extrasLine()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(speedIcon(down + up))
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(startTime)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open())
        if (extras.isNotEmpty()) {
            builder.setContentText(extras.replace("\n", "   "))
            builder.setStyle(Notification.BigTextStyle().bigText(extras))
        }
        return builder.build()
    }

    private fun buildFlag(): Notification {
        val display = describeCached()
        val code = codeCached()
        val text = if (Prefs.compactNotice(this)) display.ip
        else listOf(display.ip, display.detail).filter { it.isNotEmpty() }.joinToString("\n")
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(textIcon(if (code.isEmpty()) "?" else code))
            .setContentTitle(display.flag + " " + display.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(startTime)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open())
        if (text.isNotEmpty()) {
            builder.setContentText(text.replace("\n", "  -  "))
            builder.setStyle(Notification.BigTextStyle().bigText(text))
        }
        return builder.build()
    }

    // ------------------------------------------------------------------ icons
    /**
     * Download plus upload as one big number with a small unit under it (K or M). Below
     * [MIN_SPEED] it shows 0, so the icon does not flicker with tiny background traffic.
     */
    private fun speedIcon(bytesPerSecond: Double): Icon {
        val kb = if (bytesPerSecond < MIN_SPEED) 0.0 else bytesPerSecond / 1024.0
        val number: String
        val unit: String
        if (kb < 1000) {
            number = if (kb in 0.1..9.99) "%.1f".format(java.util.Locale.US, kb) else kb.toInt().toString()
            unit = "K"
        } else {
            val mb = kb / 1024.0
            number = if (mb < 10) "%.1f".format(java.util.Locale.US, mb) else mb.toInt().toString()
            unit = "M"
        }
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 78f
        }
        while (paint.measureText(number) > size - 2 && paint.textSize > 24f) paint.textSize -= 2f
        canvas.drawText(number, size / 2f, 64f, paint)
        paint.textSize = 32f
        canvas.drawText(unit, size / 2f, 94f, paint)
        return Icon.createWithBitmap(bitmap)
    }

    /** White text on a transparent square: the system tints it to match the status bar. */
    private fun textIcon(text: String): Icon {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 72f
        }
        while (paint.measureText(text) > size - 4 && paint.textSize > 20f) paint.textSize -= 2f
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)
        return Icon.createWithBitmap(bitmap)
    }

    companion object {
        const val CHANNEL = "speed"
        private const val MIN_SPEED = 20 * 1024.0   // bytes per second: below this the icon shows 0
        private const val ID_MAIN = 41
        private const val ID_FLAG = 42

        /** Starts or stops the service to match the choices made in the app. */
        fun sync(context: Context) {
            val app = context.applicationContext
            try {
                val intent = Intent(app, SpeedService::class.java)
                if (Prefs.anyNotice(app)) {
                    app.startForegroundService(intent)
                } else {
                    app.stopService(intent)
                }
            } catch (e: Exception) {
                // the system may refuse a start from the background; the next app visit retries
            }
        }
    }
}
