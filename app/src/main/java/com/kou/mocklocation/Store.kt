package com.kou.mocklocation

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Place(val name: String, val pt: Pt)
data class SavedRoute(val name: String, val points: List<Pt>)
data class Camera(val lat: Double, val lon: Double, val zoom: Double)
data class Preset(val label: String, val kmh: Float)

val SPEEDS = listOf(Preset("Jalan", 5f), Preset("Lari", 11f), Preset("Sepeda", 20f), Preset("Motor", 45f), Preset("Mobil", 70f))

/** Tiny SharedPreferences + JSON persistence. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("mock", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOf(key: String, def: E): E =
        runCatching { enumValueOf<E>(p.getString(key, def.name)!!) }.getOrDefault(def)

    var mode: Mode
        get() = enumOf("mode", Mode.ROUTE)
        set(v) = p.edit { putString("mode", v.name) }
    var loop: Loop
        get() = enumOf("loop", Loop.ONCE)
        set(v) = p.edit { putString("loop", v.name) }
    var mapStyle: MapStyle
        get() = enumOf("style", MapStyle.DARK)
        set(v) = p.edit { putString("style", v.name) }
    var speedKmh: Float
        get() = p.getFloat("speed", 5f)
        set(v) = p.edit { putFloat("speed", v) }
    var humanize: Boolean
        get() = p.getBoolean("humanize", true)
        set(v) = p.edit { putBoolean("humanize", v) }
    var floating: Boolean
        get() = p.getBoolean("floating", false)
        set(v) = p.edit { putBoolean("floating", v) }
    var draft: List<Pt>
        get() = pts(JSONArray(p.getString("draft", "[]")))
        set(v) = p.edit { putString("draft", json(v).toString()) }
    var target: Pt?
        get() = pts(JSONArray(p.getString("target", "[]"))).firstOrNull()
        set(v) = p.edit { putString("target", json(listOfNotNull(v)).toString()) }
    var camera: Camera
        get() = Camera(p.getFloat("camLat", -6.1754f).toDouble(), p.getFloat("camLon", 106.8272f).toDouble(), p.getFloat("camZoom", 15f).toDouble())
        set(v) = p.edit { putFloat("camLat", v.lat.toFloat()); putFloat("camLon", v.lon.toFloat()); putFloat("camZoom", v.zoom.toFloat()) }

    var favorites: List<Place>
        get() = objs("favorites").map { Place(it.getString("n"), Pt(it.getDouble("lat"), it.getDouble("lon"))) }
        set(v) = p.edit { putString("favorites", JSONArray(v.map { JSONObject().put("n", it.name).put("lat", it.pt.lat).put("lon", it.pt.lon) }).toString()) }
    var routes: List<SavedRoute>
        get() = objs("routes").map { SavedRoute(it.getString("n"), pts(it.getJSONArray("p"))) }
        set(v) = p.edit { putString("routes", JSONArray(v.map { JSONObject().put("n", it.name).put("p", json(it.points)) }).toString()) }

    private fun objs(key: String) = runCatching {
        JSONArray(p.getString(key, "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    }.getOrDefault(emptyList())

    private fun json(pts: List<Pt>) = JSONArray(pts.map { JSONArray().put(it.lat).put(it.lon) })
    private fun pts(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it).let { p -> Pt(p.getDouble(0), p.getDouble(1)) } }
}
