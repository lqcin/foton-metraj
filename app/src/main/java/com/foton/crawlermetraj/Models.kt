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
    /**
     * v0.7.1 başlangıç kuralı:
     * - firstMeter = çoklu kare kontrolünde seçilen HAM başlangıç sayaç değeri.
     * - |firstMeter| < 4.00 m ise hesap başlangıcı 0.00 m kabul edilir.
     * - Aksi halde önceki saha kuralına göre 0.50 m başlangıç payı eklenir ve
     *   çekim yönüne göre hesap başlangıcı firstMeter değerinin 0.50 m gerisine alınır.
     */
    val effectiveFirstMeter: Double?
        get() {
            val first = firstMeter ?: return null
            val last = lastMeter ?: return null
            if (kotlin.math.abs(first) < 4.0) return 0.0
            return if (last >= first) first - 0.50 else first + 0.50
        }

    /** Tek hesap kaynağı: son sayaç ile düzeltilmiş başlangıç arasındaki mutlak fark. */
    val metraj: Double?
        get() {
            val start = effectiveFirstMeter ?: return null
            val last = lastMeter ?: return null
            return kotlin.math.abs(last - start)
        }

    val isSuccess: Boolean
        get() = firstMeter != null && lastMeter != null && metraj != null
}
