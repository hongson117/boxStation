package vn.lienson.boxstation

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.Locale

object FptShieldHelper {

    private const val PREF_NAME = "box_station_prefs"
    private const val KEY_SHIELD_ENABLED = "fpt_shield_enabled"
    private const val KEY_BLOCKED_COUNT = "fpt_shield_blocked_count"

    // Danh sách toàn bộ bloatware (FPT, TV, Phim, Giọng nói, OEM) cần đóng băng để giải phóng RAM tối đa cho Trạm phát
    val TARGET_PACKAGES = listOf(
        // 1. Dịch vụ FPT, Launcher FPT & Cơ chế khóa cước từ xa
        "com.fptplay.launcher",         // Launcher FPT, phát hình, VOD
        "com.fptplay.box.service",      // Dịch vụ chạy ngầm brick_box và WebSocket FPT
        "rogo.iot.app.tv.playrogotv",   // Rogo IoT Smart Home FPT
        "com.bda.shoppingtv",           // Mua sắm Shopping TV

        // 2. Truyền hình, Phim ảnh, Video & Media Bloatware
        "com.google.android.videos",    // Google Play Phim
        "com.google.android.youtube.tv",// YouTube TV
        "com.google.android.youtube.tvmusic", // YouTube Music
        "com.google.android.play.games",// Google Play Trò chơi
        "com.google.android.backdrop",  // Screensaver ảnh nền độ phân giải cao
        "com.android.music",            // Trình phát nhạc hệ thống cũ
        "com.android.gallery3d",        // Thư viện ảnh
        "com.rtk.mediabrowser",         // Realtek Media Browser
        "com.rtk.mediabrowser.installer",

        // 3. Nhận dạng giọng nói & Trợ lý ảo ngốn RAM
        "com.google.android.katniss",   // Google Assistant TV (chiếm RAM & CPU ngầm)
        "com.google.android.tts",       // Google Text-to-Speech

        // 4. Bloatware rác của hãng OEM
        "com.hisense.consumecpuservice",
        "com.hisense.bigfac",
        "com.example.facservicetest",
        "com.hisense.myappinfo",
        "com.hisense.screenzoom",
        "com.hisense.stbinfo",
        "com.hisense.systemnotification",
        "com.hisense.usbupdate",
        "com.hisense.systemupdate"
    )

    private val LOCK_SCREEN_KEYWORDS = listOf(
        "khóa thiết bị", "hết hạn hợp đồng", "tạm ngưng dịch vụ",
        "liên hệ 1900", "thanh toán cước", "vui lòng gia hạn",
        "device locked", "expired contract"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Kiểm tra chính xác thiết bị có phải là FPT Play Box (Hisense / FHRT2X / IP940N) không.
     * TUYỆT ĐỐI KHÔNG ÁP DỤNG TRÊN CÁC DÒNG BOX KHÁC NHƯ ROCKTEK G2.
     */
    fun isFptDevice(): Boolean {
        val model = Build.MODEL.uppercase(Locale.ROOT)
        val mfg = Build.MANUFACTURER.uppercase(Locale.ROOT)
        val product = Build.PRODUCT.uppercase(Locale.ROOT)
        val device = Build.DEVICE.uppercase(Locale.ROOT)

        return model.contains("FPT") ||
               model.contains("650") ||
               mfg.contains("HISENSE") ||
               product.contains("FHRT") ||
               device.contains("IP940")
    }

    fun isEnabled(context: Context): Boolean {
        if (!isFptDevice()) return false
        return getPrefs(context).getBoolean(KEY_SHIELD_ENABLED, false)
    }

    fun getBlockedCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_BLOCKED_COUNT, 0)
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!isFptDevice()) {
            AppLogger.w("FPT-SHIELD", "⚠️ Thiết bị này (${Build.MODEL}) không phải FPT Play Box, bỏ qua lệnh bật Trạm Siêu Nhẹ.")
            return false
        }

        getPrefs(context).edit().putBoolean(KEY_SHIELD_ENABLED, enabled).apply()
        if (enabled) {
            AppLogger.i("FPT-SHIELD", "🛡️ [TRẠM SIÊU NHẸ ĐÃ BẬT] Đang cách ly toàn bộ dịch vụ FPT, Phim, Voice để giải phóng RAM tối đa!")
            protectNow(context)
        } else {
            AppLogger.i("FPT-SHIELD", "🔄 [ROLLBACK THÀNH CÔNG] Đã tắt chế độ Trạm Siêu Nhẹ, trả về nguyên bản FPT 100%.")
        }
        return true
    }

    fun getStatusString(context: Context): String {
        if (!isFptDevice()) {
            return "Không áp dụng (Thiết bị khác FPT)"
        }
        return if (isEnabled(context)) {
            val count = getBlockedCount(context)
            "BẬT (Trạm Siêu Nhẹ - Đã giải phóng RAM, đóng băng bloatware $count lần)"
        } else {
            "TẮT (FPT nguyên bản)"
        }
    }

    /**
     * Thực hiện đóng băng toàn bộ danh sách bloatware & giải phóng RAM tối đa
     */
    fun protectNow(context: Context) {
        if (!isEnabled(context) || !isFptDevice()) return

        var killedAny = false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        for (pkg in TARGET_PACKAGES) {
            try {
                // 1. Thử qua shell am force-stop
                val p = Runtime.getRuntime().exec(arrayOf("am", "force-stop", pkg))
                p.waitFor()
                if (p.exitValue() == 0) {
                    killedAny = true
                }
            } catch (e: Exception) {
                // pass
            }

            try {
                // 2. Thử qua killBackgroundProcesses
                am?.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                // pass
            }
        }

        if (killedAny) {
            val current = getBlockedCount(context)
            getPrefs(context).edit().putInt(KEY_BLOCKED_COUNT, current + 1).apply()
            AppLogger.d("FPT-SHIELD", "🛡️ Đã giải phóng RAM & đóng băng bloatware (${current + 1} lần)")
        }

        // Đảm bảo AceHub luôn được giữ thức và phục vụ phát luồng
        AutoInstallService.instance?.wakeAceHub()
    }

    /**
     * Dành cho AutoInstallService: Kiểm tra xem màn hình hiện tại có phải là màn hình khóa cước của FPT hay không
     */
    fun isFptLockScreen(packageName: String, allTexts: String): Boolean {
        if (!isEnabled(AutoInstallService.instance ?: return false)) return false

        val lowerPkg = packageName.lowercase(Locale.ROOT)
        val lowerText = allTexts.lowercase(Locale.ROOT)

        if (lowerPkg.contains("com.fptplay")) {
            for (kw in LOCK_SCREEN_KEYWORDS) {
                if (lowerText.contains(kw)) {
                    AppLogger.w("FPT-SHIELD", "⚠️ Phát hiện màn hình khóa cước FPT: '$kw' -> Tự động đưa về Home")
                    return true
                }
            }
        }
        return false
    }
}
