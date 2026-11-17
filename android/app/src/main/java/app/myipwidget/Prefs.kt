package app.myipwidget

import android.content.Context

/** The choices made in the app: what to show on the widget, and the city for the weather. */
object Prefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun showWeather(context: Context) = prefs(context).getBoolean("show_weather", true)

    fun setShowWeather(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_weather", value).apply()
    }

    fun showUsage(context: Context) = prefs(context).getBoolean("show_usage", true)

    fun setShowUsage(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_usage", value).apply()
    }

    fun showRate(context: Context) = prefs(context).getBoolean("show_rate", true)

    fun setShowRate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_rate", value).apply()
    }

    fun showDate(context: Context) = prefs(context).getBoolean("show_date", true)

    fun setShowDate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_date", value).apply()
    }

    /** Live speed (and weather / volume) in a notification, with the icon in the status bar. */
    fun showSpeedNotice(context: Context) = prefs(context).getBoolean("show_speed_notice", false)

    fun setShowSpeedNotice(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_speed_notice", value).apply()
    }

    /** A second notification whose status-bar icon is the country code. */
    fun showFlagNotice(context: Context) = prefs(context).getBoolean("show_flag_notice", false)

    fun setShowFlagNotice(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_flag_notice", value).apply()
    }

    /** One short line only: no weather / volume text under the speed. */
    fun compactNotice(context: Context) = prefs(context).getBoolean("compact_notice", false)

    fun setCompactNotice(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("compact_notice", value).apply()
    }


    /** Background style of the widget: 0 follows the phone (light / dark), then dark, light, glass, blue, green. */
    fun theme(context: Context) = prefs(context).getInt("theme", 0).coerceIn(0, 5)

    fun setTheme(context: Context, value: Int) {
        prefs(context).edit().putInt("theme", value).apply()
    }

    // ---- widget: more things to show
    fun showAqi(context: Context) = prefs(context).getBoolean("show_aqi", true)

    fun setShowAqi(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_aqi", value).apply()
    }

    fun showPrayer(context: Context) = prefs(context).getBoolean("show_prayer", true)

    fun setShowPrayer(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_prayer", value).apply()
    }

    // ---- notification: what the text under the speed holds
    fun noticeTemp(context: Context) = prefs(context).getBoolean("notice_temp", true)

    fun setNoticeTemp(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("notice_temp", value).apply()
    }

    fun noticeRate(context: Context) = prefs(context).getBoolean("notice_rate", true)

    fun setNoticeRate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("notice_rate", value).apply()
    }

    fun noticeUsage(context: Context) = prefs(context).getBoolean("notice_usage", true)

    fun setNoticeUsage(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("notice_usage", value).apply()
    }

    fun noticePrayer(context: Context) = prefs(context).getBoolean("notice_prayer", true)

    fun setNoticePrayer(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("notice_prayer", value).apply()
    }

    /** The master switch for the notification bar. Before it existed: on when speed or flag was on. */
    fun noticeEnabled(context: Context): Boolean {
        val p = prefs(context)
        return if (p.contains("notice_enabled")) p.getBoolean("notice_enabled", false)
        else showSpeedNotice(context) || showFlagNotice(context)
    }

    fun setNoticeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("notice_enabled", value).apply()
    }

    fun anyNotice(context: Context) = noticeEnabled(context)

    fun cityName(context: Context): String = prefs(context).getString("city_name", "") ?: ""

    fun cityLat(context: Context): Double? = prefs(context).getString("city_lat", null)?.toDoubleOrNull()

    fun cityLon(context: Context): Double? = prefs(context).getString("city_lon", null)?.toDoubleOrNull()

    fun hasCity(context: Context) = cityLat(context) != null && cityLon(context) != null

    fun setCity(context: Context, name: String, lat: Double, lon: Double) {
        prefs(context).edit()
            .putString("city_name", name)
            .putString("city_lat", lat.toString())
            .putString("city_lon", lon.toString())
            .apply()
        Weather.clear(context)  // the old city's temperature must not be shown for the new one
    }

    fun clearCity(context: Context) {
        prefs(context).edit().remove("city_name").remove("city_lat").remove("city_lon").apply()
        Weather.clear(context)
    }
}
