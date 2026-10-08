package com.foton.crawlermetraj

import androidx.documentfile.provider.DocumentFile
import java.util.Locale

object PoleFileUtils {
    private val videos = setOf("mp4", "mov", "mkv", "avi", "m4v", "3gp")
    private val images = setOf("jpg", "jpeg", "png", "webp", "bmp")

    enum class Kind { VIDEO, IMAGE }

    data class MediaFile(val file: DocumentFile, val kind: Kind)

    /**
     * Yalnız seçilen klasörün doğrudan içindeki medya dosyalarını toplar.
     * Alt klasörlere girmez. Böylece benzer isimli firma/iş klasörleri birbirine karışmaz.
     */
    fun collect(folder: DocumentFile): List<MediaFile> {
        val out = mutableListOf<MediaFile>()
        folder.listFiles().forEach { node ->
            if (!node.isFile) return@forEach
            val ext = node.name?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: ""
            when {
                ext in videos -> out += MediaFile(node, Kind.VIDEO)
                ext in images -> out += MediaFile(node, Kind.IMAGE)
            }
        }
        return out.sortedWith(compareBy<MediaFile>({ it.file.lastModified() }, { it.file.name ?: "" }))
    }
}
