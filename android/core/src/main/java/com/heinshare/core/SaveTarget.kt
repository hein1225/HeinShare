package com.heinshare.core

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 落盘目标抽象：把接收到的文件写进「某个可写位置」。
 *
 * 位置可能是三种：
 *   - 应用私有目录（无需任何权限）
 *   - 系统「下载」目录（MediaStore / 旧版公共目录）
 *   - 用户通过系统目录选择器（SAF）指定的任意目录
 *
 * 三者对外接口一致，协议层不需要知道文件最终落在哪。
 */
interface SaveTarget {

    /** 打开一个待写入的句柄。relPath 用 '/' 分隔的相对路径。 */
    fun open(relPath: String): SaveHandle

    /** 给设置页看的可读描述。 */
    fun describe(): String
}

/**
 * 一个已经落盘的文件。
 *
 * [target] 是「能直接拿去打开」的标识：
 *   - MediaStore / SAF 落盘 → `content://...` URI
 *   - 私有目录 / 旧版公共下载目录 → 文件绝对路径（App 层用 FileProvider 转成 URI）
 *
 * [display] 是给人看的路径描述（记录页显示用）。
 */
data class SavedFile(
    val name: String,
    val target: String,
    val display: String,
    val mime: String = "application/octet-stream"
)

/** 一次写入的句柄：先写 part，写完再 commit 转正。 */
interface SaveHandle {
    val output: OutputStream

    /** 提交，返回最终位置的可读标识（路径或 URI）。 */
    fun commit(): SavedFile

    /** 放弃（出错的残包）。 */
    fun abort()
}

// ---------------------------------------------------------------------------
// 1) 应用私有目录
// ---------------------------------------------------------------------------

/** 应用私有目录：Android/data/<包名>/files/<子目录>。 */
class FileSaveTarget(private val destination: File) : SaveTarget {

    override fun describe(): String = destination.absolutePath

