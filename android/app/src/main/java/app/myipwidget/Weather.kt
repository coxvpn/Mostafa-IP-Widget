package app.myipwidget

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class City(val name: String, val region: String, val country: String, val lat: Double, val lon: Double)

data class WeatherInfo(val temp: Double, val code: Int)

/** Temperature for a city the person chose (Open-Meteo: free, no key). */
object Weather {
    const val FRESH_MS = 25 * 60 * 1000L

    /** Cities matching a name: a list (maybe empty), or null when the search could not be made. */
    fun search(query: String): List<City>? {
        val text = query.trim()
        if (text.length < 2) return emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(text, "UTF-8") + "&count=8&language=fa&format=json"
        val body = IpLookup.httpGet(url) ?: return null
        try {
            val array = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val found = ArrayList<City>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val lat = item.optDouble("latitude", Double.NaN)
                val lon = item.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue
                val name = IpLookup.clean(item.optString("name"), 40)
                if (name.isEmpty()) continue
                found.add(
                    City(
                        name,
                        IpLookup.clean(item.optString("admin1"), 40),
                        IpLookup.clean(item.optString("country"), 40),
                        lat,
                        lon,
                    ),
                )
            }
            return found
        } catch (e: Exception) {
            return null
        }
    }

    fun fetch(lat: Double, lon: Double): WeatherInfo? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + "%.4f".format(Locale.US, lat) +
            "&longitude=" + "%.4f".format(Locale.US, lon) +
            "&current=temperature_2m,weather_code&timezone=auto"
        val body = IpLookup.httpGet(url) ?: return null
        return try {
            val current = JSONObject(body).getJSONObject("current")
            WeatherInfo(current.getDouble("temperature_2m"), current.getInt("weather_code"))
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ cache
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun cached(context: Context): WeatherInfo? {
        val p = prefs(context)
        if (!p.contains("temp")) return null
        return WeatherInfo(p.getFloat("temp", 0f).toDouble(), p.getInt("code", 0))
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Looks the weather up again if the saved one is older than [maxAgeMs]. Call from a background thread. */
    fun refreshIfStale(context: Context, maxAgeMs: Long = FRESH_MS) {
        if (!Prefs.showWeather(context)) return
        val lat = Prefs.cityLat(context) ?: return
        val lon = Prefs.cityLon(context) ?: return
        val p = prefs(context)
        val age = System.currentTimeMillis() - p.getLong("time", 0L)
        if (p.contains("temp") && age in 0..maxAgeMs) return
        val info = fetch(lat, lon) ?: return
        p.edit()
            .putFloat("temp", info.temp.toFloat())
            .putInt("code", info.code)
            .putLong("time", System.currentTimeMillis())
            .apply()
    }

    // ---------------------------------------------------------------- display
    fun symbol(code: Int): String = when (code) {
        0 -> "☀️"                                   // sun
        1, 2 -> "⛅"                                      // sun behind cloud
        3 -> "☁️"                                   // cloud
        45, 48 -> "🌫️"                        // fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"  // rain
        71, 73, 75, 77, 85, 86 -> "❄️"              // snow
        95, 96, 99 -> "⛈️"                          // thunderstorm
        else -> "☁️"
    }

    fun format(info: WeatherInfo): String = symbol(info.code) + " " + Math.round(info.temp) + "°C"
}
