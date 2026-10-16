package net.pocketmap

import android.content.Context
import java.io.File
import java.io.FileNotFoundException

data class BasemapTileStores(val seedFile: File, val cacheFile: File)

object BasemapTileStorage {
    private const val PREFS = "mbtiles"
    private const val VERSION_KEY = "seed_version"

    @Synchronized
    fun prepare(context: Context, basemap: Basemap): BasemapTileStores {
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: "unknown"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (prefs.getString(VERSION_KEY, null) != version) {
            File(context.filesDir, "maps").deleteRecursively()
            File(context.filesDir, "osmdroid").deleteRecursively()
            File(context.cacheDir, "tiles").deleteRecursively()
            prefs.edit().putString(VERSION_KEY, version).commit()
        }

        val dir = File(context.filesDir, "maps").apply { mkdirs() }
        val safeName = basemap.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val seed = File(dir, "$safeName-seed.mbtiles")
        val cache = File(dir, "$safeName-cache.mbtiles")

        if (!seed.exists()) {
            try {
                context.assets.open("maps/$safeName.mbtiles").use { input ->
                    seed.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: FileNotFoundException) {
                seed.createNewFile()
            }
        }
        if (seed.length() == 0L) MbTilesCache.createEmptyStandard(seed)

        // Creating the writer later will add Trail's FIFO bookkeeping table.
        // Keep the cache file distinct from the immutable seed archive.
        if (!cache.exists()) MbTilesCache.createEmptyStandard(cache)

        return BasemapTileStores(seed, cache)
    }
}
