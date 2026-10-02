package vn.lienson.boxstation

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*

data class InstalledApp(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val isSystem: Boolean,
    val installTime: Long,
    val updateTime: Long
) {
    val sizeFormatted: String get() = StorageManagerHelper.formatSize(sizeBytes)
    val installDateFormatted: String get() = StorageManagerHelper.formatDate(installTime)
    val updateDateFormatted: String get() = StorageManagerHelper.formatDate(updateTime)
}

object AppManagerHelper {

    private val iconCache = mutableMapOf<String, ByteArray>()

    fun getInstalledApps(context: Context, includeSystem: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val list = mutableListOf<InstalledApp>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSys = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            // Mặc định chỉ lấy app người dùng cài, trừ phi includeSystem = true
            if (!includeSystem && isSys && !isUpdatedSys) {
                continue
            }

            val appName = try {
                appInfo.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg.packageName
            }

            val size = try {
                val f = File(appInfo.sourceDir)
                if (f.exists()) f.length() else 0L
            } catch (e: Exception) {
                0L
            }

            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }

            list.add(
                InstalledApp(
                    name = appName,
                    packageName = pkg.packageName,
                    versionName = pkg.versionName ?: "1.0",
                    versionCode = vCode,
                    sizeBytes = size,
                    isSystem = isSys,
                    installTime = pkg.firstInstallTime,
                    updateTime = pkg.lastUpdateTime
                )
            )
        }

        return list.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun getAppIcon(context: Context, packageName: String): ByteArray? {
        iconCache[packageName]?.let { return it }

        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = appInfo.loadIcon(pm)
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val w = drawable.intrinsicWidth.coerceAtLeast(64).coerceAtMost(256)
                val h = drawable.intrinsicHeight.coerceAtLeast(64).coerceAtMost(256)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
            val bytes = baos.toByteArray()
            if (iconCache.size < 150) {
                iconCache[packageName] = bytes
            }
            bytes
        } catch (e: Exception) {
            null
        }
    }

    fun launchApp(context: Context, packageName: String): Pair<Boolean, String> {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AppLogger.i("APP", "🚀 Đã khởi chạy ứng dụng: $packageName")
                Pair(true, "Đã khởi chạy ứng dụng trên TV!")
            } else {
                Pair(false, "Không tìm thấy giao diện khởi chạy cho ứng dụng này")
            }
        } catch (e: Exception) {
            AppLogger.e("APP", "Lỗi khởi chạy app: ${e.message}", e)
            Pair(false, "Lỗi: ${e.message}")
        }
    }

    fun uninstallApp(context: Context, packageName: String): Pair<Boolean, String> {
        if (packageName == context.packageName) {
            return Pair(false, "Không thể gỡ bỏ chính ứng dụng BoxStation đang chạy!")
        }

        // 1. Thử lệnh shell pm uninstall nếu có quyền
        try {
            val p = Runtime.getRuntime().exec(arrayOf("pm", "uninstall", "--user", "0", packageName))
            val exitCode = p.waitFor()
            if (exitCode == 0) {
                AppLogger.i("INSTALLER", "🗑️ Gỡ bỏ thành công qua shell: $packageName")
                return Pair(true, "Đã gỡ cài đặt ứng dụng $packageName thành công!")
            }
        } catch (e: Exception) {
            // tiếp tục qua Intent
        }

        // 2. Kích hoạt hộp thoại Intent ACTION_DELETE (kèm Auto-Click Trợ năng tự xác nhận)
        return try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.i("INSTALLER", "🗑️ Đã mở hộp thoại gỡ cài đặt: $packageName")
            Pair(true, "Đã gửi lệnh gỡ cài đặt lên TV (Dịch vụ Trợ năng sẽ tự bấm Xác nhận)!")
        } catch (e: Exception) {
            AppLogger.e("INSTALLER", "Lỗi gỡ app: ${e.message}", e)
            Pair(false, "Lỗi: ${e.message}")
        }
    }
}