    override fun open(relPath: String): SaveHandle {
        val segments = relPath.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: "received.bin"
        val parent = segments.dropLast(1).fold(destination) { acc, s -> File(acc, s) }
        parent.mkdirs()
        val target = Receiver.uniquePath(parent, name)
        val part = File(parent, name + ".part")
        part.delete()
        val stream = FileOutputStream(part)
        return object : SaveHandle {
            override val output: OutputStream = stream
            override fun commit(): SavedFile {
                try { stream.close() } catch (_: Exception) {}
                if (!part.renameTo(target)) {
                    // 跨文件系统等极端情况：退回复制
                    part.copyTo(target, true)
                    part.delete()
                }
                return SavedFile(name, target.absolutePath, target.absolutePath)
            }
            override fun abort() {
                try { stream.close() } catch (_: Exception) {}
                part.delete()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2) 系统「下载」目录
// ---------------------------------------------------------------------------

/**
 * 系统「下载」目录。
 *
 * Android 10（API 29）及以上用 MediaStore 写入，不需要任何权限；
 * Android 9 及以下用公共下载目录，需要 WRITE_EXTERNAL_STORAGE。
 */
class DownloadsSaveTarget(
    private val context: Context,
    private val subDir: String
) : SaveTarget {

    private val api29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 旧版公共下载目录；API29+ 为 null。 */
    private val legacyRoot: File? = if (!api29) {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subDir)
    } else {
        null
    }

    /**
     * 这个位置是否**真的能写**。
     *
     * 不能只看 `File.canWrite()`：API29+ 走 MediaStore，权限是隐式的，
     * 但存储可能已满、或者厂商 ROM 限制了 Downloads 集合。所以这里在
     * API29+ 上真的插一条探针记录再删掉，确认通道可用。
     */
    fun writableLegacy(): Boolean {
        val root = legacyRoot
        if (root != null) {
            if (!root.exists() && !root.mkdirs()) return false
            return root.canWrite()
        }
        return if (api29) probeMediaStore() else true
    }

    /** API29+：往 Downloads/Heinshare 插一条探针，成功即认为可写。 */
    private fun probeMediaStore(): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, ".heinshare-probe")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + subDir
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            try { resolver.openOutputStream(uri)?.close() } catch (_: Exception) {}
            resolver.delete(uri, null, null)
            true
        } catch (_: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            false
        }
    }

    override fun describe(): String = "下载/$subDir/"

    override fun open(relPath: String): SaveHandle {
        return if (api29) openMediaStore(relPath) else openLegacy(relPath)
    }

    // --- API 29+：MediaStore -------------------------------------------------

    private fun openMediaStore(relPath: String): SaveHandle {
        val resolver = context.contentResolver
        val segments = relPath.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: "received.bin"
        val folder = segments.dropLast(1).joinToString("/")
        val relative = buildString {
            append(Environment.DIRECTORY_DOWNLOADS)
            append('/')
            append(subDir)
            if (folder.isNotEmpty()) {
                append('/')
                append(folder)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name + ".part")
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw SaveException("无法在系统「下载」目录创建文件")

        val stream = resolver.openOutputStream(uri)
            ?: throw SaveException("无法写入系统「下载」目录")

        return object : SaveHandle {
            override val output: OutputStream = stream
            override fun commit(): SavedFile {
                try { stream.close() } catch (_: Exception) {}
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                try { resolver.update(uri, done, null, null) } catch (_: Exception) {}
                val shown = "下载/$subDir/${if (folder.isNotEmpty()) folder + "/" else ""}$name"
                return SavedFile(name, uri.toString(), shown, mimeOf(name))
            }
            override fun abort() {
                try { stream.close() } catch (_: Exception) {}
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
        }
    }

    // --- API 28 及以下：公共目录直接写 ---------------------------------------

    private fun openLegacy(relPath: String): SaveHandle {
        val root = legacyRoot ?: throw SaveException("系统「下载」目录不可用")
        val segments = relPath.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: "received.bin"
        val parent = segments.dropLast(1).fold(root) { acc, s -> File(acc, s) }
        parent.mkdirs()
        val target = Receiver.uniquePath(parent, name)
        val part = File(parent, name + ".part")
        part.delete()
        val stream = FileOutputStream(part)
        return object : SaveHandle {
            override val output: OutputStream = stream
            override fun commit(): SavedFile {
                try { stream.close() } catch (_: Exception) {}
                if (!part.renameTo(target)) {
                    part.copyTo(target, true)
                    part.delete()
                }
                return SavedFile(name, target.absolutePath, target.absolutePath, mimeOf(name))
            }
            override fun abort() {
                try { stream.close() } catch (_: Exception) {}
                part.delete()
            }
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }
}

// ---------------------------------------------------------------------------
// 3) 用户通过系统目录选择器指定的目录（SAF）
// ---------------------------------------------------------------------------

/**
 * SAF 目录。用户在系统「选择文件夹」里挑一个目录后即可往里写。
 *
 * 写权限由 ACTION_OPEN_DOCUMENT_TREE 授予的持久化 Uri 权限持有。
 */
class SafSaveTarget(
    private val context: Context,
    private val treeUri: Uri,
    private val label: String
) : SaveTarget {

    private val resolver: ContentResolver = context.contentResolver

    override fun describe(): String = label

    override fun open(relPath: String): SaveHandle {
        val segments = relPath.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: "received.bin"
        val folder = segments.dropLast(1)

        var parentId = DocumentsContract.getTreeDocumentId(treeUri)
        for (seg in folder) {
            parentId = findOrCreateDir(parentId, seg)
        }

        val tempName = name + ".part"
        val tempId = createDocument(parentId, tempName, mimeOf(name))
        val tempUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, tempId)

        val stream = resolver.openOutputStream(tempUri)
            ?: throw SaveException("无法写入所选目录")

        return object : SaveHandle {
            override val output: OutputStream = stream
            override fun commit(): SavedFile {
                try { stream.close() } catch (_: Exception) {}
                val finalName = uniqueName(parentId, name)
                var finalUri = tempUri
                try {
                    val renamed = DocumentsContract.renameDocument(resolver, tempUri, finalName)
                    if (renamed != null) finalUri = renamed
                } catch (_: Exception) {
                    // 重命名失败就保留 .part 名，也不算失败
                }
                return SavedFile(finalName, finalUri.toString(), "$label/$finalName", mimeOf(name))
            }
            override fun abort() {
                try { stream.close() } catch (_: Exception) {}
                try { DocumentsContract.deleteDocument(resolver, tempUri) } catch (_: Exception) {}
            }
        }
    }

    private fun findOrCreateDir(parentId: String, name: String): String {
        findChild(parentId, name)?.let { return it }
        val created = createDocument(parentId, name, DocumentsContract.Document.MIME_TYPE_DIR)
        return created
    }

    /** 在 parentId 下找名为 name 的子文档（目录或文件）。 */
    private fun findChild(parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            while (cursor != null && cursor.moveToNext()) {
                val display = cursor.getString(1) ?: continue
                if (display.equals(name, ignoreCase = true)) return cursor.getString(0)
            }
        } catch (_: Exception) {
        } finally {
            try { cursor?.close() } catch (_: Exception) {}
        }
        return null
    }

    private fun createDocument(parentId: String, name: String, mime: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = DocumentsContract.createDocument(resolver, uri, mime, name)
            ?: throw SaveException("无法在所选目录创建 $name")
        return DocumentsContract.getDocumentId(created)
    }

    /** 生成一个不冲突的文件名（同名则加 (1)、(2)…）。 */
    private fun uniqueName(parentId: String, name: String): String {
        if (findChild(parentId, name) == null) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 1..9999) {
            val candidate = "$stem ($n)$ext"
            if (findChild(parentId, candidate) == null) return candidate
        }
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}

class SaveException(message: String) : Exception(message)
