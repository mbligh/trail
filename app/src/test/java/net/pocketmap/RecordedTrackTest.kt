package net.pocketmap
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
class RecordedTrackTest {
    @Test fun exportsValidGpxWithTimeElevationAndEscapedName() {
        val text = RecordedTrack.gpx("Walk & <woods>", listOf(RecordedTrack.Point(40.7, -74.7, 0L, 100.5)))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
        assertEquals("Walk & <woods>", doc.getElementsByTagName("name").item(0).textContent)
        assertEquals("1970-01-01T00:00:00Z", doc.getElementsByTagName("time").item(0).textContent)
        assertEquals("100.5", doc.getElementsByTagName("ele").item(0).textContent)
        assertEquals("40.7", doc.getElementsByTagName("trkpt").item(0).attributes.getNamedItem("lat").nodeValue)
    }
    @Test fun breaksLineAcrossLongGpsOutage() {
        val text = RecordedTrack.gpx("Walk", listOf(RecordedTrack.Point(40.0, -74.0, 0L, null), RecordedTrack.Point(41.0, -74.0, 120_000L, null)))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
        assertEquals(2, doc.getElementsByTagName("trkseg").length)
    }
    @Test fun recoversSavedPointsAndIgnoresIncompleteWrite() {
        val file = java.io.File.createTempFile("track", ".csv")
        try { file.writeText("40.7,-74.7,1000,12.0,4.5\n40.8,-74.8,31000,,12.0\nbroken"); val points = RecordedTrack.read(file); assertEquals(2, points.size); assertEquals(4.5f, points[0].accuracy); assertEquals(12.0f, points[1].accuracy) } finally { file.delete() }
    }
}
