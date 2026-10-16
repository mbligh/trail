package net.pocketmap

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.drawable.Drawable
import android.util.Log
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Writable, standard MBTiles cache for tiles fetched at runtime.
 *
 * Tiles use the MBTiles/TMS Y convention on disk. trail_cache is deliberately
 * outside the MBTiles schema and only records FIFO insertion order for eviction.
 */
class MbTilesCache(
    private val file: File,
    private val highWaterBytes: Long = 500L * 1024 * 1024,
    private val lowWaterBytes: Long = 400L * 1024 * 1024
) : IFilesystemCache {
    private val db: SQLiteDatabase
    private var writesSinceSizeCheck = 0

    init {
        require(lowWaterBytes < highWaterBytes)
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createStandardSchema(db)
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS trail_cache (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                zoom_level INTEGER NOT NULL,
                tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL,
                UNIQUE (zoom_level, tile_column, tile_row)
            )"""
        )
    }

    @Synchronized
    override fun saveFile(
        pTileSourceInfo: ITileSource,
        pMapTileIndex: Long,
        pStream: InputStream,
        pExpirationTime: Long?
    ): Boolean {
        if (!db.isOpen) return false
        return try {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex).toLong()
            val row = xyzToTmsY(zoom, MapTileIndex.getY(pMapTileIndex).toLong())
            val data = pStream.readBytes()

            db.beginTransaction()
            try {
                ContentValues().also {
                    it.put("zoom_level", zoom)
                    it.put("tile_column", x)
                    it.put("tile_row", row)
                    it.put("tile_data", data)
                    db.insertWithOnConflict("tiles", null, it, SQLiteDatabase.CONFLICT_REPLACE)
                }
                ContentValues().also {
                    it.put("zoom_level", zoom)
                    it.put("tile_column", x)
                    it.put("tile_row", row)
                    db.insertWithOnConflict("trail_cache", null, it, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            writesSinceSizeCheck++
            if (writesSinceSizeCheck >= 64) {
                writesSinceSizeCheck = 0
                trimIfNeeded()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to store map tile", e)
            false
        }
    }

    @Synchronized
    override fun exists(pTileSourceInfo: ITileSource, pMapTileIndex: Long): Boolean =
        tileData(pMapTileIndex) != null

    @Synchronized
    override fun remove(tileSource: ITileSource, pMapTileIndex: Long): Boolean {
        if (!db.isOpen) return false
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex).toLong()
        val row = xyzToTmsY(zoom, MapTileIndex.getY(pMapTileIndex).toLong())
        val args = arrayOf(zoom.toString(), x.toString(), row.toString())
        db.beginTransaction()
        return try {
            db.delete("trail_cache", "zoom_level=? AND tile_column=? AND tile_row=?", args)
            val deleted = db.delete("tiles", "zoom_level=? AND tile_column=? AND tile_row=?", args)
            db.setTransactionSuccessful()
            deleted > 0
        } finally {
            db.endTransaction()
        }
    }

    override fun getExpirationTimestamp(pTileSource: ITileSource, pMapTileIndex: Long): Long? = null

    @Synchronized
    override fun loadTile(pTileSource: ITileSource, pMapTileIndex: Long): Drawable? {
        val data = tileData(pMapTileIndex) ?: return null
        return pTileSource.getDrawable(ByteArrayInputStream(data))
    }

    @Synchronized
    override fun onDetach() {
        if (db.isOpen) db.close()
    }

    @Synchronized
    private fun tileData(index: Long): ByteArray? {
        if (!db.isOpen) return null
        val zoom = MapTileIndex.getZoom(index)
        val x = MapTileIndex.getX(index).toLong()
        val row = xyzToTmsY(zoom, MapTileIndex.getY(index).toLong())
        db.query(
            "tiles",
            arrayOf("tile_data"),
            "zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(zoom.toString(), x.toString(), row.toString()),
            null, null, null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getBlob(0) else null
        }
    }

    private fun trimIfNeeded() {
        if (activeBytes() <= highWaterBytes) return

        while (activeBytes() > lowWaterBytes) {
            val victims = ArrayList<Triple<Int, Long, Long>>(512)
            db.rawQuery(
                "SELECT zoom_level, tile_column, tile_row FROM trail_cache ORDER BY sequence LIMIT 512",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    victims.add(Triple(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2)))
                }
            }
            if (victims.isEmpty()) break

            db.beginTransaction()
            try {
                for ((zoom, x, row) in victims) {
                    val args = arrayOf(zoom.toString(), x.toString(), row.toString())
                    db.delete("tiles", "zoom_level=? AND tile_column=? AND tile_row=?", args)
                    db.delete("trail_cache", "zoom_level=? AND tile_column=? AND tile_row=?", args)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * SQLite does not shrink the file when rows are deleted. Free pages are reused,
     * so measure live allocated pages rather than file.length() when applying the cap.
     */
    private fun activeBytes(): Long {
        val pages = pragmaLong("page_count")
        val free = pragmaLong("freelist_count")
        val pageSize = pragmaLong("page_size")
        return (pages - free).coerceAtLeast(0) * pageSize
    }

    private fun pragmaLong(name: String): Long =
        db.rawQuery("PRAGMA $name", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    companion object {
        private const val TAG = "TrailMbTiles"

        fun xyzToTmsY(zoom: Int, xyzY: Long): Long = (1L shl zoom) - 1L - xyzY

        fun createEmptyStandard(file: File) {
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { createStandardSchema(it) }
        }

        private fun createStandardSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS tiles (
                    zoom_level INTEGER NOT NULL,
                    tile_column INTEGER NOT NULL,
                    tile_row INTEGER NOT NULL,
                    tile_data BLOB NOT NULL
                )"""
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)"
            )
        }
    }
}
