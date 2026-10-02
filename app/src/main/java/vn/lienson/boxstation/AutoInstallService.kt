package vn.lienson.boxstation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat

class AutoInstallService : AccessibilityService() {

    companion object {
        var isServiceEnabled = false
            private set

        var instance: AutoInstallService? = null
            private set

        var rebootRequested = false

        private val REBOOT_TARGET_TEXTS = listOf(
            "khởi động lại", "reboot", "restart"
        )

        private val TARGET_TEXTS_INSTALLER = listOf(
            "cài đặt", "cập nhật", "tiếp tục", "cho phép", "xong", "mở",
            "install", "update", "continue", "allow", "done", "open"
        )

        private val TARGET_TEXTS_COMMON = listOf(
            "gỡ cài đặt", "gỡ bỏ", "đồng ý", "xác nhận",
            "buộc dừng", "buộc đóng", "dừng bắt buộc",
            "tắt", "tắt ứng dụng", "vô hiệu hóa",
            "uninstall", "ok", "delete", "force stop", "disable"
        )

        private val TARGET_VIEW_IDS = listOf(
            "com.android.packageinstaller:id/ok_button",
            "com.android.packageinstaller:id/btn_allow",
            "com.android.packageinstaller:id/btn_continue",
            "com.google.android.packageinstaller:id/ok_button",
            "android:id/button1",
            "com.android.settings:id/force_stop_button",
            "com.android.settings:id/right_button"
        )

        fun checkAccessibilityEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${AutoInstallService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = enabledServicesSetting.split(":")
            for (componentName in colonSplitter) {
                if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                    componentName.contains(context.packageName)
                ) {
                    return true
                }
            }
            return false
        }

