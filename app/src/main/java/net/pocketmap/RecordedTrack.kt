package net.pocketmap

import java.io.File
import java.time.Instant

object RecordedTrack {
    data class Point(val latitude: Double, val longitude: Double, val time: Long, val altitude: Double?)
    fun read(file: File): List<Point> = if (!file.exists()) emptyList() else file.useLines { lines ->
        lines.mapNotNull { line -> runCatching {
            val p = line.split(',')
            Point(p[0].toDouble(), p[1].toDouble(), p[2].toLong(), p.getOrNull(3)?.takeIf { it.isNotBlank() }?.toDouble())
        }.getOrNull() }.toList()
    }
    fun gpx(name: String, points: List<Point>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Trail\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>")
        append(name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        append("</name><trkseg>\n")
        points.forEachIndexed { index, p ->
            if (index > 0 && p.time - points[index - 1].time > 90_000) append("</trkseg><trkseg>\n")
            append("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">")
            p.altitude?.let { append("<ele>$it</ele>") }
            append("<time>${Instant.ofEpochMilli(p.time)}</time></trkpt>\n")
        }
        append("</trkseg></trk></gpx>\n")
    }
}
