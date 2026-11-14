package com.hetuoo.phosphor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

class Station(val name: String, val url: String, val note: String = "")

/** Station lists per decade, and the shared "what is the receiver doing" state. */
object Radio {
    val BANDS = arrayOf("40S", "50S", "60S", "70S", "80S", "SOUL")

    const val STOPPED = 0
    const val TUNING = 1
    const val ON_AIR = 2
    const val FAILED = 3

    var state = STOPPED
        private set
    var current: Station? = null
        private set

    /** Told on the main thread whenever [state] or [current] changes. */
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun set(newState: Int, station: Station? = current) {
        main.post {
            state = newState
            current = station
            listener?.invoke()
        }
    }

    /** Always-available stations, listed first. Everything else comes from the directory. */
    private fun soma(name: String, id: String) = Station(name, "https://ice1.somafm.com/$id-128-mp3", "PRESET")

    val presets: Array<List<Station>> = arrayOf(
        emptyList(),
        listOf(soma("SOMA ILLINOIS STREET LOUNGE", "illstreet")),
        listOf(soma("SOMA SECRET AGENT", "secretagent")),
        listOf(soma("SOMA LEFT COAST 70S", "seventies")),
        listOf(soma("SOMA UNDERGROUND 80S", "u80s")),
        listOf(soma("SOMA SEVEN INCH SOUL", "7soul")),
    )

    /** Directory searches per band: "name=" lookups come first, then "tag=" by popularity. */
    private val queries = arrayOf(
        listOf("tag=40s", "tag=1940s", "tag=big band"),
        listOf("tag=50s", "tag=1950s", "tag=doo wop"),
        listOf("name=Heart 60s", "tag=60s"),
        listOf("name=Heart 70s", "tag=70s"),
        listOf("name=Heart 80s", "tag=80s"),
        listOf("tag=soul", "tag=motown"),
    )

    private val fallbackHosts = listOf(
        "de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info",
    )

    /** The Radio Browser community directory publishes its mirrors under one DNS name. */
    private fun hosts(): List<String> {
        val found = try {
            InetAddress.getAllByName("all.api.radio-browser.info")
                .map { it.canonicalHostName }
                .filter { it.endsWith(".api.radio-browser.info") && !it.startsWith("all.") }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
        return (found + fallbackHosts).distinct()
    }

    private fun get(host: String, query: String): JSONArray {
        val (key, value) = query.split('=', limit = 2)
        val url = "https://$host/json/stations/search?$key=" + URLEncoder.encode(value, "UTF-8") +
            "&hidebroken=true&order=clickcount&reverse=true&limit=25"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "PhosphorLauncher/1.1")
            return JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** Blocking. Working stations for a band from the directory, or null when it can't be reached. */
    fun fetch(band: Int): List<Station>? {
        for (host in hosts()) {
            try {
                val out = ArrayList<Station>()
                val seen = HashSet<String>()
                for (q in queries[band]) {
                    val arr = get(host, q)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
                        val name = o.optString("name").replace(Regex("\\s+"), " ").trim().uppercase()
                        if (url.isEmpty() || name.isEmpty() || !seen.add(url) || !seen.add(name)) continue
                        val kbps = o.optInt("bitrate")
                        val note = listOf(o.optString("countrycode").uppercase(), if (kbps > 0) "${kbps}K" else "")
                            .filter { it.isNotEmpty() }.joinToString(" ")
                        out.add(Station(name, url, note))
                    }
                }
                return out.take(40)
            } catch (e: Exception) {
                // Try the next mirror.
            }
        }
        return null
    }

    fun encode(list: List<Station>) = list.joinToString("\n") {
        "${it.name.replace('\t', ' ')}\t${it.url}\t${it.note}"
    }

    fun decode(s: String) = s.split('\n').mapNotNull {
        val p = it.split('\t')
        if (p.size >= 2 && p[1].isNotEmpty()) Station(p[0], p[1], p.getOrElse(2) { "" }) else null
    }
}

/** Plays one stream in the foreground so the music carries on when another app is opened. */
class RadioService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var noisyRegistered = false

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    /** Headphones pulled out: stop rather than blare from the speaker. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = shutDown(Radio.STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (intent?.action == ACTION_PLAY && name != null && url != null) {
            play(Station(name, url))
        } else {
            shutDown(Radio.STOPPED)
        }
        return START_NOT_STICKY
    }

    private fun play(station: Station) {
        val note = notification(station.name)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTE_ID, note)
        }
        Radio.set(Radio.TUNING, station)
        releasePlayer()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> shutDown(Radio.STOPPED)
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.setVolume(0f, 0f)
                        AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
                    }
                }
                .build()
        }
        audio.requestAudioFocus(focus!!)
        if (!noisyRegistered) {
            val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(noisy, f)
            noisyRegistered = true
        }

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setOnPreparedListener {
                    it.start()
                    Radio.set(Radio.ON_AIR)
                }
                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> Radio.set(Radio.TUNING)
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> Radio.set(Radio.ON_AIR)
                    }
                    false
                }
                setOnErrorListener { _, _, _ ->
                    shutDown(Radio.FAILED)
                    true
                }
                setOnCompletionListener { shutDown(Radio.FAILED) }
                setDataSource(station.url)
                prepareAsync()
            }
        } catch (e: Exception) {
            shutDown(Radio.FAILED)
        }
    }

    private fun notification(title: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Radio", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(EXTRA_SHOW_RADIO, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Phosphor radio")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.reset()
                it.release()
            } catch (e: Exception) {
                // Already gone.
            }
        }
        player = null
    }

    private fun shutDown(finalState: Int) {
        releasePlayer()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        if (noisyRegistered) {
            unregisterReceiver(noisy)
            noisyRegistered = false
        }
        Radio.set(finalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releasePlayer()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        if (noisyRegistered) unregisterReceiver(noisy)
        noisyRegistered = false
        if (Radio.state == Radio.TUNING || Radio.state == Radio.ON_AIR) Radio.set(Radio.STOPPED)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.hetuoo.phosphor.PLAY"
        const val ACTION_STOP = "com.hetuoo.phosphor.STOP"
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
        const val EXTRA_SHOW_RADIO = "show_radio"
        private const val CHANNEL = "radio"
        private const val NOTE_ID = 7

        fun play(ctx: Context, s: Station) {
            ctx.startForegroundService(
                Intent(ctx, RadioService::class.java).setAction(ACTION_PLAY)
                    .putExtra(EXTRA_NAME, s.name).putExtra(EXTRA_URL, s.url)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RadioService::class.java))
        }
    }
}
