package net.pocketmap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

class LocationReportTest {
    @Test fun coordinatesAndSatelliteLinkMatchRegardlessOfLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val lines = LocationReport.message(40.6727254, -74.7490004).lines()
            assertEquals("40.672725, -74.749000", lines[0])
            val uri = URI(lines[1])
            assertEquals("https", uri.scheme)
            assertEquals("www.google.com", uri.host)
            val params = uri.rawQuery.split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals("40.672725,-74.749000", params["center"])
            assertEquals("satellite", params["basemap"])
            assertEquals("map", params["map_action"])
            assertEquals("1", params["api"])
        } finally { Locale.setDefault(previous) }
    }
}
