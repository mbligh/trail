package net.pocketmap

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.ReusableBitmapDrawable
import org.osmdroid.tileprovider.ExpirableBitmapDrawable
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex

/**
 * Allows display zoom through 19 without ever requesting non-existent network tiles.
 * Above a source's native maximum zoom, the corresponding native parent tile is
 * cropped and enlarged in memory. Unlike the old implementation, tile completion
 * never clears the provider cache.
 */
class OverzoomTileProvider(context: Context, source: ITileSource) : MapTileProviderBasic(context, source) {
    override fun getMaximumZoomLevel() = 19

    override fun getMapTile(index: Long): Drawable? {
        val zoom = MapTileIndex.getZoom(index)
        val nativeZoom = tileSource.maximumZoomLevel
        if (zoom <= nativeZoom) return super.getMapTile(index)

        val diff = zoom - nativeZoom
        val parent = MapTileIndex.getTileIndex(
            nativeZoom,
            MapTileIndex.getX(index) shr diff,
            MapTileIndex.getY(index) shr diff
        )

        val original = super.getMapTile(parent) as? BitmapDrawable ?: return null
        tileCache.getMapTile(index)?.let { cached ->
            if (ExpirableBitmapDrawable.getState(original) == ExpirableBitmapDrawable.UP_TO_DATE) return cached
        }
        val bitmap = MapTileApproximater.approximateTileFromLowerZoom(original, index, diff) ?: return null
        return ReusableBitmapDrawable(bitmap).also {
            putTileIntoCache(index, it, ExpirableBitmapDrawable.SCALED)
        }
    }

    override fun mapTileRequestCompleted(state: org.osmdroid.tileprovider.MapTileRequestState, drawable: Drawable) {
        // Critical: do not clear the cache here. The previous implementation did,
        // causing every arriving parent tile to invalidate the whole map repeatedly.
        super.mapTileRequestCompleted(state, drawable)
    }
}
