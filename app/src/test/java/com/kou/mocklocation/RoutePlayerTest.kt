package com.kou.mocklocation

import org.junit.Assert.*
import org.junit.Test

class RoutePlayerTest {
    private val route = listOf(Pt(0.0, 0.0), Pt(0.0, 0.01), Pt(0.01, 0.01))
    private val total = RoutePlayer.total(route)

    private fun assertPt(e: Pt, a: Fix) {
        assertEquals(e.lat, a.lat, 1e-7); assertEquals(e.lon, a.lon, 1e-7)
    }

    @Test fun startsAtFirstPoint() = assertPt(route[0], RoutePlayer.at(route, 0.0))

    @Test fun onceClampsAtEnd() = assertPt(route[2], RoutePlayer.along(route, total * 5, Loop.ONCE))

    @Test fun movesExpectedDistance() {
        val f = RoutePlayer.at(route, 100.0)
        assertEquals(100.0, RoutePlayer.dist(route[0], f.pt), 0.5)
        assertEquals(90f, f.bearing, 0.5f)
    }

    @Test fun loopReturnsToStart() {
        val cycle = RoutePlayer.cycle(route, Loop.LOOP)
        assertTrue(cycle > total)
        assertPt(route[0], RoutePlayer.along(route, cycle, Loop.LOOP))
        assertPt(route[2], RoutePlayer.along(route, cycle + total, Loop.LOOP))
    }

    @Test fun pingPongComesBack() {
        assertPt(route[2], RoutePlayer.along(route, total, Loop.PINGPONG))
        assertPt(route[0], RoutePlayer.along(route, 2 * total - 1e-9, Loop.PINGPONG))
        assertTrue(RoutePlayer.along(route, total + 50, Loop.PINGPONG).bearing in 170f..190f) // heading back south
    }

    @Test fun singlePointAndDuplicates() {
        assertPt(route[0], RoutePlayer.along(listOf(route[0]), 999.0, Loop.LOOP))
        assertPt(route[0], RoutePlayer.along(listOf(route[0], route[0]), 10.0, Loop.PINGPONG))
    }

    @Test fun moveMatchesDist() {
        val p = RoutePlayer.move(Pt(-6.2, 106.8), 45.0, 250.0)
        assertEquals(250.0, RoutePlayer.dist(Pt(-6.2, 106.8), p), 0.01)
        assertEquals(45f, RoutePlayer.bearing(Pt(-6.2, 106.8), p), 0.1f)
    }

    @Test fun parsesCoordinates() {
        assertEquals(Pt(-6.175392, 106.827153), parseLatLon("-6.175392, 106.827153"))
        assertEquals(Pt(1.0, 2.0), parseLatLon(" 1 2 "))
        assertNull(parseLatLon("Monas"))
        assertNull(parseLatLon("95, 10"))
    }

    @Test fun gpxRoundTrip() {
        val xml = Gpx.write(route, "a & <b>")
        assertEquals(route, Gpx.parse(xml.byteInputStream()))
    }

    @Test fun gpxFallsBackToWaypoints() {
        val xml = """<gpx><wpt lat="1.5" lon="2.5"/><wpt lat="3" lon="4"/></gpx>"""
        assertEquals(listOf(Pt(1.5, 2.5), Pt(3.0, 4.0)), Gpx.parse(xml.byteInputStream()))
    }

    @Test fun formats() {
        assertEquals("850 m", fmtDist(850.2))
        assertEquals("1,25 km", fmtDist(1250.0))
        assertEquals("2 mnt 5 dtk", fmtDur(125.0))
        assertEquals("1 j 1 mnt", fmtDur(3660.0))
    }
}
