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
