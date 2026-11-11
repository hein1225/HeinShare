package com.hishare.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 从 SAF Uri 读取文件信息（显示名 / 大小 / 类型）。
 *
 * 文件来自系统文件选择器或别的文件管理器分享过来的 Uri，
 * 所以一律走 ContentResolver 查 OpenableColumns。
 */
object MediaStoreRepo {

    /** 从 SAF Uri 读取显示名。 */
    fun queryName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    /**
     * 从 SAF Uri 读取大小。
     *
     * 有些提供方不给 SIZE 列，这时退回用 AssetFileDescriptor 量真实长度，
     * 否则会被当成空文件发出去。
     */
    fun querySize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !cursor.isNull(idx)) {
                            val size = cursor.getLong(idx)
                            if (size > 0) return size
                        }
                    }
                }
        } catch (_: Exception) {
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** 取 MIME 类型，拿不到就按后缀猜。 */
    fun queryMime(context: Context, uri: Uri, name: String): String {
        try {
            context.contentResolver.getType(uri)?.let { if (it.isNotBlank()) return it }
        } catch (_: Exception) {
        }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic" -> "image/*"
            "mp4", "mov", "mkv", "avi", "webm" -> "video/*"
            "mp3", "aac", "wav", "flac" -> "audio/*"
            "pdf" -> "application/pdf"
            "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> "application/*"
            else -> "*/*"
        }
    }
}
