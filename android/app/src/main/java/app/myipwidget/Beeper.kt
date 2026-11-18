package app.myipwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.Calendar

/** The beeps: up to five times of day, each on the weekdays the person ticked. */
object Beeper {
    const val SLOTS = 5
    const val ACTION = "app.myipwidget.BEEP"

    /** The next moment any ticked beep is due, or null when none is on. */
    fun nextTime(context: Context, now: Long = System.currentTimeMillis()): Long? {
        if (!Prefs.beepEnabled(context)) return null
        var best: Long? = null
        for (slot in 0 until SLOTS) {
            if (!Prefs.beepOn(context, slot)) continue
            val mask = Prefs.beepDays(context, slot)
            if (mask == 0) continue
            for (offset in 0..7) {
                val c = Calendar.getInstance()
                c.timeInMillis = now
                c.add(Calendar.DAY_OF_YEAR, offset)
                c.set(Calendar.HOUR_OF_DAY, Prefs.beepHour(context, slot))
                c.set(Calendar.MINUTE, Prefs.beepMinute(context, slot))
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                // Calendar.SUNDAY is 1 ... SATURDAY is 7
                val bit = 1 shl (c.get(Calendar.DAY_OF_WEEK) - 1)
                if (mask and bit != 0 && c.timeInMillis > now + 1000L) {
                    if (best == null || c.timeInMillis < best) best = c.timeInMillis
                    break
                }
            }
        }
        return best
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 11, Intent(context, BeepReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Sets one alarm for the next beep (or removes it when there is none). Call after any change. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        try {
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val pending = pending(app)
            alarms.cancel(pending)
            val at = nextTime(app) ?: return
            val exactOk = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
            if (exactOk) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: Exception) {
            // without an alarm there is simply no beep
        }
    }

    // each melody: pairs of (frequency in Hz, length in ms); 0 Hz is a short rest
    private val MELODIES = arrayOf(
        // 0 bell: three soft rising notes
        intArrayOf(784, 260, 988, 260, 1319, 520),
        // 1 gentle: slow falling pair
        intArrayOf(880, 320, 659, 640),
        // 2 drop: one clean high note
        intArrayOf(1568, 600),
        // 3 marimba: quick playful run
        intArrayOf(523, 140, 659, 140, 784, 140, 1047, 420),
        // 4 lullaby: calm four notes
        intArrayOf(659, 300, 587, 300, 523, 300, 392, 700),
        // 5 door chime: ding-dong
        intArrayOf(659, 450, 523, 900),
        // 6 bird: a quick trill that ends high
        intArrayOf(1760, 90, 2093, 90, 1760, 90, 2093, 90, 2349, 450),
        // 7 harp: a rising run, slow
        intArrayOf(392, 180, 494, 180, 587, 180, 784, 180, 988, 180, 1175, 600),
        // 8 star: two sparkles and a long note
        intArrayOf(1175, 160, 1568, 160, 2093, 700),
        // 9 santur: repeated notes, then a step down
        intArrayOf(587, 110, 587, 110, 587, 110, 880, 220, 659, 600),
    )

    const val ACTION_AZAN = "app.myipwidget.AZAN_SOUND"
    private val AZAN_KEYS = listOf("azan_fajr", "azan_dhuhr", "azan_maghrib")

    /** One alarm for the next prayer call, only when a call sound is ticked. Call after any change. */
    fun scheduleAzan(context: Context) {
        val app = context.applicationContext
        try {
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                app, 12, Intent(app, AzanReceiver::class.java).setAction(ACTION_AZAN),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.cancel(pending)
            if (AZAN_KEYS.none { Prefs.azanSound(app, it) }) return
            val azan = Prayer.next(app, System.currentTimeMillis() + 3000L) ?: return
            val withKey = PendingIntent.getBroadcast(
                app, 12,
                Intent(app, AzanReceiver::class.java).setAction(ACTION_AZAN)
                    .putExtra("key", app.resources.getResourceEntryName(azan.nameRes)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val exactOk = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
            if (exactOk) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, azan.time, withKey)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, azan.time, withKey)
        } catch (e: Exception) {
            // no sound at the call
        }
    }

    private fun synth(melody: IntArray): ShortArray {
        val rate = 22050
        val out = ArrayList<Short>()
        var i = 0
        while (i < melody.size) {
            val freq = melody[i].toDouble()
            val ms = melody[i + 1]
            val n = rate * ms / 1000
            for (k in 0 until n) {
                val t = k.toDouble() / rate
                // a soft attack and a slow fade, a little third harmonic for a bell-like colour
                val attack = minOf(1.0, k / (rate * 0.01))
                val fade = Math.exp(-3.2 * k / n)
                val wave = Math.sin(2 * Math.PI * freq * t) + 0.25 * Math.sin(2 * Math.PI * freq * 3 * t)
                out.add((wave * attack * fade * 0.55 * 16000).toInt().toShort())
            }
            i += 2
        }
        return out.toShortArray()
    }

    /** Plays one melody and waits until it ends. Runs on its own thread. */
    fun play(sound: Int) {
        try {
            val data = synth(MELODIES[sound.coerceIn(0, MELODIES.size - 1)])
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(22050)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.play()
            Thread.sleep(data.size * 1000L / 22050 + 200)
            track.release()
        } catch (e: Exception) {
            // no sound this time
        }
    }
}

class BeepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                Beeper.play(Prefs.beepSound(app))
            } finally {
                Beeper.schedule(app)
                pending.finish()
            }
        }.start()
    }
}

class AzanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: ""
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                if (key.isNotEmpty() && Prefs.azanSound(app, key)) Beeper.play(Prefs.beepSound(app))
            } finally {
                Beeper.scheduleAzan(app)
                pending.finish()
            }
        }.start()
    }
}
