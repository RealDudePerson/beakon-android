package com.beakon.companion.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.widget.RemoteViews
import com.beakon.companion.MainActivity
import com.beakon.companion.R
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The widget's map, rendered as a **bitmap**.
 *
 * A `RemoteViews` widget cannot host a `WebView`, so the map is OpenStreetMap
 * tiles fetched over HTTPS, decoded, and composited with a marker pin in code.
 * The tile server is platform-trusted, so the fetch uses OkHttp's default
 * (platform) trust — the same "no cleartext, no trust-all" rule as the app, with
 * no pinning, because pinning applies only to the user's own instance.
 *
 * The map is a **grid of tiles** stitched around the point, not one tile: a
 * single 256 px tile cannot cover a 480 px-wide bitmap, and a point near its
 * tile's edge left most of the frame flat grey with the pin on the edge of the
 * streets. The grid covers the whole bitmap whatever the point's position in
 * its tile, and the pin lands at the centre by construction.
 *
 * A tile that fails to fetch or decode is skipped; only when *every* tile fails
 * does the caller show a placeholder, so the widget always shows text plus
 * *something*, never a blank rectangle.
 */
object WidgetRenderer {

    /** Zoom level for the map. 15 shows a neighbourhood. */
    const val ZOOM = 15

    /** OSM tiles are 256x256 PNGs. */
    const val TILE_SIZE = 256

    /** Bitmap size pushed to the widget's ImageView when the widget's own size is unknown. */
    const val MAP_WIDTH = 480
    const val MAP_HEIGHT = 240

    /**
     * The app's existing descriptive User-Agent (see `NominatimGeocoderLookup`);
     * the OSM tile policy, like Nominatim's, requires one.
     */
    const val USER_AGENT = "Beakon Companion/1.6.1"

    private const val TILE_URL = "https://tile.openstreetmap.org"

