package com.foton.crawlermetraj

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object ResultStore {
    private const val PREFS = "foton_crawler_store"
    private const val KEY_RECORDS = "records"
    private const val KEY_PROCESSED = "processed"
    private const val KEY_MONITOR_ACTIVE = "monitor_active"
    private const val KEY_MONITOR_FOLDER = "monitor_folder"
    private const val KEY_MONITOR_FIRMA = "monitor_firma"
    private const val KEY_MONITOR_TARIH = "monitor_tarih"
    private const val KEY_MONITOR_STATUS = "monitor_status"

    data class MonitorConfig(
        val active: Boolean,
        val folderUri: String?,
        val firma: String?,
        val tarih: String?,
        val status: String
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun processKey(firma: String, tarih: String, uri: Uri): String =
        "$firma|$tarih|${uri}"

    @Synchronized
    fun isProcessed(context: Context, firma: String, tarih: String, uri: Uri): Boolean {
        // Eski sürümde OCR başarısız kayıtlar da "işlendi" sayılıyordu.
        // Artık yalnızca ilk+son sayaç değeri bulunan kayıt tamamlanmış kabul edilir.
        val raw = prefs(context).getString(KEY_RECORDS, "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val targetUri = uri.toString()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("firma") != firma || o.optString("tarih") != tarih || o.optString("uri") != targetUri) continue
            val firstOk = o.has("firstMeter") && !o.isNull("firstMeter")
            val lastOk = o.has("lastMeter") && !o.isNull("lastMeter")
            if (firstOk && lastOk) return true
        }
        return false
    }

    @Synchronized
    fun addResult(
        context: Context,
        firma: String,
        tarih: String,
        uri: Uri,
        result: VideoResult
    ): Boolean {
        val p = prefs(context)
        val key = processKey(firma, tarih, uri)
        val existing = try {
            JSONArray(p.getString(KEY_RECORDS, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        // Aynı video daha önce başarısız kaydedildiyse onu güncelle; mükerrer satır oluşturma.
        val records = JSONArray()
        val targetUri = uri.toString()
        for (i in 0 until existing.length()) {
            val old = existing.optJSONObject(i) ?: continue
            val same = old.optString("firma") == firma &&
                old.optString("tarih") == tarih &&
                old.optString("uri") == targetUri
            if (!same) records.put(old)
        }

        val h = result.header
        val obj = JSONObject().apply {
            put("firma", firma)
            put("tarih", tarih)
            put("uri", targetUri)
            put("createdAt", System.currentTimeMillis())
            put("fileName", result.fileName)
            put("parsel", h.parsel ?: JSONObject.NULL)
            put("konum", h.konum ?: JSONObject.NULL)
            put("capMm", h.capMm ?: JSONObject.NULL)
            put("yon", h.yon ?: JSONObject.NULL)
            put("firstMeter", result.firstMeter ?: JSONObject.NULL)
            put("lastMeter", result.lastMeter ?: JSONObject.NULL)
            put("firstTimeMs", result.firstTimeMs ?: JSONObject.NULL)
            put("lastTimeMs", result.lastTimeMs ?: JSONObject.NULL)
            put("error", result.error ?: JSONObject.NULL)
        }
        records.put(obj)

        val processed = p.getStringSet(KEY_PROCESSED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (result.isSuccess) processed.add(key) else processed.remove(key)

        return p.edit()
            .putString(KEY_RECORDS, records.toString())
            .putStringSet(KEY_PROCESSED, processed)
            .commit()
    }

    @Synchronized
    fun getResults(context: Context, firma: String, tarih: String): List<VideoResult> {
        val raw = prefs(context).getString(KEY_RECORDS, "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val rows = mutableListOf<Pair<Long, VideoResult>>()

        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("firma") != firma || o.optString("tarih") != tarih) continue

            val header = HeaderInfo(
                parsel = o.optNullableString("parsel"),
                konum = o.optNullableString("konum"),
                capMm = o.optNullableInt("capMm"),
                yon = o.optNullableString("yon")
            )
            val result = VideoResult(
                fileName = o.optString("fileName", "video"),
                header = header,
                firstMeter = o.optNullableDouble("firstMeter"),
                lastMeter = o.optNullableDouble("lastMeter"),
                firstTimeMs = o.optNullableLong("firstTimeMs"),
                lastTimeMs = o.optNullableLong("lastTimeMs"),
                error = o.optNullableString("error")
            )
            rows += o.optLong("createdAt", 0L) to result
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    fun saveMonitorConfig(
        context: Context,
        active: Boolean,
        folderUri: String?,
        firma: String?,
        tarih: String?,
        status: String
    ) {
        prefs(context).edit()
            .putBoolean(KEY_MONITOR_ACTIVE, active)
            .putString(KEY_MONITOR_FOLDER, folderUri)
            .putString(KEY_MONITOR_FIRMA, firma)
            .putString(KEY_MONITOR_TARIH, tarih)
            .putString(KEY_MONITOR_STATUS, status)
            .apply()
    }

    fun setMonitorStatus(context: Context, active: Boolean, status: String) {
        prefs(context).edit()
            .putBoolean(KEY_MONITOR_ACTIVE, active)
            .putString(KEY_MONITOR_STATUS, status)
            .apply()
    }

    fun getMonitorConfig(context: Context): MonitorConfig {
        val p = prefs(context)
        return MonitorConfig(
            active = p.getBoolean(KEY_MONITOR_ACTIVE, false),
            folderUri = p.getString(KEY_MONITOR_FOLDER, null),
            firma = p.getString(KEY_MONITOR_FIRMA, null),
            tarih = p.getString(KEY_MONITOR_TARIH, null),
            status = p.getString(KEY_MONITOR_STATUS, "Hazır") ?: "Hazır"
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
