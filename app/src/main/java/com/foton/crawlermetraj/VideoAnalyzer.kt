package com.foton.crawlermetraj

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * v0.5.0 - saha videosu doğrulamalı sayaç okuma.
 *
 * Sabit kurallar:
 *  - İlk sayaç: videonun tam 1.000 ms karesi
 *  - Son sayaç: video süresi - 1.000 ms karesi
 *  - Metraj: ABS(son - ilk)
 *
 * Güvenlik kuralları:
 *  - Metre sayacı ile Lens/Araç Basıncı/Eğim birbirine karıştırılmaz.
 *  - Sayaç için yalnız "Metre Sayacı" satırı veya o satıra ait dar ROI kabul edilir.
 *  - Lens basıncı gibi tek ondalıklı değerler sayaç olarak kabul edilmez.
 *  - Hat adı dosya adından alınır ve A38-A38-1 gibi ekler korunur.
 *  - Parsel öncelikle videodaki OSD "Boru Tanımı: ...PARSEL" bilgisinden alınır.
 */
class VideoAnalyzer(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun analyze(uri: Uri, fileName: String, folderName: String? = null): VideoResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val fileHeader = headerFromFileName(fileName, folderName)

            if (durationMs <= 0L) {
                return VideoResult(fileName, fileHeader, null, null, null, null, "Video süresi okunamadı")
            }
            if (durationMs <= 2_000L) {
                return VideoResult(
                    fileName,
                    fileHeader,
                    null,
                    null,
                    null,
                    null,
                    "Video çok kısa (2 saniye veya daha az)"
                )
            }

            val firstTime = 1_000L
            val lastTime = durationMs - 1_000L

            val firstFrame = getFrame(retriever, firstTime)
            val lastFrame = getFrame(retriever, lastTime)

            var osdHeader = HeaderInfo()
            var firstMeter: Double? = null
            var lastMeter: Double? = null

            if (firstFrame != null) {
                try {
                    osdHeader = readHeader(firstFrame)
                    firstMeter = readMeter(firstFrame)
                } finally {
                    firstFrame.recycle()
                }
            }

            if (lastFrame != null) {
                try {
                    lastMeter = readMeter(lastFrame)
                } finally {
                    lastFrame.recycle()
                }
            }

            // Excel'e gidecek alanların kaynak önceliği bilinçli olarak sabittir.
            // Hat: dosya adı > OSD. Parsel: OSD > klasör adı.
            val header = HeaderInfo(
                parsel = osdHeader.parsel ?: fileHeader.parsel,
                konum = fileHeader.konum ?: osdHeader.konum,
                capMm = osdHeader.capMm,
                yon = osdHeader.yon
            )

            val error = when {
                firstFrame == null && lastFrame == null -> "İlk ve son kare alınamadı"
                firstFrame == null -> "1. saniye karesi alınamadı"
                lastFrame == null -> "Son - 1 saniye karesi alınamadı"
                firstMeter == null && lastMeter == null -> "İlk ve son Metre Sayacı okunamadı"
                firstMeter == null -> "1. saniyedeki Metre Sayacı okunamadı"
                lastMeter == null -> "Son - 1 saniyedeki Metre Sayacı okunamadı"
                else -> null
            }

            VideoResult(
                fileName = fileName,
                header = header,
                firstMeter = firstMeter,
                lastMeter = lastMeter,
                firstTimeMs = if (firstMeter != null) firstTime else null,
                lastTimeMs = if (lastMeter != null) lastTime else null,
                error = error
            )
        } catch (e: Exception) {
            VideoResult(
                fileName = fileName,
                header = headerFromFileName(fileName, folderName),
                firstMeter = null,
                lastMeter = null,
                firstTimeMs = null,
                lastTimeMs = null,
                error = e.message ?: "Video analiz hatası"
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun getFrame(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        return try {
            // API mikrosaniye ister. OPTION_CLOSEST ile istenen zamana en yakın gerçek kare alınır.
            retriever.getFrameAtTime(timeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Metre sayacı okumada iki katman kullanılır:
     * 1) Gerçek videolarda doğrulanan, yalnız ilk OSD satırını alan dar ROI.
     * 2) Dar ROI başarısızsa geniş OSD alanında yalnız "Metre Sayacı" etiketli satır.
     *
     * İkinci satırdaki Lens Basıncı hiçbir durumda serbest sayı olarak kabul edilmez.
     */
    private suspend fun readMeter(frame: Bitmap): Double? {
        // Örnek gerçek 2560x1440 videoda doğrulanan sayaç satırı bandı.
        // Dikey bant özellikle Lens Basıncı satırı başlamadan biter.
        val singleLineCrop = cropByRatio(frame, 0.635f, 0.770f, 0.900f, 0.806f)
        try {
            readMeterFromSingleLine(singleLineCrop)?.let { return it }
        } finally {
            singleLineCrop.recycle()
        }

        // Pozisyon birkaç piksel değişirse etiketli satırı semantik olarak ara.
        // Burada sayı ancak aynı OCR satırında "Metre Sayacı" etiketi varsa kabul edilir.
        val osdBlock = cropByRatio(frame, 0.600f, 0.735f, 0.920f, 0.900f)
        return try {
            readMeterByLabel(osdBlock)
        } finally {
            osdBlock.recycle()
        }
    }

    private suspend fun readMeterFromSingleLine(crop: Bitmap): Double? {
        val strong = mutableListOf<Double>()
        val weak = mutableListOf<Double>()

        suspend fun inspect(bitmap: Bitmap) {
            try {
                val result = recognizeResult(bitmap)
                result.textBlocks.flatMap { it.lines }.forEach { line ->
                    val text = normalizeOcrChars(line.text)
                    if (containsForbiddenMeterLabel(text)) return@forEach
                    val value = parseStrictMeter(text) ?: return@forEach
                    if (containsMeterLabel(text)) strong += value else weak += value
                }
            } finally {
                bitmap.recycle()
            }
        }

        inspect(scale(crop, 5, true))

        val gray = grayscaleAutoContrast(crop)
        try {
            inspect(scale(gray, 5, true))
        } finally {
            gray.recycle()
        }

        val chroma = isolateHighChromaText(crop, thicken = true)
        try {
            inspect(scale(chroma, 5, false))
        } finally {
            chroma.recycle()
        }

        val binary = otsuBinary(crop)
        try {
            inspect(scale(binary, 5, false))
        } finally {
            binary.recycle()
        }

        // Etiket açıkça görülmüşse, iki OCR varyantının uzlaşması tercih edilir.
        chooseConsensus(strong, allowSingle = true)?.let { return it }

        // Etiket OCR'da düşmüşse dar ROI sayesinde sayı yine sayaç satırındadır;
        // fakat tek bir yöntemin gördüğü değeri kabul etmeyiz. En az iki yöntem aynı
        // iki ondalıklı değeri görmelidir.
        return chooseConsensus(weak, allowSingle = false)
    }

    private suspend fun readMeterByLabel(crop: Bitmap): Double? {
        val candidates = mutableListOf<Double>()

        suspend fun inspect(bitmap: Bitmap) {
            try {
                val result = recognizeResult(bitmap)
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val text = normalizeOcrChars(line.text)
                        if (!containsMeterLabel(text)) continue
                        if (containsForbiddenMeterLabel(text)) continue
                        parseStrictMeter(text)?.let { candidates += it }
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }

        inspect(scale(crop, 4, true))

        val gray = grayscaleAutoContrast(crop)
        try {
            inspect(scale(gray, 4, true))
        } finally {
            gray.recycle()
        }

        val chroma = isolateHighChromaText(crop, thicken = true)
        try {
            inspect(scale(chroma, 4, false))
        } finally {
            chroma.recycle()
        }

        return chooseConsensus(candidates, allowSingle = true)
    }

    private fun containsMeterLabel(text: String): Boolean {
        val s = normalizeForSearch(text)
        val hasMetre = s.contains("metre") || s.contains("meter")
        val hasSayac = s.contains("sayac") || s.contains("sayaci")
        return (hasMetre && hasSayac) || s.contains("metresayac") || s.contains("metersayac")
    }

    private fun containsForbiddenMeterLabel(text: String): Boolean {
        val s = normalizeForSearch(text)
        return s.contains("lens") ||
            s.contains("basinc") ||
            s.contains("arac") ||
            s.contains("egim") ||
            s.contains("pressure") ||
            s.contains("slope")
    }

    /**
     * Sayaç bu kamera OSD'sinde iki ondalıklıdır (0,00 / 11,95 / 23,18).
     * Bu nedenle 129.0 / 135.0 gibi Lens Basıncı değerleri yapısal olarak da elenir.
     */
    private fun parseStrictMeter(text: String): Double? {
        val cleaned = normalizeMeterText(text)
        val matches = Regex("(?<!\\d)(-?\\d{1,4}[\\.,]\\d{2})(?!\\d)")
            .findAll(cleaned)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .filter { it.isFinite() && it in -999.99..9999.99 }
            .toList()

        return matches.singleOrNull()
    }

    private fun chooseConsensus(values: List<Double>, allowSingle: Boolean): Double? {
        if (values.isEmpty()) return null
        val rounded = values.map { round(it * 100.0) / 100.0 }
        val grouped = rounded.groupingBy { it }.eachCount()
        val winner = grouped.maxByOrNull { it.value } ?: return null
        if (winner.value >= 2) return winner.key
        return if (allowSingle && grouped.size == 1) winner.key else null
    }

    /**
     * Yardımcı OSD bilgileri sadece 1. saniye karesinden okunur.
     * Yanlış rakam taşınmasın diye parsel yalnız PARSEL kelimesine bağlı olarak,
     * çap yalnız Çap satırından, yön yalnız İnceleme Yönü satırından alınır.
     */
    private suspend fun readHeader(frame: Bitmap): HeaderInfo {
        val crop = cropByRatio(frame, 0.000f, 0.000f, 0.500f, 0.300f)
        return try {
            var header = HeaderInfo()

            suspend fun inspect(bitmap: Bitmap) {
                try {
                    header = header.merge(parseHeader(recognizeResult(bitmap).text))
                } finally {
                    bitmap.recycle()
                }
            }

            inspect(scale(crop, 3, true))

            if (header.parsel == null || header.capMm == null || header.yon == null || header.konum == null) {
                val gray = grayscaleAutoContrast(crop)
                try {
                    inspect(scale(gray, 3, true))
                } finally {
                    gray.recycle()
                }
            }

            if (header.parsel == null || header.capMm == null || header.yon == null || header.konum == null) {
                val chroma = isolateHighChromaText(crop, thicken = true)
                try {
                    inspect(scale(chroma, 3, false))
                } finally {
                    chroma.recycle()
                }
            }

            header
        } finally {
            crop.recycle()
        }
    }

    private fun parseHeader(raw: String): HeaderInfo {
        if (raw.isBlank()) return HeaderInfo()

        val cleanedRaw = normalizeOcrChars(raw)
        val lines = cleanedRaw.lines().map { it.trim() }.filter { it.isNotBlank() }

        var parsel: String? = null
        var kesitNo: String? = null
        var cap: Int? = null
        var yon: String? = null

        // Parsel: yalnız "123PARSEL" biçiminden al. Tarih/saat veya basınç sayıları asla kullanılmaz.
        Regex("(?i)(\\d{1,6})\\s*PARSEL")
            .find(cleanedRaw)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { parsel = it }

        for (line in lines) {
            val search = normalizeForSearch(line)

            if (kesitNo == null && search.contains("borukesitno")) {
                valueAfterSeparator(line)?.let { value ->
                    normalizeHatToken(value)?.takeIf { it.isNotBlank() }?.let { kesitNo = it }
                }
            }

            if (cap == null && (search.contains("capmm") || search.startsWith("cap"))) {
                val after = valueAfterSeparator(line) ?: line
                Regex("(?<!\\d)(\\d{2,4})(?!\\d)")
                    .find(after)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in 50..5000 }
                    ?.let { cap = it }
            }

            if (yon == null && search.contains("incelemeyonu")) {
                val value = valueAfterSeparator(line)
                    ?: line.trim().split(Regex("\\s+")).lastOrNull()
                value?.uppercase(Locale("tr", "TR"))
                    ?.replace(Regex("[^A-ZÇĞİÖŞÜ]"), "")
                    ?.takeIf { it.length in 1..4 && it !in setOf("YON", "YONU") }
                    ?.let { yon = it }
            }
        }

        return HeaderInfo(parsel = parsel, konum = kesitNo, capMm = cap, yon = yon)
    }

    /**
     * Dosya adı örnekleri:
     *  a43-44_20260924_...       -> A43-A44
     *  A38-A38-1_20260623_...   -> A38-A38-1
     * Son ek (-1 gibi) artık kaybolmaz.
     */
    private fun headerFromFileName(fileName: String, folderName: String?): HeaderInfo {
        val stem = fileName.substringBeforeLast('.')
        val match = Regex("^(.+?)_\\d{8}_\\d{2}_\\d{2}_\\d{2}(?:_\\d+)?$", RegexOption.IGNORE_CASE)
            .find(stem)
        val rawHat = (match?.groupValues?.getOrNull(1) ?: stem.substringBefore('_')).trim()
        val hat = normalizeHatToken(rawHat)

        return HeaderInfo(
            parsel = parseParselFromFolder(folderName),
            konum = hat.takeIf { it.isNotBlank() }
        )
    }

    private fun normalizeHatToken(raw: String): String {
        val normalized = raw.uppercase(Locale("tr", "TR"))
            .replace('—', '-')
            .replace('–', '-')
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^A-ZÇĞİÖŞÜ0-9-]"), "")
            .trim('-')

        if (normalized.isBlank()) return ""
        val parts = normalized.split('-').filter { it.isNotBlank() }
        if (parts.size < 2) return normalized

        val first = parts.first()
        val prefix = first.takeWhile { it.isLetter() }
        val out = mutableListOf(first)

        parts.drop(1).forEachIndexed { index, part ->
            // Yalnız ikinci düğüm tamamen rakamsa ilk düğümün harfini miras alır.
            // Üçüncü ve sonraki parçalar (örn. A38-A38-1) varyant/kol numarasıdır.
            val value = if (index == 0 && part.all { it.isDigit() } && prefix.isNotBlank()) {
                prefix + part
            } else {
                part
            }
            out += value
        }
        return out.joinToString("-")
    }

    private fun parseParselFromFolder(folderName: String?): String? {
        if (folderName.isNullOrBlank()) return null
        return Regex("(\\d{1,6})\\s*(?:PRSL|PARSEL)", RegexOption.IGNORE_CASE)
            .find(folderName.uppercase(Locale("tr", "TR")))
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun valueAfterSeparator(line: String): String? {
        val colon = line.indexOf(':')
        if (colon >= 0 && colon + 1 < line.length) return line.substring(colon + 1).trim()
        val semi = line.indexOf(';')
        if (semi >= 0 && semi + 1 < line.length) return line.substring(semi + 1).trim()
        return null
    }

    private fun normalizeOcrChars(text: String): String = text
        .replace(';', ',')

    private fun normalizeMeterText(text: String): String = normalizeOcrChars(text)
        .replace('O', '0')
        .replace('o', '0')
        .replace('|', '1')

    private fun normalizeForSearch(text: String): String = text
        .lowercase(Locale("tr", "TR"))
        .replace('ç', 'c')
        .replace('ğ', 'g')
        .replace('ı', 'i')
        .replace('ö', 'o')
        .replace('ş', 's')
        .replace('ü', 'u')
        .replace(Regex("[^a-z0-9]"), "")

    private suspend fun recognizeResult(bitmap: Bitmap): Text {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await()
    }

    private fun scale(source: Bitmap, factor: Int, filter: Boolean): Bitmap =
        Bitmap.createScaledBitmap(
            source,
            max(1, source.width * factor),
            max(1, source.height * factor),
            filter
        )

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
            hit[i] = chroma >= 38 && hi >= 75
        }

        val out = IntArray(src.size) { Color.WHITE }
        val radius = if (thicken) 1 else 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!hit[i]) continue
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
            val y = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c))
                .toInt().coerceIn(0, 255)
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
