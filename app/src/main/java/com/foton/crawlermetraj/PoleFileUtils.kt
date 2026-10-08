package com.foton.crawlermetraj

import androidx.documentfile.provider.DocumentFile
import java.util.Locale

object PoleFileUtils {
    private val videos = setOf("mp4", "mov", "mkv", "avi", "m4v", "3gp")
    private val images = setOf("jpg", "jpeg", "png", "webp", "bmp")

    enum class Kind { VIDEO, IMAGE }

    data class MediaFile(val file: DocumentFile, val kind: Kind)

    fun collect(folder: DocumentFile): List<MediaFile> {
        val out = mutableListOf<MediaFile>()
        fun walk(node: DocumentFile) {
            if (node.isDirectory) {
                node.listFiles().forEach { walk(it) }
            } else if (node.isFile) {
                val ext = node.name?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: ""
                when {
                    ext in videos -> out += MediaFile(node, Kind.VIDEO)
                    ext in images -> out += MediaFile(node, Kind.IMAGE)
                }
            }
        }
        walk(folder)
        return out.sortedWith(compareBy<MediaFile>({ it.file.lastModified() }, { it.file.name ?: "" }))
    }
}
