package net.pocketmap

import java.util.Locale

object LocationReport {
    fun message(latitude: Double, longitude: Double): String {
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        val url = "https://www.google.com/maps/@?api=1&map_action=map&center=$lat%2C$lon&zoom=18&basemap=satellite"
        return "$lat, $lon\n$url\n\n"
    }
}
