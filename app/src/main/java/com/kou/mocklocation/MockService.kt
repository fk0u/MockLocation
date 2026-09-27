package com.kou.mocklocation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Mode { TELEPORT, ROUTE, JOYSTICK }

data class Status(
    val running: Boolean = false,
    val paused: Boolean = false,
    val mode: Mode = Mode.TELEPORT,
    val loop: Loop = Loop.ONCE,
    val fix: Fix? = null,
    val speed: Double = 0.0,       // current m/s
    val setSpeed: Double = 0.0,    // requested m/s
    val traveled: Double = 0.0,
    val length: Double = 0.0,      // one route cycle, meters
    val finished: Boolean = false,
    val dwell: Double = 0.0,       // seconds left at the current waypoint stop
    val error: String? = null,
)

/** Service ↔ UI bridge; both live in the same process. */
object MockState {
    val status = MutableStateFlow(Status())
    @Volatile var joyX = 0f
    @Volatile var joyY = 0f
}

@SuppressLint("MissingPermission")
class MockService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random.Default
    private val providers = mutableListOf<String>()
    private lateinit var lm: LocationManager
    private var running = false
    private var mode = Mode.TELEPORT
    private var route = emptyList<Pt>()
    private var loop = Loop.ONCE
    private var speed = 1.4
    private var humanize = false
    private var dwell = 0.0
    private var dwellLeft = 0.0
    private var altitude = 12.0
    private var pos = Pt(0.0, 0.0)
    private var bearing = 0f
    private var traveled = 0.0
    private var paused = false
    private var last = 0L
    private var started = 0L
    private var lastNotify = 0L
    private var joystick: JoystickOverlay? = null

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: SecurityException) { return fail(ERR_MOCK) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val action = i?.action
        if (action != ACTION_START && !running) { stopSelf(); return START_NOT_STICKY }
        when (action) {
            ACTION_START -> start(i!!)
            ACTION_PAUSE -> { paused = !paused; notifyNow() }
            ACTION_SPEED -> speed = i!!.getDoubleExtra(EXTRA_SPEED, speed)
            ACTION_JUMP -> { pos = Pt(i!!.getDoubleExtra(EXTRA_LAT, pos.lat), i.getDoubleExtra(EXTRA_LON, pos.lon)); notifyNow() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(i: Intent) {
        val lats = i.getDoubleArrayExtra(EXTRA_LATS) ?: return stopSelf()
        val lons = i.getDoubleArrayExtra(EXTRA_LONS) ?: return stopSelf()
        val points = lats.indices.map { Pt(lats[it], lons[it]) }
        if (points.isEmpty()) return stopSelf()
        mode = Mode.valueOf(i.getStringExtra(EXTRA_MODE)!!)
        loop = Loop.valueOf(i.getStringExtra(EXTRA_LOOP) ?: Loop.ONCE.name)
        speed = i.getDoubleExtra(EXTRA_SPEED, 1.4)
        humanize = i.getBooleanExtra(EXTRA_HUMANIZE, false)
        dwell = i.getIntExtra(EXTRA_DWELL, 0).toDouble()
        altitude = i.getDoubleExtra(EXTRA_ALT, 12.0)
        route = points; pos = points.first(); traveled = 0.0; paused = false; bearing = 0f; dwellLeft = 0.0
        MockState.joyX = 0f; MockState.joyY = 0f

        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: Exception) { return fail(ERR_PERMISSION) }
        if (!setupProviders()) return fail(ERR_MOCK)

        joystick?.remove(); joystick = null
        if (mode == Mode.JOYSTICK && i.getBooleanExtra(EXTRA_FLOATING, false) && Settings.canDrawOverlays(this)) {
            joystick = JoystickOverlay(this, (speed * 3.6).toFloat(), onSpeed = { speed = it }, onStop = { stopSelf() })
        }
        running = true
        last = SystemClock.elapsedRealtime(); started = last
        handler.removeCallbacks(tick); handler.post(tick)
        QsTile.refresh(this)
    }

    /** GPS is required; network is best-effort so fused-location apps don't see the real position. */
    private fun setupProviders(): Boolean {
        lm = getSystemService(LocationManager::class.java)
        providers.clear()
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                runCatching { lm.removeTestProvider(p) }
                @Suppress("DEPRECATION")
                lm.addTestProvider(p, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                lm.setTestProviderEnabled(p, true)
                providers += p
            } catch (e: SecurityException) {
                return false
            } catch (e: Exception) {
                // provider not supported on this device
            }
        }
        return LocationManager.GPS_PROVIDER in providers
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - last) / 1000.0).coerceIn(0.0, 2.0); last = now
        val t = (now - started) / 1000.0
        val length = RoutePlayer.cycle(route, loop)
        var finished = false
        var v = 0.0
        when (mode) {
            Mode.TELEPORT -> {}
            Mode.ROUTE -> {
                finished = loop == Loop.ONCE && traveled >= length
                if (!paused && !finished) {
                    if (dwellLeft > 0) dwellLeft = (dwellLeft - dt).coerceAtLeast(0.0)
                    else {
                        v = speed * if (humanize) 1 + 0.1 * sin(t / 6) + 0.04 * sin(t / 1.7) else 1.0
                        val next = traveled + v * dt
                        val stop = if (dwell > 0) RoutePlayer.nextStop(route, loop, traveled, next) else null
                        if (stop != null) { traveled = stop; dwellLeft = dwell } else traveled = next
                    }
                }
                val f = RoutePlayer.along(route, traveled, loop)
                pos = f.pt
                if (route.size > 1) bearing = f.bearing
            }
            Mode.JOYSTICK -> {
                val x = MockState.joyX; val y = MockState.joyY
                val mag = min(1f, hypot(x, y))
                if (!paused && mag > 0.08f) {
                    bearing = ((Math.toDegrees(atan2(x.toDouble(), -y.toDouble())) + 360) % 360).toFloat()
                    v = speed * mag
                    pos = RoutePlayer.move(pos, bearing.toDouble(), v * dt)
                }
            }
        }
        val out = if (humanize) RoutePlayer.move(pos, rnd.nextDouble(360.0), rnd.nextDouble(1.0)) else pos
        push(out, v)
        MockState.status.value = Status(true, paused, mode, loop, Fix(out.lat, out.lon, bearing), v, speed,
            traveled, length, finished, dwellLeft)
        if (now - lastNotify > 3000) notifyNow()
    }

    private fun push(p: Pt, v: Double) {
        val acc = if (humanize) 3f + rnd.nextFloat() * 5f else 4f
        for (name in providers) lm.setTestProviderLocation(name, Location(name).apply {
            latitude = p.lat; longitude = p.lon
            altitude = this@MockService.altitude + if (humanize) rnd.nextDouble(-1.5, 1.5) else 0.0
            bearing = this@MockService.bearing; speed = v.toFloat()
            accuracy = acc
            verticalAccuracyMeters = 3f; speedAccuracyMetersPerSecond = 0.3f; bearingAccuracyDegrees = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
    }

    private fun fail(err: String) {
        running = false
        MockState.status.value = Status(error = err)
        stopSelf()
    }

    private fun notifyNow() {
        lastNotify = SystemClock.elapsedRealtime()
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mock location", NotificationManager.IMPORTANCE_LOW))
        val s = MockState.status.value
        val title = when (mode) {
            Mode.TELEPORT -> "Teleport aktif"
            Mode.ROUTE -> when { s.finished -> "Rute selesai"; s.dwell > 0 -> "Singgah di titik"; else -> "Menjalankan rute" }
            Mode.JOYSTICK -> "Mode joystick"
        } + if (paused) " · dijeda" else ""
        val text = if (mode == Mode.ROUTE && running)
            "${fmtDist(min(traveled, s.length))} / ${fmtDist(s.length)} · ${(speed * 3.6).toInt()} km/j"
        else fmtPt(s.fix?.pt ?: pos)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF5EEAD4.toInt())
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply { if (mode != Mode.TELEPORT) addAction(0, if (paused) "Lanjut" else "Jeda", action(ACTION_PAUSE)) }
            .addAction(0, "Stop", action(ACTION_STOP))
            .build()
    }

    private fun action(a: String) = PendingIntent.getService(this, a.hashCode(),
        Intent(this, MockService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        joystick?.remove(); joystick = null
        providers.forEach { runCatching { lm.removeTestProvider(it) } }
        if (running) MockState.status.value = Status()
        running = false
        QsTile.refresh(this)
    }

    override fun onBind(i: Intent?): IBinder? = null

    companion object {
        const val TICK_MS = 500L
        const val ERR_MOCK = "mock"
        const val ERR_PERMISSION = "permission"
        private const val NOTIF_ID = 1
        private const val CHANNEL = "mock"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_SPEED = "speed"
        private const val ACTION_JUMP = "jump"
        private const val EXTRA_LATS = "lats"
        private const val EXTRA_LONS = "lons"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_LOOP = "loop"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_HUMANIZE = "humanize"
        private const val EXTRA_FLOATING = "floating"
        private const val EXTRA_DWELL = "dwell"
        private const val EXTRA_ALT = "alt"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"

        fun start(ctx: Context, mode: Mode, points: List<Pt>, speed: Double, loop: Loop, humanize: Boolean, floating: Boolean,
                  dwellSec: Int = 0, altitude: Double = 12.0) {
            ctx.startForegroundService(Intent(ctx, MockService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name).putExtra(EXTRA_LOOP, loop.name)
                .putExtra(EXTRA_LATS, points.map { it.lat }.toDoubleArray())
                .putExtra(EXTRA_LONS, points.map { it.lon }.toDoubleArray())
                .putExtra(EXTRA_SPEED, speed).putExtra(EXTRA_HUMANIZE, humanize).putExtra(EXTRA_FLOATING, floating)
                .putExtra(EXTRA_DWELL, dwellSec).putExtra(EXTRA_ALT, altitude))
        }

        private fun send(ctx: Context, action: String, block: Intent.() -> Unit = {}) {
            if (MockState.status.value.running) ctx.startService(Intent(ctx, MockService::class.java).setAction(action).apply(block))
        }

        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun togglePause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun setSpeed(ctx: Context, mps: Double) = send(ctx, ACTION_SPEED) { putExtra(EXTRA_SPEED, mps) }
        fun jump(ctx: Context, p: Pt) = send(ctx, ACTION_JUMP) { putExtra(EXTRA_LAT, p.lat); putExtra(EXTRA_LON, p.lon) }
    }
}
