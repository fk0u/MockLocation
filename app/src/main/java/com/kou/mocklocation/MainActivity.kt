package com.kou.mocklocation

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/1.0"
            osmdroidBasePath = File(cacheDir, "osm")
            osmdroidTileCache = File(cacheDir, "osm/tiles")
        }
        setContent { AppTheme { App(viewModel()) } }
    }
}

data class Setup(
    val location: Boolean = false,
    val notifications: Boolean = true,
    val devOptions: Boolean = false,
    val mockApp: Boolean = false,
    val overlay: Boolean = false,
) {
    val ready get() = location && mockApp
}

class AppVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val store = Store(app)

    var mode by mutableStateOf(store.mode)
    val points = mutableStateListOf<Pt>().apply { addAll(store.draft) }
    var target by mutableStateOf(store.target)
    var speedKmh by mutableFloatStateOf(store.speedKmh)
    var loop by mutableStateOf(store.loop)
    var humanize by mutableStateOf(store.humanize)
    var floating by mutableStateOf(store.floating)
    var mapStyle by mutableStateOf(store.mapStyle)
    var favorites by mutableStateOf(store.favorites)
        private set
    var routes by mutableStateOf(store.routes)
        private set
    var camera = store.camera
        private set

    var follow by mutableStateOf(true)
    var flyTo by mutableStateOf<Pt?>(null)
    var fitRequest by mutableStateOf(0)
    val trail = mutableStateListOf<Pt>()

    var query by mutableStateOf("")
    var results by mutableStateOf(emptyList<Place>())
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    var setup by mutableStateOf(Setup())
        private set
    var showSetup by mutableStateOf(false)
    var locationAsked = 0

    val status = MockState.status
    private val msgs = Channel<String>(Channel.BUFFERED)
    val messages = msgs.receiveAsFlow()

    init {
        refreshSetup()
        if (!setup.ready) showSetup = true
        viewModelScope.launch {
            status.collect { s ->
                s.error?.let { err ->
                    status.update { it.copy(error = null) }
                    refreshSetup()
                    showSetup = true
                    say(if (err == MockService.ERR_MOCK) "Pilih aplikasi ini sebagai aplikasi lokasi palsu" else "Izin lokasi dibutuhkan")
                }
                val f = s.fix
                if (s.running && f != null) {
                    val last = trail.lastOrNull()
                    if (last == null || RoutePlayer.dist(last, f.pt) > 3) {
                        trail += f.pt
                        if (trail.size > 800) trail.removeRange(0, 200)
                    }
                }
            }
        }
        // Follow speed changes made outside the panel (floating joystick).
        viewModelScope.launch {
            status.distinctUntilChangedBy { it.setSpeed }.collect { if (it.running) speedKmh = (it.setSpeed * 3.6).toFloat() }
        }
    }

    fun say(msg: String) { msgs.trySend(msg) }

    // ---- map interaction ----

    fun onMapTap(p: Pt) {
        val s = status.value
        when (mode) {
            Mode.ROUTE -> if (!(s.running && s.mode == Mode.ROUTE)) points += p
            Mode.TELEPORT, Mode.JOYSTICK -> {
                target = p
                if (s.running && s.mode == mode) MockService.jump(ctx, p)
            }
        }
    }

    fun movePoint(i: Int, p: Pt) { if (i in points.indices) points[i] = p }
    fun removePoint(i: Int) { if (i in points.indices) points.removeAt(i) }
    fun undo() { if (points.isNotEmpty()) points.removeAt(points.lastIndex) }
    fun reverse() { val r = points.reversed(); points.clear(); points += r }
    fun clearRoute() { points.clear() }

    // ---- favorites & routes ----

    fun saveFavorite(name: String) {
        val t = target ?: return
        favorites = favorites.filter { it.name != name } + Place(name, t)
        store.favorites = favorites
        say("★ \"$name\" disimpan")
    }

    fun deleteFavorite(p: Place) {
        favorites = favorites - p; store.favorites = favorites
        say("\"${p.name}\" dihapus")
    }

    fun saveRoute(name: String) {
        routes = routes.filter { it.name != name } + SavedRoute(name, points.toList())
        store.routes = routes
        say("Rute \"$name\" disimpan")
    }

    fun loadRoute(r: SavedRoute) {
        points.clear(); points += r.points; fitRequest++
    }

    fun deleteRoute(r: SavedRoute) { routes = routes - r; store.routes = routes }

    fun importGpx(uri: Uri) = viewModelScope.launch {
        val pts = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.use { Gpx.parse(it) } }.getOrNull()
        }
        when {
            pts == null -> say("File GPX tidak bisa dibaca")
            pts.isEmpty() -> say("Tidak ada titik di file GPX")
            else -> { mode = Mode.ROUTE; points.clear(); points += pts; fitRequest++; say("${pts.size} titik diimpor") }
        }
    }

    fun exportGpx(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Gpx.write(points, "Rute Mock").toByteArray()) } }.isSuccess
        }
        say(if (ok) "Rute diekspor" else "Gagal mengekspor")
    }

    // ---- search ----

    fun pick(p: Place) {
        results = emptyList(); query = ""
        flyTo = p.pt
        onMapTap(p.pt)
    }

    fun clearSearch() { searchJob?.cancel(); searching = false; query = ""; results = emptyList() }

    fun search() {
        val q = query.trim()
        parseLatLon(q)?.let { return pick(Place(fmtPt(it), it)) }
        if (q.length < 3) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            // Nominatim policy: explicit submit only (no autocomplete), identify the app.
            results = runCatching { geocode(q) }.getOrElse { say("Pencarian gagal — cek koneksi"); emptyList() }
            if (results.isEmpty() && !q.isBlank()) say("Tidak ditemukan: $q")
            searching = false
        }
    }

    private suspend fun geocode(q: String): List<Place> = withContext(Dispatchers.IO) {
        val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=" + URLEncoder.encode(q, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "${ctx.packageName}/1.0 (Android mock location app)")
        c.setRequestProperty("Accept-Language", "id,en")
        c.connectTimeout = 8000; c.readTimeout = 8000
        try {
            val a = JSONArray(c.inputStream.bufferedReader().readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Place(it.getString("display_name"), Pt(it.getString("lat").toDouble(), it.getString("lon").toDouble()))
            }
        } finally { c.disconnect() }
    }

    // ---- mock control ----

    fun start(center: Pt) {
        refreshSetup()
        if (!setup.ready) { showSetup = true; return }
        val pts = when (mode) {
            Mode.TELEPORT -> listOfNotNull(target)
            Mode.ROUTE -> points.toList()
            Mode.JOYSTICK -> listOf(target ?: center)
        }
        if (mode == Mode.TELEPORT && pts.isEmpty()) return say("Ketuk peta untuk memilih titik tujuan")
        if (mode == Mode.ROUTE && pts.size < 2) return say("Tambahkan minimal 2 titik rute")
        if (mode == Mode.JOYSTICK && target == null) target = center
        trail.clear(); follow = true
        persist()
        MockService.start(ctx, mode, pts, speedKmh / 3.6, loop, humanize, floating && setup.overlay)
    }

    fun stop() = MockService.stop(ctx)
    fun togglePause() = MockService.togglePause(ctx)
    fun pushSpeed() { MockService.setSpeed(ctx, speedKmh / 3.6); store.speedKmh = speedKmh }

    // ---- setup ----

    fun refreshSetup() {
        val c = ctx
        setup = Setup(
            location = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            notifications = Build.VERSION.SDK_INT < 33 ||
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            devOptions = Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
            mockApp = isMockApp(c),
            overlay = Settings.canDrawOverlays(c),
        )
    }

    private fun isMockApp(c: Context): Boolean = try {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), c.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), c.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { true } // can't tell; let the service try and report

    fun openDevSettings(c: Context) {
        val action = if (setup.devOptions) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS
        try { c.startActivity(Intent(action)) } catch (e: ActivityNotFoundException) { c.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    fun openOverlaySettings(c: Context) =
        c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))

    fun openAppSettings(c: Context) =
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))

    fun saveCamera(cam: Camera) { camera = cam; store.camera = cam }

    fun persist() {
        store.mode = mode; store.draft = points.toList(); store.target = target
        store.speedKmh = speedKmh; store.loop = loop; store.humanize = humanize
        store.floating = floating; store.mapStyle = mapStyle
    }
}
