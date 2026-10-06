package com.foton.crawlermetraj

import androidx.documentfile.provider.DocumentFile
import java.util.Locale

object VideoFileUtils {
    private val supported = setOf("mp4", "mov", "mkv", "avi", "m4v", "3gp")

    fun collectVideos(folder: DocumentFile): List<DocumentFile> {
        val out = mutableListOf<DocumentFile>()

        fun walk(node: DocumentFile) {
            if (node.isDirectory) {
                node.listFiles().forEach { walk(it) }
            } else if (node.isFile) {
                val ext = node.name?.substringAfterLast('.', "")?.lowercase(Locale.US)
                if (ext in supported) out += node
            }
        }

        walk(folder)
        return out.sortedBy { it.name?.lowercase(Locale("tr", "TR")) ?: "" }
    }
}
