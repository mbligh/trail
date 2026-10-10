package net.pocketmap

import android.content.res.AssetManager
import org.json.JSONArray
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import java.net.URI
import java.security.MessageDigest

data class Basemap(val name: String, val url: String, val attribution: String = name, val attributionUrl: String? = null) {
    init {
        require(name.isNotBlank()) { "Basemap name is required" }
        require(listOf("{z}", "{x}", "{y}").all { url.contains(it) }) { "Basemap URL must include {z}, {x}, and {y}" }
        require(URI(tileUrl(0, 0, 0)).scheme == "https") { "Basemap URL must use HTTPS" }
    }
    fun tileUrl(z: Int, x: Int, y: Int) = url.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
    fun tileSource(): XYTileSource {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return object : XYTileSource("Trail-$digest", 0, 19, 256, ".png", arrayOf(url), attribution) {
            override fun getTileURLString(index: Long): String = tileUrl(MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
        }
    }
    companion object {
        fun load(assets: AssetManager): List<Basemap> {
            val json = JSONArray(assets.open("basemaps.json").bufferedReader().use { it.readText() })
            val maps = (0 until json.length()).map { index ->
                val item = json.getJSONObject(index)
                Basemap(item.getString("name"), item.getString("url"), item.optString("attribution", item.getString("name")), item.optString("attributionUrl").takeIf { it.isNotBlank() })
            }
            require(maps.isNotEmpty() && maps.map { it.name }.distinct().size == maps.size) { "Basemaps must have unique names and at least one entry" }
            return maps
        }
    }
}
