package com.foton.crawlermetraj

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Pole kamera analiz motoru.
 * Crawler VideoAnalyzer'dan tamamen ayrıdır; crawler sayaç motoruna dokunmaz.
 *
 * Referans OSD düzeni 1600x959 görüntüden oranlanır:
 * - Üst bilgi bloğu: iş/hat/boru bilgileri
 * - Sol alt: Boylam + Enlem + distance
 * - Sağ alt: tarih-saat
 */
class PoleAnalyzer(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun analyzeVideo(uri: Uri, fileName: String): PoleVideoObservation = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frameTimeUs = when {
                durationMs == null -> 1_000_000L
                durationMs >= 2_000L -> 1_000_000L
                else -> max(0L, durationMs / 2L) * 1000L
            }
            val frame = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return@withContext PoleVideoObservation(
                    uri = uri.toString(), fileName = fileName, durationMs = durationMs,
                    error = "Video başlangıç karesi alınamadı"
                )

            try {
                val top = cropRatio(frame, TOP_LEFT, TOP_TOP, TOP_RIGHT, TOP_BOTTOM)
                val geo = cropRatio(frame, GEO_LEFT, GEO_TOP, GEO_RIGHT, GEO_BOTTOM)
                val time = cropRatio(frame, TIME_LEFT, TIME_TOP, TIME_RIGHT, TIME_BOTTOM)

                val topInfo = readBestTop(top)
                val geoInfo = readBestGeo(geo)
                val timestamp = readBestTimestamp(time)

                PoleVideoObservation(
                    uri = uri.toString(),
                    fileName = fileName,
                    isAdi = topInfo.isAdi,
                    tarih = topInfo.tarih,
                    yon = topInfo.yon,
                    mahal = topInfo.mahal,
                    hat = topInfo.hat,
                    boruTipi = topInfo.boruTipi,
                    boruMalzemesi = topInfo.boruMalzemesi,
                    capMm = topInfo.capMm,
                    boruDerinligi = topInfo.boruDerinligi,
                    isinSahibi = topInfo.isinSahibi,
                    operator = topInfo.operator,
                    boylam = geoInfo?.first,
                    enlem = geoInfo?.second,
                    timestampMs = timestamp,
                    durationMs = durationMs,
                    error = buildError(topInfo, geoInfo, timestamp)
                )
            } finally {
                frame.recycle()
            }
        } catch (e: Exception) {
            PoleVideoObservation(uri = uri.toString(), fileName = fileName, error = e.message ?: "Video analiz hatası")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    suspend fun analyzePhoto(uri: Uri, fileName: String): PolePhotoObservation = withContext(Dispatchers.IO) {
        try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: return@withContext PolePhotoObservation(uri.toString(), fileName, error = "Fotoğraf açılamadı")

            try {
                val geo = cropRatio(bitmap, GEO_LEFT, GEO_TOP, GEO_RIGHT, GEO_BOTTOM)
                val time = cropRatio(bitmap, TIME_LEFT, TIME_TOP, TIME_RIGHT, TIME_BOTTOM)
                val geoValues = readBestGeoWithDistance(geo)
                val timestamp = readBestTimestamp(time)

                PolePhotoObservation(
                    uri = uri.toString(),
                    fileName = fileName,
                    boylam = geoValues?.boylam,
                    enlem = geoValues?.enlem,
                    timestampMs = timestamp,
                    distanceM = geoValues?.distance,
                    error = when {
                        geoValues?.boylam == null || geoValues.enlem == null -> "Fotoğraf GPS okunamadı"
                        timestamp == null -> "Fotoğraf saati okunamadı"
                        geoValues.distance == null -> "Fotoğraf distance okunamadı"
                        else -> null
                    }
                )
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            PolePhotoObservation(uri.toString(), fileName, error = e.message ?: "Fotoğraf analiz hatası")
        }
    }

    private data class TopInfo(
        val isAdi: String? = null,
        val tarih: String? = null,
        val yon: String? = null,
        val mahal: String? = null,
        val hat: String? = null,
        val boruTipi: String? = null,
        val boruMalzemesi: String? = null,
        val capMm: Int? = null,
        val boruDerinligi: String? = null,
        val isinSahibi: String? = null,
        val operator: String? = null
    ) {
        fun score(): Int = listOf(isAdi, tarih, yon, mahal, hat, boruTipi, boruMalzemesi, boruDerinligi, isinSahibi, operator)
            .count { !it.isNullOrBlank() } + (if (capMm != null) 1 else 0) + (if (!hat.isNullOrBlank()) 2 else 0)
    }

    private data class GeoDistance(val boylam: Double?, val enlem: Double?, val distance: Double?)

    private fun buildError(top: TopInfo, geo: Pair<Double, Double>?, timestamp: Long?): String? {
        val missing = mutableListOf<String>()
        if (top.hat.isNullOrBlank()) missing += "hat"
        if (geo == null) missing += "GPS"
        if (timestamp == null) missing += "saat"
        return if (missing.isEmpty()) null else "Eksik: ${missing.joinToString(", ")}"
    }

    private fun readBestTop(bitmap: Bitmap): TopInfo {
        var best = TopInfo()
        for (variant in variants(bitmap)) {
            try {
                val parsed = parseTop(recognize(variant))
                if (parsed.score() > best.score()) best = parsed
            } finally {
                if (variant !== bitmap && !variant.isRecycled) variant.recycle()
            }
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        return best
    }

    private fun readBestGeo(bitmap: Bitmap): Pair<Double, Double>? {
        val full = readBestGeoWithDistance(bitmap) ?: return null
        val b = full.boylam ?: return null
        val e = full.enlem ?: return null
        return b to e
    }

    private fun readBestGeoWithDistance(bitmap: Bitmap): GeoDistance? {
        var best: GeoDistance? = null
        var bestScore = -1
        for (variant in variants(bitmap)) {
            try {
                val parsed = parseGeoDistance(recognize(variant))
                val score = (if (parsed.boylam != null) 2 else 0) +
                    (if (parsed.enlem != null) 2 else 0) +
                    (if (parsed.distance != null) 1 else 0)
                if (score > bestScore) {
                    best = parsed
                    bestScore = score
                }
            } finally {
                if (variant !== bitmap && !variant.isRecycled) variant.recycle()
            }
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        return best
    }

    private fun readBestTimestamp(bitmap: Bitmap): Long? {
        var result: Long? = null
        for (variant in variants(bitmap)) {
            try {
                val parsed = parseTimestamp(recognize(variant))
                if (parsed != null) {
                    result = parsed
                    break
                }
            } finally {
                if (variant !== bitmap && !variant.isRecycled) variant.recycle()
            }
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        return result
    }

    private fun recognize(bitmap: Bitmap): String {
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Aynı sabit koordinatı renk bağımsız iki görünümle dener. */
    private fun variants(src: Bitmap): List<Bitmap> {
        val width = max(1, src.width * 2)
        val height = max(1, src.height * 2)
        val scaled = Bitmap.createScaledBitmap(src, width, height, true)
        val gray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        // Hafif kontrast: a*x + translate
        val contrast = 1.55f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        matrix.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )))
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return listOf(scaled, gray)
    }

    private fun parseTop(text: String): TopInfo {
        var isAdi: String? = null
        var tarih: String? = null
        var yon: String? = null
        var mahal: String? = null
        var hat: String? = null
        var boruTipi: String? = null
        var malzeme: String? = null
        var cap: Int? = null
        var derinlik: String? = null
        var sahip: String? = null
        var operator: String? = null

        text.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = normalizeKey(line.substring(0, idx))
            val value = line.substring(idx + 1).trim().trim('-', '–', '—', ' ')
            if (value.isBlank()) return@forEach
            when {
                key == "isinadi" || key == "isadi" -> isAdi = value
                key == "tarih" -> tarih = value
                key == "yon" -> yon = value
                key == "mahal" -> mahal = value
                key.contains("baslangicbitismh") || key.contains("baslangicbitis") -> hat = value.uppercase(Locale("tr", "TR"))
                key == "borutipi" -> boruTipi = value
                key == "borumalzemesi" -> malzeme = value
                key == "borucapi" || key == "cap" -> cap = Regex("\\d{2,4}").find(value)?.value?.toIntOrNull()
                key == "boruderinligi" -> derinlik = value
                key == "isinsahibi" -> sahip = value
                key == "operator" -> operator = value
            }
        }
        return TopInfo(isAdi, tarih, yon, mahal, hat, boruTipi, malzeme, cap, derinlik, sahip, operator)
    }

    /** Sol-alt sabit kutuda ilk iki ondalıklı sayı GPS, üçüncü sayı distance'tır. */
    private fun parseGeoDistance(text: String): GeoDistance {
        val normalized = text
            .replace('O', '0').replace('o', '0')
            .replace(',', '.')
        val values = Regex("[-+]?\\d{1,3}\\.\\d{1,6}")
            .findAll(normalized)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        return GeoDistance(
            boylam = values.getOrNull(0),
            enlem = values.getOrNull(1),
            distance = values.getOrNull(2)
        )
    }

    private fun parseTimestamp(text: String): Long? {
        val normalized = text.replace('O', '0').replace('o', '0')
        val match = Regex("(\\d{4})[-/.](\\d{2})[-/.](\\d{2})\\s+(\\d{2})[:.](\\d{2})[:.](\\d{2})")
            .find(normalized) ?: return null
        val canonical = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]} " +
            "${match.groupValues[4]}:${match.groupValues[5]}:${match.groupValues[6]}"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }.parse(canonical)?.time
        } catch (_: Exception) { null }
    }

    private fun normalizeKey(value: String): String = value.lowercase(Locale("tr", "TR"))
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9]"), "")

    private fun cropRatio(bitmap: Bitmap, l: Double, t: Double, r: Double, b: Double): Bitmap {
        val left = (bitmap.width * l).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * t).toInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * r).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * b).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    fun close() {
        try { recognizer.close() } catch (_: Exception) {}
    }

    companion object {
        // Örnek 1600x959 Pole görüntüsünde doğrulanan sabit OSD bölgeleri.
        private const val TOP_LEFT = 0.055
        private const val TOP_TOP = 0.030
        private const val TOP_RIGHT = 0.520
        private const val TOP_BOTTOM = 0.570

        private const val GEO_LEFT = 0.055
        private const val GEO_TOP = 0.800
        private const val GEO_RIGHT = 0.460
        private const val GEO_BOTTOM = 0.940

        private const val TIME_LEFT = 0.600
        private const val TIME_TOP = 0.820
        private const val TIME_RIGHT = 0.940
        private const val TIME_BOTTOM = 0.940
    }
}