        fun tryEnableViaShell() {
            try {
                val serviceStr = "vn.lienson.boxstation/vn.lienson.boxstation.AutoInstallService"
                Runtime.getRuntime().exec(arrayOf("settings", "put", "secure", "enabled_accessibility_services", serviceStr)).waitFor()
                Runtime.getRuntime().exec(arrayOf("settings", "put", "secure", "accessibility_enabled", "1")).waitFor()
            } catch (e: Exception) {
                // pass
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceEnabled = true
        AppLogger.i("AUTO-CLICK", "🟢 [TRỢ NĂNG HOẠT ĐỘNG] Dịch vụ tự động bấm Cài đặt / Trợ năng v1.1.0 đã kích hoạt!")
        wakeAceHub()
    }

    fun wakeAceHub() {
        try {
            val aceIntent = Intent().apply {
                component = ComponentName("vn.lienson.acesport.g2probe", "vn.lienson.acesport.g2probe.G2OrchestratorService")
                action = "ACTION_START_HUB"
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            startService(aceIntent)
            AppLogger.i("SUPERVISOR", "🚀 Trợ năng BoxStation đã tự động gửi lệnh kích hoạt AceHub Service!")
        } catch (e: Exception) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage("vn.lienson.acesport.g2probe")
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    AppLogger.i("SUPERVISOR", "🚀 Trợ năng BoxStation đã tự động mở AceHub Activity!")
                }
            } catch (e2: Exception) {
                AppLogger.w("SUPERVISOR", "Không thể wake AceHub: ${e2.message}")
            }
        }
    }

    fun takeScreenCapture(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = ContextCompat.getMainExecutor(this)
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            callback(copy)
                        } catch (e: Exception) {
                            AppLogger.e("SCREENSHOT", "Lỗi convert hardware buffer: ${e.message}", e)
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        AppLogger.e("SCREENSHOT", "Lỗi chụp màn hình Accessibility: mã lỗi $errorCode")
                        callback(null)
                    }
                }
            )
        } else {
            callback(null)
        }
    }

    /**
     * Bấm vào tọa độ (x, y) trên màn hình bằng Gestures API (không cần root hay INJECT_EVENTS)
     */
    fun clickAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            AppLogger.e("GESTURE", "Lỗi clickAt ($x, $y): ${e.message}")
            false
        }
    }

    /**
     * Vuốt trên màn hình từ (startX, startY) đến (endX, endY)
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 200): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            AppLogger.e("GESTURE", "Lỗi swipe: ${e.message}")
            false
        }
    }

    /**
     * Tìm và bấm vào nút có chứa text cụ thể
     */
    fun clickText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (clickNode(node)) {
                    AppLogger.i("AUTO-CLICK", "🎯 [BẤM THEO TEXT] Đã bấm thành công: '$targetText'")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Chuỗi lệnh tự động khởi động lại thiết bị qua Menu Nguồn Trợ năng
     */
    fun triggerRebootSequence(onResult: (Boolean, String) -> Unit) {
        rebootRequested = true
        val root = rootInActiveWindow

        // 1. Nếu Menu Nguồn đã mở sẵn trên màn hình, click luôn
        if (root != null && clickRebootNode(root)) {
            rebootRequested = false
            onResult(true, "Đã bấm nút Khởi động lại thành công!")
            return
        }

        // 2. Mở Menu Nguồn hệ thống
        val opened = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        if (!opened) {
            rebootRequested = false
            onResult(false, "Không thể mở Menu Nguồn qua Trợ năng")
            return
        }

        // 3. Lên lịch kiểm tra và bấm nút Khởi động lại sau khi menu xuất hiện
        val handler = Handler(Looper.getMainLooper())
        fun attemptRebootClick(attempt: Int) {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && clickRebootNode(activeRoot)) {
                rebootRequested = false
                AppLogger.i("REBOOT", "🚀 Đã click nút Khởi động lại thành công (lần thử $attempt)!")
                onResult(true, "Đã gửi lệnh Khởi động lại Box thành công!")
                return
            }

            if (attempt < 4) {
                handler.postDelayed({ attemptRebootClick(attempt + 1) }, 400)
            } else {
                // Fallback: Chạm tọa độ giữa màn hình - vị trí dòng Khởi động lại (960, 560 trên màn 1080p)
                val tapped = clickAt(960f, 560f)
                rebootRequested = false
                if (tapped) {
                    AppLogger.i("REBOOT", "🚀 Đã tap tọa độ nút Khởi động lại (960, 560)")
                    onResult(true, "Đã chạm vào vị trí Khởi động lại trên màn hình!")
                } else {
                    onResult(false, "Đã mở Menu Nguồn nhưng không bấm được Khởi động lại")
                }
            }
        }

        handler.postDelayed({ attemptRebootClick(1) }, 500)
    }

    private fun clickRebootNode(root: AccessibilityNodeInfo): Boolean {
        for (target in REBOOT_TARGET_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(target)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (clickNode(node)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // Kiểm tra FPT Shield: Tự động thoát màn hình khóa cước FPT & ẩn FPT Launcher
        if (FptShieldHelper.isEnabled(this) && pkg.contains("com.fptplay", ignoreCase = true)) {
            if (pkg == "com.fptplay.launcher") {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage("vn.lienson.acesport.g2probe")
                    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                        AppLogger.i("FPT-SHIELD", "🛡️ Đã tự động ẩn FPT Launcher và chuyển sang giao diện Trạm phát AceHub!")
                        return
                    }
                } catch (_: Exception) {}
            }

            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val sb = StringBuilder()
                collectNodeTexts(rootNode, sb)
                if (FptShieldHelper.isFptLockScreen(pkg, sb.toString())) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }
            }
        }

        // Tự động bấm Khởi động lại khi Menu Nguồn hệ thống xuất hiện
        val isSystemUi = pkg == "android" || pkg.contains("systemui", ignoreCase = true)
        if (isSystemUi && rebootRequested) {
            val rootNode = rootInActiveWindow
            if (rootNode != null && clickRebootNode(rootNode)) {
                rebootRequested = false
                AppLogger.i("AUTO-CLICK", "🎯 [TỰ ĐỘNG REBOOT] Đã bấm nút Khởi động lại trên Menu Nguồn hệ thống!")
                return
            }
        }

        // Tự động bấm Cài đặt / Gỡ bỏ ứng dụng
        val isInstaller = pkg.contains("packageinstaller", ignoreCase = true)
        val isSettings = pkg.contains("settings", ignoreCase = true)
        if (!isInstaller && !isSettings) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        findAndClickTargetNode(rootNode, isInstaller)
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            sb.append(text).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectNodeTexts(child, sb)
                child.recycle()
            }
        }
    }

    private fun findAndClickTargetNode(node: AccessibilityNodeInfo, isInstaller: Boolean): Boolean {
        // 1. Kiểm tra View ID
        val viewId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            node.viewIdResourceName?.lowercase() ?: ""
        } else ""

        for (targetId in TARGET_VIEW_IDS) {
            if (viewId.contains(targetId, ignoreCase = true)) {
                if (clickNode(node)) {
                    AppLogger.i("AUTO-CLICK", "🎯 [TỰ ĐỘNG BẤM THEO ID] Đã bấm nút có ID: $viewId")
                    return true
                }
            }
        }

        // 2. Kiểm tra Text trên nút (chỉ bấm nếu là nút hoặc clickable, hoặc trong trình cài đặt)
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        if (text.isNotEmpty()) {
            val targets = if (isInstaller) {
                TARGET_TEXTS_INSTALLER + TARGET_TEXTS_COMMON
            } else {
                TARGET_TEXTS_COMMON
            }

            for (target in targets) {
                if (text == target || (text.startsWith(target) && text.length <= target.length + 3)) {
                    val isButtonLike = node.isClickable ||
                            node.className?.contains("Button", ignoreCase = true) == true ||
                            node.parent?.isClickable == true
                    if (isButtonLike && clickNode(node)) {
                        AppLogger.i("AUTO-CLICK", "🎯 [TỰ ĐỘNG BẤM THEO TEXT] Đã bấm nút: '$text' (${node.className})")
                        return true
                    }
                }
            }
        }

        // 3. Quét đệ quy các View con
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (findAndClickTargetNode(child, isInstaller)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }

        return false
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.isEnabled) {
                val ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return ok
            }
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent
        }

        // Fallback: nếu node không clickable nhưng có vị trí trên màn hình, click theo tọa độ tâm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val centerX = bounds.centerX().toFloat()
                val centerY = bounds.centerY().toFloat()
                if (clickAt(centerX, centerY)) {
                    AppLogger.i("AUTO-CLICK", "🎯 [BẤM THEO TỌA ĐỘ BOUNDS] Đã click tâm node tại ($centerX, $centerY)")
                    return true
                }
            }
        }

        return false
    }

    override fun onInterrupt() {
        AppLogger.w("AUTO-CLICK", "🟡 Dịch vụ tự động bấm bị tạm ngưng")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        isServiceEnabled = false
        AppLogger.i("AUTO-CLICK", "🔴 Dịch vụ tự động bấm đã dừng")
    }
}