    /** Web-Mercator tile column for a longitude. Pure, so it is unit-tested. */
    fun tileX(lon: Double, zoom: Int = ZOOM): Int =
        floor(normalizedX(lon, zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    /** Web-Mercator tile row for a latitude. Pure, so it is unit-tested. */
    fun tileY(lat: Double, zoom: Int = ZOOM): Int =
        floor(normalizedY(lat, zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    /** The point's horizontal fraction (0..1) within its tile. */
    fun fractionX(lon: Double, zoom: Int = ZOOM): Double {
        val x = normalizedX(lon, zoom)
        return x - floor(x)
    }

    /** The point's vertical fraction (0..1) within its tile. */
    fun fractionY(lat: Double, zoom: Int = ZOOM): Double {
        val y = normalizedY(lat, zoom)
        return y - floor(y)
    }

    private fun normalizedX(lon: Double, zoom: Int): Double {
        val n = 1 shl zoom
        val clamped = lon.coerceIn(-180.0, 180.0)
        return (clamped + 180.0) / 360.0 * n
    }

    private fun normalizedY(lat: Double, zoom: Int): Double {
        val n = 1 shl zoom
        // Web Mercator is undefined at the poles; clamp to the usual limit.
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val rad = Math.toRadians(clamped)
        return (1.0 - ln(kotlin.math.tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0 * n
    }

    /** The tile URL for a point, e.g. `https://tile.openstreetmap.org/15/9647/12306.png`. */
    fun tileUrl(lat: Double, lon: Double, zoom: Int = ZOOM): String =
        "$TILE_URL/$zoom/${tileX(lon, zoom)}/${tileY(lat, zoom)}.png"

    /** The tile URL for tile coordinates, e.g. `https://tile.openstreetmap.org/15/9647/12306.png`. */
    fun tileUrlAt(tileX: Int, tileY: Int, zoom: Int = ZOOM): String =
        "$TILE_URL/$zoom/$tileX/$tileY.png"

    /** A tile's position in the output bitmap: which tile, and where its top-left corner goes. */
    data class TilePlacement(
        val tileX: Int,
        val tileY: Int,
        val destLeft: Float,
        val destTop: Float,
    )

    /** The point's horizontal world-pixel coordinate at [zoom] (256 px per tile). Pure. */
    fun worldPixelX(lon: Double, zoom: Int = ZOOM): Double = normalizedX(lon, zoom) * TILE_SIZE

    /** The point's vertical world-pixel coordinate at [zoom] (256 px per tile). Pure. */
    fun worldPixelY(lat: Double, zoom: Int = ZOOM): Double = normalizedY(lat, zoom) * TILE_SIZE

    /**
     * Every tile needed to cover a [width]x[height] bitmap centred on the point.
     * Pure, so it is unit-tested: the point's own tile must cover the canvas
     * centre, and every canvas corner must fall inside some placement — that is
     * the property the single-tile version broke at tile edges.
     */
    fun gridTiles(
        lat: Double,
        lon: Double,
        width: Int,
        height: Int,
        zoom: Int = ZOOM,
    ): List<TilePlacement> {
        val maxIndex = (1 shl zoom) - 1
        val cx = worldPixelX(lon, zoom)
        val cy = worldPixelY(lat, zoom)
        val leftEdge = cx - width / 2.0
        val topEdge = cy - height / 2.0
        val x0 = floor(leftEdge / TILE_SIZE).toInt().coerceIn(0, maxIndex)
        val x1 = floor((leftEdge + width - 1) / TILE_SIZE).toInt().coerceIn(0, maxIndex)
        val y0 = floor(topEdge / TILE_SIZE).toInt().coerceIn(0, maxIndex)
        val y1 = floor((topEdge + height - 1) / TILE_SIZE).toInt().coerceIn(0, maxIndex)
        return buildList {
            for (ty in y0..y1) {
                for (tx in x0..x1) {
                    add(
                        TilePlacement(
                            tx,
                            ty,
                            (tx * TILE_SIZE - leftEdge).toFloat(),
                            (ty * TILE_SIZE - topEdge).toFloat(),
                        ),
                    )
                }
            }
        }
    }

    /** A client for external tile fetches: platform trust, bounded timeouts. */
    fun tileClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches and decodes the OSM tile for a point. Returns null on any failure
     * (network, TLS, non-200, undecodable body); the caller shows a placeholder.
     * Blocking — call off the main thread.
     */
    fun fetchTile(
        client: OkHttpClient,
        lat: Double,
        lon: Double,
        zoom: Int = ZOOM,
    ): Bitmap? = fetchTileAt(client, tileX(lon, zoom), tileY(lat, zoom), zoom)

    /**
     * Fetches and decodes one tile by its coordinates. Returns null on any
     * failure; the caller skips a missing tile and keeps the rest of the grid.
     * Blocking — call off the main thread.
     */
    fun fetchTileAt(
        client: OkHttpClient,
        tileX: Int,
        tileY: Int,
        zoom: Int = ZOOM,
    ): Bitmap? {
        val request = Request.Builder()
            .url(tileUrlAt(tileX, tileY, zoom))
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetches the grid covering a [width]x[height] bitmap centred on the point.
     * Only successfully decoded tiles are returned, keyed by `(tileX, tileY)`;
     * a failed tile is simply absent and the composer leaves its area the
     * neutral background. An empty map means every tile failed. Blocking — call
     * off the main thread. A handful of tiles per refresh (at most ~18 for the
     * largest capped bitmap) is ordinary tile use, not bulk downloading.
     */
    fun fetchTileGrid(
        client: OkHttpClient,
        lat: Double,
        lon: Double,
        width: Int,
        height: Int,
        zoom: Int = ZOOM,
    ): Map<Pair<Int, Int>, Bitmap> {
        val fetched = mutableMapOf<Pair<Int, Int>, Bitmap>()
        for (placement in gridTiles(lat, lon, width, height, zoom)) {
            val key = placement.tileX to placement.tileY
            if (key !in fetched) {
                fetchTileAt(client, placement.tileX, placement.tileY, zoom)
                    ?.let { fetched[key] = it }
            }
        }
        return fetched
    }

    /**
     * Composites the tile grid with a marker pin at the point, centred in a
     * [width]x[height] bitmap. Any area no tile covers is filled with a neutral
     * colour, so the result is never transparent. When [stale] is true the map
     * is washed out (a translucent white overlay) while the pin stays at full
     * strength, so an old fix reads as old instead of current.
     */
    fun composeGrid(
        tiles: Map<Pair<Int, Int>, Bitmap>,
        lat: Double,
        lon: Double,
        width: Int = MAP_WIDTH,
        height: Int = MAP_HEIGHT,
        zoom: Int = ZOOM,
        stale: Boolean = false,
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(0xE8, 0xE8, 0xE8))

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (placement in gridTiles(lat, lon, width, height, zoom)) {
            val tile = tiles[placement.tileX to placement.tileY] ?: continue
            val destination = RectF(
                placement.destLeft,
                placement.destTop,
                placement.destLeft + tile.width,
                placement.destTop + tile.height,
            )
            canvas.drawBitmap(tile, null, destination, paint)
        }

        if (stale) {
            canvas.drawColor(Color.argb(0x59, 0xFF, 0xFF, 0xFF))
        }

        drawMarker(canvas, width / 2f, height / 2f)
        return output
    }

    /**
     * The image shown when the map cannot be drawn. Never blank: a neutral
     * background plus a short "map unavailable" label.
     */
    fun placeholder(width: Int = MAP_WIDTH, height: Int = MAP_HEIGHT): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(0xDD, 0xDD, 0xDD))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x66, 0x66, 0x66)
            textAlign = Paint.Align.CENTER
            textSize = (minOf(width, height) / 10f).coerceAtLeast(12f)
        }
        val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText("map unavailable", width / 2f, baseline, text)
        return output
    }

    private fun drawMarker(canvas: Canvas, cx: Float, cy: Float) {
        val radius = minOf(canvas.width, canvas.height) / 16f
        // Soft shadow under the pin tip, so it lifts off the map.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x55, 0x00, 0x00, 0x00)
        }
        canvas.drawCircle(cx, cy + radius * 0.35f, radius * 0.75f, shadow)
        // White halo so the red pin reads on any map background.
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy - radius, radius * 1.35f, halo)
        val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD3, 0x2F, 0x2F) }
        canvas.drawCircle(cx, cy - radius, radius, pin)
        val tail = Path().apply {
            moveTo(cx - radius * 0.65f, cy - radius * 0.55f)
            lineTo(cx + radius * 0.65f, cy - radius * 0.55f)
            lineTo(cx, cy)
            close()
        }
        canvas.drawPath(tail, pin)
        val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy - radius, radius * 0.42f, centre)
    }

    /**
     * Builds the widget's `RemoteViews`: the map bitmap, the display name and the
     * age string, plus an optional message for the empty/error states. The whole
     * widget is one tap target opening the person's map (D3). When [stale] is
     * true the age text is muted and a "Stale location" note is shown (unless
     * another message already says more), so an old fix never reads as current.
     */
    fun remoteViews(
        context: Context,
        appWidgetId: Int,
        name: String,
        age: String,
        message: String?,
        map: Bitmap,
        tapUrl: String?,
        stale: Boolean = false,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.beakon_widget)
        views.setImageViewBitmap(R.id.widget_map, map)
        views.setTextViewText(R.id.widget_name, name)
        views.setTextViewText(R.id.widget_age, age)
        if (stale) {
            views.setTextColor(R.id.widget_age, Color.rgb(0x9E, 0x9E, 0x9E))
        }
        val note = message ?: if (stale) "Stale location" else null
        if (note.isNullOrBlank()) {
            views.setViewVisibility(R.id.widget_message, android.view.View.GONE)
        } else {
            views.setTextViewText(R.id.widget_message, note)
            views.setViewVisibility(R.id.widget_message, android.view.View.VISIBLE)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (!tapUrl.isNullOrBlank()) data = Uri.parse(tapUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pending)
        return views
    }

    /** Web-Mercator latitude limit, matching the OSM tile scheme. */
    private const val MAX_LATITUDE = 85.05112878
}
