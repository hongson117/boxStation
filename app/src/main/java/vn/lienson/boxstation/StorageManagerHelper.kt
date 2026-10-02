package vn.lienson.boxstation

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

data class StorageDrive(
    val id: String,
    val name: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val isUsb: Boolean
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedPercent: Int get() = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
    val totalFormatted: String get() = StorageManagerHelper.formatSize(totalBytes)
    val freeFormatted: String get() = StorageManagerHelper.formatSize(freeBytes)
    val usedFormatted: String get() = StorageManagerHelper.formatSize(usedBytes)
}

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String,
    val isMedia: Boolean,
    val isVideo: Boolean,
    val isAudio: Boolean,
    val isImage: Boolean,
    val isApk: Boolean
) {
    val sizeFormatted: String get() = if (isDirectory) "--" else StorageManagerHelper.formatSize(sizeBytes)
    val dateFormatted: String get() = StorageManagerHelper.formatDate(lastModified)
}

object StorageManagerHelper {

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "3gp")
    private val AUDIO_EXTENSIONS = setOf("mp3", "aac", "wav", "flac", "ogg", "m4a", "wma")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg")

    fun getStorageDrives(context: Context): List<StorageDrive> {
        val drives = mutableListOf<StorageDrive>()
        val seenPaths = mutableSetOf<String>()

        // 1. Internal Storage
        try {
            val internalFile = Environment.getExternalStorageDirectory()
            if (internalFile != null && internalFile.exists() && seenPaths.add(internalFile.canonicalPath)) {
                val stat = StatFs(internalFile.path)
                val total = stat.totalBytes
                val free = stat.availableBytes
                drives.add(
                    StorageDrive(
                        id = "internal",
                        name = "Bộ nhớ trong (Internal)",
                        path = internalFile.canonicalPath,
                        totalBytes = total,
                        freeBytes = free,
                        isUsb = false
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e("Storage", "Lỗi lấy internal storage: ${e.message}")
        }

        // 2. USB drives via context.getExternalFilesDirs
        try {
            val externalDirs = context.getExternalFilesDirs(null)
            externalDirs?.forEach { dir ->
                if (dir != null) {
                    val fullPath = dir.canonicalPath
                    // Match /storage/XXXX-XXXX
                    val match = Regex("""^(/storage/[^/]+)""").find(fullPath)
                    if (match != null) {
                        val rootPath = match.groupValues[1]
                        if (!rootPath.contains("emulated") && seenPaths.add(rootPath)) {
                            val rootFile = File(rootPath)
                            if (rootFile.exists() && rootFile.canRead()) {
                                val stat = try { StatFs(rootPath) } catch (e: Exception) { null }
                                val total = stat?.totalBytes ?: 0L
                                val free = stat?.availableBytes ?: 0L
                                val driveId = "usb_" + rootFile.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                                drives.add(
                                    StorageDrive(
                                        id = driveId,
                                        name = "Ổ cứng USB (${rootFile.name})",
                                        path = rootPath,
                                        totalBytes = total,
                                        freeBytes = free,
                                        isUsb = true
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Storage", "Lỗi quét getExternalFilesDirs: ${e.message}")
        }

        // 3. Scan /storage directly
        try {
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                storageDir.listFiles()?.forEach { file ->
                    val path = file.canonicalPath
                    val name = file.name
                    if (!name.equals("emulated", ignoreCase = true) &&
                        !name.equals("self", ignoreCase = true) &&
                        !name.equals("knox", ignoreCase = true) &&
                        file.isDirectory && file.canRead() &&
                        seenPaths.add(path)
                    ) {
                        val stat = try { StatFs(path) } catch (e: Exception) { null }
                        val total = stat?.totalBytes ?: 0L
                        val free = stat?.availableBytes ?: 0L
                        val driveId = "usb_" + name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                        drives.add(
                            StorageDrive(
                                id = driveId,
                                name = "Ổ cứng USB ($name)",
                                path = path,
                                totalBytes = total,
                                freeBytes = free,
                                isUsb = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Storage", "Lỗi quét /storage: ${e.message}")
        }

        // 4. Scan /mnt/media_rw
        try {
            val mediaRwDir = File("/mnt/media_rw")
            if (mediaRwDir.exists() && mediaRwDir.isDirectory) {
                mediaRwDir.listFiles()?.forEach { file ->
                    val path = file.canonicalPath
                    if (file.isDirectory && file.canRead() && seenPaths.add(path)) {
                        val stat = try { StatFs(path) } catch (e: Exception) { null }
                        val total = stat?.totalBytes ?: 0L
                        val free = stat?.availableBytes ?: 0L
                        drives.add(
                            StorageDrive(
                                id = "mnt_" + file.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), ""),
                                name = "Ổ cứng MediaRW (${file.name})",
                                path = path,
                                totalBytes = total,
                                freeBytes = free,
                                isUsb = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored if permission denied on non-root
        }

        return drives
    }

    fun listDirectory(dirPath: String, showHidden: Boolean = false): List<FileItem> {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val files = dir.listFiles()
        if (files == null) {
            AppLogger.w("Storage", "⚠️ Không thể đọc thư mục '$dirPath' (canRead=${dir.canRead()}, exists=${dir.exists()}). Kiểm tra quyền bộ nhớ!")
            return emptyList()
        }

        return files.filter { file ->
            if (showHidden) true
            else {
                val name = file.name
                !name.startsWith("$") &&
                !name.startsWith(".") &&
                !name.equals("System Volume Information", ignoreCase = true) &&
                !name.equals("LOST.DIR", ignoreCase = true) &&
                !name.equals("Thumbs.db", ignoreCase = true) &&
                !name.equals("desktop.ini", ignoreCase = true)
            }
        }.map { file ->
            val ext = if (file.isDirectory) "" else file.extension.lowercase(Locale.ROOT)
            val isVid = VIDEO_EXTENSIONS.contains(ext)
            val isAud = AUDIO_EXTENSIONS.contains(ext)
            val isImg = IMAGE_EXTENSIONS.contains(ext)
            val isApk = ext == "apk"

            FileItem(
                name = file.name,
                path = file.canonicalPath,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
                extension = ext,
                isMedia = isVid || isAud || isImg,
                isVideo = isVid,
                isAudio = isAud,
                isImage = isImg,
                isApk = isApk
            )
        }.sortedWith(
            compareBy<FileItem> { !it.isDirectory }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }

    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return "--"
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "json" -> "application/json"
            "txt", "log" -> "text/plain; charset=utf-8"
            "html" -> "text/html; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
