package app.myipwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class IpWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        NetWatch.start(context)
    }

    override fun onDisabled(context: Context) {
        if (!Prefs.anyNotice(context)) NetWatch.stop(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        NetWatch.start(context)
        val pending = goAsync()
        refresh(context) { pending.finish() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // the button on the widget: also look at the weather again if it is a few minutes old
            ACTION_REFRESH -> {
                val pending = goAsync()
                refresh(context, BUTTON_WEATHER_AGE_MS, force = true) { pending.finish() }
            }
            // the delayed second look after a network change
            NetWatch.ACTION_LATER -> {
                val pending = goAsync()
                refresh(context, force = true) { pending.finish() }
            }
            // the system says the connection changed
            NetWatch.ACTION_NET -> {
                if (!hasWidgets(context) && !Prefs.anyNotice(context)) {
                    NetWatch.stop(context)
                    return
                }
                if (NetWatch.networkChanged(context)) {
                    val pending = goAsync()
                    refresh(context, force = true) { pending.finish() }
                    NetWatch.scheduleLater(context, 8000)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                SpeedService.sync(context)
                if (hasWidgets(context)) {
                    NetWatch.start(context)
                    val pending = goAsync()
                    refresh(context) { pending.finish() }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "app.myipwidget.REFRESH"
        private const val BUTTON_WEATHER_AGE_MS = 2 * 60 * 1000L

        private val latest = AtomicInteger(0)
        private val fetchingSince = AtomicLong(0L)

        private fun hasWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getAppWidgetIds(ComponentName(context, IpWidget::class.java)).isNotEmpty()
        }

        private fun describeCached(context: Context): Display {
            val cached = IpLookup.load(context)
            return if (cached != null) IpLookup.describe(context, cached) else IpLookup.checking(context)
        }

        /** Draws the widget again from what is saved (after a setting changed). */
        fun redrawFromCache(context: Context) {
            render(context.applicationContext, describeCached(context.applicationContext))
        }

        /**
         * Shows the last known values with a "busy" button at once, then looks up the IP on a
         * background thread and updates every widget on the home screen. The flag is drawn as soon
         * as the IP is known; the weather and today's volume follow.
         */
        fun refresh(
            context: Context,
            weatherMaxAgeMs: Long = Weather.FRESH_MS,
            force: Boolean = false,
            done: () -> Unit = {},
        ) {
            val app = context.applicationContext
            render(app, describeCached(app), busy = true)

            // One lookup at a time: when the net is bad, piling up more would only hold more sockets open.
            val now = System.currentTimeMillis()
            val since = fetchingSince.get()
            // A real change (network, VPN, the button) always starts a new lookup: the one in flight may
            // be asking through the old connection, and its answer is thrown away.
            if (!force && since != 0L && now - since < 20_000L) {
                done()
                return
            }
            fetchingSince.set(now)
            val mine = latest.incrementAndGet()

            Thread {
                try {
                    val info = if (NetWatch.isOnline(app)) IpLookup.fetch() else null
                    // a newer refresh may have started meanwhile; only the newest one may save and draw
                    if (info != null && mine == latest.get()) IpLookup.save(app, info)
                    if (mine == latest.get()) {
                        render(app, if (info != null) IpLookup.describe(app, info) else IpLookup.offline(app), busy = true)
                    }
                    try {
                        if (info != null) {
                            Weather.refreshIfStale(app, weatherMaxAgeMs)
                            Rate.refreshIfStale(app, weatherMaxAgeMs)
                        }
                        Usage.update(app)
                    } catch (e: Exception) {
                        // the extras are optional
                    }
                    if (mine == latest.get()) {
                        render(app, if (info != null) IpLookup.describe(app, info) else IpLookup.offline(app))
                    }
                } finally {
                    if (mine == latest.get()) fetchingSince.set(0L)
                    done()
                }
            }.start()
        }

        fun render(context: Context, display: Display, busy: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, IpWidget::class.java))
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.flag, display.flag)
            views.setTextViewText(R.id.country, display.title)
            views.setTextViewText(R.id.ip, display.ip)
            views.setTextViewText(R.id.detail, display.detail)
            views.setTextViewText(R.id.refresh, if (busy) "…" else "↻")

            // what to show, as chosen in the app
            val showWeather = Prefs.showWeather(context) && Prefs.hasCity(context)
            val showUsage = Prefs.showUsage(context)
            val showDate = Prefs.showDate(context)
            val showRate = Prefs.showRate(context)
            val weather = Weather.cached(context)
            val today = Usage.cached(context)
            views.setViewVisibility(R.id.weather, if (showWeather) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.weather, if (weather != null) Weather.format(weather) else "\u2026")
            views.setViewVisibility(R.id.date, if (showDate) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.date2, if (showDate) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.date, PersianDate.short())
            views.setTextViewText(R.id.date2, PersianDate.gregorian())
            views.setViewVisibility(R.id.rate, if (showRate) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.rate, Rate.format(Rate.cached(context)))
            views.setViewVisibility(R.id.wifi, if (showUsage) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.sim, if (showUsage) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.wifi, Usage.wifiLine(context, today))
            views.setTextViewText(R.id.sim, Usage.simLine(context, today))
            views.setViewVisibility(R.id.extra, if (showRate || showUsage) View.VISIBLE else View.GONE)
            // keep the space, so the button stays at the right edge
            views.setViewVisibility(R.id.info, if (showWeather || showDate) View.VISIBLE else View.INVISIBLE)

            val refreshIntent = Intent(context, IpWidget::class.java).setAction(ACTION_REFRESH)
            val refreshPending = PendingIntent.getBroadcast(
                context, 0, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.refresh, refreshPending)

            // tapping the rest of the widget opens the app
            val openPending = PendingIntent.getActivity(
                context, 3, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.root, openPending)

            manager.updateAppWidget(ids, views)
        }
    }
}
