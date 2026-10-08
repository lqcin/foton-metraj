package com.foton.crawlermetraj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object PoleStore {
    private const val PREFS = "foton_pole_store"
    private const val KEY_VERSION = "analysis_version"
    private const val KEY_RECORDS = "records"
    private const val KEY_PHOTOS = "pending_photos"
    private const val KEY_PROCESSED = "processed"
    private const val KEY_MONITOR_ACTIVE = "monitor_active"
    private const val KEY_MONITOR_FOLDER = "monitor_folder"
    private const val KEY_MONITOR_STATUS = "monitor_status"
    private const val KEY_ACTIVE_FOLDER = "active_folder"

    const val ANALYSIS_VERSION = 2

    private const val COORD_TOLERANCE = 0.0015
    private const val PRE_TIME_TOLERANCE_MS = 5_000L
    private const val EXTRA_AFTER_VIDEO_MS = 120_000L
    private const val HARD_MAX_MATCH_MS = 5 * 60_000L

    data class MonitorConfig(
        val active: Boolean,
        val folderUri: String?,
        val status: String
    )

    @Synchronized
    fun ensureVersion(context: Context) {
        val p = prefs(context)
        val current = p.getInt(KEY_VERSION, 0)
        if (current == ANALYSIS_VERSION) return

        // v0.9.1 ile Pole verileri klasör URI'sine göre izole ediliyor.
        // Eski tek-havuz kayıtlarını taşımıyoruz; yanlış klasör karışımı kalmasın.
        p.edit()
            .clear()
            .putInt(KEY_VERSION, ANALYSIS_VERSION)
            .putBoolean(KEY_MONITOR_ACTIVE, false)
            .putString(KEY_MONITOR_STATUS, "Pole klasör izolasyonu etkinleştirildi")
            .apply()
    }

    @Synchronized
    fun setActiveFolder(context: Context, folderUri: String?) {
        ensureVersion(context)
        prefs(context).edit().putString(KEY_ACTIVE_FOLDER, folderUri).apply()
    }

    @Synchronized
    fun getActiveFolder(context: Context): String? {
        ensureVersion(context)
        return prefs(context).getString(KEY_ACTIVE_FOLDER, null)
    }

    @Synchronized
    fun isProcessed(context: Context, uri: String, folderUri: String? = getActiveFolder(context)): Boolean {
        ensureVersion(context)
        return readProcessed(context, folderUri).contains(uri)
    }

    @Synchronized
    fun upsertVideo(context: Context, video: PoleVideoObservation, folderUri: String? = getActiveFolder(context)) {
        ensureVersion(context)
        val records = readRecords(context, folderUri).toMutableList()
        val idx = records.indexOfFirst { it.video.uri == video.uri }
        val record = if (idx >= 0) {
            val old = records[idx]
            old.copy(video = video)
        } else PoleRecord(video = video)
        if (idx >= 0) records[idx] = record else records += record
        writeRecords(context, records, folderUri)
        markProcessed(context, video.uri, folderUri)
        reconcile(context, folderUri)
    }

    @Synchronized
    fun upsertPhoto(context: Context, photo: PolePhotoObservation, folderUri: String? = getActiveFolder(context)) {
        ensureVersion(context)
        val photos = readPhotos(context, folderUri).toMutableList()
        val idx = photos.indexOfFirst { it.uri == photo.uri }
        if (idx >= 0) photos[idx] = photo else photos += photo
        writePhotos(context, photos, folderUri)
        markProcessed(context, photo.uri, folderUri)
        reconcile(context, folderUri)
    }

    /**
     * Eşleşme yalnız GPS + saat ile yapılır.
     * - Boylam ve enlem birlikte tolerans içinde olmalı.
     * - Fotoğraf zamanı video başlangıcından en fazla 5 sn önce olabilir.
     * - Üst sınır video süresi + 120 sn; hiçbir durumda 5 dakikayı aşmaz.
     * - Aynı GPS için birden fazla video varsa zaman farkı en küçük olan seçilir.
     */
    @Synchronized
    fun reconcile(context: Context, folderUri: String? = getActiveFolder(context)) {
        ensureVersion(context)
        val records = readRecords(context, folderUri).toMutableList()
        val photos = readPhotos(context, folderUri).toMutableList()
        if (records.isEmpty() || photos.isEmpty()) return

        val consumedPhotoUris = mutableSetOf<String>()

        for (photo in photos) {
            val pb = photo.boylam ?: continue
            val pe = photo.enlem ?: continue
            val pt = photo.timestampMs ?: continue
            val distance = photo.distanceM ?: continue

            var bestIndex = -1
            var bestDelta = Long.MAX_VALUE

            records.forEachIndexed { index, record ->
                if (record.isMatched) return@forEachIndexed
                val vb = record.video.boylam ?: return@forEachIndexed
                val ve = record.video.enlem ?: return@forEachIndexed
                val vt = record.video.timestampMs ?: return@forEachIndexed
                if (abs(vb - pb) > COORD_TOLERANCE || abs(ve - pe) > COORD_TOLERANCE) return@forEachIndexed

                val duration = record.video.durationMs ?: 0L
                val lower = vt - PRE_TIME_TOLERANCE_MS
                val relativeUpper = vt + duration + EXTRA_AFTER_VIDEO_MS
                val upper = minOf(vt + HARD_MAX_MATCH_MS, relativeUpper.coerceAtLeast(vt + 60_000L))
                if (pt !in lower..upper) return@forEachIndexed

                val delta = abs(pt - vt)
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestIndex = index
                }
            }

            if (bestIndex >= 0) {
                val old = records[bestIndex]
                records[bestIndex] = old.copy(
                    photoUri = photo.uri,
                    photoFileName = photo.fileName,
                    photoTimestampMs = photo.timestampMs,
                    distanceM = distance,
                    matchDeltaSec = bestDelta / 1000L,
                    matchNote = "GPS + saat eşleşti"
                )
                consumedPhotoUris += photo.uri
            }
        }

        if (consumedPhotoUris.isNotEmpty()) {
            writeRecords(context, records, folderUri)
            writePhotos(context, photos.filterNot { it.uri in consumedPhotoUris }, folderUri)
        }
    }

    @Synchronized
    fun getRecords(context: Context, folderUri: String? = getActiveFolder(context)): List<PoleRecord> {
        ensureVersion(context)
        reconcile(context, folderUri)
        return readRecords(context, folderUri).sortedWith(
            compareBy<PoleRecord> { it.video.timestampMs ?: Long.MAX_VALUE }
                .thenBy { it.video.fileName }
        )
    }

    @Synchronized
    fun getPendingPhotos(context: Context, folderUri: String? = getActiveFolder(context)): List<PolePhotoObservation> {
        ensureVersion(context)
        return readPhotos(context, folderUri)
    }

    @Synchronized
    fun clearPoleData(context: Context, folderUri: String? = getActiveFolder(context)) {
        ensureVersion(context)
        val suffix = scopeSuffix(folderUri)
        prefs(context).edit()
            .remove(KEY_RECORDS + suffix)
            .remove(KEY_PHOTOS + suffix)
            .remove(KEY_PROCESSED + suffix)
            .apply()
    }

    @Synchronized
    fun saveMonitorConfig(context: Context, active: Boolean, folderUri: String?, status: String) {
        ensureVersion(context)
        prefs(context).edit()
            .putBoolean(KEY_MONITOR_ACTIVE, active)
            .putString(KEY_MONITOR_FOLDER, folderUri)
            .putString(KEY_MONITOR_STATUS, status)
            .apply()
    }

    @Synchronized
    fun setMonitorStatus(context: Context, active: Boolean, status: String) {
        ensureVersion(context)
        prefs(context).edit()
            .putBoolean(KEY_MONITOR_ACTIVE, active)
            .putString(KEY_MONITOR_STATUS, status)
            .apply()
    }

    @Synchronized
    fun getMonitorConfig(context: Context): MonitorConfig {
        ensureVersion(context)
        val p = prefs(context)
        return MonitorConfig(
            active = p.getBoolean(KEY_MONITOR_ACTIVE, false),
            folderUri = p.getString(KEY_MONITOR_FOLDER, null),
            status = p.getString(KEY_MONITOR_STATUS, "Hazır") ?: "Hazır"
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Her seçilen klasör kendi kayıt havuzuna sahiptir. Klasör adı değil, tam URI esas alınır.
     * Böylece aynı/benzer isimli iki klasör kesinlikle tek firma/iş kabul edilmez.
     */
    private fun scopeSuffix(folderUri: String?): String {
        val folder = folderUri ?: return "__NO_FOLDER"
        return "__" + Integer.toHexString(folder.hashCode())
    }

    private fun scopedKey(base: String, folderUri: String?): String = base + scopeSuffix(folderUri)

    private fun markProcessed(context: Context, uri: String, folderUri: String?) {
        val set = readProcessed(context, folderUri).toMutableSet()
        set += uri
        prefs(context).edit().putString(scopedKey(KEY_PROCESSED, folderUri), JSONArray(set.toList()).toString()).apply()
    }

    private fun readProcessed(context: Context, folderUri: String?): Set<String> {
        val raw = prefs(context).getString(scopedKey(KEY_PROCESSED, folderUri), null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } catch (_: Exception) { emptySet() }
    }

    private fun readRecords(context: Context, folderUri: String?): List<PoleRecord> {
        val raw = prefs(context).getString(scopedKey(KEY_RECORDS, folderUri), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) add(recordFromJson(arr.getJSONObject(i))) }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeRecords(context: Context, records: List<PoleRecord>, folderUri: String?) {
        val arr = JSONArray()
        records.forEach { arr.put(recordToJson(it)) }
        prefs(context).edit().putString(scopedKey(KEY_RECORDS, folderUri), arr.toString()).apply()
    }

    private fun readPhotos(context: Context, folderUri: String?): List<PolePhotoObservation> {
        val raw = prefs(context).getString(scopedKey(KEY_PHOTOS, folderUri), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) add(photoFromJson(arr.getJSONObject(i))) }
        } catch (_: Exception) { emptyList() }
    }

    private fun writePhotos(context: Context, photos: List<PolePhotoObservation>, folderUri: String?) {
        val arr = JSONArray()
        photos.forEach { arr.put(photoToJson(it)) }
        prefs(context).edit().putString(scopedKey(KEY_PHOTOS, folderUri), arr.toString()).apply()
    }

    private fun recordToJson(r: PoleRecord): JSONObject = JSONObject().apply {
        put("video", videoToJson(r.video))
        putNullable("photoUri", r.photoUri)
        putNullable("photoFileName", r.photoFileName)
        putNullable("photoTimestampMs", r.photoTimestampMs)
        putNullable("distanceM", r.distanceM)
        putNullable("matchDeltaSec", r.matchDeltaSec)
        putNullable("matchNote", r.matchNote)
    }

    private fun recordFromJson(o: JSONObject): PoleRecord = PoleRecord(
        video = videoFromJson(o.getJSONObject("video")),
        photoUri = o.optNullableString("photoUri"),
        photoFileName = o.optNullableString("photoFileName"),
        photoTimestampMs = o.optNullableLong("photoTimestampMs"),
        distanceM = o.optNullableDouble("distanceM"),
        matchDeltaSec = o.optNullableLong("matchDeltaSec"),
        matchNote = o.optNullableString("matchNote")
    )

    private fun videoToJson(v: PoleVideoObservation): JSONObject = JSONObject().apply {
        put("uri", v.uri); put("fileName", v.fileName)
        putNullable("isAdi", v.isAdi); putNullable("tarih", v.tarih); putNullable("yon", v.yon)
        putNullable("mahal", v.mahal); putNullable("hat", v.hat); putNullable("boruTipi", v.boruTipi)
        putNullable("boruMalzemesi", v.boruMalzemesi); putNullable("capMm", v.capMm)
        putNullable("boruDerinligi", v.boruDerinligi); putNullable("isinSahibi", v.isinSahibi)
        putNullable("operator", v.operator); putNullable("boylam", v.boylam); putNullable("enlem", v.enlem)
        putNullable("timestampMs", v.timestampMs); putNullable("durationMs", v.durationMs); putNullable("error", v.error)
    }

    private fun videoFromJson(o: JSONObject) = PoleVideoObservation(
        uri = o.optString("uri"), fileName = o.optString("fileName"),
        isAdi = o.optNullableString("isAdi"), tarih = o.optNullableString("tarih"), yon = o.optNullableString("yon"),
        mahal = o.optNullableString("mahal"), hat = o.optNullableString("hat"), boruTipi = o.optNullableString("boruTipi"),
        boruMalzemesi = o.optNullableString("boruMalzemesi"), capMm = o.optNullableInt("capMm"),
        boruDerinligi = o.optNullableString("boruDerinligi"), isinSahibi = o.optNullableString("isinSahibi"),
        operator = o.optNullableString("operator"), boylam = o.optNullableDouble("boylam"), enlem = o.optNullableDouble("enlem"),
        timestampMs = o.optNullableLong("timestampMs"), durationMs = o.optNullableLong("durationMs"), error = o.optNullableString("error")
    )

    private fun photoToJson(p: PolePhotoObservation): JSONObject = JSONObject().apply {
        put("uri", p.uri); put("fileName", p.fileName)
        putNullable("boylam", p.boylam); putNullable("enlem", p.enlem); putNullable("timestampMs", p.timestampMs)
        putNullable("distanceM", p.distanceM); putNullable("error", p.error)
    }

    private fun photoFromJson(o: JSONObject) = PolePhotoObservation(
        uri = o.optString("uri"), fileName = o.optString("fileName"),
        boylam = o.optNullableDouble("boylam"), enlem = o.optNullableDouble("enlem"),
        timestampMs = o.optNullableLong("timestampMs"), distanceM = o.optNullableDouble("distanceM"),
        error = o.optNullableString("error")
    )

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }
    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
    private fun JSONObject.optNullableDouble(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }
    private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
}
