package com.foton.crawlermetraj

data class HeaderInfo(
    val parsel: String? = null,
    val konum: String? = null,
    val capMm: Int? = null,
    val yon: String? = null
) {
    fun merge(other: HeaderInfo): HeaderInfo = HeaderInfo(
        parsel = parsel ?: other.parsel,
        konum = konum ?: other.konum,
        capMm = capMm ?: other.capMm,
        yon = yon ?: other.yon
    )
}

data class VideoResult(
    val fileName: String,
    val header: HeaderInfo,
    val firstMeter: Double?,
    val lastMeter: Double?,
    val firstTimeMs: Long?,
    val lastTimeMs: Long?,
    val error: String? = null
) {
    val metraj: Double?
        get() = if (firstMeter != null && lastMeter != null) kotlin.math.abs(lastMeter - firstMeter) else null

    val isSuccess: Boolean
        get() = metraj != null
}
