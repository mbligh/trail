package net.pocketmap

import android.content.Context
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileAssetsProvider
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileAreaBorderComputer
import org.osmdroid.util.MapTileAreaZoomComputer
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * Trail's provider chain:
 *   Android assets -> dynamic/seed MBTiles -> lower-zoom approximation -> network
 *
 * The downloader writes only to the dynamic MBTiles cache.
 */
class TrailTileProvider private constructor(
    private val source: ITileSource,
    registerReceiver: IRegisterReceiver,
    providers: Array<MapTileModuleProviderBase>,
    private val writer: MbTilesCache,
    private val networkAvailability: NetworkAvailabliltyCheck
) : MapTileProviderArray(source, registerReceiver, providers) {

    init {
        getTileCache().getProtectedTileComputers().add(MapTileAreaZoomComputer(-1))
        getTileCache().getProtectedTileComputers().add(MapTileAreaBorderComputer(1))
        getTileCache().setAutoEnsureCapacity(false)
        getTileCache().setStressedMemory(false)

        // Provider order here mirrors the request chain and avoids downloading a tile
        // merely to pre-cache it when the seed/cache MBTiles already contains it.
        providers.filterIsInstance<MapTileAssetsProvider>().firstOrNull()?.let {
            getTileCache().getPreCache().addProvider(it)
        }
        providers.filterIsInstance<MapTileFileArchiveProvider>().firstOrNull()?.let {
            getTileCache().getPreCache().addProvider(it)
        }
        providers.filterIsInstance<MapTileDownloader>().firstOrNull()?.let {
            getTileCache().getPreCache().addProvider(it)
        }
        getTileCache().getProtectedTileContainers().add(this)
    }

    override fun getTileWriter(): IFilesystemCache = writer

    override fun isDowngradedMode(pMapTileIndex: Long): Boolean {
        if (!networkAvailability.networkAvailable || !useDataConnection()) return true
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        return zoom < source.minimumZoomLevel || zoom > source.maximumZoomLevel
    }

    companion object {
        fun create(
            context: Context,
            tileSource: ITileSource,
            seedFile: File,
            cacheFile: File
        ): TrailTileProvider {
            val appContext = context.applicationContext
            val registerReceiver = SimpleRegisterReceiver(appContext)
            val network = NetworkAvailabliltyCheck(appContext)
            val writer = MbTilesCache(cacheFile)

            val assets = MapTileAssetsProvider(registerReceiver, appContext.assets, tileSource)

            val seedArchive = MBTilesFileArchive.getDatabaseFileArchive(seedFile)
            val cacheArchive = MBTilesFileArchive.getDatabaseFileArchive(cacheFile)
            // MapTileFileArchiveProvider reverses the supplied array, so seed then cache
            // results in cache-first lookup, followed by immutable seed data.
            val archive = MapTileFileArchiveProvider(
                registerReceiver,
                tileSource,
                arrayOf<IArchiveFile>(seedArchive, cacheArchive)
            )

            val downloader = MapTileDownloader(tileSource, writer, network)
            val approximater = MapTileApproximater().apply {
                addProvider(assets)
                addProvider(archive)
                // At overzoom levels, fetch the nearest native parent if it is not
                // already cached. This prevents different screen tiles from being
                // synthesized from different lower zoom levels.
                addProvider(downloader)
            }

            return TrailTileProvider(
                tileSource,
                registerReceiver,
                // Prefer a real native tile over an approximation. For requests above
                // the source max zoom the downloader is skipped automatically, so the
                // approximater becomes the fallback.
                arrayOf(assets, archive, downloader, approximater),
                writer,
                network
            )
        }
    }
}
