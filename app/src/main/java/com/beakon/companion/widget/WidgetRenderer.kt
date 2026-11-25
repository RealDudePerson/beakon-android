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
 * A `RemoteViews` widget cannot host a `WebView`, so the map is an OpenStreetMap
 * tile fetched over HTTPS, decoded, and composited with a marker pin in code.
 * The tile server is platform-trusted, so the fetch uses OkHttp's default
 * (platform) trust — the same "no cleartext, no trust-all" rule as the app, with
 * no pinning, because pinning applies only to the user's own instance.
 *
 * A tile that fails to fetch or decode is replaced by a visible placeholder, so
 * the widget always shows text plus *something*, never a blank rectangle.
 */
object WidgetRenderer {

    /** Zoom level for the single-tile map. 15 shows a neighbourhood. */
    const val ZOOM = 15

    /** OSM tiles are 256x256 PNGs. */
    const val TILE_SIZE = 256

    /** Bitmap size pushed to the widget's ImageView. */
    const val MAP_WIDTH = 480
    const val MAP_HEIGHT = 240

    /**
     * The app's existing descriptive User-Agent (see `NominatimGeocoderLookup`);
     * the OSM tile policy, like Nominatim's, requires one.
     */
    const val USER_AGENT = "Beakon Companion/1.1"

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
    ): Bitmap? {
        val request = Request.Builder()
            .url(tileUrl(lat, lon, zoom))
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
     * Composites [tile] with a marker pin at the point, centred in a
     * [width]x[height] bitmap. Any area the tile does not cover is filled with a
     * neutral colour, so the result is never transparent.
     */
    fun compose(
        tile: Bitmap,
        lat: Double,
        lon: Double,
        width: Int = MAP_WIDTH,
        height: Int = MAP_HEIGHT,
        zoom: Int = ZOOM,
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(0xE8, 0xE8, 0xE8))

        val fx = fractionX(lon, zoom)
        val fy = fractionY(lat, zoom)
        val left = width / 2f - (fx * tile.width).toFloat()
        val top = height / 2f - (fy * tile.height).toFloat()
        val destination = RectF(left, top, left + tile.width, top + tile.height)
        canvas.drawBitmap(tile, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))

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
     * widget is one tap target opening the person's map (D3).
     */
    fun remoteViews(
        context: Context,
        appWidgetId: Int,
        name: String,
        age: String,
        message: String?,
        map: Bitmap,
        tapUrl: String?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.beakon_widget)
        views.setImageViewBitmap(R.id.widget_map, map)
        views.setTextViewText(R.id.widget_name, name)
        views.setTextViewText(R.id.widget_age, age)
        if (message.isNullOrBlank()) {
            views.setViewVisibility(R.id.widget_message, android.view.View.GONE)
        } else {
            views.setTextViewText(R.id.widget_message, message)
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
