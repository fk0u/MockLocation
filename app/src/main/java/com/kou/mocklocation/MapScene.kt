package com.kou.mocklocation

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

enum class MapStyle(val label: String) { DARK("Gelap"), STREET("Jalan"), SATELLITE("Satelit") }

/** Keyless dark map: OSM tiles desaturated, inverted and tinted navy. */
val darkFilter = ColorMatrixColorFilter(ColorMatrix().apply {
    setSaturation(0.25f)
    postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
    postConcat(ColorMatrix(floatArrayOf(0.6f, 0f, 0f, 0f, 6f, 0f, 0.68f, 0f, 0f, 10f, 0f, 0f, 0.8f, 0f, 20f, 0f, 0f, 0f, 1f, 0f)))
})

// Keyless Esri tiles (tile.openstreetmap.org blocks third-party apps). Note Esri's {z}/{y}/{x} order.
private fun esri(name: String, service: String, copyright: String) = object : OnlineTileSourceBase(name, 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"), copyright) {
    override fun getTileURLString(index: Long) =
        "$baseUrl${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}"
}

private val street = esri("EsriStreet", "World_Street_Map", "© Esri, HERE, Garmin, © OpenStreetMap contributors")
private val satellite = esri("EsriImagery", "World_Imagery", "© Esri, Maxar, Earthstar Geographics")

val MapStyle.tiles: ITileSource get() = when (this) {
    MapStyle.DARK, MapStyle.STREET -> street
    MapStyle.SATELLITE -> satellite
}

/** Draws route, trail, target and the animated live position; handles tap and waypoint drag. */
class Scene(ctx: Context) : Overlay() {
    var points: List<Pt> = emptyList()
    var target: Pt? = null
    var trail: List<Pt> = emptyList()
    var editRoute = false
    var onTap: (Pt) -> Unit = {}
    var onTapPoint: (Int) -> Unit = {}
    var onDragPoint: (Int, Pt) -> Unit = { _, _ -> }
    var onUserPan: () -> Unit = {}

    private val d = ctx.resources.displayMetrics.density
    private fun paint(color: Long, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toInt()
        if (stroke > 0) { style = Paint.Style.STROKE; strokeWidth = stroke * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    }
    private val routeGlow = paint(0x405EEAD4, 10f)
    private val routeLine = paint(0xFF5EEAD4, 3.5f).apply { pathEffect = DashPathEffect(floatArrayOf(10 * d, 7 * d), 0f) }
    private val trailGlow = paint(0x40FF4D6D, 9f)
    private val trailLine = paint(0xFFFF4D6D, 3f)
    private val dotFill = paint(0xFF111720)
    private val dotStart = paint(0xFF5EEAD4)
    private val dotRing = paint(0xFF5EEAD4, 2.5f)
    private val text = paint(0xFFE7EEF6).apply { textAlign = Paint.Align.CENTER; textSize = 11 * d; typeface = Typeface.DEFAULT_BOLD }
    private val textDark = Paint(text).apply { color = 0xFF04201D.toInt() }
    private val halo = paint(0x335EEAD4)
    private val white = paint(0xFFFFFFFF)
    private val liveDot = paint(0xFFFF4D6D)
    private val pulse = paint(0xFFFF4D6D)
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)

    // Smoothly interpolate between service ticks so the marker glides instead of jumping.
    private var from: Fix? = null
    private var to: Fix? = null
    private var since = 0L
    var live: Fix?
        get() = to
        set(v) {
            if (v == to) return
            val cur = current()
            from = if (cur != null && v != null && RoutePlayer.dist(cur.pt, v.pt) < 2000) cur else null
            to = v; since = SystemClock.uptimeMillis()
        }

    private fun current(): Fix? {
        val b = to ?: return null
        val a = from ?: return b
        val t = ((SystemClock.uptimeMillis() - since) / MockService.TICK_MS.toFloat()).coerceIn(0f, 1f)
        val db = ((b.bearing - a.bearing + 540) % 360) - 180
        return Fix(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, (a.bearing + db * t + 360) % 360)
    }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = map.projection
        fun xy(p: Pt) = pj.toPixels(GeoPoint(p.lat, p.lon), null).let { PointF(it.x.toFloat(), it.y.toFloat()) }
        fun path(ps: List<Pt>) = Path().apply {
            ps.forEachIndexed { i, p -> val q = xy(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
        }

        if (points.size > 1) path(points).let { c.drawPath(it, routeGlow); c.drawPath(it, routeLine) }
        if (trail.size > 1) path(trail).let { c.drawPath(it, trailGlow); c.drawPath(it, trailLine) }

        // Numbered waypoints; for big GPX imports only mark start and end.
        val labelled = points.size <= 60
        points.forEachIndexed { i, p ->
            if (!labelled && i != 0 && i != points.lastIndex) return@forEachIndexed
            val q = xy(p); val first = i == 0
            c.drawCircle(q.x, q.y, 12 * d, if (first) dotStart else dotFill)
            c.drawCircle(q.x, q.y, 12 * d, dotRing)
            c.drawText(if (labelled) "${i + 1}" else if (first) "A" else "B", q.x, q.y + 4 * d, if (first) textDark else text)
        }

        target?.let {
            val q = xy(it)
            c.drawCircle(q.x, q.y, 22 * d, halo); c.drawCircle(q.x, q.y, 11 * d, dotRing)
            c.drawCircle(q.x, q.y, 5 * d, white)
        }

        current()?.let { f ->
            val q = xy(f.pt)
            val phase = (SystemClock.uptimeMillis() % 1800) / 1800f
            pulse.alpha = ((1 - phase) * 110).toInt()
            c.drawCircle(q.x, q.y, 10 * d + 30 * d * phase, pulse)
            beam.shader = RadialGradient(q.x, q.y, 46 * d, 0x88FF4D6D.toInt(), 0x00FF4D6D, Shader.TileMode.CLAMP)
            c.drawArc(RectF(q.x - 46 * d, q.y - 46 * d, q.x + 46 * d, q.y + 46 * d), f.bearing - 90 - 32, 64f, true, beam)
            c.drawCircle(q.x, q.y, 10 * d, white)
            c.drawCircle(q.x, q.y, 7 * d, liveDot)
            map.postInvalidateDelayed(16)
        }
    }

    private var drag = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var panned = false

    override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; panned = false
                drag = if (editRoute) hit(e, map) else -1
                return drag >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                val far = hypot(e.x - downX, e.y - downY) > 8 * d
                if (drag >= 0) {
                    if (far && !moved) { moved = true; map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                    if (moved) map.projection.fromPixels(e.x.toInt(), e.y.toInt()).let { onDragPoint(drag, Pt(it.latitude, it.longitude)) }
                    return true
                }
                if (far && !panned && e.pointerCount == 1) { panned = true; onUserPan() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag >= 0) {
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    map.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTapPoint(drag)
                }
                drag = -1
                return true
            }
        }
        return false
    }

    private fun hit(e: MotionEvent, map: MapView): Int {
        var best = -1; var bestD = 24 * d
        points.forEachIndexed { i, p ->
            val q = map.projection.toPixels(GeoPoint(p.lat, p.lon), null)
            val dd = hypot(q.x - e.x, q.y - e.y)
            if (dd < bestD) { bestD = dd; best = i }
        }
        return best
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val g = map.projection.fromPixels(e.x.toInt(), e.y.toInt())
        map.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onTap(Pt(g.latitude, g.longitude))
        return true
    }
}
