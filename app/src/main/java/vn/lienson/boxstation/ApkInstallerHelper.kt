package vn.lienson.boxstation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class InstallResult(
    val success: Boolean,
    val method: String,
    val message: String,
    val details: List<String> = emptyList()
)

object ApkInstallerHelper {

    fun installApk(context: Context, apkFile: File): InstallResult {
        val details = mutableListOf<String>()

        if (!apkFile.exists() || !apkFile.isFile) {
            val msg = "File APK không tồn tại hoặc không thể đọc: ${apkFile.absolutePath}"
            AppLogger.e("INSTALLER", "🔴 [THẤT BẠI] $msg")
            details.add("🔴 File không tồn tại")
            return InstallResult(false, "NONE", msg, details)
        }

        if (!apkFile.name.endsWith(".apk", ignoreCase = true)) {
            val msg = "File không phải định dạng .apk (${apkFile.name})"
            AppLogger.e("INSTALLER", "🔴 [THẤT BẠI] $msg")
            details.add("🔴 Định dạng file không hợp lệ")
            return InstallResult(false, "NONE", msg, details)
        }

        val sizeStr = StorageManagerHelper.formatSize(apkFile.length())
        AppLogger.i("INSTALLER", "📦 [BẮT ĐẦU CÀI ĐẶT] Tệp: ${apkFile.name} | Dung lượng: $sizeStr | Nguồn: ${apkFile.absolutePath}")
        details.add("📦 Nhận tệp: ${apkFile.name} ($sizeStr)")

        // 1. Thử Phương Thức 1: Cài đặt ngầm qua Shell (pm install) nếu có quyền elevated/root
        AppLogger.d("INSTALLER", "⚡ [PHƯƠNG THỨC 1 - SHELL] Đang thử cài đặt ngầm bằng lệnh pm install...")
        val pmResult = tryShellPmInstall(apkFile.absolutePath)
        if (pmResult.success) {
            val msg = "Cài đặt ngầm thành công không cần thao tác trên TV: ${pmResult.message}"
            AppLogger.i("INSTALLER", "🟢 [THÀNH CÔNG NGẦM] $msg")
            details.add("🟢 Phương thức 1 (pm install): Thành công ngầm!")
            return InstallResult(true, "SILENT_PM_INSTALL", msg, details)
        } else {
            AppLogger.w("INSTALLER", "🟡 [SHELL BỎ QUA] Phương thức 1 không khả dụng: ${pmResult.message}. Chuyển sang Phương thức 2...")
            details.add("🟡 Phương thức 1 (Shell): ${pmResult.message}")
        }

        // 2. Thử Phương Thức 2: Kích hoạt System Package Installer Intent qua FileProvider
        AppLogger.d("INSTALLER", "📺 [PHƯƠNG THỨC 2 - FILEPROVIDER] Đang tạo Content URI và gọi Intent Package Installer...")
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            }

            context.startActivity(intent)
            val msg = "Đã kích hoạt hộp thoại cài đặt ${apkFile.name} trên màn hình TV!"
            AppLogger.i("INSTALLER", "🟢 [THÀNH CÔNG GỬI LÊN TV] $msg Vui lòng bấm 'Cài đặt' trên màn hình TV.")
            details.add("🟢 Phương thức 2 (FileProvider Intent): Đã hiện hộp thoại cài đặt trên TV")
            return InstallResult(true, "INTENT_FILEPROVIDER", msg, details)
        } catch (e: Exception) {
            val msg = "Lỗi khi kích hoạt Intent cài đặt APK: ${e.message}"
            AppLogger.e("INSTALLER", "🔴 [THẤT BẠI HOÀN TOÀN] $msg", e)
            details.add("🔴 Phương thức 2 (FileProvider): Thất bại - ${e.message}")
            return InstallResult(false, "FAILED", msg, details)
        }
    }

    private fun tryShellPmInstall(apkPath: String): InstallResult {
        val commands = listOf(
            arrayOf("su", "-c", "pm install -r -d \"$apkPath\""),
            arrayOf("sh", "-c", "pm install -r -d \"$apkPath\"")
        )

        for (cmd in commands) {
            try {
                val proc = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val errReader = BufferedReader(InputStreamReader(proc.errorStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append(" ")
                }
                while (errReader.readLine().also { line = it } != null) {
                    output.append(line).append(" ")
                }
                proc.waitFor()
                val resultText = output.toString().trim()
                if (proc.exitValue() == 0 && resultText.contains("Success", ignoreCase = true)) {
                    return InstallResult(true, "pm_install", resultText)
                }
            } catch (e: Exception) {
                // Ignore and try next
            }
        }
        return InstallResult(false, "pm_install", "Thiếu quyền root shell (ROM FPT chặn shell pm)")
    }
}
