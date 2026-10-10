package net.pocketmap

import android.util.AtomicFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class TrailRepository(directory: File) {
    private val cache = AtomicFile(File(directory, "trails.kml"))
    companion object {
        const val MAP_URL = "https://www.google.com/maps/d/kml?mid=1zy-nQvF6-6P7JSs3paMU8byWHoE&forcekml=1"
    }
    fun cached(): TrailKml.Document? = try { TrailKml.parse(cache.readFully()) } catch (_: Exception) { null }
    fun refresh(): TrailKml.Document {
        val connection = URL(MAP_URL).openConnection() as HttpURLConnection
        val bytes = try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Trail/1.2 (net.pocketmap)")
            connection.useCaches = false
            require(connection.responseCode == 200) { "Map download failed (${connection.responseCode})" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count != -1) {
                    require(output.size() + count <= TrailKml.MAX_BYTES) { "Map file is too large" }
                    output.write(buffer, 0, count)
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    count = input.read(buffer)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
        val document = TrailKml.parse(bytes) // Only replace the cache after validation succeeds.
        val stream = cache.startWrite()
        try { stream.write(bytes); cache.finishWrite(stream) }
        catch (error: Exception) { cache.failWrite(stream); throw error }
        return document
    }
}
