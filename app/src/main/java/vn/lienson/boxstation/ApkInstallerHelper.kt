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
    val message: String
)

object ApkInstallerHelper {

    fun installApk(context: Context, apkFile: File): InstallResult {
        if (!apkFile.exists() || !apkFile.isFile) {
            return InstallResult(false, "none", "File APK không tồn tại: ${apkFile.absolutePath}")
        }
        if (!apkFile.name.endsWith(".apk", ignoreCase = true)) {
            return InstallResult(false, "none", "File không phải định dạng .apk")
        }

        AppLogger.i("Installer", "Bắt đầu cài đặt APK: ${apkFile.name} (${StorageManagerHelper.formatSize(apkFile.length())})")

        // 1. Thử cài đặt qua Shell ngầm (pm install) nếu có quyền root / elevated
        val pmResult = tryShellPmInstall(apkFile.absolutePath)
        if (pmResult.success) {
            AppLogger.i("Installer", "🟢 Cài đặt ngầm thành công qua pm install: ${pmResult.message}")
            return pmResult
        } else {
            AppLogger.d("Installer", "pm install không khả dụng hoặc bị giới hạn: ${pmResult.message}")
        }

        // 2. Kích hoạt System Package Installer Intent qua FileProvider
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
            AppLogger.i("Installer", "🟢 Đã bật hộp thoại cài đặt APK lên màn hình TV qua FileProvider")
            return InstallResult(
                success = true,
                method = "Intent/FileProvider",
                message = "Đã kích hoạt hộp thoại cài đặt ${apkFile.name} trên màn hình TV!"
            )
        } catch (e: Exception) {
            AppLogger.e("Installer", "Lỗi kích hoạt Intent cài APK: ${e.message}", e)
            return InstallResult(
                success = false,
                method = "Intent/FileProvider",
                message = "Lỗi kích hoạt trình cài đặt: ${e.message}"
            )
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
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
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
        return InstallResult(false, "pm_install", "Thiếu quyền root shell")
    }
}
