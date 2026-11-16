package app.myipwidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var flagView: TextView
    private lateinit var countryView: TextView
    private lateinit var ipView: TextView
    private lateinit var detailView: TextView
    private lateinit var refreshButton: Button
    private lateinit var weatherNow: TextView
    private lateinit var usageNow: TextView
    private lateinit var cityName: TextView
    private lateinit var checkWeather: CheckBox
    private lateinit var checkUsage: CheckBox
    private lateinit var grantUsage: Button
    private lateinit var checkSpeedNotice: CheckBox
    private lateinit var checkFlagNotice: CheckBox
    private var currentIp: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        flagView = findViewById(R.id.flag)
        countryView = findViewById(R.id.country)
        ipView = findViewById(R.id.ip)
        detailView = findViewById(R.id.detail)
        weatherNow = findViewById(R.id.weather_now)
        usageNow = findViewById(R.id.usage_now)
        cityName = findViewById(R.id.city_name)
        checkWeather = findViewById(R.id.check_weather)
        checkUsage = findViewById(R.id.check_usage)
        grantUsage = findViewById(R.id.grant_usage)

        refreshButton = findViewById(R.id.refresh)
        refreshButton.setOnClickListener { refresh() }
        ipView.setOnClickListener { copyIp() }

        checkWeather.isChecked = Prefs.showWeather(this)
        checkWeather.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowWeather(this, checked)
            updateExtras()
        }
        checkUsage.isChecked = Prefs.showUsage(this)
        checkUsage.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowUsage(this, checked)
            updateExtras()
        }
        checkSpeedNotice = findViewById(R.id.check_speed_notice)
        checkFlagNotice = findViewById(R.id.check_flag_notice)
        checkSpeedNotice.isChecked = Prefs.showSpeedNotice(this)
        checkSpeedNotice.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowSpeedNotice(this, checked)
            noticeChanged(checked)
        }
        checkFlagNotice.isChecked = Prefs.showFlagNotice(this)
        checkFlagNotice.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowFlagNotice(this, checked)
            noticeChanged(checked)
        }
        findViewById<Button>(R.id.choose_city).setOnClickListener { askCity() }
        grantUsage.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                // no such settings page on this phone
            }
        }

        val cached = IpLookup.load(this)
        show(if (cached != null) IpLookup.describe(this, cached) else IpLookup.checking(this))
        showExtras()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // coming back from the usage-access settings page: use the new permission
        showExtras()
        updateExtras()
    }

    /** Starts / stops the notifications; from Android 13 the notification permission is needed first. */
    private fun noticeChanged(turnedOn: Boolean) {
        if (turnedOn && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        SpeedService.sync(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.notice_denied, Toast.LENGTH_LONG).show()
        }
        SpeedService.sync(this)
    }

    private fun show(display: Display) {
        currentIp = display.ip
        flagView.text = display.flag
        countryView.text = display.title
        ipView.text = display.ip
        detailView.text = display.detail
    }

    /** Fills in the weather, today's volume, the city name and the permission button from what is saved. */
    private fun showExtras() {
        val weather = Weather.cached(this)
        weatherNow.text = if (Prefs.hasCity(this) && weather != null) Weather.format(weather) else ""
        weatherNow.visibility = if (weatherNow.text.isEmpty()) View.GONE else View.VISIBLE

        cityName.text = if (Prefs.hasCity(this)) Prefs.cityName(this) else getString(R.string.no_city)

        val hasAccess = Usage.hasAccess(this)
        grantUsage.visibility = if (hasAccess) View.GONE else View.VISIBLE
        val used = Usage.cached(this)
        usageNow.text = when {
            !hasAccess -> getString(R.string.usage_no_access)
            used != null -> getString(
                R.string.usage_today,
                "↓ " + Formatter.formatShortFileSize(this, used.first) +
                    "   ↑ " + Formatter.formatShortFileSize(this, used.second),
            )
            else -> ""
        }
        usageNow.visibility = if (usageNow.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Looks up the weather / volume in the background, then redraws this screen and the widget. */
    private fun updateExtras(weatherMaxAgeMs: Long = 2 * 60 * 1000L) {
        val app = applicationContext
        Thread {
            try {
                Weather.refreshIfStale(app, weatherMaxAgeMs)
                Usage.update(app)
            } catch (e: Exception) {
                // optional extras
            }
            IpWidget.redrawFromCache(app)
            runOnUiThread { if (!isFinishing) showExtras() }
        }.start()
    }

    private fun refresh() {
        val app = applicationContext
        refreshButton.isEnabled = false
        Thread {
            val info = if (NetWatch.isOnline(app)) IpLookup.fetch() else null
            if (info != null) IpLookup.save(app, info)
            val display = if (info != null) IpLookup.describe(app, info) else IpLookup.offline(app)
            runOnUiThread {
                if (!isFinishing) {
                    show(display)
                    refreshButton.isEnabled = true
                }
            }
            IpWidget.render(app, display)
            updateExtras()
        }.start()
    }

    // ---------------------------------------------------------------- the city
    private fun askCity() {
        val input = EditText(this)
        input.hint = getString(R.string.city_hint)
        input.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_city)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ -> searchCity(input.text.toString()) }
            .setNeutralButton(R.string.remove_city) { _, _ ->
                Prefs.clearCity(this)
                showExtras()
                updateExtras()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun searchCity(query: String) {
        Toast.makeText(this, R.string.searching, Toast.LENGTH_SHORT).show()
        Thread {
            val results = Weather.search(query)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    results == null -> Toast.makeText(this, R.string.search_failed, Toast.LENGTH_LONG).show()
                    results.isEmpty() -> Toast.makeText(this, R.string.no_city_found, Toast.LENGTH_LONG).show()
                    else -> showCityList(results)
                }
            }
        }.start()
    }

    private fun showCityList(cities: List<City>) {
        val labels = cities.map { c ->
            listOf(c.name, c.region, c.country).filter { it.isNotEmpty() }.joinToString(" - ")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_city)
            .setItems(labels) { _, index -> chooseCity(cities[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseCity(city: City) {
        Prefs.setCity(this, city.name, city.lat, city.lon)
        Prefs.setShowWeather(this, true)
        checkWeather.isChecked = true
        showExtras()
        updateExtras(0L)
    }

    private fun copyIp() {
        if (currentIp.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("IP", currentIp))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
