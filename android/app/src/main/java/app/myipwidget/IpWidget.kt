package app.myipwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.concurrent.atomic.AtomicInteger

class IpWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        NetWatch.start(context)
    }

    override fun onDisabled(context: Context) {
        NetWatch.stop(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        NetWatch.start(context)
        val pending = goAsync()
        refresh(context) { pending.finish() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // the button on the widget, or the delayed second look after a network change
            ACTION_REFRESH, NetWatch.ACTION_LATER -> {
                val pending = goAsync()
                refresh(context) { pending.finish() }
            }
            // the system says the connection changed
            NetWatch.ACTION_NET -> {
                if (!hasWidgets(context)) {
                    NetWatch.stop(context)
                    return
                }
                if (NetWatch.networkChanged(context)) {
                    val pending = goAsync()
                    refresh(context) { pending.finish() }
                    NetWatch.scheduleLater(context, 8000)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
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

        private val latest = AtomicInteger(0)

        private fun hasWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getAppWidgetIds(ComponentName(context, IpWidget::class.java)).isNotEmpty()
        }

        /**
         * Shows the last known values with a "busy" button at once, then looks up the IP on a
         * background thread and updates every widget on the home screen.
         */
        fun refresh(context: Context, done: () -> Unit = {}) {
            val app = context.applicationContext
            val mine = latest.incrementAndGet()

            val cached = IpLookup.load(app)
            render(app, if (cached != null) IpLookup.describe(app, cached) else IpLookup.checking(app), busy = true)

            Thread {
                try {
                    val info = if (NetWatch.isOnline(app)) IpLookup.fetch() else null
                    if (info != null) IpLookup.save(app, info)
                    // a newer refresh may have started meanwhile; only the newest one may draw
                    if (mine == latest.get()) {
                        render(app, if (info != null) IpLookup.describe(app, info) else IpLookup.offline(app))
                    }
                } finally {
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
