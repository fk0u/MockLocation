package com.kou.mocklocation

import org.w3c.dom.Element
import java.io.InputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*

data class Pt(val lat: Double, val lon: Double)
data class Fix(val lat: Double, val lon: Double, val bearing: Float) {
    val pt get() = Pt(lat, lon)
}

enum class Loop { ONCE, LOOP, PINGPONG }

/** Pure geo math; everything here is unit-tested on the JVM. */
object RoutePlayer {
    private const val R = 6371000.0

    fun dist(a: Pt, b: Pt): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun bearing(a: Pt, b: Pt): Float {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    /** Destination after moving [meters] from [p] towards [bearingDeg]. */
    fun move(p: Pt, bearingDeg: Double, meters: Double): Pt {
        val b = Math.toRadians(bearingDeg); val d = meters / R
        val lat1 = Math.toRadians(p.lat); val lon1 = Math.toRadians(p.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return Pt(Math.toDegrees(lat2), (Math.toDegrees(lon2) + 540) % 360 - 180)
    }

    fun total(route: List<Pt>) = route.zipWithNext { a, b -> dist(a, b) }.sum()

    /** Distance of one full cycle for the given loop mode. */
    fun cycle(route: List<Pt>, loop: Loop) = when (loop) {
        Loop.ONCE -> total(route)
        Loop.LOOP -> if (route.size < 2) 0.0 else total(route + route.first())
        Loop.PINGPONG -> 2 * total(route)
    }

    /** Position [d] meters along [route], clamped to its ends. */
    fun at(route: List<Pt>, d: Double): Fix {
        require(route.isNotEmpty())
        if (route.size == 1) return Fix(route[0].lat, route[0].lon, 0f)
        var left = d.coerceAtLeast(0.0)
        for (i in 0 until route.size - 1) {
            val a = route[i]; val b = route[i + 1]
            val seg = dist(a, b)
            if (left <= seg || i == route.size - 2) {
                val f = if (seg == 0.0) 1.0 else (left / seg).coerceIn(0.0, 1.0)
                return Fix(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, bearing(a, b))
            }
            left -= seg
        }
        error("unreachable")
    }

    /** Position after travelling [d] meters, honouring the loop mode. */
    fun along(route: List<Pt>, d: Double, loop: Loop): Fix {
        val total = total(route)
        if (route.size < 2 || total == 0.0) return at(route, 0.0)
        return when (loop) {
            Loop.ONCE -> at(route, d)
            Loop.LOOP -> (route + route.first()).let { at(it, d % total(it)) }
            Loop.PINGPONG -> (d % (2 * total)).let { m -> if (m <= total) at(route, m) else at(route.reversed(), m - total) }
        }
    }

    /** Distances within one cycle, in (0, cycle], where the route passes a waypoint worth stopping at. */
    fun stops(route: List<Pt>, loop: Loop): List<Double> {
        if (route.size < 2) return emptyList()
        val cum = route.zipWithNext { a, b -> dist(a, b) }.runningFold(0.0, Double::plus)
        val total = cum.last()
        return when (loop) {
            Loop.ONCE -> cum.subList(1, cum.size - 1)                  // the final point is the finish, not a stop
            Loop.LOOP -> cum.drop(1) + (total + dist(route.last(), route.first()))
            Loop.PINGPONG -> cum.drop(1) + cum.dropLast(1).reversed().map { 2 * total - it }
        }.filter { it > 0 }
    }

    /** First waypoint stop reached when moving from [from] to [to] meters (exclusive, inclusive), or null. */
    fun nextStop(route: List<Pt>, loop: Loop, from: Double, to: Double): Double? {
        val stops = stops(route, loop).ifEmpty { return null }
        val c = cycle(route, loop)
        val cycles = if (loop == Loop.ONCE) 0L..0L else (from / c).toLong()..(to / c).toLong()
        for (k in cycles) for (s in stops) (k * c + s).let { if (it > from && it <= to) return it }
        return null
    }
}

/** Most-recent-first history: [p] goes on top, replacing any entry within 15 m of it. */
fun addRecent(history: List<Place>, p: Place, max: Int = 20) =
    (listOf(p) + history.filter { RoutePlayer.dist(it.pt, p.pt) > 15 }).take(max)

private val LATLON = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

/** "lat, lon" typed or pasted by the user (e.g. copied from Google Maps). */
fun parseLatLon(s: String): Pt? {
    val m = LATLON.matchEntire(s) ?: return null
    val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) Pt(lat, lon) else null
}

object Gpx {
    /** Track points, else route points, else waypoints. */
    fun parse(input: InputStream): List<Pt> {
        val f = DocumentBuilderFactory.newInstance()
        // User-supplied file: refuse DTDs (XXE). Not every parser supports the flag.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = f.newDocumentBuilder().parse(input)
        for (tag in listOf("trkpt", "rtept", "wpt")) {
            val nodes = doc.getElementsByTagName(tag)
            if (nodes.length > 0) return (0 until nodes.length).mapNotNull {
                val e = nodes.item(it) as Element
                val lat = e.getAttribute("lat").toDoubleOrNull(); val lon = e.getAttribute("lon").toDoubleOrNull()
                if (lat == null || lon == null) null else Pt(lat, lon)
            }
        }
        return emptyList()
    }

    fun write(points: List<Pt>, name: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="MockLocation" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk><name>").append(name.replace("&", "&amp;").replace("<", "&lt;")).append("</name><trkseg>\n")
        points.forEach { append(String.format(Locale.US, "    <trkpt lat=\"%.7f\" lon=\"%.7f\"/>\n", it.lat, it.lon)) }
        append("  </trkseg></trk>\n</gpx>\n")
    }
}

private val ID = Locale.forLanguageTag("id")

fun fmtDist(m: Double) = if (m < 1000) "${m.roundToInt()} m" else String.format(ID, "%.2f km", m / 1000)

fun fmtDur(s: Double): String {
    if (!s.isFinite() || s < 0) return "–"
    val t = s.roundToLong(); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return when { h > 0 -> "$h j $m mnt"; m > 0 -> "$m mnt $sec dtk"; else -> "$sec dtk" }
}

fun fmtPt(p: Pt) = String.format(Locale.US, "%.6f, %.6f", p.lat, p.lon)
