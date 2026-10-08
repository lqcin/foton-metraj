package com.foton.crawlermetraj

data class PoleVideoObservation(
    val uri: String,
    val fileName: String,
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
    val operator: String? = null,
    val boylam: Double? = null,
    val enlem: Double? = null,
    val timestampMs: Long? = null,
    val durationMs: Long? = null,
    val error: String? = null
)

data class PolePhotoObservation(
    val uri: String,
    val fileName: String,
    val boylam: Double? = null,
    val enlem: Double? = null,
    val timestampMs: Long? = null,
    val distanceM: Double? = null,
    val error: String? = null
)

data class PoleRecord(
    val video: PoleVideoObservation,
    val photoUri: String? = null,
    val photoFileName: String? = null,
    val photoTimestampMs: Long? = null,
    val distanceM: Double? = null,
    val matchDeltaSec: Long? = null,
    val matchNote: String? = null
) {
    val isMatched: Boolean
        get() = photoUri != null && distanceM != null

    val status: String
        get() = when {
            video.boylam == null || video.enlem == null || video.timestampMs == null -> "Video GPS/saat okunamadı"
            isMatched -> "Tamamlandı"
            else -> "Fotoğraf bekleniyor"
        }
}
