package app.myipwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private companion object {
        const val SOURCE_URL = "https://github.com/coxvpn/Mostafa-IP-Widget"
    }

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
    private lateinit var checkDate: CheckBox
    private lateinit var dateNow: TextView
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
        findViewById<CheckBox>(R.id.check_rate).apply {
            isChecked = Prefs.showRate(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setShowRate(this@MainActivity, checked)
                updateExtras(0L)
            }
        }
        dateNow = findViewById(R.id.date_now)
        checkDate = findViewById(R.id.check_date)
        checkDate.isChecked = Prefs.showDate(this)
        checkDate.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowDate(this, checked)
            updateExtras()
        }
        findViewById<CheckBox>(R.id.check_aqi).apply {
            isChecked = Prefs.showAqi(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setShowAqi(this@MainActivity, checked)
                updateExtras(0L)
            }
        }
        findViewById<CheckBox>(R.id.check_prayer).apply {
            isChecked = Prefs.showPrayer(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setShowPrayer(this@MainActivity, checked)
                if (checked && !Prefs.hasCity(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, R.string.no_city_for_azan, Toast.LENGTH_LONG).show()
                }
                updateExtras()
            }
        }
        // what the notification text holds
        findViewById<CheckBox>(R.id.check_notice_temp).apply {
            isChecked = Prefs.noticeTemp(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setNoticeTemp(this@MainActivity, checked) }
        }
        findViewById<CheckBox>(R.id.check_notice_rate).apply {
            isChecked = Prefs.noticeRate(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setNoticeRate(this@MainActivity, checked) }
        }
        findViewById<CheckBox>(R.id.check_notice_usage).apply {
            isChecked = Prefs.noticeUsage(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setNoticeUsage(this@MainActivity, checked) }
        }
        findViewById<CheckBox>(R.id.check_notice_prayer).apply {
            isChecked = Prefs.noticePrayer(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setNoticePrayer(this@MainActivity, checked) }
        }
        findViewById<Button>(R.id.choose_theme).apply {
            text = getString(R.string.theme_button, themeName(Prefs.theme(this@MainActivity)))
            setOnClickListener { askTheme(this) }
        }
        findViewById<Button>(R.id.about).setOnClickListener { showAbout() }
        findViewById<Button>(R.id.add_widget).setOnClickListener { addWidget() }
        findViewById<CheckBox>(R.id.check_compact).apply {
            isChecked = Prefs.compactNotice(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setCompactNotice(this@MainActivity, checked) }
        }
        findViewById<Button>(R.id.notice_settings).setOnClickListener { openNoticeSettings() }
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
        findViewById<Button>(R.id.battery).setOnClickListener { requestUnrestricted() }
        askUnrestrictedOnce()
    }

    private fun themeName(index: Int): String = resources.getStringArray(R.array.theme_names)[index]

    /** A list of looks for the widget background; the widget changes at once. */
    private fun askTheme(button: Button) {
        AlertDialog.Builder(this)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(resources.getStringArray(R.array.theme_names), Prefs.theme(this)) { dialog, which ->
                Prefs.setTheme(this, which)
                button.text = getString(R.string.theme_button, themeName(which))
                IpWidget.redrawFromCache(applicationContext)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    /** The phone's own question "let this app run without battery limits?" (otherwise it may be put to sleep). */
    private fun requestUnrestricted() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                // no such settings page on this phone
            }
        }
    }

    /** Right after the first start: explain, then show the phone's question. Asked only once. */
    private fun askUnrestrictedOnce() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (isUnrestricted() || prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_allow) { _, _ -> requestUnrestricted() }
            .setNegativeButton(R.string.battery_later, null)
            .show()
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

    /** Asks the home screen to add the widget (the phone shows its own "add" question). */
    private fun addWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(this, IpWidget::class.java)
        if (manager != null && manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        } else {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /** The phone's own page for the speed notification: there it can be set to "silent" or collapsed. */
    private fun openNoticeSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, SpeedService.CHANNEL),
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            } catch (e2: Exception) {
                // no such settings page on this phone
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(getString(R.string.about_text, version, SOURCE_URL))
            .setPositiveButton(R.string.source_code) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL)))
                } catch (e: Exception) {
                    // no browser installed
                }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
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
        val rateView = findViewById<TextView>(R.id.rate_now)
        rateView.text = getString(R.string.rate_line, Rate.format(Rate.cached(this)))
        rateView.visibility = if (Prefs.showRate(this)) View.VISIBLE else View.GONE
        dateNow.text = PersianDate.full()
        dateNow.visibility = if (Prefs.showDate(this)) View.VISIBLE else View.GONE
        val weather = Weather.cached(this)
        weatherNow.text = if (Prefs.hasCity(this) && weather != null) {
            Weather.format(weather) + "  " + Weather.range(weather) +
                (if (weather.aqi >= 0) "  AQI " + weather.aqi else "")
        } else {
            ""
        }
        weatherNow.visibility = if (weatherNow.text.isEmpty()) View.GONE else View.VISIBLE

        cityName.text = if (Prefs.hasCity(this)) Prefs.cityName(this) else getString(R.string.no_city)

        val hasAccess = Usage.hasAccess(this)
        grantUsage.visibility = if (hasAccess) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.battery).visibility = if (isUnrestricted()) View.GONE else View.VISIBLE
        val used = Usage.cached(this)
        usageNow.text = when {
            !hasAccess -> getString(R.string.usage_no_access)
            used != null -> getString(
                R.string.usage_today,
                Usage.oneLine(this, used),
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
                Rate.refreshIfStale(app, weatherMaxAgeMs)
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
