package app.myipwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class IpWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Show the last known values at once, then look for fresh ones.
        val cached = IpLookup.load(context)
        render(context, if (cached != null) IpLookup.describe(context, cached) else IpLookup.checking(context))
        val pending = goAsync()
        refresh(context) { pending.finish() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            refresh(context) { pending.finish() }
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "app.myipwidget.REFRESH"

        /** Looks up the IP on a background thread and updates every widget on the home screen. */
        fun refresh(context: Context, done: () -> Unit = {}) {
            val app = context.applicationContext
            Thread {
                try {
                    val info = IpLookup.fetch()
                    if (info != null) IpLookup.save(app, info)
                    render(app, if (info != null) IpLookup.describe(app, info) else IpLookup.offline(app))
                } finally {
                    done()
                }
            }.start()
        }

        fun render(context: Context, display: Display) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, IpWidget::class.java))
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.flag, display.flag)
            views.setTextViewText(R.id.country, display.title)
            views.setTextViewText(R.id.ip, display.ip)
            views.setTextViewText(R.id.detail, display.detail)

            val intent = Intent(context, IpWidget::class.java).setAction(ACTION_REFRESH)
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.root, pending)

            manager.updateAppWidget(ids, views)
        }
    }
}
