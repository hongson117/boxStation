package vn.lienson.boxstation

import android.content.Context
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class StationHttpServer(private val context: Context, val port: Int = 8888) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port)
            isRunning = true
            AppLogger.i("Server", "🟢 BoxStation HTTP Server đã khởi động thành công trên cổng $port")

            threadPool.execute {
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        threadPool.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Server", "Lỗi khởi động Server trên cổng $port: ${e.message}", e)
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // pass
        }
        AppLogger.i("Server", "🔴 BoxStation HTTP Server đã dừng")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val inputStream = BufferedInputStream(socket.getInputStream())
            val outputStream = BufferedOutputStream(socket.getOutputStream())

            val rawHeader = readHttpHeader(inputStream)
            if (rawHeader.isEmpty()) {
                socket.close()
                return
            }

            val lines = rawHeader.split("\r\n")
            val requestLine = lines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val fullUri = parts[1]
            val path = fullUri.substringBefore("?")
            val queryString = if (fullUri.contains("?")) fullUri.substringAfter("?") else ""
            val queryParams = parseQueryParams(queryString)

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            when {
                // Trang chủ và quản lý file
                method == "GET" && (path == "/" || path == "/files") -> {
                    serveFileBrowser(outputStream, queryParams["path"])
                }

                // Streaming video / audio có hỗ trợ HTTP Range (Seeking mượt mà)
                method == "GET" && path == "/stream" -> {
                    serveStream(outputStream, queryParams["path"], headers["range"], false)
                }

                // Download file
                method == "GET" && path == "/download" -> {
                    serveStream(outputStream, queryParams["path"], headers["range"], true)
                }

                // Upload file / APK
                method == "POST" && (path == "/upload" || path == "/api/upload") -> {
                    handleUpload(inputStream, outputStream, headers, queryParams["dir"])
                }

                // Tạo thư mục mới
                method == "POST" && path == "/api/mkdir" -> {
                    handleMkdir(inputStream, outputStream, headers)
                }

                // Xóa file hoặc thư mục
                method == "POST" && path == "/api/delete" -> {
                    handleDelete(inputStream, outputStream, headers)
                }

                // Cài đặt APK
                method == "GET" && path == "/install" -> {
                    serveInstallPage(outputStream)
                }
                method == "POST" && path == "/api/install" -> {
                    handleApkInstallUpload(inputStream, outputStream, headers)
                }
                method == "POST" && path == "/api/install-local" -> {
                    handleApkInstallLocal(inputStream, outputStream, headers)
                }

                // Reboot FPT Box từ xa
                (method == "GET" || method == "POST") && path == "/api/reboot" -> {
                    handleReboot(outputStream)
                }

                // Thực thi shell lệnh chẩn đoán
                method == "GET" && path == "/api/sh" -> {
                    val cmd = queryParams["cmd"] ?: "uptime"
                    val out = SystemManagerHelper.executeShell(cmd)
                    sendResponse(outputStream, 200, "text/plain; charset=utf-8", out.toByteArray())
                }

                // Trạng thái hệ thống JSON
                method == "GET" && path == "/status" -> {
                    serveStatusJson(outputStream)
                }

                // Xem log trực tiếp
                method == "GET" && path == "/log" -> {
                    serveLogPage(outputStream)
                }

                else -> {
                    sendResponse(outputStream, 404, "text/plain; charset=utf-8", "404 Not Found".toByteArray())
                }
            }

            outputStream.flush()
            socket.close()
        } catch (e: Exception) {
            // Client disconnects normally during range stream
            try { socket.close() } catch (ex: Exception) {}
        }
    }

    private fun readHttpHeader(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(1)
        var state = 0
        while (input.read(buffer) != -1) {
            val b = buffer[0]
            baos.write(buffer, 0, 1)
            when (state) {
                0 -> if (b == '\r'.code.toByte()) state = 1 else state = 0
                1 -> if (b == '\n'.code.toByte()) state = 2 else state = 0
                2 -> if (b == '\r'.code.toByte()) state = 3 else state = 0
                3 -> if (b == '\n'.code.toByte()) return baos.toString("UTF-8") else state = 0
            }
            if (baos.size() > 65536) break // Tránh header quá lớn
        }
        return baos.toString("UTF-8")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (query.isEmpty()) return params
        query.split("&").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                params[key] = value
            }
        }
        return params
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<String> = emptyList()
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            206 -> "Partial Content"
            302 -> "Found"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val headerBuilder = StringBuilder()
        headerBuilder.append("HTTP/1.1 $statusCode $statusText\r\n")
        headerBuilder.append("Content-Type: $contentType\r\n")
        headerBuilder.append("Content-Length: ${body.size}\r\n")
        headerBuilder.append("Connection: close\r\n")
        headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
        extraHeaders.forEach { headerBuilder.append("$it\r\n") }
        headerBuilder.append("\r\n")

        out.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    // --- 1. FILE BROWSER & NAS UI ---
    private fun serveFileBrowser(out: OutputStream, requestedPath: String?) {
        val drives = StorageManagerHelper.getStorageDrives(context)
        val currentPath = requestedPath?.trim()

        val html = StringBuilder()
        html.append(getHtmlHead("BoxStation - Mini NAS & Remote Manager"))
        html.append("<div class='container'>")

        // Top Navigation Bar
        html.append("""
            <div class='header-bar'>
                <div class='logo-title'>
                    <span class='logo-icon'>📡</span>
                    <div>
                        <h1>BoxStation</h1>
                        <span class='badge'>FPT Box &amp; G2 Mini NAS</span>
                    </div>
                </div>
                <div class='nav-actions'>
                    <a href='/install' class='btn btn-accent'>📦 Cài đặt APK</a>
                    <a href='/log' class='btn btn-outline'>📋 Nhật ký</a>
                    <button onclick='confirmReboot()' class='btn btn-danger'>🔄 Reboot Box</button>
                </div>
            </div>
        """.trimIndent())

        // Drive Status Cards
        html.append("<div class='drives-grid'>")
        for (drive in drives) {
            val isCurrent = currentPath != null && currentPath.startsWith(drive.path)
            val activeClass = if (isCurrent) "drive-card active" else "drive-card"
            val icon = if (drive.isUsb) "💾" else "📱"
            html.append("""
                <div class='$activeClass' onclick="location.href='/files?path=${URLEncoder.encode(drive.path, "UTF-8")}'">
                    <div class='drive-header'>
                        <span class='drive-icon'>$icon</span>
                        <div class='drive-meta'>
                            <div class='drive-name'>${drive.name}</div>
                            <div class='drive-path'>${drive.path}</div>
                        </div>
                    </div>
                    <div class='progress-bar'>
                        <div class='progress-fill' style='width: ${drive.usedPercent}%'></div>
                    </div>
                    <div class='drive-stats'>
                        <span>Trống: <b>${drive.freeFormatted}</b></span>
                        <span>Tổng: <b>${drive.totalFormatted}</b> (${drive.usedPercent}%)</span>
                    </div>
                </div>
            """.trimIndent())
        }
        html.append("</div>")

        // Files Explorer Section
        if (currentPath.isNullOrEmpty() || currentPath == "/") {
            // Màn hình chọn ổ cứng
            html.append("""
                <div class='content-box'>
                    <h3>📂 Vui lòng chọn Ổ cứng hoặc Thư mục phía trên để duyệt file</h3>
                    <p style='color: var(--text-muted);'>Hệ thống tự động phát hiện ổ cứng cắm qua cổng USB (NTFS, FAT32, exFAT, ext4) và bộ nhớ trong của Box.</p>
                </div>
            """.trimIndent())
        } else {
            val currentDir = File(currentPath)
            if (!currentDir.exists() || !currentDir.isDirectory) {
                html.append("<div class='alert alert-danger'>Thư mục không tồn tại: $currentPath</div>")
            } else {
                val files = StorageManagerHelper.listDirectory(currentPath)
                val parentPath = currentDir.parentFile?.canonicalPath

                // Breadcrumbs & Action Toolbar
                html.append("""
                    <div class='browser-toolbar'>
                        <div class='breadcrumbs'>
                            <a href='/files'>🏠 Gốc</a>
                """.trimIndent())

                val parts = currentPath.split("/").filter { it.isNotEmpty() }
                var accum = ""
                for (p in parts) {
                    accum += "/$p"
                    html.append(" <span>/</span> <a href='/files?path=${URLEncoder.encode(accum, "UTF-8")}'>$p</a>")
                }

                html.append("""
                        </div>
                        <div class='toolbar-btns'>
                            <button onclick='openUploadModal()' class='btn btn-primary'>⬆️ Tải file lên</button>
                            <button onclick='openMkdirModal()' class='btn btn-outline'>📁 Tạo thư mục</button>
                        </div>
                    </div>
                """.trimIndent())

                // Files Table
                html.append("""
                    <div class='table-responsive'>
                        <table class='file-table'>
                            <thead>
                                <tr>
                                    <th>Tên</th>
                                    <th style='width: 120px;'>Kích thước</th>
                                    <th style='width: 160px;'>Ngày cập nhật</th>
                                    <th style='width: 220px; text-align: right;'>Thao tác</th>
                                </tr>
                            </thead>
                            <tbody>
                """.trimIndent())

                // Nút quay lại thư mục cha
                if (parentPath != null && parentPath != "/storage" && parentPath != "/") {
                    html.append("""
                        <tr class='row-parent' onclick="location.href='/files?path=${URLEncoder.encode(parentPath, "UTF-8")}'">
                            <td colspan='4'>📁 <b>.. (Thư mục cha)</b></td>
                        </tr>
                    """.trimIndent())
                }

                if (files.isEmpty()) {
                    html.append("<tr><td colspan='4' style='text-align:center; padding: 24px; color: var(--text-muted);'>Thư mục trống</td></tr>")
                } else {
                    for (f in files) {
                        val encPath = URLEncoder.encode(f.path, "UTF-8")
                        val icon = when {
                            f.isDirectory -> "📁"
                            f.isVideo -> "🎬"
                            f.isAudio -> "🎵"
                            f.isImage -> "🖼️"
                            f.isApk -> "📦"
                            else -> "📄"
                        }

                        val rowClick = if (f.isDirectory) {
                            "location.href='/files?path=$encPath'"
                        } else if (f.isVideo || f.isAudio) {
                            "playMedia('$encPath', '${f.name}', ${f.isVideo})"
                        } else ""

                        html.append("""
                            <tr>
                                <td class='file-name' onclick="$rowClick">
                                    <span class='item-icon'>$icon</span>
                                    <span class='item-title'>${f.name}</span>
                                </td>
                                <td>${f.sizeFormatted}</td>
                                <td>${f.dateFormatted}</td>
                                <td class='file-actions'>
                        """.trimIndent())

                        if (f.isDirectory) {
                            html.append("""
                                <a href='/files?path=$encPath' class='btn-sm btn-outline'>Mở</a>
                                <button onclick="deleteItem('$encPath', '${f.name}', true)" class='btn-sm btn-danger'>Xóa</button>
                            """.trimIndent())
                        } else {
                            if (f.isVideo || f.isAudio) {
                                html.append("""
                                    <button onclick="playMedia('$encPath', '${f.name}', ${f.isVideo})" class='btn-sm btn-primary'>Phát</button>
                                """.trimIndent())
                            }
                            if (f.isApk) {
                                html.append("""
                                    <button onclick="installLocalApk('$encPath', '${f.name}')" class='btn-sm btn-accent'>Cài APK</button>
                                """.trimIndent())
                            }
                            html.append("""
                                <a href='/download?path=$encPath' class='btn-sm btn-outline' download>Tải về</a>
                                <button onclick="deleteItem('$encPath', '${f.name}', false)" class='btn-sm btn-danger'>Xóa</button>
                            """.trimIndent())
                        }

                        html.append("</td></tr>")
                    }
                }

                html.append("""
                            </tbody>
                        </table>
                    </div>
                """.trimIndent())
            }
        }

        // Modals & Media Player Drawer
        html.append("""
            <!-- Video/Audio Modal Player -->
            <div id='mediaModal' class='modal' style='display:none;'>
                <div class='modal-content modal-media'>
                    <div class='modal-header'>
                        <h3 id='mediaTitle'>Đang phát</h3>
                        <span class='close-btn' onclick='closeMediaModal()'>&times;</span>
                    </div>
                    <div class='modal-body' id='mediaPlayerContainer'></div>
                </div>
            </div>

            <!-- Upload Modal -->
            <div id='uploadModal' class='modal' style='display:none;'>
                <div class='modal-content'>
                    <div class='modal-header'>
                        <h3>⬆️ Tải file lên thư mục hiện tại</h3>
                        <span class='close-btn' onclick='closeUploadModal()'>&times;</span>
                    </div>
                    <form id='uploadForm' method='POST' action='/upload?dir=${URLEncoder.encode(currentPath ?: "", "UTF-8")}' enctype='multipart/form-data'>
                        <div class='form-group'>
                            <input type='file' name='file' id='fileInput' required class='file-input' />
                        </div>
                        <div id='uploadProgress' style='display:none;' class='progress-bar'>
                            <div id='uploadFill' class='progress-fill' style='width: 0%'></div>
                        </div>
                        <div class='form-actions'>
                            <button type='button' class='btn btn-outline' onclick='closeUploadModal()'>Hủy</button>
                            <button type='submit' class='btn btn-primary' id='uploadBtn'>Tải lên</button>
                        </div>
                    </form>
                </div>
            </div>

            <!-- Mkdir Modal -->
            <div id='mkdirModal' class='modal' style='display:none;'>
                <div class='modal-content'>
                    <div class='modal-header'>
                        <h3>📁 Tạo thư mục mới</h3>
                        <span class='close-btn' onclick='closeMkdirModal()'>&times;</span>
                    </div>
                    <div class='form-group'>
                        <label>Tên thư mục:</label>
                        <input type='text' id='mkdirName' class='text-input' placeholder='VD: Phim_4K' />
                    </div>
                    <div class='form-actions'>
                        <button type='button' class='btn btn-outline' onclick='closeMkdirModal()'>Hủy</button>
                        <button type='button' class='btn btn-primary' onclick='submitMkdir("${URLEncoder.encode(currentPath ?: "", "UTF-8")}")'>Tạo thư mục</button>
                    </div>
                </div>
            </div>
        """.trimIndent())

        html.append(getScriptBlock())
        html.append("</div></body></html>")

        sendResponse(out, 200, "text/html; charset=utf-8", html.toString().toByteArray(StandardCharsets.UTF_8))
    }

    // --- 2. HTTP RANGE STREAMING ENGINE ---
    private fun serveStream(out: OutputStream, requestedPath: String?, rangeHeader: String?, isDownload: Boolean) {
        if (requestedPath.isNullOrEmpty()) {
            sendResponse(out, 400, "text/plain", "Missing path".toByteArray())
            return
        }

        val file = File(requestedPath)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            sendResponse(out, 404, "text/plain", "File not found".toByteArray())
            return
        }

        val fileLength = file.length()
        val mimeType = StorageManagerHelper.getMimeType(file.name)
        val disposition = if (isDownload) "attachment; filename=\"${file.name}\"" else "inline; filename=\"${file.name}\""

        // Xử lý Range Request (Ví dụ: Range: bytes=1000-5000 hoặc bytes=1000-)
        var start: Long = 0
        var end: Long = fileLength - 1
        var isRange = false

        if (!rangeHeader.isNullOrEmpty() && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.substring(6).trim()
            val dashIdx = rangeVal.indexOf('-')
            if (dashIdx != -1) {
                val startStr = rangeVal.substring(0, dashIdx).trim()
                val endStr = rangeVal.substring(dashIdx + 1).trim()
                try {
                    if (startStr.isNotEmpty()) {
                        start = startStr.toLong()
                    }
                    if (endStr.isNotEmpty()) {
                        end = endStr.toLong()
                    }
                    if (end >= fileLength) {
                        end = fileLength - 1
                    }
                    isRange = true
                } catch (e: Exception) {
                    // ignore malformed range
                }
            }
        }

        val contentLength = end - start + 1
        val statusCode = if (isRange) 206 else 200
        val statusText = if (isRange) "Partial Content" else "OK"

        val headerBuilder = StringBuilder()
        headerBuilder.append("HTTP/1.1 $statusCode $statusText\r\n")
        headerBuilder.append("Content-Type: $mimeType\r\n")
        headerBuilder.append("Content-Length: $contentLength\r\n")
        headerBuilder.append("Content-Disposition: $disposition\r\n")
        headerBuilder.append("Accept-Ranges: bytes\r\n")
        headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
        if (isRange) {
            headerBuilder.append("Content-Range: bytes $start-$end/$fileLength\r\n")
        }
        headerBuilder.append("Connection: close\r\n\r\n")

        out.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()

        // Stream dữ liệu qua buffer 64KB
        val raf = RandomAccessFile(file, "r")
        try {
            raf.seek(start)
            val buffer = ByteArray(65536)
            var bytesRemaining = contentLength
            while (bytesRemaining > 0) {
                val toRead = if (bytesRemaining > buffer.size) buffer.size else bytesRemaining.toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) break
                out.write(buffer, 0, read)
                bytesRemaining -= read
            }
            out.flush()
        } catch (e: Exception) {
            // Client closed stream (ví dụ người xem tua video hoặc đóng tab)
        } finally {
            try { raf.close() } catch (e: Exception) {}
        }
    }

    // --- 3. MULTIPART FILE UPLOAD ---
    private fun handleUpload(input: InputStream, out: OutputStream, headers: Map<String, String>, targetDirParam: String?) {
        val contentType = headers["content-type"] ?: ""
        if (!contentType.contains("multipart/form-data")) {
            sendResponse(out, 400, "text/plain", "Bad Request: Expected multipart".toByteArray())
            return
        }

        val boundaryMatch = Regex("""boundary=(.+)""").find(contentType)
        val boundary = boundaryMatch?.groupValues?.get(1)?.trim() ?: run {
            sendResponse(out, 400, "text/plain", "Missing boundary".toByteArray())
            return
        }

        val destDir = if (!targetDirParam.isNullOrEmpty()) File(targetDirParam) else context.cacheDir
        if (!destDir.exists()) destDir.mkdirs()

        try {
            val uploadedFile = parseAndSaveMultipartFile(input, boundary, destDir)
            if (uploadedFile != null && uploadedFile.exists()) {
                AppLogger.i("Upload", "Tải lên thành công: ${uploadedFile.name} (${StorageManagerHelper.formatSize(uploadedFile.length())}) vào ${destDir.absolutePath}")
                // Redirect lại trang trước
                val redirectUrl = "/files?path=${URLEncoder.encode(destDir.canonicalPath, "UTF-8")}"
                sendResponse(out, 302, "text/plain", "Redirecting".toByteArray(), listOf("Location: $redirectUrl"))
            } else {
                sendResponse(out, 500, "text/plain; charset=utf-8", "Lỗi lưu file tải lên".toByteArray())
            }
        } catch (e: Exception) {
            AppLogger.e("Upload", "Lỗi upload: ${e.message}", e)
            sendResponse(out, 500, "text/plain; charset=utf-8", "Lỗi: ${e.message}".toByteArray())
        }
    }

    private fun parseAndSaveMultipartFile(input: InputStream, boundary: String, destDir: File): File? {
        val boundaryBytes = ("--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
        val headerEndBytes = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)

        // Đọc header của multipart part đầu tiên
        val partHeader = readUntil(input, headerEndBytes)
        val headerStr = String(partHeader, StandardCharsets.UTF_8)

        // Trích xuất filename
        val fnMatch = Regex("""filename="([^"]+)"""").find(headerStr)
        val fileName = fnMatch?.groupValues?.get(1) ?: "uploaded_${System.currentTimeMillis()}"
        val sanitizedName = File(fileName).name

        val targetFile = File(destDir, sanitizedName)
        val fos = FileOutputStream(targetFile)
        val bis = BufferedInputStream(input)

        try {
            // Stream phần nội dung file vào đĩa cho đến khi gặp boundary kết thúc
            streamUntilBoundary(bis, boundaryBytes, fos)
            fos.flush()
        } finally {
            fos.close()
        }
        return targetFile
    }

    private fun readUntil(input: InputStream, delimiter: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(1)
        var matchIdx = 0
        while (input.read(buffer) != -1) {
            val b = buffer[0]
            baos.write(buffer, 0, 1)
            if (b == delimiter[matchIdx]) {
                matchIdx++
                if (matchIdx == delimiter.size) break
            } else {
                matchIdx = if (b == delimiter[0]) 1 else 0
            }
            if (baos.size() > 8192) break
        }
        return baos.toByteArray()
    }

    private fun streamUntilBoundary(input: InputStream, boundary: ByteArray, out: OutputStream) {
        val buffer = ByteArray(65536)
        val matchBuffer = ByteArray(boundary.size + 4) // Chứa \r\n + boundary
        var matchLen = 0

        // Boundary pattern trong stream thực tế có tiền tố \r\n
        val fullBoundary = ("\r\n" + String(boundary, StandardCharsets.ISO_8859_1)).toByteArray(StandardCharsets.ISO_8859_1)

        var b: Int
        while (input.read().also { b = it } != -1) {
            val byteVal = b.toByte()
            if (byteVal == fullBoundary[matchLen]) {
                matchBuffer[matchLen++] = byteVal
                if (matchLen == fullBoundary.size) {
                    // Đã tới boundary kết thúc phần file, dừng ghi
                    break
                }
            } else {
                if (matchLen > 0) {
                    out.write(matchBuffer, 0, matchLen)
                    matchLen = 0
                    if (byteVal == fullBoundary[0]) {
                        matchBuffer[matchLen++] = byteVal
                    } else {
                        out.write(b)
                    }
                } else {
                    out.write(b)
                }
            }
        }
    }

    // --- 4. TẠO THƯ MỤC & XÓA ---
    private fun handleMkdir(input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val body = readBodyString(input, headers)
        val params = parseQueryParams(body)
        val parentPath = params["path"]
        val dirName = params["name"]

        if (parentPath.isNullOrEmpty() || dirName.isNullOrEmpty()) {
            sendResponse(out, 400, "application/json", """{"success":false,"message":"Thiếu tham số"}""".toByteArray())
            return
        }

        val newDir = File(parentPath, dirName.replace(Regex("[^a-zA-Z0-9_.-]"), "_"))
        val ok = newDir.mkdirs()
        AppLogger.i("Storage", "Tạo thư mục: ${newDir.absolutePath} -> $ok")
        val json = """{"success":$ok,"path":"${newDir.canonicalPath}"}"""
        sendResponse(out, 200, "application/json", json.toByteArray())
    }

    private fun handleDelete(input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val body = readBodyString(input, headers)
        val params = parseQueryParams(body)
        val targetPath = params["path"]

        if (targetPath.isNullOrEmpty()) {
            sendResponse(out, 400, "application/json", """{"success":false,"message":"Thiếu path"}""".toByteArray())
            return
        }

        val target = File(targetPath)
        val ok = target.deleteRecursively()
        AppLogger.i("Storage", "Xóa ${target.absolutePath} -> $ok")
        val json = """{"success":$ok}"""
        sendResponse(out, 200, "application/json", json.toByteArray())
    }

    // --- 5. APK INSTALL PORTAL ---
    private fun serveInstallPage(out: OutputStream) {
        val html = StringBuilder()
        html.append(getHtmlHead("BoxStation - Cài đặt APK từ xa"))
        html.append("""
            <div class='container'>
                <div class='header-bar'>
                    <div class='logo-title'>
                        <span class='logo-icon'>📦</span>
                        <div>
                            <h1>Cài đặt APK từ xa</h1>
                            <span class='badge'>Không cần ADB hay Cáp kết nối</span>
                        </div>
                    </div>
                    <div class='nav-actions'>
                        <a href='/files' class='btn btn-outline'>📂 Xem ổ đĩa</a>
                        <button onclick='confirmReboot()' class='btn btn-danger'>🔄 Reboot Box</button>
                    </div>
                </div>

                <div class='content-box'>
                    <h3>Chọn hoặc Kéo thả file .apk vào đây để cài lên TV</h3>
                    <p style='color: var(--text-muted); margin-bottom: 20px;'>
                        Hệ thống sẽ tải file lên FPT Box và tự động mở trình cài đặt ngay trên màn hình TV hoặc cài ngầm.
                    </p>

                    <div id='dropZone' class='drop-zone' onclick="document.getElementById('apkFile').click()">
                        <div class='drop-icon'>📥</div>
                        <div class='drop-title'>Nhấp để chọn file APK hoặc Kéo thả file vào đây</div>
                        <div class='drop-sub'>Hỗ trợ file APK mọi dung lượng</div>
                        <input type='file' id='apkFile' accept='.apk' style='display:none;' onchange='uploadAndInstallApk()' />
                    </div>

                    <div id='installStatus' style='display:none; margin-top: 20px;' class='status-card'>
                        <div id='installSpinner' class='spinner'></div>
                        <div id='installStatusText' style='font-size: 16px; font-weight: 500;'>Đang tải lên và xử lý...</div>
                    </div>
                </div>

                <div class='content-box' style='margin-top: 20px;'>
                    <h3>💡 Hướng dẫn cài đặt cho FPT Play Box</h3>
                    <ul style='color: var(--text-muted); line-height: 1.8; padding-left: 20px;'>
                        <li>Khi file tải lên hoàn tất, hộp thoại <b>Cài đặt ứng dụng</b> sẽ tự động xuất hiện trên màn hình TV.</li>
                        <li>Dùng điều khiển từ xa (Remote) của FPT Box bấm <b>Cài đặt (Install)</b> để hoàn tất.</li>
                        <li>Ứng dụng mới cài sẽ xuất hiện trong màn hình chính hoặc menu ứng dụng của Android TV.</li>
                    </ul>
                </div>
            </div>

            <script>
                function uploadAndInstallApk() {
                    const input = document.getElementById('apkFile');
                    if (!input.files || input.files.length === 0) return;
                    const file = input.files[0];
                    if (!file.name.toLowerCase().endsWith('.apk')) {
                        alert('Vui lòng chỉ chọn file có đuôi .apk');
                        return;
                    }

                    const statusDiv = document.getElementById('installStatus');
                    const statusText = document.getElementById('installStatusText');
                    statusDiv.style.display = 'flex';
                    statusText.innerText = 'Đang tải file ' + file.name + ' lên Box...';

                    const formData = new FormData();
                    formData.append('apk', file);

                    const xhr = new XMLHttpRequest();
                    xhr.open('POST', '/api/install', true);
                    xhr.upload.onprogress = function(e) {
                        if (e.lengthComputable) {
                            const percent = Math.round((e.loaded / e.total) * 100);
                            statusText.innerText = 'Đang tải lên: ' + percent + '% (' + file.name + ')';
                        }
                    };
                    xhr.onload = function() {
                        if (xhr.status === 200) {
                            try {
                                const res = JSON.parse(xhr.responseText);
                                statusText.innerText = (res.success ? '🟢 ' : '🔴 ') + res.message;
                            } catch(e) {
                                statusText.innerText = '🟢 Hoàn tất! Vui lòng nhìn lên màn hình TV để xác nhận.';
                            }
                        } else {
                            statusText.innerText = '🔴 Lỗi máy chủ (' + xhr.status + ')';
                        }
                    };
                    xhr.onerror = function() {
                        statusText.innerText = '🔴 Lỗi kết nối mạng tới Box!';
                    };
                    xhr.send(formData);
                }

                // Drag & Drop
                const dropZone = document.getElementById('dropZone');
                ['dragenter', 'dragover'].forEach(name => {
                    dropZone.addEventListener(name, (e) => { e.preventDefault(); dropZone.classList.add('dragover'); }, false);
                });
                ['dragleave', 'drop'].forEach(name => {
                    dropZone.addEventListener(name, (e) => { e.preventDefault(); dropZone.classList.remove('dragover'); }, false);
                });
                dropZone.addEventListener('drop', (e) => {
                    const dt = e.dataTransfer;
                    if (dt.files && dt.files.length > 0) {
                        document.getElementById('apkFile').files = dt.files;
                        uploadAndInstallApk();
                    }
                }, false);
            </script>
        """.trimIndent())
        html.append(getScriptBlock())
        html.append("</body></html>")

        sendResponse(out, 200, "text/html; charset=utf-8", html.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleApkInstallUpload(input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val contentType = headers["content-type"] ?: ""
        val boundaryMatch = Regex("""boundary=(.+)""").find(contentType)
        val boundary = boundaryMatch?.groupValues?.get(1)?.trim() ?: run {
            sendResponse(out, 400, "application/json", """{"success":false,"message":"Missing boundary"}""".toByteArray())
            return
        }

        val apkDir = File(context.cacheDir, "apks")
        if (!apkDir.exists()) apkDir.mkdirs()

        try {
            val uploadedFile = parseAndSaveMultipartFile(input, boundary, apkDir)
            if (uploadedFile != null && uploadedFile.exists()) {
                val result = ApkInstallerHelper.installApk(context, uploadedFile)
                val json = """{"success":${result.success},"method":"${result.method}","message":"${result.message.replace("\"", "\\\"")}"}"""
                sendResponse(out, 200, "application/json", json.toByteArray())
            } else {
                sendResponse(out, 500, "application/json", """{"success":false,"message":"Không thể lưu file APK"}""".toByteArray())
            }
        } catch (e: Exception) {
            sendResponse(out, 500, "application/json", """{"success":false,"message":"${e.message}"}""".toByteArray())
        }
    }

    private fun handleApkInstallLocal(input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val body = readBodyString(input, headers)
        val params = parseQueryParams(body)
        val apkPath = params["path"]

        if (apkPath.isNullOrEmpty()) {
            sendResponse(out, 400, "application/json", """{"success":false,"message":"Thiếu path"}""".toByteArray())
            return
        }

        val apkFile = File(apkPath)
        val result = ApkInstallerHelper.installApk(context, apkFile)
        val json = """{"success":${result.success},"method":"${result.method}","message":"${result.message.replace("\"", "\\\"")}"}"""
        sendResponse(out, 200, "application/json", json.toByteArray())
    }

    // --- 6. REBOOT TRIGGER ---
    private fun handleReboot(out: OutputStream) {
        val (success, message) = SystemManagerHelper.rebootBox(context)
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset='utf-8'/>
                <meta name='viewport' content='width=device-width, initial-scale=1'/>
                <title>Khởi động lại Box</title>
                <style>
                    body { background: #0F172A; color: #F8FAFC; font-family: system-ui, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                    .card { background: #1E293B; border: 1px solid #334155; border-radius: 12px; padding: 32px; max-width: 480px; text-align: center; }
                    h2 { margin-top: 0; color: #38BDF8; }
                    .count { font-size: 48px; font-weight: bold; color: #F59E0B; margin: 20px 0; }
                    p { color: #94A3B8; line-height: 1.6; }
                    a { color: #38BDF8; text-decoration: none; }
                </style>
            </head>
            <body>
                <div class='card'>
                    <h2>🔄 Lệnh Khởi Động Lại Đã Gửi</h2>
                    <p>$message</p>
                    <div class='count' id='countdown'>15</div>
                    <p>FPT Box đang khởi động lại. Trang sẽ tự động tải lại sau khi Box online.</p>
                </div>
                <script>
                    let c = 15;
                    const timer = setInterval(() => {
                        c--;
                        document.getElementById('countdown').innerText = c;
                        if (c <= 0) {
                            clearInterval(timer);
                            location.href = '/files';
                        }
                    }, 1000);
                </script>
            </body>
            </html>
        """.trimIndent()
        sendResponse(out, 200, "text/html; charset=utf-8", html.toByteArray())
    }

    // --- 7. STATUS & LOG ---
    private fun serveStatusJson(out: OutputStream) {
        val sys = SystemManagerHelper.getSystemInfo(context)
        val drives = StorageManagerHelper.getStorageDrives(context)
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"model\":\"${sys.deviceModel}\",")
        sb.append("\"version\":\"${sys.androidVersion}\",")
        sb.append("\"uptime\":\"${sys.uptime}\",")
        sb.append("\"ram\":{\"total\":\"${sys.totalRam}\",\"free\":\"${sys.freeRam}\"},")
        sb.append("\"drives\":[")
        drives.forEachIndexed { i, d ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${d.id}\",\"name\":\"${d.name}\",\"path\":\"${d.path}\",\"total\":\"${d.totalFormatted}\",\"free\":\"${d.freeFormatted}\",\"percent\":${d.usedPercent}}")
        }
        sb.append("],")
        sb.append("\"ips\":{")
        var first = true
        sys.ipAddresses.forEach { (k, v) ->
            if (!first) sb.append(",")
            first = false
            sb.append("\"$k\":\"$v\"")
        }
        sb.append("}}")
        sendResponse(out, 200, "application/json", sb.toString().toByteArray())
    }

    private fun serveLogPage(out: OutputStream) {
        val logs = AppLogger.getLogs().reversed().joinToString("<br>")
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset='utf-8'/>
                <meta name='viewport' content='width=device-width, initial-scale=1'/>
                <title>BoxStation - Nhật ký hệ thống</title>
                <style>
                    body { background: #0F172A; color: #F8FAFC; font-family: monospace; padding: 20px; }
                    .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
                    h2 { margin: 0; color: #38BDF8; font-family: system-ui; }
                    .btn { background: #334155; color: #FFF; padding: 8px 16px; border-radius: 6px; text-decoration: none; font-family: system-ui; }
                    .log-box { background: #020617; border: 1px solid #1E293B; border-radius: 8px; padding: 16px; line-height: 1.7; font-size: 14px; max-height: 80vh; overflow-y: auto; }
                </style>
            </head>
            <body>
                <div class='header'>
                    <h2>📋 BoxStation Logs</h2>
                    <div>
                        <a href='/files' class='btn'>📂 Duyệt file</a>
                        <a href='/log' class='btn' style='background: #06B6D4;'>🔄 Làm mới</a>
                    </div>
                </div>
                <div class='log-box'>
                    $logs
                </div>
            </body>
            </html>
        """.trimIndent()
        sendResponse(out, 200, "text/html; charset=utf-8", html.toByteArray())
    }

    private fun readBodyString(input: InputStream, headers: Map<String, String>): String {
        val lenStr = headers["content-length"] ?: return ""
        val len = lenStr.toIntOrNull() ?: return ""
        val bytes = ByteArray(len)
        var totalRead = 0
        while (totalRead < len) {
            val r = input.read(bytes, totalRead, len - totalRead)
            if (r == -1) break
            totalRead += r
        }
        return String(bytes, 0, totalRead, StandardCharsets.UTF_8)
    }

    // --- HTML STYLES & COMMON SCRIPTS ---
    private fun getHtmlHead(title: String): String {
        return """
            <!DOCTYPE html>
            <html lang='vi'>
            <head>
                <meta charset='utf-8'/>
                <meta name='viewport' content='width=device-width, initial-scale=1'/>
                <title>$title</title>
                <style>
                    :root {
                        --bg-dark: #0F172A;
                        --bg-card: #1E293B;
                        --border: #334155;
                        --primary: #06B6D4;
                        --primary-hover: #0891B2;
                        --accent: #10B981;
                        --danger: #EF4444;
                        --text-white: #F8FAFC;
                        --text-muted: #94A3B8;
                    }
                    * { box-sizing: border-box; }
                    body {
                        margin: 0;
                        background: var(--bg-dark);
                        color: var(--text-white);
                        font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    }
                    .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
                    .header-bar {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 24px;
                        flex-wrap: wrap;
                        gap: 16px;
                    }
                    .logo-title { display: flex; align-items: center; gap: 14px; }
                    .logo-icon { font-size: 32px; }
                    h1 { margin: 0; font-size: 24px; font-weight: 700; color: var(--text-white); }
                    .badge {
                        background: rgba(6, 182, 212, 0.15);
                        color: var(--primary);
                        border: 1px solid rgba(6, 182, 212, 0.3);
                        font-size: 12px;
                        padding: 3px 8px;
                        border-radius: 4px;
                        display: inline-block;
                        margin-top: 4px;
                    }
                    .nav-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
                    .btn {
                        display: inline-flex;
                        align-items: center;
                        gap: 6px;
                        padding: 8px 16px;
                        border-radius: 8px;
                        font-weight: 500;
                        font-size: 14px;
                        cursor: pointer;
                        text-decoration: none;
                        border: none;
                        transition: all 0.2s;
                    }
                    .btn-primary { background: var(--primary); color: #000; }
                    .btn-primary:hover { background: var(--primary-hover); }
                    .btn-accent { background: var(--accent); color: #000; }
                    .btn-danger { background: var(--danger); color: #FFF; }
                    .btn-outline { background: var(--bg-card); color: var(--text-white); border: 1px solid var(--border); }
                    .btn-outline:hover { background: #334155; }
                    .btn-sm { padding: 4px 10px; font-size: 12px; border-radius: 6px; text-decoration: none; cursor: pointer; border: none; }
                    
                    /* Drives Cards */
                    .drives-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
                        gap: 16px;
                        margin-bottom: 24px;
                    }
                    .drive-card {
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                        border-radius: 12px;
                        padding: 16px;
                        cursor: pointer;
                        transition: all 0.2s;
                    }
                    .drive-card:hover, .drive-card.active {
                        border-color: var(--primary);
                        transform: translateY(-2px);
                        box-shadow: 0 4px 16px rgba(6, 182, 212, 0.15);
                    }
                    .drive-header { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
                    .drive-icon { font-size: 28px; }
                    .drive-name { font-weight: 600; font-size: 15px; }
                    .drive-path { font-size: 12px; color: var(--text-muted); font-family: monospace; }
                    .progress-bar {
                        height: 8px;
                        background: #334155;
                        border-radius: 4px;
                        overflow: hidden;
                        margin-bottom: 8px;
                    }
                    .progress-fill { height: 100%; background: var(--primary); transition: width 0.3s; }
                    .drive-stats { display: flex; justify-content: space-between; font-size: 12px; color: var(--text-muted); }

                    /* Explorer Toolbar */
                    .browser-toolbar {
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                        border-radius: 10px 10px 0 0;
                        padding: 14px 18px;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        flex-wrap: wrap;
                        gap: 12px;
                    }
                    .breadcrumbs { font-size: 14px; font-weight: 500; }
                    .breadcrumbs a { color: var(--primary); text-decoration: none; }
                    .breadcrumbs span { color: var(--text-muted); margin: 0 4px; }
                    .toolbar-btns { display: flex; gap: 8px; }

                    /* Table */
                    .table-responsive {
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                        border-top: none;
                        border-radius: 0 0 10px 10px;
                        overflow-x: auto;
                    }
                    .file-table { width: 100%; border-collapse: collapse; text-align: left; }
                    .file-table th {
                        background: #152033;
                        padding: 12px 16px;
                        font-size: 12px;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                        color: var(--text-muted);
                        border-bottom: 1px solid var(--border);
                    }
                    .file-table td {
                        padding: 12px 16px;
                        font-size: 14px;
                        border-bottom: 1px solid #243247;
                    }
                    .file-table tr:hover { background: #233149; }
                    .file-name { cursor: pointer; display: flex; align-items: center; gap: 10px; }
                    .item-icon { font-size: 20px; }
                    .item-title { font-weight: 500; word-break: break-all; }
                    .file-actions { text-align: right; display: flex; gap: 6px; justify-content: flex-end; }
                    .row-parent { background: #131d2e; cursor: pointer; }

                    /* Content box */
                    .content-box {
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                        border-radius: 12px;
                        padding: 24px;
                    }

                    /* Drop zone */
                    .drop-zone {
                        border: 2px dashed var(--primary);
                        border-radius: 12px;
                        padding: 40px 20px;
                        text-align: center;
                        background: rgba(6, 182, 212, 0.05);
                        cursor: pointer;
                        transition: all 0.2s;
                    }
                    .drop-zone:hover, .drop-zone.dragover {
                        background: rgba(6, 182, 212, 0.15);
                        border-color: #38BDF8;
                    }
                    .drop-icon { font-size: 48px; margin-bottom: 12px; }
                    .drop-title { font-size: 18px; font-weight: 600; margin-bottom: 6px; }
                    .drop-sub { font-size: 13px; color: var(--text-muted); }

                    /* Modals */
                    .modal {
                        position: fixed;
                        top: 0; left: 0; right: 0; bottom: 0;
                        background: rgba(0, 0, 0, 0.75);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        z-index: 1000;
                        padding: 20px;
                    }
                    .modal-content {
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                        border-radius: 12px;
                        width: 100%;
                        max-width: 500px;
                        padding: 24px;
                    }
                    .modal-media { max-width: 900px; }
                    .modal-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 20px;
                    }
                    .modal-header h3 { margin: 0; font-size: 18px; }
                    .close-btn { font-size: 24px; cursor: pointer; color: var(--text-muted); }
                    .close-btn:hover { color: #FFF; }
                    .form-group { margin-bottom: 20px; }
                    .form-group label { display: block; margin-bottom: 8px; font-size: 14px; }
                    .text-input, .file-input {
                        width: 100%;
                        padding: 10px 14px;
                        border-radius: 8px;
                        border: 1px solid var(--border);
                        background: #0F172A;
                        color: #FFF;
                        font-size: 14px;
                    }
                    .form-actions { display: flex; justify-content: flex-end; gap: 10px; }
                </style>
            </head>
            <body>
        """.trimIndent()
    }

    private fun getScriptBlock(): String {
        return """
            <script>
                function confirmReboot() {
                    if (confirm('Bạn có chắc chắn muốn khởi động lại FPT Box từ xa không?')) {
                        location.href = '/api/reboot';
                    }
                }

                function openUploadModal() { document.getElementById('uploadModal').style.display = 'flex'; }
                function closeUploadModal() { document.getElementById('uploadModal').style.display = 'none'; }
                function openMkdirModal() { document.getElementById('mkdirModal').style.display = 'flex'; }
                function closeMkdirModal() { document.getElementById('mkdirModal').style.display = 'none'; }

                function submitMkdir(currentDirEnc) {
                    const name = document.getElementById('mkdirName').value.trim();
                    if (!name) return alert('Vui lòng nhập tên thư mục');
                    fetch('/api/mkdir', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                        body: 'path=' + currentDirEnc + '&name=' + encodeURIComponent(name)
                    }).then(r => r.json()).then(res => {
                        if (res.success) {
                            location.reload();
                        } else {
                            alert('Không thể tạo thư mục');
                        }
                    });
                }

                function deleteItem(pathEnc, name, isDir) {
                    const msg = isDir ? 'Xác nhận xóa thư mục "' + name + '" và tất cả file bên trong?' : 'Xác nhận xóa file "' + name + '"?';
                    if (!confirm(msg)) return;
                    fetch('/api/delete', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                        body: 'path=' + pathEnc
                    }).then(r => r.json()).then(res => {
                        if (res.success) {
                            location.reload();
                        } else {
                            alert('Không thể xóa mục này');
                        }
                    });
                }

                function installLocalApk(pathEnc, name) {
                    if (!confirm('Kích hoạt cài đặt file APK "' + name + '" lên Box?')) return;
                    fetch('/api/install-local', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                        body: 'path=' + pathEnc
                    }).then(r => r.json()).then(res => {
                        alert(res.message);
                    });
                }

                function playMedia(pathEnc, name, isVideo) {
                    const modal = document.getElementById('mediaModal');
                    const title = document.getElementById('mediaTitle');
                    const container = document.getElementById('mediaPlayerContainer');
                    title.innerText = (isVideo ? '🎬 ' : '🎵 ') + name;

                    const streamUrl = '/stream?path=' + pathEnc;
                    if (isVideo) {
                        container.innerHTML = '<video controls autoplay style="width: 100%; max-height: 70vh; border-radius: 8px; background: #000;" src="' + streamUrl + '"></video>';
                    } else {
                        container.innerHTML = '<audio controls autoplay style="width: 100%; padding: 20px 0;" src="' + streamUrl + '"></audio>';
                    }
                    modal.style.display = 'flex';
                }

                function closeMediaModal() {
                    const modal = document.getElementById('mediaModal');
                    const container = document.getElementById('mediaPlayerContainer');
                    container.innerHTML = '';
                    modal.style.display = 'none';
                }
            </script>
        """.trimIndent()
    }
}
