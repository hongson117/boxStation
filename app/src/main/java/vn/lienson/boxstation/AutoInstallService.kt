package vn.lienson.boxstation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoInstallService : AccessibilityService() {

    companion object {
        var isServiceEnabled = false
            private set

        private val TARGET_TEXTS = listOf(
            "cài đặt", "cập nhật", "tiếp tục", "cho phép", "xong", "mở",
            "install", "update", "continue", "allow", "done", "open"
        )

        private val TARGET_VIEW_IDS = listOf(
            "com.android.packageinstaller:id/ok_button",
            "com.android.packageinstaller:id/btn_allow",
            "com.android.packageinstaller:id/btn_continue",
            "com.google.android.packageinstaller:id/ok_button",
            "android:id/button1"
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
        isServiceEnabled = true
        AppLogger.i("AUTO-CLICK", "🟢 [TRỢ NĂNG HOẠT ĐỘNG] Dịch vụ tự động bấm Cài đặt đã kích hoạt thành công!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""
        if (!pkg.contains("packageinstaller", ignoreCase = true) &&
            !pkg.contains("settings", ignoreCase = true)
        ) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        findAndClickTargetNode(rootNode)
    }

    private fun findAndClickTargetNode(node: AccessibilityNodeInfo): Boolean {
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

        // 2. Kiểm tra Text trên nút
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        if (text.isNotEmpty()) {
            for (target in TARGET_TEXTS) {
                if (text == target || (text.startsWith(target) && text.length <= target.length + 3)) {
                    if (clickNode(node)) {
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
                if (findAndClickTargetNode(child)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }

        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
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
        return false
    }

    override fun onInterrupt() {
        AppLogger.w("AUTO-CLICK", "🟡 Dịch vụ tự động bấm bị tạm ngưng")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceEnabled = false
        AppLogger.i("AUTO-CLICK", "🔴 Dịch vụ tự động bấm đã dừng")
    }
}
