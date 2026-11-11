package com.hishare.android

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/** 相册/视频库里的一项。 */
data class MediaItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val isVideo: Boolean
)

/**
 * 从 MediaStore 读取图片 / 视频。
 *
 * 用 ContentResolver.loadThumbnail 缩略图（API 29+），低版本回退到直接 setImageURI。
 */
object MediaStoreRepo {

    fun queryImages(context: Context): List<MediaItem> =
        query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)

    fun queryVideos(context: Context): List<MediaItem> =
        query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)

    fun queryAll(context: Context): List<MediaItem> =
        queryImages(context) + queryVideos(context)

    private fun query(context: Context, collection: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        val result = ArrayList<MediaItem>()
        try {
            context.contentResolver.query(
                collection, projection, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC LIMIT 300"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "file-$id" else "file-$id"
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    result.add(
                        MediaItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = name,
                            size = size,
                            isVideo = isVideo
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // 部分设备没有读媒体库权限，返回空列表即可
        }
        return result
    }

    /** 从 SAF Uri 读取显示名。 */
    fun queryName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: uri.lastPathSegment.orEmpty()
                }
            }
        return uri.lastPathSegment ?: "file"
    }

    /** 从 SAF Uri 读取大小。 */
    fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
                }
            }
        return 0L
    }
}
