package app.myipwidget

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.text.format.Formatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Data downloaded / uploaded today over Wi-Fi and mobile data (needs "usage access", allowed once in Settings). */
object Usage {

    @Suppress("DEPRECATION")
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** (downloaded, uploaded) bytes since midnight, or null if it cannot be read. */
    private fun query(context: Context): Pair<Long, Long>? {
        if (!hasAccess(context)) return null
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()

        var down = 0L
        var up = 0L
        var any = false
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val bucket = manager.querySummaryForDevice(type, null, midnight, now)
                down += bucket.rxBytes
                up += bucket.txBytes
                any = true
            } catch (e: Exception) {
                // this kind of network could not be read; count the other one
            }
        }
        return if (any) Pair(down, up) else null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("usage", Context.MODE_PRIVATE)

    /** Reads the system's numbers and keeps them for the widget. Call from a background thread. */
    fun update(context: Context) {
        val result = query(context)
        val edit = prefs(context).edit()
        if (result == null) {
            edit.clear()
        } else {
            edit.putString("date", today()).putLong("down", result.first).putLong("up", result.second)
        }
        edit.apply()
    }

    fun cached(context: Context): Pair<Long, Long>? {
        val p = prefs(context)
        if (p.getString("date", null) != today()) return null
        return Pair(p.getLong("down", 0L), p.getLong("up", 0L))
    }

    fun format(context: Context, value: Pair<Long, Long>?): String {
        if (value == null) return "↓ —\n↑ —"
        return "↓ " + Formatter.formatShortFileSize(context, value.first) +
            "\n↑ " + Formatter.formatShortFileSize(context, value.second)
    }
}
