package vn.lienson.boxstation

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

sealed class WebDavTarget {
    data class Root(val basePath: String) : WebDavTarget()
    data class DirTarget(val dir: File, val webDavPath: String) : WebDavTarget()
    data class FileTarget(val file: File, val webDavPath: String) : WebDavTarget()
    object NotFound : WebDavTarget()
}

object WebDavHelper {

    private val rfc1123Format: SimpleDateFormat
        get() {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            return format
        }

    fun handleOptions(out: OutputStream) {
        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("DAV: 1, 2\r\n")
        response.append("Allow: OPTIONS, GET, HEAD, PROPFIND\r\n")
        response.append("MS-Author-Via: DAV\r\n")
        response.append("Content-Length: 0\r\n")
        response.append("Access-Control-Allow-Origin: *\r\n")
        response.append("Access-Control-Allow-Methods: OPTIONS, GET, HEAD, PROPFIND\r\n")
        response.append("Access-Control-Allow-Headers: Depth, Range, Authorization, Content-Type, Accept\r\n")
        response.append("Connection: close\r\n\r\n")
        out.write(response.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    fun handlePropfind(context: Context, out: OutputStream, rawPath: String, depthHeader: String?) {
        val target = resolveWebDavTarget(context, rawPath)
        val depth = depthHeader?.trim() ?: "1"

        if (target is WebDavTarget.NotFound) {
            val notFound = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: 9\r\nConnection: close\r\n\r\nNot Found"
            out.write(notFound.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            return
        }

        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        xml.append("<D:multistatus xmlns:D=\"DAV:\">\n")

        when (target) {
            is WebDavTarget.Root -> {
                // 1. Root collection response
                val rootHref = if (target.basePath.isEmpty()) "/" else ensureTrailingSlash(target.basePath)
                xml.append(buildCollectionResponse(rootHref, "BoxStation Media", System.currentTimeMillis()))

                // 2. Immediate children (Storage drives) if depth != "0"
                if (depth != "0") {
                    val drives = StorageManagerHelper.getStorageDrives(context)
                    for (drive in drives) {
                        val driveHref = "$rootHref${encodeSegment(drive.id)}/"
                        val lastMod = try {
                            val f = File(drive.path)
                            if (f.exists()) f.lastModified() else System.currentTimeMillis()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                        xml.append(buildCollectionResponse(driveHref, drive.name, lastMod))
                    }
                }
            }

            is WebDavTarget.DirTarget -> {
                val dirHref = ensureTrailingSlash(encodeWebDavPath(target.webDavPath))
                val dirName = if (target.dir.name.isNotEmpty()) target.dir.name else target.webDavPath.trim('/')
                xml.append(buildCollectionResponse(dirHref, dirName, target.dir.lastModified()))

                if (depth != "0") {
                    val children = target.dir.listFiles()
                    if (children != null) {
                        // Sort: directories first, then files alphabetically
                        val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
                        for (child in sorted) {
                            val childName = child.name
                            if (childName.startsWith(".")) continue // Skip hidden dotfiles
                            val childHref = if (child.isDirectory) {
                                "$dirHref${encodeSegment(childName)}/"
                            } else {
                                "$dirHref${encodeSegment(childName)}"
                            }

                            if (child.isDirectory) {
                                xml.append(buildCollectionResponse(childHref, childName, child.lastModified()))
                            } else {
                                val mimeType = StorageManagerHelper.getMimeType(childName)
                                xml.append(buildFileResponse(childHref, childName, child.length(), mimeType, child.lastModified()))
                            }
                        }
                    }
                }
            }

            is WebDavTarget.FileTarget -> {
                val fileHref = encodeWebDavPath(target.webDavPath)
                val mimeType = StorageManagerHelper.getMimeType(target.file.name)
                xml.append(buildFileResponse(fileHref, target.file.name, target.file.length(), mimeType, target.file.lastModified()))
            }

            is WebDavTarget.NotFound -> {
                return
            }
        }

        xml.append("</D:multistatus>")

        val xmlBytes = xml.toString().toByteArray(StandardCharsets.UTF_8)
        val header = StringBuilder()
        header.append("HTTP/1.1 207 Multi-Status\r\n")
        header.append("Content-Type: application/xml; charset=utf-8\r\n")
        header.append("Content-Length: ${xmlBytes.size}\r\n")
        header.append("Access-Control-Allow-Origin: *\r\n")
        header.append("Access-Control-Allow-Methods: OPTIONS, GET, HEAD, PROPFIND\r\n")
        header.append("Access-Control-Allow-Headers: Depth, Range, Authorization, Content-Type, Accept\r\n")
        header.append("Connection: close\r\n\r\n")

        out.write(header.toString().toByteArray(StandardCharsets.UTF_8))
        out.write(xmlBytes)
        out.flush()
    }

    fun handleHead(out: OutputStream, file: File) {
        val fileLength = file.length()
        val mimeType = StorageManagerHelper.getMimeType(file.name)
        val lastMod = rfc1123Format.format(Date(file.lastModified()))

        val header = StringBuilder()
        header.append("HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: $mimeType\r\n")
        header.append("Content-Length: $fileLength\r\n")
        header.append("Accept-Ranges: bytes\r\n")
        header.append("Last-Modified: $lastMod\r\n")
        header.append("Access-Control-Allow-Origin: *\r\n")
        header.append("Connection: close\r\n\r\n")

        out.write(header.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    fun resolveWebDavTarget(context: Context, rawPath: String): WebDavTarget {
        val decodedPath = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (e: Exception) {
            rawPath
        }

        val basePrefix = when {
            decodedPath.startsWith("/webdav") -> "/webdav"
            decodedPath.startsWith("/dav") -> "/dav"
            else -> ""
        }

        val relPath = decodedPath.removePrefix(basePrefix).trim('/')
        if (relPath.isEmpty()) {
            return WebDavTarget.Root(basePrefix)
        }

        val firstPart = relPath.substringBefore('/')
        val subPath = if (relPath.contains('/')) relPath.substringAfter('/') else ""

        val drives = StorageManagerHelper.getStorageDrives(context)

        // 1. Check if firstPart matches a known drive
        var matchedDrive: StorageDrive? = null
        for (d in drives) {
            if (d.id.equals(firstPart, ignoreCase = true) ||
                File(d.path).name.equals(firstPart, ignoreCase = true) ||
                (firstPart.equals("internal", ignoreCase = true) && !d.isUsb)
            ) {
                matchedDrive = d
                break
            }
        }

        if (matchedDrive != null) {
            val targetFile = if (subPath.isEmpty()) File(matchedDrive.path) else File(matchedDrive.path, subPath)
            if (targetFile.exists()) {
                val fullWebDav = "$basePrefix/$firstPart${if (subPath.isNotEmpty()) "/$subPath" else ""}"
                return if (targetFile.isDirectory) {
                    WebDavTarget.DirTarget(targetFile, fullWebDav)
                } else {
                    WebDavTarget.FileTarget(targetFile, fullWebDav)
                }
            }
        }

        // 2. Direct storage paths (e.g. /webdav/storage/7C6E55646E55186A/Film/...)
        if (firstPart.equals("storage", ignoreCase = true)) {
            val targetFile = File("/storage", subPath)
            if (targetFile.exists()) {
                val fullWebDav = "$basePrefix/storage${if (subPath.isNotEmpty()) "/$subPath" else ""}"
                return if (targetFile.isDirectory) {
                    WebDavTarget.DirTarget(targetFile, fullWebDav)
                } else {
                    WebDavTarget.FileTarget(targetFile, fullWebDav)
                }
            }
        }

        // 3. Direct sdcard path
        if (firstPart.equals("sdcard", ignoreCase = true)) {
            val targetFile = File("/sdcard", subPath)
            if (targetFile.exists()) {
                val fullWebDav = "$basePrefix/sdcard${if (subPath.isNotEmpty()) "/$subPath" else ""}"
                return if (targetFile.isDirectory) {
                    WebDavTarget.DirTarget(targetFile, fullWebDav)
                } else {
                    WebDavTarget.FileTarget(targetFile, fullWebDav)
                }
            }
        }

        // 4. Raw absolute path
        val directFile = File("/$relPath")
        if (directFile.exists()) {
            val fullWebDav = "$basePrefix/$relPath"
            return if (directFile.isDirectory) {
                WebDavTarget.DirTarget(directFile, fullWebDav)
            } else {
                WebDavTarget.FileTarget(directFile, fullWebDav)
            }
        }

        return WebDavTarget.NotFound
    }

    private fun buildCollectionResponse(href: String, displayName: String, lastModified: Long): String {
        val safeHref = href
        val safeName = escapeXml(displayName)
        val modDate = rfc1123Format.format(Date(lastModified))

        return """  <D:response>
    <D:href>$safeHref</D:href>
    <D:propstat>
      <D:prop>
        <D:displayname>$safeName</D:displayname>
        <D:resourcetype><D:collection/></D:resourcetype>
        <D:getlastmodified>$modDate</D:getlastmodified>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
"""
    }

    private fun buildFileResponse(href: String, displayName: String, contentLength: Long, contentType: String, lastModified: Long): String {
        val safeHref = href
        val safeName = escapeXml(displayName)
        val modDate = rfc1123Format.format(Date(lastModified))

        return """  <D:response>
    <D:href>$safeHref</D:href>
    <D:propstat>
      <D:prop>
        <D:displayname>$safeName</D:displayname>
        <D:resourcetype/>
        <D:getcontentlength>$contentLength</D:getcontentlength>
        <D:getcontenttype>$contentType</D:getcontenttype>
        <D:getlastmodified>$modDate</D:getlastmodified>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
"""
    }

    private fun ensureTrailingSlash(path: String): String {
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun encodeSegment(segment: String): String {
        return try {
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            segment
        }
    }

    private fun encodeWebDavPath(path: String): String {
        val segments = path.split("/").map { segment ->
            if (segment.isEmpty()) "" else encodeSegment(segment)
        }
        return segments.joinToString("/")
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
