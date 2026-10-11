package net.pocketmap
import org.junit.Assert.*
import org.junit.Test
class HeadingMathTest {
    @Test fun smoothingCrossesNorthByShortestRoute() {
        assertEquals(0f, HeadingMath.smooth(359f, 1f, 0.5f), 0.001f)
        assertEquals(0f, HeadingMath.smooth(1f, 359f, 0.5f), 0.001f)
        assertEquals(355f, HeadingMath.smooth(350f, 10f, 0.25f), 0.001f)
    }
    @Test fun projectedScreenEdgeHasCorrectCompassDirection() {
        assertEquals(0f, HeadingMath.azimuth(0f, 1f)!!, 0.001f)
        assertEquals(90f, HeadingMath.azimuth(1f, 0f)!!, 0.001f)
        assertEquals(180f, HeadingMath.azimuth(0f, -1f)!!, 0.001f)
        assertEquals(270f, HeadingMath.azimuth(-1f, 0f)!!, 0.001f)
        assertNull(HeadingMath.azimuth(0.01f, 0.01f))
    }
}
