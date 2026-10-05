package com.foton.crawlermetraj

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class VideoAnalyzer(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun analyze(uri: Uri, fileName: String): VideoResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) {
                return VideoResult(fileName, HeaderInfo(), null, null, null, null, "Video süresi okunamadı")
            }

            var header = HeaderInfo()
            var firstMeter: Double? = null
            var firstTimeMs: Long? = null

            val forwardLimit = min(6_000L, durationMs)
            var t = 0L
            while (t <= forwardLimit) {
                val frame = getFrame(retriever, t)
                if (frame != null) {
                    if (header.parsel == null || header.konum == null || header.capMm == null || header.yon == null) {
                        header = header.merge(readHeader(frame))
                    }
                    val meter = readMeter(frame)
                    frame.recycle()
                    if (meter != null) {
                        firstMeter = meter
                        firstTimeMs = t
                        break
                    }
                }
                t += 500L
            }

            var lastMeter: Double? = null
            var lastTimeMs: Long? = null
            val backwardLimit = max(0L, durationMs - 6_000L)
            t = max(0L, durationMs - 100L)
            while (t >= backwardLimit) {
                val frame = getFrame(retriever, t)
                if (frame != null) {
                    if (header.parsel == null || header.konum == null || header.capMm == null || header.yon == null) {
                        header = header.merge(readHeader(frame))
                    }
                    val meter = readMeter(frame)
                    frame.recycle()
                    if (meter != null) {
                        lastMeter = meter
                        lastTimeMs = t
                        break
                    }
                }
                if (t < 500L) break
                t -= 500L
            }

            val err = when {
                firstMeter == null && lastMeter == null -> "İlk ve son sayaç okunamadı"
                firstMeter == null -> "İlk sayaç okunamadı"
                lastMeter == null -> "Son sayaç okunamadı"
                else -> null
            }

            VideoResult(
                fileName = fileName,
                header = header,
                firstMeter = firstMeter,
                lastMeter = lastMeter,
                firstTimeMs = firstTimeMs,
                lastTimeMs = lastTimeMs,
                error = err
            )
        } catch (e: Exception) {
            VideoResult(fileName, HeaderInfo(), null, null, null, null, e.message ?: "Video analiz hatası")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun getFrame(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        return try {
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun readMeter(frame: Bitmap): Double? {
        // Gönderilen crawler görüntülerinde metre sayacı sağ-alt sabit bölgede.
        val cropped = cropByRatio(frame, 0.65f, 0.70f, 1.00f, 1.00f)
        val prepared = isolateRedText(cropped)
        cropped.recycle()

        return try {
            val text = recognize(prepared)
            parseMeter(text)
        } finally {
            prepared.recycle()
        }
    }

    private suspend fun readHeader(frame: Bitmap): HeaderInfo {
        // Parsel / Konum / Yön / Çap sol-üst sabit OSD alanında.
        val cropped = cropByRatio(frame, 0.00f, 0.00f, 0.60f, 0.58f)
        val prepared = isolateRedText(cropped)
        cropped.recycle()

        return try {
            val text = recognize(prepared)
            parseHeader(text)
        } finally {
            prepared.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await().text
    }

    private fun parseMeter(raw: String): Double? {
        val cleaned = raw
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')

        // Ekrandaki sayaç iki ondalıklı. İlk tercih "Metre Sayacı" satırındaki sayı.
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        val meterLine = lines.firstOrNull {
            val low = it.lowercase(Locale("tr", "TR"))
            low.contains("metre") || low.contains("sayac") || low.contains("sayaç")
        }

        parseDecimalFromText(meterLine ?: "")?.let { return it }

        // OCR etiketi kaçırırsa, sağ-alt kırpım içindeki ilk ondalıklı sayıyı kullan.
        for (line in lines) {
            parseDecimalFromText(line)?.let { return it }
        }
        return null
    }

    private fun parseDecimalFromText(text: String): Double? {
        val match = Regex("(-?\\d{1,4}[\\.,]\\d{1,2})").find(text) ?: return null
        return match.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    private fun parseHeader(raw: String): HeaderInfo {
        var parsel: String? = null
        var konum: String? = null
        var cap: Int? = null
        var yon: String? = null

        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }

        for (line in lines) {
            val low = line.lowercase(Locale("tr", "TR"))

            if (parsel == null && low.contains("parsel")) {
                Regex("(\\d{1,6})\\s*parsel", RegexOption.IGNORE_CASE)
                    .find(line.replace(" ", ""))
                    ?.groupValues?.getOrNull(1)
                    ?.let { parsel = it }

                if (parsel == null) {
                    Regex("(\\d{1,6})").find(line)?.groupValues?.getOrNull(1)?.let { parsel = it }
                }
            }

            if (konum == null && (low.contains("konum") || low.contains("location"))) {
                valueAfterSeparator(line)?.let { value ->
                    val cleaned = value.uppercase(Locale("tr", "TR"))
                        .replace(" ", "")
                        .replace('—', '-')
                        .replace('–', '-')
                    if (cleaned.isNotBlank()) konum = cleaned
                }
            }

            if (cap == null && (low.contains("çap") || low.contains("cap"))) {
                Regex("(\\d{2,4})").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { cap = it }
            }

            if (yon == null && (low.contains("yön") || low.contains("yon"))) {
                valueAfterSeparator(line)?.let { value ->
                    val cleaned = value.uppercase(Locale("tr", "TR"))
                        .replace(Regex("[^A-ZÇĞİÖŞÜ0-9]"), "")
                    if (cleaned.isNotBlank()) yon = cleaned
                }
            }
        }

        return HeaderInfo(parsel = parsel, konum = konum, capMm = cap, yon = yon)
    }

    private fun valueAfterSeparator(line: String): String? {
        val idxColon = line.indexOf(':')
        val idxSemi = line.indexOf(';')
        val idx = when {
            idxColon >= 0 -> idxColon
            idxSemi >= 0 -> idxSemi
            else -> -1
        }
        if (idx >= 0 && idx + 1 < line.length) return line.substring(idx + 1).trim()

        // OCR iki nokta üst üsteyi kaybetmişse anahtar kelimeden sonrasını al.
        val pieces = line.trim().split(Regex("\\s+"))
        return if (pieces.size >= 2) pieces.drop(1).joinToString(" ") else null
    }

    private fun cropByRatio(
        source: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Bitmap {
        val x = (source.width * left).toInt().coerceIn(0, source.width - 1)
        val y = (source.height * top).toInt().coerceIn(0, source.height - 1)
        val r = (source.width * right).toInt().coerceIn(x + 1, source.width)
        val b = (source.height * bottom).toInt().coerceIn(y + 1, source.height)
        return Bitmap.createBitmap(source, x, y, r - x, b - y)
    }

    private fun isolateRedText(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val isRedText = r >= 110 && r >= (g * 1.22f) && r >= (b * 1.22f)
            pixels[i] = if (isRedText) Color.BLACK else Color.WHITE
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun close() {
        recognizer.close()
    }
}
