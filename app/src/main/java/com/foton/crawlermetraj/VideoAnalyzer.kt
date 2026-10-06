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

    suspend fun analyze(uri: Uri, fileName: String, folderName: String? = null): VideoResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) {
                return VideoResult(fileName, buildBaseHeader(fileName, folderName), null, null, null, null, "Video süresi okunamadı")
            }

            var header = buildBaseHeader(fileName, folderName)
            var firstMeter: Double? = null
            var firstTimeMs: Long? = null

            // Sadece videonun başındaki ilk okunabilir sayaç değeri aranır.
            val forwardLimit = min(7_000L, durationMs)
            var t = 0L
            while (t <= forwardLimit) {
                val frame = getFrame(retriever, t)
                if (frame != null) {
                    if (header.capMm == null || header.yon == null) {
                        header = header.merge(readHeaderMeta(frame))
                    }
                    val meter = readMeter(frame)
                    frame.recycle()
                    if (meter != null) {
                        firstMeter = meter
                        firstTimeMs = t
                        break
                    }
                }
                t += 400L
            }

            var lastMeter: Double? = null
            var lastTimeMs: Long? = null

            // Sadece videonun sonundaki son okunabilir sayaç değeri aranır.
            val backwardLimit = max(0L, durationMs - 7_000L)
            t = max(0L, durationMs - 80L)
            while (t >= backwardLimit) {
                val frame = getFrame(retriever, t)
                if (frame != null) {
                    if (header.capMm == null || header.yon == null) {
                        header = header.merge(readHeaderMeta(frame))
                    }
                    val meter = readMeter(frame)
                    frame.recycle()
                    if (meter != null) {
                        lastMeter = meter
                        lastTimeMs = t
                        break
                    }
                }
                if (t < 400L) break
                t -= 400L
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
            VideoResult(fileName, buildBaseHeader(fileName, folderName), null, null, null, null, e.message ?: "Video analiz hatası")
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun buildBaseHeader(fileName: String, folderName: String?): HeaderInfo {
        return HeaderInfo(
            parsel = parseParselFromFolder(folderName),
            konum = parseHatFromFileName(fileName)
        )
    }

    /**
     * Örn: "DRNKY KUZU GRP 3.KSM 145 PRSL ROBOT" -> 145
     */
    private fun parseParselFromFolder(folderName: String?): String? {
        if (folderName.isNullOrBlank()) return null
        val normalized = folderName.uppercase(Locale("tr", "TR"))
        Regex("(\\d{1,6})\\s*(?:PRSL|PARSEL)", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues?.getOrNull(1)
            ?.let { return it }
        return null
    }

    /**
     * Örn:
     * a43-44_20260924_15_53_11_901.mp4 -> A43-A44
     * A44-A43_20260924_15_36_14_439.mp4 -> A44-A43
     */
    private fun parseHatFromFileName(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.')
        val prefix = stem.substringBefore(Regex("_20\\d{6}"))
            .trim()
            .replace('–', '-')
            .replace('—', '-')
            .replace('_', '-')

        val m = Regex("(?i)^([A-ZÇĞİÖŞÜ]*)(\\d{1,5})-([A-ZÇĞİÖŞÜ]*)(\\d{1,5})").find(prefix)
            ?: return null
        val p1 = m.groupValues[1].uppercase(Locale("tr", "TR"))
        val n1 = m.groupValues[2]
        val p2raw = m.groupValues[3].uppercase(Locale("tr", "TR"))
        val n2 = m.groupValues[4]
        val p2 = if (p2raw.isBlank()) p1 else p2raw
        return "$p1$n1-$p2$n2"
    }

    private fun getFrame(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        return try {
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun readMeter(frame: Bitmap): Double? {
        // Crawler OSD'sinde sayaç sağ-alt tarafta sabit. Alan özellikle dar tutulur;
        // lens/arac basıncı ve eğim değerlerinin sayaç sanılması engellenir.
        val crop = cropByRatio(frame, 0.57f, 0.76f, 0.91f, 0.94f)

        try {
            // 1) Kırmızı yazıyı ayır, büyüt ve OCR yap. En güvenilir yöntem.
            val strongMask = isolateRedText(crop, relaxed = false)
            val strongScaled = scaleUp(strongMask, 4)
            strongMask.recycle()
            try {
                parseMeter(recognize(strongScaled), requireMeterLabel = false)?.let { return it }
            } finally {
                strongScaled.recycle()
            }

            // 2) Kırmızı tonu görüntü/sıkıştırma nedeniyle zayıfsa daha gevşek eşikle tekrar dene.
            val relaxedMask = isolateRedText(crop, relaxed = true)
            val relaxedScaled = scaleUp(relaxedMask, 4)
            relaxedMask.recycle()
            try {
                parseMeter(recognize(relaxedScaled), requireMeterLabel = false)?.let { return it }
            } finally {
                relaxedScaled.recycle()
            }

            // 3) Son çare: renkli kırpımı büyütüp doğrudan OCR.
            val colorScaled = scaleUp(crop, 3)
            try {
                parseMeter(recognize(colorScaled), requireMeterLabel = true)?.let { return it }
            } finally {
                colorScaled.recycle()
            }
        } finally {
            crop.recycle()
        }
        return null
    }

    private suspend fun readHeaderMeta(frame: Bitmap): HeaderInfo {
        // Parsel ve hat artık klasör/dosya adından geliyor. OCR sadece çap ve yön için tutuldu.
        val cropped = cropByRatio(frame, 0.00f, 0.00f, 0.45f, 0.30f)
        val prepared = isolateRedText(cropped, relaxed = true)
        cropped.recycle()
        val scaled = scaleUp(prepared, 3)
        prepared.recycle()

        return try {
            val text = recognize(scaled)
            parseHeaderMeta(text)
        } finally {
            scaled.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await().text
    }

    private fun parseMeter(raw: String, requireMeterLabel: Boolean): Double? {
        val cleaned = normalizeOcr(raw)
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Etiket doğru okunmuşsa yalnızca o satırdaki değeri kabul et.
        val meterLine = lines.firstOrNull {
            val compact = it.lowercase(Locale("tr", "TR")).replace(" ", "")
            compact.contains("metre") || compact.contains("sayac") || compact.contains("sayaç")
        }
        if (meterLine != null) {
            parseDecimalFromText(meterLine)?.let { return plausibleMeter(it) }
        }

        if (requireMeterLabel) return null

        // Kırpımın ilk satırı metre satırıdır. Diğer basınç/eğim satırlarına düşmemek için
        // sadece ilk iki OCR satırı içinde değer aranır.
        for (line in lines.take(2)) {
            parseDecimalFromText(line)?.let { value ->
                plausibleMeter(value)?.let { return it }
            }
        }
        return null
    }

    private fun normalizeOcr(raw: String): String = raw
        .replace('O', '0')
        .replace('o', '0')
        .replace('I', '1')
        .replace('l', '1')
        .replace('S', '5')
        .replace('s', '5')
        .replace(';', ':')

    private fun parseDecimalFromText(text: String): Double? {
        // Sayaç görüntüde iki ondalıklı. OCR bazen virgülü nokta/iki nokta olarak algılıyor.
        val m = Regex("(-?\\d{1,4}[\\.,:]\\d{1,2})").find(text) ?: return null
        return m.groupValues[1]
            .replace(',', '.')
            .replace(':', '.')
            .toDoubleOrNull()
    }

    private fun plausibleMeter(value: Double): Double? {
        if (!value.isFinite()) return null
        // Kanal crawler sayaçları için makul ve yanlış basınç değerlerini dışlamaya yardımcı aralık.
        if (value < -5.0 || value > 500.0) return null
        return value
    }

    private fun parseHeaderMeta(raw: String): HeaderInfo {
        var cap: Int? = null
        var yon: String? = null
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }

        for (line in lines) {
            val low = line.lowercase(Locale("tr", "TR"))
            if (cap == null && (low.contains("çap") || low.contains("cap"))) {
                Regex("(\\d{2,4})").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { cap = it }
            }
            if (yon == null && (low.contains("yön") || low.contains("yon"))) {
                val value = valueAfterSeparator(line)
                val cleaned = value?.uppercase(Locale("tr", "TR"))
                    ?.replace(Regex("[^A-ZÇĞİÖŞÜ0-9]"), "")
                if (!cleaned.isNullOrBlank()) yon = cleaned
            }
        }
        return HeaderInfo(capMm = cap, yon = yon)
    }

    private fun valueAfterSeparator(line: String): String? {
        val idx = listOf(line.indexOf(':'), line.indexOf(';')).filter { it >= 0 }.minOrNull() ?: -1
        if (idx >= 0 && idx + 1 < line.length) return line.substring(idx + 1).trim()
        val pieces = line.trim().split(Regex("\\s+"))
        return if (pieces.size >= 2) pieces.drop(1).joinToString(" ") else null
    }

    private fun cropByRatio(source: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (source.width * left).toInt().coerceIn(0, source.width - 1)
        val y = (source.height * top).toInt().coerceIn(0, source.height - 1)
        val r = (source.width * right).toInt().coerceIn(x + 1, source.width)
        val b = (source.height * bottom).toInt().coerceIn(y + 1, source.height)
        return Bitmap.createBitmap(source, x, y, r - x, b - y)
    }

    private fun scaleUp(source: Bitmap, factor: Int): Bitmap {
        return Bitmap.createScaledBitmap(source, source.width * factor, source.height * factor, false)
    }

    private fun isolateRedText(source: Bitmap, relaxed: Boolean): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val isRedText = if (relaxed) {
                r >= 90 && (r - g) >= 28 && (r - b) >= 28
            } else {
                r >= 120 && (r - g) >= 42 && (r - b) >= 42
            }
            pixels[i] = if (isRedText) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun close() {
        recognizer.close()
    }
}
