package vn.lienson.boxstation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
                // Giao thức WebDAV (VidHub, Infuse, Kodi, VLC, Apple TV, PotPlayer)
                method == "OPTIONS" -> {
                    WebDavHelper.handleOptions(outputStream)
                }
                method == "PROPFIND" -> {
                    WebDavHelper.handlePropfind(context, outputStream, path, headers["depth"])
                }
                (method == "GET" || method == "HEAD") && (path.startsWith("/webdav") || path.startsWith("/dav")) -> {
                    val target = WebDavHelper.resolveWebDavTarget(context, path)
                    when (target) {
                        is WebDavTarget.FileTarget -> {
                            if (method == "HEAD") {
                                WebDavHelper.handleHead(outputStream, target.file)
                            } else {
                                serveStream(outputStream, target.file.absolutePath, headers["range"], false)
                            }
                        }
                        is WebDavTarget.DirTarget -> {
                            sendResponse(outputStream, 302, "text/plain", "Redirecting...".toByteArray(), listOf("Location: /files?path=${URLEncoder.encode(target.dir.absolutePath, "UTF-8")}"))
                        }
                        is WebDavTarget.Root -> {
                            sendResponse(outputStream, 302, "text/plain", "Redirecting...".toByteArray(), listOf("Location: /files"))
                        }
                        is WebDavTarget.NotFound -> {
                            sendResponse(outputStream, 404, "text/plain", "Resource not found".toByteArray())
                        }
                    }
                }

                // Trang chủ và quản lý file
                method == "GET" && (path == "/" || path == "/files") -> {
                    serveFileBrowser(outputStream, queryParams["path"])
                }

                // Streaming video / audio có hỗ trợ HTTP Range (Seeking mượt mà)
                (method == "GET" || method == "HEAD") && path == "/stream" -> {
                    serveStream(outputStream, queryParams["path"], headers["range"], false, isHead = method == "HEAD")
                }

                // Download file
                (method == "GET" || method == "HEAD") && path == "/download" -> {
                    serveStream(outputStream, queryParams["path"], headers["range"], true, isHead = method == "HEAD")
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

                // Quản lý ứng dụng
                method == "GET" && path == "/apps" -> {
                    serveAppsPage(outputStream, queryParams["all"] == "1")
                }
                method == "GET" && path == "/api/apps" -> {
                    serveAppsJson(outputStream, queryParams["all"] == "1")
                }
                method == "GET" && path == "/api/app-icon" -> {
                    serveAppIcon(outputStream, queryParams["pkg"])
                }
                method == "POST" && path == "/api/app/launch" -> {
                    handleAppLaunch(inputStream, outputStream, headers)
                }
                method == "POST" && path == "/api/app/uninstall" -> {
                    handleAppUninstall(inputStream, outputStream, headers)
                }
                method == "POST" && path == "/api/app/stop" -> {
                    handleAppStop(inputStream, outputStream, headers)
                }

                // Chụp màn hình TV từ xa & Remote ảo
                method == "GET" && path == "/screen" -> {
                    serveScreenPage(outputStream)
                }
                method == "GET" && path == "/api/screenshot" -> {
                    serveScreenshotImage(outputStream)
                }
                (method == "GET" || method == "POST") && path == "/api/remote" -> {
                    handleRemoteControl(outputStream, queryParams["key"])
                }
                (method == "GET" || method == "POST") && path == "/api/click" -> {
                    handleClickApi(outputStream, queryParams)
                }

                // Tự động Reboot 03:00 sáng & Reboot thủ công
                (method == "GET" || method == "POST") && path == "/api/auto-reboot" -> {
                    handleAutoRebootConfig(outputStream, queryParams)
                }
                (method == "GET" || method == "POST") && path == "/api/reboot" -> {
                    handleReboot(outputStream)
                }

                // FPT Shield: Bật/tắt cách ly cơ chế khóa box FPT
                (method == "GET" || method == "POST") && path == "/api/fpt-shield" -> {
                    handleFptShieldConfig(outputStream, queryParams)
                }

                // Cấp quyền bộ nhớ
                (method == "GET" || method == "POST") && path == "/api/open-storage-settings" -> {
                    handleOpenStorageSettings(outputStream)
                }

                // Kiểm tra & Mở ADB / Developer Settings
                (method == "GET" || method == "POST") && path == "/api/adb-test" -> {
                    handleAdbTest(outputStream)
                }

                // Mở Activity hệ thống linh hoạt
                (method == "GET" || method == "POST") && path == "/api/open-activity" -> {
                    handleOpenActivity(outputStream, queryParams["target"])
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
        html.append(renderHeaderBar("files", "BoxStation", "v1.0.4", "📡"))

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

        // Thẻ WebDAV Server cho VidHub, Infuse, Kodi, Apple TV
        val ipAddress = SystemManagerHelper.getPreferredIp()
        val webDavUrl = "http://$ipAddress:8888/webdav"
        html.append("""
            <div class='webdav-card' style='background: linear-gradient(135deg, #1e293b, #0f172a); border: 1px solid #334155; border-radius: 12px; padding: 16px 20px; margin-bottom: 24px; box-shadow: 0 4px 12px rgba(0,0,0,0.3);'>
                <div style='display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px;'>
                    <div style='display: flex; align-items: center; gap: 12px;'>
                        <span style='font-size: 28px;'>📡</span>
                        <div>
                            <div style='font-size: 16px; font-weight: 700; color: #38bdf8;'>WebDAV Server (Dành cho VidHub, Infuse, Kodi, VLC, Apple TV)</div>
                            <div style='font-size: 13px; color: #94a3b8; margin-top: 2px;'>
                                Thêm nguồn dữ liệu vào VidHub bằng giao thức <b>WebDAV</b> để tự động quét phim, poster, fanart và stream 4K mượt mà:
                            </div>
                        </div>
                    </div>
                    <div style='display: flex; align-items: center; gap: 8px;'>
                        <code style='background: #00000088; border: 1px solid #475569; padding: 6px 12px; border-radius: 6px; font-size: 14px; color: #22c55e;'>$webDavUrl</code>
                        <button onclick="navigator.clipboard.writeText('$webDavUrl'); alert('Đã sao chép link WebDAV!');" class='btn-sm btn-accent' style='white-space: nowrap;'>📋 Copy Link</button>
                    </div>
                </div>
                <div style='margin-top: 10px; font-size: 12px; color: #64748b; border-top: 1px dashed #334155; padding-top: 8px;'>
                    💡 <b>Cách kết nối VidHub:</b> Mở VidHub &rarr; <b>Thêm nguồn (+)</b> &rarr; Chọn <b>WebDAV</b> &rarr; Nhập URL trên (hoặc IP: <code>$ipAddress</code>, Port: <code>8888</code>, Path: <code>/webdav</code>). Tài khoản & Mật khẩu để trống.
                </div>
            </div>
        """.trimIndent())

        if (drives.isEmpty()) {
            html.append("""
                <div class='storage-banner' style='background: #ef444422; border-left: 4px solid #ef4444; padding: 12px 16px; margin-bottom: 20px; border-radius: 8px;'>
                    <div class='banner-text' style='color: #fca5a5;'>
                        ⚠️ <b>Chưa nhận ổ cứng:</b> Hãy cắm lại cổng USB hoặc mở cài đặt quyền trên TV nếu cần.
                    </div>
                    <button onclick='requestStoragePermission()' class='btn-sm btn-accent'>🔑 Mở cài đặt TV</button>
                </div>
            """.trimIndent())
        }

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
                                    <th>Tên mục</th>
                                    <th class='col-desktop' style='width: 120px;'>Kích thước</th>
                                    <th class='col-desktop' style='width: 160px;'>Ngày cập nhật</th>
                                    <th style='width: 150px; text-align: right;'>Thao tác</th>
                                </tr>
                            </thead>
                            <tbody>
                """.trimIndent())

                // Nút quay lại thư mục cha
                if (parentPath != null && parentPath != "/storage" && parentPath != "/") {
                    val encParent = URLEncoder.encode(parentPath, "UTF-8")
                    html.append("""
                        <tr class='row-parent file-row' onclick="location.href='/files?path=$encParent'">
                            <td colspan='4'>
                                <div class='file-cell'>
                                    <span class='item-icon'>📁</span>
                                    <div class='item-meta-wrap'>
                                        <div class='item-title'><b>.. (Thư mục cha)</b></div>
                                    </div>
                                </div>
                            </td>
                        </tr>
                    """.trimIndent())
                }

                if (files.isEmpty()) {
                    html.append("""
                        <tr>
                            <td colspan='4' style='text-align:center; padding: 40px 16px; color: var(--text-muted);'>
                                <div style='font-size: 36px; margin-bottom: 8px;'>📂</div>
                                <div style='font-size: 15px; font-weight: 600; color: var(--text-white);'>Thư mục này hiện không có file khả dụng</div>
                                <div style='font-size: 13px; margin-top: 6px; color: var(--text-muted);'>Hệ thống đã tự động lọc các thư mục rác Windows (${'$'}RECYCLE.BIN, System Volume Information...).</div>
                            </td>
                        </tr>
                    """.trimIndent())
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

                        val safeName = f.name.replace("'", "\\'")
                        val rowClick = if (f.isDirectory) {
                            "location.href='/files?path=$encPath'"
                        } else if (f.isVideo || f.isAudio) {
                            "playMedia('$encPath', '$safeName', ${f.isVideo})"
                        } else ""

                        val subText = if (f.isDirectory) "Thư mục" else "${f.sizeFormatted} • ${f.dateFormatted}"

                        html.append("""
                            <tr class='file-row' onclick="$rowClick">
                                <td>
                                    <div class='file-cell'>
                                        <span class='item-icon'>$icon</span>
                                        <div class='item-meta-wrap'>
                                            <div class='item-title'>${f.name}</div>
                                            <div class='item-sub-mobile'>$subText</div>
                                        </div>
                                    </div>
                                </td>
                                <td class='col-desktop'>${f.sizeFormatted}</td>
                                <td class='col-desktop'>${f.dateFormatted}</td>
                                <td class='file-actions' onclick='event.stopPropagation()'>
                        """.trimIndent())

                        if (f.isDirectory) {
                            html.append("""
                                <a href='/files?path=$encPath' class='btn-sm btn-outline'>Mở</a>
                                <button onclick="deleteItem('$encPath', '$safeName', true)" class='btn-sm btn-danger'>Xóa</button>
                            """.trimIndent())
                        } else {
                            if (f.isVideo || f.isAudio) {
                                html.append("""
                                    <button onclick="playMedia('$encPath', '$safeName', ${f.isVideo})" class='btn-sm btn-primary'>Phát</button>
                                """.trimIndent())
                            }
                            if (f.isApk) {
                                html.append("""
                                    <button onclick="installLocalApk('$encPath', '$safeName')" class='btn-sm btn-accent'>Cài APK</button>
                                """.trimIndent())
                            }
                            html.append("""
                                <a href='/download?path=$encPath' class='btn-sm btn-outline' download>Tải về</a>
                                <button onclick="deleteItem('$encPath', '$safeName', false)" class='btn-sm btn-danger'>Xóa</button>
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
    private fun serveStream(out: OutputStream, requestedPath: String?, rangeHeader: String?, isDownload: Boolean, isHead: Boolean = false) {
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

        if (isHead) return

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
        val fullBoundary = ("\r\n" + String(boundary, StandardCharsets.ISO_8859_1)).toByteArray(StandardCharsets.ISO_8859_1)
        val bLen = fullBoundary.size
        val buffer = ByteArray(65536)
        val bufferedOut = java.io.BufferedOutputStream(out, 65536)
        var tail = ByteArray(0)

        try {
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                val combined = if (tail.isNotEmpty()) {
                    val c = ByteArray(tail.size + n)
                    System.arraycopy(tail, 0, c, 0, tail.size)
                    System.arraycopy(buffer, 0, c, tail.size, n)
                    c
                } else {
                    val c = ByteArray(n)
                    System.arraycopy(buffer, 0, c, 0, n)
                    c
                }

                val matchIdx = indexOfBytePattern(combined, fullBoundary)
                if (matchIdx != -1) {
                    if (matchIdx > 0) {
                        bufferedOut.write(combined, 0, matchIdx)
                    }
                    bufferedOut.flush()
                    return
                }

                if (combined.size > bLen) {
                    val writeLen = combined.size - bLen
                    bufferedOut.write(combined, 0, writeLen)
                    tail = ByteArray(bLen)
                    System.arraycopy(combined, writeLen, tail, 0, bLen)
                } else {
                    tail = combined
                }
            }
            if (tail.isNotEmpty()) {
                bufferedOut.write(tail)
            }
            bufferedOut.flush()
        } finally {
            bufferedOut.flush()
        }
    }

    private fun indexOfBytePattern(data: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || data.size < target.size) return -1
        outer@ for (i in 0..(data.size - target.size)) {
            for (j in target.indices) {
                if (data[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
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
        html.append("<div class='container'>")
        html.append(renderHeaderBar("install", "Cài đặt APK từ xa", "v1.0.4", "📦"))
        html.append("""
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
                val detailsJson = result.details.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
                val json = """{"success":${result.success},"method":"${result.method}","message":"${result.message.replace("\"", "\\\"")}","details":[$detailsJson]}"""
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
        val detailsJson = result.details.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        val json = """{"success":${result.success},"method":"${result.method}","message":"${result.message.replace("\"", "\\\"")}","details":[$detailsJson]}"""
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
    }

    private fun handleOpenStorageSettings(out: OutputStream) {
        try {
            val intents = mutableListOf<Intent>()
            // 1. Android TV universal App Info (Settings -> Apps -> BoxStation -> Permissions)
            intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            // 2. All Files Access intents
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                intents.add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                intents.add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            // 3. Fallback to general settings
            intents.add(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            var opened = false
            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    opened = true
                    break
                } catch (e: Exception) {
                    // try next intent
                }
            }

            if (opened) {
                AppLogger.i("STORAGE", "📱 Đã gửi Intent mở màn hình cài đặt quyền/ứng dụng trên TV")
                sendResponse(out, 200, "application/json", """{"success":true,"message":"Đã mở cài đặt ứng dụng trên TV!"}""".toByteArray())
            } else {
                sendResponse(out, 500, "application/json", """{"success":false,"message":"Không tìm thấy màn hình cài đặt phù hợp"}""".toByteArray())
            }
        } catch (e: Exception) {
            AppLogger.e("STORAGE", "Lỗi mở màn hình quyền: ${e.message}", e)
            sendResponse(out, 500, "application/json", """{"success":false,"message":"Lỗi: ${e.message}"}""".toByteArray())
        }
    }

    private fun handleAdbTest(out: OutputStream) {
        val cr = context.contentResolver
        val pm = context.packageManager

        val devSettingsEnabled = try {
            Settings.Global.getInt(cr, "development_settings_enabled", -1)
        } catch (e: Exception) { -2 }

        val adbEnabledGlobal = try {
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, -1)
        } catch (e: Exception) { -2 }

        val adbEnabledSecure = try {
            Settings.Secure.getInt(cr, "adb_enabled", -1)
        } catch (e: Exception) { -2 }

        // Test direct write
        var directPutResult = "OK"
        try {
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        } catch (e: Exception) {
            directPutResult = "${e.javaClass.simpleName}: ${e.message}"
        }

        // Test Activities
        val testIntents = listOf(
            "ACTION_APPLICATION_DEVELOPMENT_SETTINGS" to Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            "ACTION_DEVICE_INFO_SETTINGS" to Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            "ACTION_SETTINGS" to Intent(Settings.ACTION_SETTINGS)
        )

        val resolvedList = mutableListOf<String>()
        var openedActivity: String? = null
        for ((name, intent) in testIntents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolves = pm.queryIntentActivities(intent, 0)
            val count = resolves.size
            val matchedNames = resolves.map { it.activityInfo.name }
            resolvedList.add("\"$name\":{\"count\":$count,\"activities\":[${matchedNames.joinToString(",") { "\"$it\"" }}]}")
            if (openedActivity == null && count > 0) {
                try {
                    context.startActivity(intent)
                    openedActivity = "$name -> ${matchedNames.firstOrNull()}"
                } catch (e: Exception) {
                    // pass
                }
            }
        }

        // Check com.android.tv.settings package activities
        val pkgActivities = try {
            val pkgInfo = pm.getPackageInfo("com.android.tv.settings", android.content.pm.PackageManager.GET_ACTIVITIES)
            pkgInfo.activities?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val json = StringBuilder()
        json.append("{")
        json.append("\"development_settings_enabled\":$devSettingsEnabled,")
        json.append("\"adb_enabled_global\":$adbEnabledGlobal,")
        json.append("\"adb_enabled_secure\":$adbEnabledSecure,")
        json.append("\"direct_put_error\":\"${directPutResult.replace("\"", "\\\"")}\",")
        json.append("\"opened_activity\":\"${openedActivity ?: "none"}\",")
        json.append("\"intents\":{${resolvedList.joinToString(",")}},")
        json.append("\"tv_settings_activities\":[${pkgActivities.joinToString(",") { "\"$it\"" }}]")
        json.append("}")

        sendResponse(out, 200, "application/json", json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleOpenActivity(out: OutputStream, target: String?) {
        val t = target?.trim() ?: ""
        val intent = when {
            t.equals("about", ignoreCase = true) -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            t.equals("dev", ignoreCase = true) -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            t.equals("accessibility", ignoreCase = true) -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            t.equals("accessibility_oem", ignoreCase = true) -> Intent("android.settings.ACCESSIBILITY_TV_OEM_LINK").setPackage("com.android.tv.settings")
            t.equals("adb_pwd", ignoreCase = true) -> Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.vendor.util.AdbPasswordActivity"))
            t.equals("settings", ignoreCase = true) -> Intent(Settings.ACTION_SETTINGS)
            t.equals("main", ignoreCase = true) -> Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"))
            t.startsWith("action:", ignoreCase = true) -> Intent(t.substringAfter("action:"))
            t.contains("/") -> {
                val p = t.substringBefore("/")
                val c = t.substringAfter("/")
                Intent().setComponent(ComponentName(p, c))
            }
            else -> Intent(Settings.ACTION_SETTINGS)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            sendResponse(out, 200, "application/json", """{"success":true,"opened":"$target"}""".toByteArray())
        } catch (e: Exception) {
            sendResponse(out, 500, "application/json", """{"success":false,"error":"${e.javaClass.simpleName}: ${e.message}"}""".toByteArray())
        }
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
        sb.append("},")
        sb.append("\"autoReboot\":{\"enabled\":${AutoRebootHelper.isEnabled(context)},\"schedule\":\"${AutoRebootHelper.getStatusString(context)}\"},")
        sb.append("\"fptShield\":{\"supported\":${FptShieldHelper.isFptDevice()},\"enabled\":${FptShieldHelper.isEnabled(context)},\"blockedCount\":${FptShieldHelper.getBlockedCount(context)},\"status\":\"${FptShieldHelper.getStatusString(context)}\"},")
        sb.append("\"appVersion\":\"v${BuildConfig.VERSION_NAME}\"")
        sb.append("}")
        sendResponse(out, 200, "application/json", sb.toString().toByteArray())
    }

    // --- 8. HEADER BAR HELPER ---
    private fun renderHeaderBar(activeTab: String, title: String = "BoxStation", badge: String = "v${BuildConfig.VERSION_NAME}", icon: String = "📡"): String {
        val navFiles = if (activeTab == "files") "btn btn-primary" else "btn btn-outline"
        val navApps = if (activeTab == "apps") "btn btn-primary" else "btn btn-outline"
        val navScreen = if (activeTab == "screen") "btn btn-primary" else "btn btn-outline"
        val navInstall = if (activeTab == "install") "btn btn-accent" else "btn btn-outline"
        val navLog = if (activeTab == "log") "btn btn-primary" else "btn btn-outline"

        val fptShieldBadge = if (FptShieldHelper.isFptDevice()) {
            val en = FptShieldHelper.isEnabled(context)
            val color = if (en) "#10B981" else "#94A3B8"
            val bg = if (en) "rgba(16, 185, 129, 0.15)" else "rgba(148, 163, 184, 0.15)"
            val border = if (en) "rgba(16, 185, 129, 0.3)" else "rgba(148, 163, 184, 0.3)"
            val text = if (en) "🛡️ Trạm Siêu Nhẹ: BẬT" else "🛡️ Trạm Siêu Nhẹ: TẮT"
            """<button onclick='toggleFptShield()' class='badge' style='cursor:pointer; background: $bg; color: $color; border: 1px solid $border;' title='Bấm để Bật/Tắt chế độ Trạm Siêu Nhẹ: Đóng băng toàn bộ FPT Play, Truyền hình, Phim ảnh, Nhận dạng giọng nói để giải phóng RAM tối đa'>$text</button>"""
        } else ""

        return """
            <div class='header-bar'>
                <div class='logo-title'>
                    <span class='logo-icon'>$icon</span>
                    <div>
                        <h1>$title</h1>
                        <span class='badge'>FPT Box &amp; G2 Mini NAS $badge</span>
                        <span class='badge' style='background: rgba(16, 185, 129, 0.15); color: #10B981; border-color: rgba(16, 185, 129, 0.3);'>⚡ 24/7 Headless</span>
                        <span class='badge' style='background: rgba(245, 158, 11, 0.15); color: #F59E0B; border-color: rgba(245, 158, 11, 0.3);'>⏰ Reboot 03:00 Sáng</span>
                        $fptShieldBadge
                    </div>
                </div>
                <div class='nav-actions'>
                    <a href='/files' class='$navFiles'>📂 File</a>
                    <a href='/apps' class='$navApps'>📱 Ứng dụng</a>
                    <a href='/screen' class='$navScreen'>📸 Màn hình TV</a>
                    <a href='/install' class='$navInstall'>📦 Cài APK</a>
                    <a href='/log' class='$navLog'>📋 Log</a>
                    <button onclick='confirmReboot()' class='btn btn-danger'>🔄 Reboot</button>
                </div>
            </div>
        """.trimIndent()
    }

    // --- 9. APP MANAGER (DANH SÁCH & GỠ BỎ APP) ---
    private fun serveAppsPage(out: OutputStream, showAll: Boolean) {
        val apps = AppManagerHelper.getInstalledApps(context, showAll)
        val html = StringBuilder()
        html.append(getHtmlHead("BoxStation - Quản lý Ứng dụng"))
        html.append("<div class='container'>")
        html.append(renderHeaderBar("apps", "Quản lý Ứng dụng", "v1.0.4", "📱"))

        val toggleUrl = if (showAll) "/apps" else "/apps?all=1"
        val toggleText = if (showAll) "Chỉ xem app cài thêm" else "Xem tất cả (gồm hệ thống)"

        html.append("""
            <div class='browser-toolbar' style='border-radius: 10px 10px 0 0; margin-bottom: 0;'>
                <div style='display: flex; align-items: center; gap: 12px; flex-wrap: wrap;'>
                    <input type='text' id='appSearch' placeholder='🔍 Tìm tên hoặc package...' class='text-input' style='max-width: 280px; padding: 8px 12px;' onkeyup='filterApps()' />
                    <span style='color: var(--text-muted); font-size: 13px;'>Tổng số: <b>${apps.size}</b> ứng dụng</span>
                </div>
                <div class='toolbar-btns'>
                    <a href='$toggleUrl' class='btn btn-outline'>$toggleText</a>
                    <a href='/install' class='btn btn-accent'>📦 Cài APK mới</a>
                </div>
            </div>

            <div class='table-responsive'>
                <table class='file-table'>
                    <thead>
                        <tr>
                            <th>Ứng dụng</th>
                            <th class='col-desktop' style='width: 140px;'>Phiên bản</th>
                            <th class='col-desktop' style='width: 120px;'>Dung lượng</th>
                            <th style='width: 170px; text-align: right;'>Thao tác</th>
                        </tr>
                    </thead>
                    <tbody id='appsTableBody'>
        """.trimIndent())

        if (apps.isEmpty()) {
            html.append("<tr><td colspan='4' style='text-align:center; padding: 32px; color: var(--text-muted);'>Không tìm thấy ứng dụng nào</td></tr>")
        } else {
            for (app in apps) {
                val encPkg = URLEncoder.encode(app.packageName, "UTF-8")
                val safeName = app.name.replace("'", "\\'")
                val sysBadge = if (app.isSystem) "<span class='badge' style='background: rgba(148, 163, 184, 0.15); color: #94A3B8; font-size: 10px; margin-left: 6px;'>Hệ thống</span>" else ""
                val subText = "${app.packageName} • v${app.versionName} • ${app.sizeFormatted}"

                html.append("""
                    <tr class='file-row app-item' data-search='${app.name.lowercase(Locale.ROOT)} ${app.packageName.lowercase(Locale.ROOT)}'>
                        <td>
                            <div class='file-cell'>
                                <img src='/api/app-icon?pkg=$encPkg' alt='${app.name}' style='width: 42px; height: 42px; border-radius: 9px; flex-shrink: 0; background: #1e293b; object-fit: contain;' onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'42\' height=\'42\'><rect width=\'42\' height=\'42\' fill=\'%23334155\' rx=\'9\'/></svg>'" />
                                <div class='item-meta-wrap'>
                                    <div class='item-title'>${app.name} $sysBadge</div>
                                    <div class='item-sub-mobile'>$subText</div>
                                </div>
                            </div>
                        </td>
                        <td class='col-desktop' style='font-family: monospace; font-size: 13px;'>v${app.versionName}</td>
                        <td class='col-desktop'>${app.sizeFormatted}</td>
                        <td class='file-actions' onclick='event.stopPropagation()'>
                            <button onclick="launchApp('$encPkg', '$safeName')" class='btn-sm btn-primary'>🚀 Mở</button>
                            <button onclick="stopApp('$encPkg', '$safeName')" class='btn-sm btn-outline' style='color: #F59E0B; border-color: #F59E0B; margin-left: 4px;'>⏹️ Tắt</button>
                """.trimIndent())

                if (!app.isSystem && app.packageName != context.packageName) {
                    html.append("""
                        <button onclick="uninstallApp('$encPkg', '$safeName')" class='btn-sm btn-danger' style='margin-left: 4px;'>🗑️ Gỡ</button>
                    """.trimIndent())
                }

                html.append("</td></tr>")
            }
        }

        html.append("""
                    </tbody>
                </table>
            </div>

            <script>
                function filterApps() {
                    const q = document.getElementById('appSearch').value.toLowerCase();
                    const rows = document.querySelectorAll('.app-item');
                    rows.forEach(r => {
                        const txt = r.getAttribute('data-search') || '';
                        r.style.display = txt.includes(q) ? '' : 'none';
                    });
                }

                function launchApp(pkgEnc, name) {
                    fetch('/api/app/launch', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                        body: 'pkg=' + pkgEnc
                    }).then(r => r.json()).then(res => {
                        alert(res.message);
                    });
                }

                function stopApp(pkgEnc, name) {
                    if (!confirm('Dừng/Đóng ứng dụng "' + name + '" trên TV?')) return;
                    fetch('/api/app/stop', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                        body: 'pkg=' + pkgEnc
                    }).then(r => r.json()).then(res => {
                        alert(res.message);
                    });
                }

                function uninstallApp(pkgEnc, name) {
                    if (!confirm('Xác nhận gỡ bỏ ứng dụng "' + name + '" khỏi TV?\n(Dịch vụ Trợ năng sẽ tự động xác nhận trên màn hình)')) return;
                    fetch('/api/app/uninstall', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                        body: 'pkg=' + pkgEnc
                    }).then(r => r.json()).then(res => {
                        alert(res.message);
                        setTimeout(() => location.reload(), 1500);
                    });
                }
            </script>
        """.trimIndent())

        html.append("</div></body></html>")
        sendResponse(out, 200, "text/html; charset=utf-8", html.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun serveAppsJson(out: OutputStream, showAll: Boolean) {
        val apps = AppManagerHelper.getInstalledApps(context, showAll)
        val sb = StringBuilder("[")
        apps.forEachIndexed { i, a ->
            if (i > 0) sb.append(",")
            sb.append("""{"name":"${a.name.replace("\"", "\\\"")}","package":"${a.packageName}","version":"${a.versionName}","size":"${a.sizeFormatted}","isSystem":${a.isSystem}}""")
        }
        sb.append("]")
        sendResponse(out, 200, "application/json", sb.toString().toByteArray())
    }

    private fun serveAppIcon(out: OutputStream, pkg: String?) {
        if (pkg.isNullOrEmpty()) {
            sendResponse(out, 400, "text/plain", "Missing pkg".toByteArray())
            return
        }
        val bytes = AppManagerHelper.getAppIcon(context, pkg)
        if (bytes != null) {
            sendResponse(out, 200, "image/png", bytes, listOf("Cache-Control: public, max-age=86400"))
        } else {
            sendResponse(out, 404, "text/plain", "Icon not found".toByteArray())
        }
    }

    private fun handleAppLaunch(input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val body = readBodyString(input, headers)
        val params = parseQueryParams(body)
        val pkg = params["pkg"]
        if (pkg.isNullOrEmpty()) {
            sendResponse(out, 400, "application/json", """{"success":false,"message":"Thiếu package name"}""".toByteArray())
            return
        }
        val (ok, msg) = AppManagerHelper.launchApp(context, pkg)
        val json = """{"success":$ok,"message":"${msg.replace("\"", "\\\"")}"}"""
        sendResponse(out, 200, "application/json", json.toByteArray())
    }

    private fun handleAppUninstall(input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val body = readBodyString(input, headers)
        val params = parseQueryParams(body)
        val pkg = params["pkg"]
        if (pkg.isNullOrEmpty()) {
            sendResponse(out, 400, "application/json", """{"success":false,"message":"Thiếu package name"}""".toByteArray())
            return
        }
        val (ok, msg) = AppManagerHelper.uninstallApp(context, pkg)
        val json = """{"success":$ok,"message":"${msg.replace("\"", "\\\"")}"}"""
        sendResponse(out, 200, "application/json", json.toByteArray())
    }

    // --- 10. SCREENSHOT CAPTURE (CHỤP MÀN HÌNH TV TỪ XA) ---
    private fun serveScreenPage(out: OutputStream) {
        val html = StringBuilder()
        html.append(getHtmlHead("BoxStation - Màn hình TV từ xa"))
        html.append("<div class='container'>")
        html.append(renderHeaderBar("screen", "Màn hình TV từ xa", "v1.0.8", "📸"))

        val isAccEnabled = AutoInstallService.isServiceEnabled
        val statusBadge = if (isAccEnabled) {
            "<div class='alert' style='background: rgba(16, 185, 129, 0.15); border: 1px solid rgba(16, 185, 129, 0.3); color: #10B981; padding: 12px 16px; border-radius: 8px; margin-bottom: 20px;'>🟢 <b>Trợ năng đang hoạt động:</b> Chụp màn hình TV và chạm tương tác trực tiếp theo thời gian thực!</div>"
        } else {
            "<div class='alert' style='background: rgba(245, 158, 11, 0.15); border: 1px solid rgba(245, 158, 11, 0.3); color: #F59E0B; padding: 12px 16px; border-radius: 8px; margin-bottom: 20px;'>🟡 <b>Chưa bật Trợ năng:</b> Để chụp màn hình TV từ xa, vui lòng mở BoxStation trên TV và bấm 'Bật Auto-Click (Trợ năng)'.</div>"
        }
        html.append(statusBadge)

        html.append("""
            <div class='browser-toolbar' style='border-radius: 10px; margin-bottom: 16px;'>
                <div style='display: flex; gap: 10px; align-items: center; flex-wrap: wrap;'>
                    <button onclick='refreshScreen()' class='btn btn-primary'>📸 Chụp lại ngay</button>
                    <button onclick='toggleAuto(this)' id='btnAuto' class='btn btn-outline'>⏱️ Tự động làm mới (Tắt)</button>
                </div>
                <div class='toolbar-btns'>
                    <a id='btnDownload' href='/api/screenshot' download='tv_screen.jpg' class='btn btn-accent'>💾 Tải ảnh về</a>
                </div>
            </div>

            <div style='background: #020617; border: 2px solid #334155; border-radius: 14px; padding: 12px; text-align: center; box-shadow: 0 10px 30px rgba(0,0,0,0.6);'>
                <img id='tvScreen' src='/api/screenshot' alt='Màn hình TV' style='max-width: 100%; max-height: 70vh; border-radius: 8px; object-fit: contain; background: #000; cursor: crosshair;' title='Click vào ảnh để chạm màn hình TV' />
                <div id='screenMeta' style='color: var(--text-muted); font-size: 12px; margin-top: 8px;'>💡 Click chuột vào ảnh để chạm trực tiếp màn hình TV! Cập nhật: vừa xong</div>
            </div>

            <!-- Virtual Remote Control Panel -->
            <div style='background: #0F172A; border: 1px solid #1E293B; border-radius: 14px; padding: 20px; margin-top: 20px; max-width: 540px; margin-left: auto; margin-right: auto;'>
                <div style='font-size: 15px; font-weight: bold; text-align: center; margin-bottom: 16px; color: #38BDF8;'>🎮 Điều Khiển TV Từ Xa (Virtual Remote)</div>

                <!-- System Controls -->
                <div style='display: flex; justify-content: center; gap: 8px; margin-bottom: 16px; flex-wrap: wrap;'>
                    <button onclick="sendKey('wake')" class='btn-sm btn-outline' style='border-color: #10B981; color: #10B981;'>⚡ Bật màn hình</button>
                    <button onclick="sendKey('home')" class='btn-sm btn-primary'>🏠 Home</button>
                    <button onclick="sendKey('back')" class='btn-sm btn-outline'>🔙 Quay lại</button>
                    <button onclick="sendKey('recents')" class='btn-sm btn-outline'>📋 Đa nhiệm</button>
                    <button onclick="sendKey('power')" class='btn-sm btn-outline' style='border-color: #F59E0B; color: #F59E0B;'>⚡ Menu Nguồn</button>
                    <button onclick="sendKey('reboot')" class='btn-sm btn-danger' style='background: #DC2626;'>🔄 Khởi động lại Box</button>
                </div>
                <div style='display: flex; justify-content: center; gap: 8px; margin-bottom: 16px; flex-wrap: wrap;'>
                    <button onclick="sendKey('volup')" class='btn-sm btn-outline'>🔊 Vol +</button>
                    <button onclick="sendKey('voldown')" class='btn-sm btn-outline'>🔉 Vol -</button>
                    <button onclick="sendKey('mute')" class='btn-sm btn-outline'>🔇 Mute</button>
                </div>

                <!-- D-Pad Directional Controls -->
                <div style='display: flex; flex-direction: column; align-items: center; gap: 6px; margin-bottom: 8px;'>
                    <div>
                        <button onclick="sendKey('up')" class='btn btn-outline' style='width: 72px; height: 44px; font-size: 18px;'>▲</button>
                    </div>
                    <div style='display: flex; gap: 6px;'>
                        <button onclick="sendKey('left')" class='btn btn-outline' style='width: 72px; height: 44px; font-size: 18px;'>◀</button>
                        <button onclick="sendKey('enter')" class='btn btn-primary' style='width: 72px; height: 44px; font-size: 16px; font-weight: bold;'>OK</button>
                        <button onclick="sendKey('right')" class='btn btn-outline' style='width: 72px; height: 44px; font-size: 18px;'>▶</button>
                    </div>
                    <div>
                        <button onclick="sendKey('down')" class='btn btn-outline' style='width: 72px; height: 44px; font-size: 18px;'>▼</button>
                    </div>
                </div>

                <div style='text-align: center; color: var(--text-muted); font-size: 12px; margin-top: 12px;'>
                    ⏰ Tự động khởi động lại Box: 03:00 sáng mỗi ngày (${AutoRebootHelper.getStatusString(context)})
                </div>
            </div>

            <script>
                let autoTimer = null;

                function refreshScreen() {
                    const img = document.getElementById('tvScreen');
                    const t = Date.now();
                    img.src = '/api/screenshot?t=' + t;
                    document.getElementById('btnDownload').href = '/api/screenshot?t=' + t;
                    document.getElementById('screenMeta').innerText = '💡 Click chuột vào ảnh để chạm màn hình TV! Cập nhật lúc: ' + new Date().toLocaleTimeString();
                }

                function toggleAuto(btn) {
                    if (autoTimer) {
                        clearInterval(autoTimer);
                        autoTimer = null;
                        btn.innerText = '⏱️ Tự động làm mới (Tắt)';
                        btn.classList.remove('btn-primary');
                        btn.classList.add('btn-outline');
                    } else {
                        autoTimer = setInterval(refreshScreen, 2000);
                        btn.innerText = '⏱️ Đang tự làm mới (mỗi 2s)';
                        btn.classList.remove('btn-outline');
                        btn.classList.add('btn-primary');
                    }
                }

                function sendKey(k) {
                    fetch('/api/remote?key=' + k, {method: 'POST'})
                        .then(r => r.json())
                        .then(res => {
                            setTimeout(refreshScreen, 400);
                        });
                }

                // Chạm trực tiếp màn hình TV khi click chuột vào ảnh
                const screenImg = document.getElementById('tvScreen');
                screenImg.addEventListener('click', function(e) {
                    const rect = screenImg.getBoundingClientRect();
                    const scaleX = 1920 / rect.width;
                    const scaleY = 1080 / rect.height;
                    const clickX = Math.round((e.clientX - rect.left) * scaleX);
                    const clickY = Math.round((e.clientY - rect.top) * scaleY);
                    fetch('/api/click?x=' + clickX + '&y=' + clickY, {method: 'POST'})
                        .then(r => r.json())
                        .then(res => {
                            setTimeout(refreshScreen, 400);
                        });
                });
            </script>
        """.trimIndent())

        html.append("</div></body></html>")
        sendResponse(out, 200, "text/html; charset=utf-8", html.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun serveScreenshotImage(out: OutputStream) {
        var bmp: Bitmap? = null

        // 1. Thử qua AccessibilityService.takeScreenshot nếu có
        val service = AutoInstallService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val latch = CountDownLatch(1)
            service.takeScreenCapture { captured ->
                bmp = captured
                latch.countDown()
            }
            try {
                latch.await(3500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {}
        }

        // 2. Thử fallback qua screencap shell nếu chưa có
        if (bmp == null) {
            try {
                val cacheFile = File(context.cacheDir, "screen_capture.png")
                val p = Runtime.getRuntime().exec(arrayOf("screencap", "-p", cacheFile.absolutePath))
                p.waitFor()
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    cacheFile.delete()
                }
            } catch (e: Exception) {}
        }

        if (bmp != null) {
            val baos = ByteArrayOutputStream()
            bmp?.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val bytes = baos.toByteArray()
            sendResponse(out, 200, "image/jpeg", bytes, listOf(
                "Cache-Control: no-cache, no-store, must-revalidate",
                "Pragma: no-cache",
                "Expires: 0"
            ))
        } else {
            val svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="800" height="450" viewBox="0 0 800 450">
                    <rect width="800" height="450" fill="#0F172A"/>
                    <circle cx="400" cy="180" r="48" fill="#1E293B" stroke="#334155" stroke-width="2"/>
                    <text x="400" y="195" font-family="sans-serif" font-size="36" fill="#06B6D4" text-anchor="middle">📸</text>
                    <text x="400" y="270" font-family="sans-serif" font-size="20" font-weight="bold" fill="#F8FAFC" text-anchor="middle">Chưa thể chụp màn hình TV</text>
                    <text x="400" y="305" font-family="sans-serif" font-size="14" fill="#94A3B8" text-anchor="middle">Vui lòng đảm bảo dịch vụ Trợ năng BoxStation đã được BẬT trên TV.</text>
                </svg>
            """.trimIndent()
            sendResponse(out, 200, "image/svg+xml", svg.toByteArray(StandardCharsets.UTF_8), listOf(
                "Cache-Control: no-cache, no-store, must-revalidate"
            ))
        }
    }

    // --- 11. LOG VIEWER ---
    private fun serveLogPage(out: OutputStream) {
        val logs = AppLogger.getLogs().reversed().joinToString("<br>")
        val html = StringBuilder()
        html.append(getHtmlHead("BoxStation - Nhật ký hệ thống"))
        html.append("<div class='container'>")
        html.append(renderHeaderBar("log", "Nhật ký hệ thống", "v1.0.4", "📋"))
        html.append("""
            <div class='browser-toolbar' style='border-radius: 10px; margin-bottom: 16px;'>
                <span style='color: var(--text-muted); font-size: 13px;'>Tự động làm mới mỗi 3 giây</span>
                <div class='toolbar-btns'>
                    <a href='/log' class='btn btn-primary'>🔄 Làm mới ngay</a>
                </div>
            </div>
            <div class='log-box' style='background: #020617; border: 1px solid #1E293B; border-radius: 10px; padding: 16px; line-height: 1.7; font-size: 13.5px; font-family: monospace; max-height: 75vh; overflow-y: auto;'>
                $logs
            </div>
            <script>setTimeout(() => location.reload(), 3000);</script>
            </div></body></html>
        """.trimIndent())
        sendResponse(out, 200, "text/html; charset=utf-8", html.toString().toByteArray(StandardCharsets.UTF_8))
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
                        -webkit-overflow-scrolling: touch;
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
                        vertical-align: middle;
                    }
                    .file-row { cursor: pointer; transition: background 0.15s ease; }
                    .file-row:hover { background: #233149; }
                    .file-cell { display: flex; align-items: center; gap: 12px; min-width: 0; }
                    .item-icon { font-size: 24px; flex-shrink: 0; }
                    .item-meta-wrap { min-width: 0; flex: 1; }
                    .item-title {
                        font-weight: 500;
                        font-size: 14.5px;
                        color: #F8FAFC;
                        word-break: break-word;
                        overflow-wrap: break-word;
                        white-space: normal;
                        line-height: 1.4;
                    }
                    .item-sub-mobile { display: none; font-size: 12px; color: var(--text-muted); margin-top: 3px; }
                    .file-actions { text-align: right; white-space: nowrap; }
                    .file-actions .btn-sm { margin-left: 4px; }
                    .row-parent { background: #131d2e; cursor: pointer; }

                    .storage-banner {
                        background: rgba(245, 158, 11, 0.12);
                        border: 1px solid rgba(245, 158, 11, 0.35);
                        border-radius: 10px;
                        padding: 12px 16px;
                        margin-bottom: 20px;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        gap: 12px;
                        flex-wrap: wrap;
                    }
                    .banner-text { font-size: 13.5px; color: #FBBF24; line-height: 1.5; }

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

                    /* Responsive CSS for Mobile Screens */
                    @media (max-width: 768px) {
                        .container { padding: 12px; }
                        .header-bar { flex-direction: column; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
                        .nav-actions { width: 100%; justify-content: flex-start; }
                        .drives-grid { grid-template-columns: 1fr; gap: 12px; margin-bottom: 16px; }
                        .browser-toolbar { flex-direction: column; align-items: flex-start; gap: 10px; padding: 12px; }
                        .toolbar-btns { width: 100%; justify-content: flex-start; }

                        /* Ẩn các cột Kích thước và Ngày cập nhật trên điện thoại */
                        .col-desktop {
                            display: none !important;
                        }
                        .item-sub-mobile {
                            display: block !important;
                        }
                        .file-table td {
                            padding: 10px 12px;
                        }
                        .file-cell {
                            gap: 10px;
                        }
                        .item-title {
                            font-size: 14px;
                        }
                        .item-icon {
                            font-size: 22px;
                        }
                        .file-actions {
                            padding-left: 4px !important;
                            padding-right: 8px !important;
                        }
                        .btn-sm {
                            padding: 5px 8px;
                            font-size: 11.5px;
                        }
                    }
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

                function toggleFptShield() {
                    fetch('/api/fpt-shield')
                        .then(r => r.json())
                        .then(data => {
                            if (!data.supported) return alert('Tính năng này chỉ dành cho FPT Play Box!');
                            const next = data.enabled ? 0 : 1;
                            const msg = next 
                                ? '🛡️ BẬT CHẾ ĐỘ TRẠM PHÁT SIÊU NHẸ:\nBoxStation sẽ tự động đóng băng toàn bộ dịch vụ FPT TV, Phim ảnh, Trợ lý giọng nói và các tiến trình rác để giải phóng RAM tối đa cho trạm phát AceStream 24/7.\n\nBạn có muốn BẬT không?' 
                                : '🔄 ROLLBACK VỀ NGUYÊN BẢN:\nBoxStation sẽ ngừng đóng băng, trả về trạng thái mặc định của FPT 100%.\n\nBạn có muốn TẮT không?';
                            if (confirm(msg)) {
                                fetch('/api/fpt-shield?enabled=' + next)
                                    .then(r => r.json())
                                    .then(res => {
                                        alert(res.message);
                                        location.reload();
                                    });
                            }
                        })
                        .catch(e => alert('Lỗi kết nối FPT Shield: ' + e));
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

                function requestStoragePermission() {
                    fetch('/api/open-storage-settings', { method: 'POST' })
                        .then(r => r.json())
                        .then(res => alert(res.message))
                        .catch(e => alert('Lỗi gửi lệnh tới Box'));
                }
            </script>
        """.trimIndent()
    }

    private fun handleAppStop(input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val body = readBodyString(input, headers)
        val params = parseQueryParams(body)
        val pkg = params["pkg"]
        if (pkg.isNullOrEmpty()) {
            sendResponse(out, 400, "application/json", """{"success":false,"message":"Thiếu package name"}""".toByteArray())
            return
        }
        val (ok, msg) = AppManagerHelper.stopApp(context, pkg)
        val safeMsg = msg.replace("\"", "\\\"")
        val json = """{"success":$ok,"message":"$safeMsg"}"""
        sendResponse(out, 200, "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleRemoteControl(out: OutputStream, key: String?) {
        val k = key?.lowercase(Locale.ROOT) ?: ""
        var success = false
        var message = ""
        val service = AutoInstallService.instance

        when (k) {
            "home" -> {
                success = service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) ?: false
                if (!success) {
                    try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "3")); success = true } catch (e: Exception) {}
                }
                message = if (success) "Đã về màn hình Home" else "Trợ năng chưa sẵn sàng"
            }
            "back" -> {
                success = service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false
                if (!success) {
                    try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "4")); success = true } catch (e: Exception) {}
                }
                message = if (success) "Đã quay lại" else "Trợ năng chưa sẵn sàng"
            }
            "recents" -> {
                success = service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
                message = if (success) "Đã mở đa nhiệm" else "Trợ năng chưa sẵn sàng"
            }
            "power" -> {
                success = service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) ?: false
                message = if (success) "Đã mở menu nguồn" else "Trợ năng chưa sẵn sàng"
            }
            "reboot" -> {
                if (service != null) {
                    service.triggerRebootSequence { ok, msg ->
                        AppLogger.i("REMOTE", "Reboot sequence: $ok - $msg")
                    }
                    success = true
                    message = "Đang kích hoạt chuỗi Khởi động lại Box qua Trợ năng..."
                } else {
                    val (rbOk, rbMsg) = SystemManagerHelper.rebootBox(context)
                    success = rbOk
                    message = rbMsg
                }
            }
            "wake" -> {
                try {
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", "224"))
                    success = true
                    message = "Đã bật màn hình TV"
                } catch (e: Exception) {
                    message = "Lỗi: ${e.message}"
                }
            }
            "up" -> {
                service?.swipe(960f, 650f, 960f, 400f, 150)
                try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "19")); success = true; message = "Up" } catch (e: Exception) {}
            }
            "down" -> {
                val rebootClicked = service?.clickText("Khởi động lại") ?: false
                if (!rebootClicked) {
                    service?.swipe(960f, 400f, 960f, 650f, 150)
                    try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "20")); success = true; message = "Down" } catch (e: Exception) {}
                } else {
                    success = true
                    message = "Đã bấm Khởi động lại trên Menu Nguồn!"
                }
            }
            "left" -> {
                service?.swipe(650f, 540f, 400f, 540f, 150)
                try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "21")); success = true; message = "Left" } catch (e: Exception) {}
            }
            "right" -> {
                service?.swipe(400f, 540f, 650f, 540f, 150)
                try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "22")); success = true; message = "Right" } catch (e: Exception) {}
            }
            "enter" -> {
                val specialClick = service?.clickText("Khởi động lại") ?: false ||
                                   service?.clickText("OK") ?: false ||
                                   service?.clickText("Đồng ý") ?: false ||
                                   service?.clickText("Xác nhận") ?: false
                if (!specialClick) {
                    try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "66")); success = true; message = "OK" } catch (e: Exception) {}
                } else {
                    success = true
                    message = "OK (Đã bấm trực tiếp vào nút trên màn hình)"
                }
            }
            "volup" -> {
                try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "24")); success = true; message = "Vol+" } catch (e: Exception) {}
            }
            "voldown" -> {
                try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "25")); success = true; message = "Vol-" } catch (e: Exception) {}
            }
            "mute" -> {
                try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "164")); success = true; message = "Mute" } catch (e: Exception) {}
            }
            else -> {
                message = "Phím không hợp lệ: $k"
            }
        }

        val json = """{"success":$success,"message":"$message"}"""
        sendResponse(out, 200, "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleClickApi(out: OutputStream, queryParams: Map<String, String>) {
        val service = AutoInstallService.instance
        if (service == null) {
            val json = """{"success":false,"message":"Trợ năng chưa bật trên Box"}"""
            sendResponse(out, 200, "application/json", json.toByteArray(StandardCharsets.UTF_8))
            return
        }

        val text = queryParams["text"]
        val xStr = queryParams["x"]
        val yStr = queryParams["y"]
        val startXStr = queryParams["startX"]
        val startYStr = queryParams["startY"]
        val endXStr = queryParams["endX"]
        val endYStr = queryParams["endY"]

        var success = false
        var message = ""

        if (!startXStr.isNullOrEmpty() && !startYStr.isNullOrEmpty() && !endXStr.isNullOrEmpty() && !endYStr.isNullOrEmpty()) {
            val sx = startXStr.toFloatOrNull() ?: 0f
            val sy = startYStr.toFloatOrNull() ?: 0f
            val ex = endXStr.toFloatOrNull() ?: 0f
            val ey = endYStr.toFloatOrNull() ?: 0f
            val dur = queryParams["duration"]?.toLongOrNull() ?: 250L
            success = service.swipe(sx, sy, ex, ey, dur)
            message = if (success) "Đã vuốt ($sx, $sy) -> ($ex, $ey)" else "Lỗi vuốt màn hình"
        } else if (!text.isNullOrEmpty()) {
            success = service.clickText(text)
            message = if (success) "Đã bấm '$text' thành công" else "Không tìm thấy nút có chữ '$text'"
        } else if (!xStr.isNullOrEmpty() && !yStr.isNullOrEmpty()) {
            val x = xStr.toFloatOrNull() ?: 0f
            val y = yStr.toFloatOrNull() ?: 0f
            success = service.clickAt(x, y)
            message = if (success) "Đã chạm tọa độ ($x, $y)" else "Lỗi chạm tọa độ ($x, $y)"
        } else {
            message = "Thiếu tham số (text, x/y, hoặc startX/startY/endX/endY)"
        }

        val json = """{"success":$success,"message":"$message"}"""
        sendResponse(out, 200, "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleAutoRebootConfig(out: OutputStream, queryParams: Map<String, String>) {
        if (queryParams.containsKey("enabled")) {
            val enabled = queryParams["enabled"] == "1" || queryParams["enabled"]?.lowercase(Locale.ROOT) == "true"
            AutoRebootHelper.setEnabled(context, enabled)
        }
        val isEn = AutoRebootHelper.isEnabled(context)
        val status = AutoRebootHelper.getStatusString(context)
        val json = """{"enabled":$isEn,"status":"$status","schedule":"03:00 hàng ngày"}"""
        sendResponse(out, 200, "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleFptShieldConfig(out: OutputStream, queryParams: Map<String, String>) {
        val supported = FptShieldHelper.isFptDevice()
        if (!supported) {
            val json = """{"supported":false,"enabled":false,"status":"Không áp dụng","message":"Thiết bị này không phải FPT Play Box (Không áp dụng)"}"""
            sendResponse(out, 200, "application/json", json.toByteArray(StandardCharsets.UTF_8))
            return
        }

        if (queryParams.containsKey("enabled")) {
            val enabled = queryParams["enabled"] == "1" || queryParams["enabled"]?.lowercase(Locale.ROOT) == "true"
            FptShieldHelper.setEnabled(context, enabled)
        }

        val isEn = FptShieldHelper.isEnabled(context)
        val status = FptShieldHelper.getStatusString(context)
        val blockedCount = FptShieldHelper.getBlockedCount(context)
        val msg = if (isEn) "Chế độ Trạm Siêu Nhẹ ĐANG BẬT: Đã đóng băng toàn bộ dịch vụ FPT TV, Phim ảnh, Giọng nói và giải phóng RAM tối đa!"
                  else "Chế độ Trạm Siêu Nhẹ ĐÃ TẮT: Đã hoàn nguyên trạng thái nguyên bản 100%!"

        val json = """{"supported":true,"enabled":$isEn,"blockedCount":$blockedCount,"status":"$status","message":"$msg"}"""
        sendResponse(out, 200, "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }
}
