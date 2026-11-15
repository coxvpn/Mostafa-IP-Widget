package app.myipwidget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var flagView: TextView
    private lateinit var countryView: TextView
    private lateinit var ipView: TextView
    private lateinit var detailView: TextView
    private var currentIp: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        flagView = findViewById(R.id.flag)
        countryView = findViewById(R.id.country)
        ipView = findViewById(R.id.ip)
        detailView = findViewById(R.id.detail)

        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }
        ipView.setOnClickListener { copyIp() }

        val cached = IpLookup.load(this)
        show(if (cached != null) IpLookup.describe(this, cached) else IpLookup.checking(this))
        refresh()
    }

    private fun show(display: Display) {
        currentIp = display.ip
        flagView.text = display.flag
        countryView.text = display.title
        ipView.text = display.ip
        detailView.text = display.detail
    }

    private fun refresh() {
        val app = applicationContext
        Thread {
            val info = IpLookup.fetch()
            if (info != null) IpLookup.save(app, info)
            val display = if (info != null) IpLookup.describe(app, info) else IpLookup.offline(app)
            runOnUiThread { if (!isFinishing) show(display) }
            IpWidget.render(app, display)
        }.start()
    }

    private fun copyIp() {
        if (currentIp.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("IP", currentIp))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
