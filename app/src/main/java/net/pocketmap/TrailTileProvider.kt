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
 *   assets -> dynamic/seed MBTiles -> cached approximation -> network
 *          -> network-parent approximation (overzoom fallback)
 *
 * The first approximation stage is intentionally before the downloader so zooming can
 * immediately display a scaled tile from data already on the device. The final stage is
 * able to fetch a native parent tile when the requested zoom is above the source maximum.
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
        // A SCALED in-memory tile is only "best we can do" when offline.
        // While online, even an overzoom request can be improved: the
        // approximater can fetch the nearest native parent tile and rescale it.
        return !networkAvailability.networkAvailable || !useDataConnection()
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

            // Fast visual fallback: use only tiles already present locally. A SCALED
            // result is treated as temporary while online, so the provider chain keeps
            // going and replaces it with a better tile when one becomes available.
            val cachedApproximater = MapTileApproximater().apply {
                addProvider(assets)
                addProvider(archive)
            }

            // Final overzoom fallback. If the requested zoom is above the source's
            // native maximum, MapTileProviderArray skips the exact downloader. This
            // approximater can then fetch the nearest native parent and scale it.
            val networkParentApproximater = MapTileApproximater().apply {
                addProvider(assets)
                addProvider(archive)
                addProvider(downloader)
            }

            return TrailTileProvider(
                tileSource,
                registerReceiver,
                arrayOf(
                    assets,
                    archive,
                    cachedApproximater,
                    downloader,
                    networkParentApproximater
                ),
                writer,
                network
            )
        }
    }
}
