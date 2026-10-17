package net.pocketmap

import android.location.Location
import java.io.File
import java.time.Instant

/**
 * User-created point markers associated with a recorded track.
 *
 * Pins live in a sidecar file so the existing CSV track format and line geometry remain
 * unchanged. The sidecar name is derived from the track CSV name and is ignored by the
 * recording picker, which only lists .csv files.
 */
object RecordedPins {
    data class Pin(
        val latitude: Double,
        val longitude: Double,
        val time: Long,
        val altitude: Double?,
        val accuracy: Float?
    )

    private fun sidecar(track: File) =
        File(track.parentFile, track.nameWithoutExtension + ".pins")

    fun append(track: File, location: Location) {
        sidecar(track).appendText(
            buildString {
                append(location.latitude)
                append(',')
                append(location.longitude)
                append(',')
                append(location.time)
                append(',')
                if (location.hasAltitude()) append(location.altitude)
                append(',')
                if (location.hasAccuracy()) append(location.accuracy)
                append('\n')
            }
        )
    }

    fun read(track: File): List<Pin> {
        val file = sidecar(track)
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size < 3) return@mapNotNull null
                val latitude = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val longitude = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val time = parts[2].toLongOrNull() ?: return@mapNotNull null
                if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null
                Pin(
                    latitude,
                    longitude,
                    time,
                    parts.getOrNull(3)?.toDoubleOrNull(),
                    parts.getOrNull(4)?.toFloatOrNull()
                )
            }.toList()
        }
    }

    fun addToKml(kml: String, pins: List<Pin>): String {
        if (pins.isEmpty()) return kml
        val style = """
            <Style id="recorded-pin">
              <IconStyle>
                <scale>1.1</scale>
                <Icon><href>https://maps.google.com/mapfiles/kml/pushpin/blue-pushpin.png</href></Icon>
              </IconStyle>
            </Style>
        """.trimIndent()
        val placemarks = pins.joinToString("\n") { pin ->
            val altitude = pin.altitude ?: 0.0
            """
            <Placemark>
              <name>Pin</name>
              <styleUrl>#recorded-pin</styleUrl>
              <TimeStamp><when>${Instant.ofEpochMilli(pin.time)}</when></TimeStamp>
              <Point><coordinates>${pin.longitude},${pin.latitude},$altitude</coordinates></Point>
            </Placemark>
            """.trimIndent()
        }
        val insertion = "\n$style\n$placemarks\n"
        return if (kml.contains("</Document>")) {
            kml.replaceFirst("</Document>", insertion + "</Document>")
        } else kml
    }

    fun addToGpx(gpx: String, pins: List<Pin>): String {
        if (pins.isEmpty()) return gpx
        val waypoints = pins.joinToString("\n") { pin ->
            buildString {
                append("""<wpt lat="${pin.latitude}" lon="${pin.longitude}">""")
                pin.altitude?.let { append("<ele>$it</ele>") }
                append("<time>${Instant.ofEpochMilli(pin.time)}</time>")
                append("<name>Pin</name><sym>Pin, Blue</sym></wpt>")
            }
        }
        return if (gpx.contains("<trk>")) {
            gpx.replaceFirst("<trk>", waypoints + "\n<trk>")
        } else gpx
    }
}
