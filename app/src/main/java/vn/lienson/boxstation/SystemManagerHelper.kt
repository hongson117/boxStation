package vn.lienson.boxstation

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.TimeUnit

data class SystemInfo(
    val deviceModel: String,
    val androidVersion: String,
    val totalRam: String,
    val freeRam: String,
    val uptime: String,
    val ipAddresses: Map<String, String>
)

object SystemManagerHelper {

    fun getSystemInfo(context: Context): SystemInfo {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRamStr = StorageManagerHelper.formatSize(memInfo.totalMem)
        val freeRamStr = StorageManagerHelper.formatSize(memInfo.availMem)

        val uptimeMillis = SystemClock.elapsedRealtime()
        val hours = TimeUnit.MILLISECONDS.toHours(uptimeMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60
        val uptimeStr = "${hours}h ${minutes}m"

        return SystemInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            totalRam = totalRamStr,
            freeRam = freeRamStr,
            uptime = uptimeStr,
            ipAddresses = getIpAddresses()
        )
    }

    fun getIpAddresses(): Map<String, String> {
        val ips = mutableMapOf<String, String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val name = when {
                            iface.name.startsWith("eth") -> "LAN (Ethernet)"
                            iface.name.startsWith("wlan") -> "Wi-Fi"
                            iface.name.contains("tailscale") || iface.name.startsWith("tun") -> "Tailscale"
                            else -> iface.name
                        }
                        ips[name] = ip
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("System", "Lỗi lấy IP: ${e.message}")
        }
        return ips
    }

    fun getPreferredIp(): String {
        val ips = getIpAddresses()
        return ips["LAN (Ethernet)"] ?: ips["Wi-Fi"] ?: ips["Tailscale"] ?: ips.values.firstOrNull() ?: "127.0.0.1"
    }

    fun rebootBox(context: Context): Pair<Boolean, String> {
        AppLogger.i("System", "Nhận lệnh REBOOT thiết bị từ xa qua Web!")

        // 1. Thử qua su -c reboot
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            proc.waitFor(2, TimeUnit.SECONDS)
            if (proc.exitValue() == 0) {
                return true to "Đang khởi động lại thiết bị (su root)..."
            }
        } catch (e: Exception) {
            // pass
        }

        // 2. Thử qua setprop sys.powerctl reboot
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("setprop", "sys.powerctl", "reboot"))
            proc.waitFor(2, TimeUnit.SECONDS)
            if (proc.exitValue() == 0) {
                return true to "Đang khởi động lại thiết bị (sys.powerctl)..."
            }
        } catch (e: Exception) {
            // pass
        }

        // 3. Thử lệnh reboot trực tiếp
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("reboot"))
            proc.waitFor(2, TimeUnit.SECONDS)
            if (proc.exitValue() == 0) {
                return true to "Đang khởi động lại thiết bị (reboot)..."
            }
        } catch (e: Exception) {
            // pass
        }

        // 4. Thử qua PowerManager API
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.reboot("remote_web_reboot")
            return true to "Đang khởi động lại thiết bị (PowerManager)..."
        } catch (e: Exception) {
            AppLogger.w("System", "PowerManager.reboot cần quyền hệ thống: ${e.message}")
        }

        // 5. Thử qua shell svc power reboot
        try {
            Runtime.getRuntime().exec(arrayOf("svc", "power", "reboot"))
            return true to "Đang khởi động lại thiết bị (svc power)..."
        } catch (e: Exception) {
            // pass
        }

        // 6. Thử qua AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        val service = AutoInstallService.instance
        if (service != null) {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            if (ok) {
                AppLogger.i("System", "🟢 Đã mở bảng nguồn hệ thống qua Trợ năng (Auto-Click sẽ tự bấm Khởi động lại)")
                return true to "Đang kích hoạt khởi động lại Box qua Trợ năng..."
            }
        }

        return false to "Không thể tự động reboot: Cần quyền Root hoặc chữ ký System. Bạn có thể rút nguồn khởi động lại nếu bị treo."
    }

    fun executeShell(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            proc.waitFor(5, TimeUnit.SECONDS)
            output.toString().trim()
        } catch (e: Exception) {
            "Lỗi thực thi shell: ${e.message}"
        }
    }
}
