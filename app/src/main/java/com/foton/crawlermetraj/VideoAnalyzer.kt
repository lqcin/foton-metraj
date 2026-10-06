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

/**
 * v0.4.3 - sabit ilk/son kare + renkten bağımsız sayaç OCR.
 *
 * Kritik kural:
 *   metraj = ABS(son sayaç - ilk sayaç)
 *
 * Parsel / hat / çap / yön gibi bilgiler yardımcı bilgidir. Bunların hiçbiri
 * okunamasa bile ilk ve son sayaç okunuyorsa sonuç BAŞARILI kabul edilir ve
 * Excel'e aktarılır.
 */
class VideoAnalyzer(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun analyze(uri: Uri, fileName: String): VideoResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) {
                return VideoResult(
                    fileName,
                    headerFromFileName(fileName),
                    null,
                    null,
                    null,
                    null,
                    "Video süresi okunamadı"
                )
            }

            // Sabit örnekleme kuralında ilk kare 1. saniye, son kare ise
            // videonun bitişinden 1 saniye öncedir. 2 saniye ve daha kısa
            // videolarda bu iki nokta güvenilir biçimde ayrılamaz.
            if (durationMs <= 2_000L) {
                return VideoResult(
                    fileName,
                    headerFromFileName(fileName),
                    null,
                    null,
                    null,
                    null,
                    "Video çok kısa (2 saniye veya daha az)"
                )
            }

            // Yardımcı bilgiler için OCR yapmıyoruz. Hat adı dosya adından
            // yakalanabiliyorsa yakalanır; yakalanamazsa boş kalması sorun değildir.
            val header = headerFromFileName(fileName)

            val first = findFirstMeter(retriever, durationMs)
            val last = findLastMeter(retriever, durationMs)

            val err = when {
                first == null && last == null -> "İlk ve son sayaç okunamadı"
                first == null -> "İlk sayaç okunamadı"
                last == null -> "Son sayaç okunamadı"
                else -> null
            }

            VideoResult(
                fileName = fileName,
                header = header,
                firstMeter = first?.value,
                lastMeter = last?.value,
                firstTimeMs = first?.timeMs,
                lastTimeMs = last?.timeMs,
                error = err
            )
        } catch (e: Exception) {
            VideoResult(
                fileName,
                headerFromFileName(fileName),
                null,
                null,
                null,
                null,
                e.message ?: "Video analiz hatası"
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private data class MeterHit(val value: Double, val timeMs: Long)

    private suspend fun findFirstMeter(
        retriever: MediaMetadataRetriever,
        durationMs: Long
    ): MeterHit? {
        // Kullanıcı kuralı: İLK SAYAÇ yalnızca videonun 1.000 ms (1. saniye)
        // karesinden alınır. Başka zamanlara kayıp yanlış bir değeri seçmeyiz.
        val t = min(1_000L, max(0L, durationMs - 1L))
        val frame = getFrame(retriever, t) ?: return null
        return try {
            readMeter(frame)?.let { MeterHit(it, t) }
        } finally {
            frame.recycle()
        }
    }

    private suspend fun findLastMeter(
        retriever: MediaMetadataRetriever,
        durationMs: Long
    ): MeterHit? {
        // Kullanıcı kuralı: SON SAYAÇ yalnızca videonun toplam süresinden
        // 1.000 ms (1 saniye) önceki kareden alınır. Başka karelere kaymayız.
        val t = durationMs - 1_000L
        val frame = getFrame(retriever, t) ?: return null
        return try {
            readMeter(frame)?.let { MeterHit(it, t) }
        } finally {
            frame.recycle()
        }
    }

    private fun getFrame(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        return try {
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sayaç konumu sabit, fakat OSD yazı rengi değişebiliyor. Bu nedenle renk
     * (kırmızı vb.) artık bir şart DEĞİL. Önce yalnız sayaç satırını dar kırparız,
     * sonra aynı kareyi renkli, gri, yüksek kontrast ve renklilik maskesiyle deneriz.
     * Böylece kırmızı / sarı / yeşil / mavi / beyaz OSD'lerde aynı kod çalışır.
     */
    private suspend fun readMeter(frame: Bitmap): Double? {
        // Sayaç değeri: sağ-alt, yalnız ilk satır. Lens basıncı satırını kesinlikle
        // kırpım dışında bırakıyoruz. Bu, 135.0 gibi yanlış okumaları azaltır.
        val valueCrop = cropByRatio(frame, 0.742f, 0.862f, 0.842f, 0.909f)
        try {
            readMeterFromCrop(valueCrop)?.let { return it }
        } finally {
            valueCrop.recycle()
        }

        // Çözünürlük / aspect ratio nedeniyle birkaç piksel kayma olursa biraz daha
        // geniş ama hâlâ yalnız metre sayacı satırını içeren fallback.
        val lineCrop = cropByRatio(frame, 0.675f, 0.852f, 0.875f, 0.911f)
        return try {
            readMeterFromCrop(lineCrop)
        } finally {
            lineCrop.recycle()
        }
    }

    private suspend fun readMeterFromCrop(crop: Bitmap): Double? {
        val candidates = mutableListOf<Double>()

        suspend fun tryBitmap(bitmap: Bitmap) {
            try {
                parseMeter(recognize(bitmap))?.let { candidates += it }
            } finally {
                bitmap.recycle()
            }
        }

        // 1) Orijinal renk. ML Kit çoğu OSD rengini doğrudan okuyabilir.
        tryBitmap(scale(crop, 5, true))

        // 2) Renkten bağımsız gri + otomatik kontrast.
        val gray = grayscaleAutoContrast(crop)
        tryBitmap(scale(gray, 5, true))
        gray.recycle()

        // 3) OSD rengi her ne olursa olsun, doygun/renkli pikselleri ayır.
        // Boru yüzeyi genellikle düşük doygunluklu olduğu için yazıyı temizler.
        val chroma = isolateHighChromaText(crop, thicken = true)
        tryBitmap(scale(chroma, 5, false))
        chroma.recycle()

        // 4) Parlaklık tabanlı Otsu siyah-beyaz. Beyaz/gri OSD için yararlı fallback.
        val binary = otsuBinary(crop)
        tryBitmap(scale(binary, 5, false))
        binary.recycle()

        return chooseMeterCandidate(candidates)
    }

    private fun scale(source: Bitmap, factor: Int, filter: Boolean): Bitmap =
        Bitmap.createScaledBitmap(
            source,
            max(1, source.width * factor),
            max(1, source.height * factor),
            filter
        )

    private suspend fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await().text
    }

    private fun parseMeter(raw: String): Double? {
        if (raw.isBlank()) return null

        val cleaned = raw
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('|', '1')
            .replace(';', ',')
            .replace(':', ':')

        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Etiket yakalandıysa o satırdaki değeri tercih et.
        val meterLine = lines.firstOrNull {
            val low = it.lowercase(Locale("tr", "TR"))
            low.contains("metre") || low.contains("sayac") || low.contains("sayaç")
        }
        parseDecimalFromText(meterLine ?: "")?.let { return validateMeter(it) }

        // Dar kırpımda yalnız sayaç değeri bulunduğu için satırlardaki makul sayıları dene.
        for (line in lines) {
            parseDecimalFromText(line)?.let { value ->
                validateMeter(value)?.let { return it }
            }
        }

        parseDecimalFromText(cleaned)?.let { return validateMeter(it) }
        return null
    }

    private fun parseDecimalFromText(text: String): Double? {
        // Normal görüntü: 0,00 / 6,89 / 11,95
        val decimal = Regex("(-?\\d{1,4}[\\.,]\\d{1,3})").find(text)
        if (decimal != null) {
            return decimal.groupValues[1].replace(',', '.').toDoubleOrNull()
        }

        // OCR virgülü/noktayı yutarsa sayaç formatının 2 ondalıklı olduğunu kullan.
        // Örn. 1195 -> 11.95, 005 -> 0.05. 1-2 haneli belirsiz değerleri
        // kabul etmiyoruz; bu yanlış okumayı toplama sokmaktan daha güvenli.
        val digits = Regex("-?\\d+").findAll(text)
            .map { it.value }
            .maxByOrNull { it.length }
            ?: return null

        val negative = digits.startsWith('-')
        val only = digits.removePrefix("-")
        if (only.length < 3 || only.length > 6) return null
        val reconstructed = only.dropLast(2) + "." + only.takeLast(2)
        val value = reconstructed.toDoubleOrNull() ?: return null
        return if (negative) -value else value
    }

    private fun chooseMeterCandidate(values: List<Double>): Double? {
        if (values.isEmpty()) return null

        // Değerleri 2 ondalığa yuvarla. İki farklı görüntü işleme yöntemi aynı
        // sayıyı görüyorsa onu güvenilir kabul et.
        val rounded = values.map { kotlin.math.round(it * 100.0) / 100.0 }
        val grouped = rounded.groupingBy { it }.eachCount()
        val consensus = grouped.maxByOrNull { it.value }
        if (consensus != null && consensus.value >= 2) return consensus.key

        // Tek aday varsa, tüm diğer ön işleme yöntemleri hiçbir şey okuyamamış demektir.
        // Sabit ve dar ROI sayesinde bunu kabul ediyoruz. Birden fazla farklı aday varsa
        // tahmin yürütmeyip OKUNAMADI bırakmak daha güvenli.
        return rounded.distinct().singleOrNull()
    }

    private fun validateMeter(value: Double): Double? {
        return value.takeIf { it.isFinite() && it in -999.99..9999.99 }
    }

    /** Dosya adı örn. a43-44_20260924_... -> A43-A44 */
    private fun headerFromFileName(fileName: String): HeaderInfo {
        val stem = fileName.substringBeforeLast('.')
        val firstToken = stem.substringBefore('_').trim()
            .replace('—', '-')
            .replace('–', '-')
        val parts = firstToken.split('-').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return HeaderInfo()

        val left = normalizeNode(parts[0], null) ?: return HeaderInfo()
        val prefix = left.takeWhile { it.isLetter() }
        val right = normalizeNode(parts[1], prefix) ?: return HeaderInfo()
        return HeaderInfo(konum = "$left-$right")
    }

    private fun normalizeNode(raw: String, inheritedPrefix: String?): String? {
        val cleaned = raw.uppercase(Locale("tr", "TR"))
            .replace(Regex("[^A-ZÇĞİÖŞÜ0-9]"), "")
        if (cleaned.isBlank()) return null

        val hasLetter = cleaned.any { it.isLetter() }
        return if (!hasLetter && !inheritedPrefix.isNullOrBlank()) {
            inheritedPrefix + cleaned
        } else cleaned
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

    private fun grayscaleAutoContrast(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val luma = IntArray(src.size)
        var minY = 255
        var maxY = 0
        for (i in src.indices) {
            val c = src[i]
            val y = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()
            luma[i] = y
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val span = max(1, maxY - minY)
        val out = IntArray(src.size)
        for (i in luma.indices) {
            val v = ((luma[i] - minY) * 255 / span).coerceIn(0, 255)
            out[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun isolateHighChromaText(source: Bitmap, thicken: Boolean): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val hit = BooleanArray(src.size)
        for (i in src.indices) {
            val c = src[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val chroma = hi - lo
            hit[i] = chroma >= 42 && hi >= 80
        }
        val out = IntArray(src.size) { Color.WHITE }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!hit[i]) continue
                val radius = if (thicken) 1 else 0
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy !in 0 until h) continue
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx !in 0 until w) continue
                        out[yy * w + xx] = Color.BLACK
                    }
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun otsuBinary(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        val lum = IntArray(src.size)
        for (i in src.indices) {
            val c = src[i]
            val y = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt().coerceIn(0, 255)
            lum[i] = y
            hist[y]++
        }
        val total = lum.size
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0
        var bestVar = -1.0
        var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > bestVar) {
                bestVar = between
                threshold = t
            }
        }
        val out = IntArray(lum.size)
        for (i in lum.indices) {
            out[i] = if (lum[i] <= threshold) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun close() {
        recognizer.close()
    }
}
