package net.pocketmap

import org.junit.Assert.assertEquals
import org.junit.Test

class BasemapTest {
    @Test fun tileTemplatesRespectProviderCoordinateOrder() {
        assertEquals("https://tile.openstreetmap.org/15/9573/12316.png", Basemap("osm", "https://tile.openstreetmap.org/{z}/{x}/{y}.png").tileUrl(15, 9573, 12316))
        assertEquals("https://maps.nj.gov/tile/15/12316/9573", Basemap("nj", "https://maps.nj.gov/tile/{z}/{y}/{x}").tileUrl(15, 9573, 12316))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingCoordinates() {
        Basemap("broken", "https://example.org/{z}/{x}.png")
    }
}
