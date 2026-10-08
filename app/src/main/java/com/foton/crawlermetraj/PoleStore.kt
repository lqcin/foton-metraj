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

    const val ANALYSIS_VERSION = 1

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
        p.edit()
            .putInt(KEY_VERSION, ANALYSIS_VERSION)
            .remove(KEY_RECORDS)
            .remove(KEY_PHOTOS)
            .remove(KEY_PROCESSED)
            .putBoolean(KEY_MONITOR_ACTIVE, false)
            .putString(KEY_MONITOR_STATUS, "Pole analiz sürümü yenilendi")
            .apply()
    }

    @Synchronized
    fun isProcessed(context: Context, uri: String): Boolean {
        ensureVersion(context)
        return readProcessed(context).contains(uri)
    }

    @Synchronized
    fun upsertVideo(context: Context, video: PoleVideoObservation) {
        ensureVersion(context)
        val records = readRecords(context).toMutableList()
        val idx = records.indexOfFirst { it.video.uri == video.uri }
        val record = if (idx >= 0) {
            val old = records[idx]
            old.copy(video = video)
        } else PoleRecord(video = video)
        if (idx >= 0) records[idx] = record else records += record
        writeRecords(context, records)
        markProcessed(context, video.uri)
        reconcile(context)
    }

    @Synchronized
    fun upsertPhoto(context: Context, photo: PolePhotoObservation) {
        ensureVersion(context)
        val photos = readPhotos(context).toMutableList()
        val idx = photos.indexOfFirst { it.uri == photo.uri }
        if (idx >= 0) photos[idx] = photo else photos += photo
        writePhotos(context, photos)
        markProcessed(context, photo.uri)
        reconcile(context)
    }

    /**
     * Eşleşme yalnız GPS + saat ile yapılır.
     * - Boylam ve enlem birlikte tolerans içinde olmalı.
     * - Fotoğraf zamanı video başlangıcından en fazla 5 sn önce olabilir.
     * - Üst sınır video süresi + 120 sn; hiçbir durumda 5 dakikayı aşmaz.
     * - Aynı GPS için birden fazla video varsa zaman farkı en küçük olan seçilir.
     */
    @Synchronized
    fun reconcile(context: Context) {
        ensureVersion(context)
        val records = readRecords(context).toMutableList()
        val photos = readPhotos(context).toMutableList()
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
            writeRecords(context, records)
            writePhotos(context, photos.filterNot { it.uri in consumedPhotoUris })
        }
    }

    @Synchronized
    fun getRecords(context: Context): List<PoleRecord> {
        ensureVersion(context)
        reconcile(context)
        return readRecords(context).sortedWith(
            compareBy<PoleRecord> { it.video.timestampMs ?: Long.MAX_VALUE }
                .thenBy { it.video.fileName }
        )
    }

    @Synchronized
    fun getPendingPhotos(context: Context): List<PolePhotoObservation> {
        ensureVersion(context)
        return readPhotos(context)
    }

    @Synchronized
    fun clearPoleData(context: Context) {
        prefs(context).edit()
            .remove(KEY_RECORDS)
            .remove(KEY_PHOTOS)
            .remove(KEY_PROCESSED)
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

    private fun markProcessed(context: Context, uri: String) {
        val set = readProcessed(context).toMutableSet()
        set += uri
        prefs(context).edit().putString(KEY_PROCESSED, JSONArray(set.toList()).toString()).apply()
    }

    private fun readProcessed(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_PROCESSED, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } catch (_: Exception) { emptySet() }
    }

    private fun readRecords(context: Context): List<PoleRecord> {
        val raw = prefs(context).getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) add(recordFromJson(arr.getJSONObject(i))) }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeRecords(context: Context, records: List<PoleRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(recordToJson(it)) }
        prefs(context).edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    private fun readPhotos(context: Context): List<PolePhotoObservation> {
        val raw = prefs(context).getString(KEY_PHOTOS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) add(photoFromJson(arr.getJSONObject(i))) }
        } catch (_: Exception) { emptyList() }
    }

    private fun writePhotos(context: Context, photos: List<PolePhotoObservation>) {
        val arr = JSONArray()
        photos.forEach { arr.put(photoToJson(it)) }
        prefs(context).edit().putString(KEY_PHOTOS, arr.toString()).apply()
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
