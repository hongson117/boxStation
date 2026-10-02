package vn.lienson.boxstation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvServerUrl: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvAllIps: TextView
    private lateinit var tvDrivesList: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvAutoReboot: TextView
    private lateinit var btnAutoClick: Button
    private lateinit var btnPermission: Button
    private lateinit var btnRestartService: Button
    private lateinit var btnRebootBox: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvAllIps = findViewById(R.id.tvAllIps)
        tvDrivesList = findViewById(R.id.tvDrivesList)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvVersion = findViewById(R.id.tvVersion)
        tvAutoReboot = findViewById(R.id.tvAutoReboot)
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        tvAutoReboot.text = "⏰ Tự động khởi động lại Box: 03:00 sáng mỗi ngày (${AutoRebootHelper.getStatusString(this)})"
        btnAutoClick = findViewById(R.id.btnAutoClick)
        btnPermission = findViewById(R.id.btnPermission)
        btnRestartService = findViewById(R.id.btnRestartService)
        btnRebootBox = findViewById(R.id.btnRebootBox)

        btnAutoClick.setOnClickListener {
            openAccessibilitySettings()
        }

        btnPermission.setOnClickListener {
            checkAndRequestStoragePermission()
        }

        btnRestartService.setOnClickListener {
            restartStationService()
            Toast.makeText(this, "Đang khởi động lại dịch vụ BoxStation...", Toast.LENGTH_SHORT).show()
        }

        btnRebootBox.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Khởi động lại Box")
                .setMessage("Bạn có chắc chắn muốn khởi động lại thiết bị ngay bây giờ?")
                .setPositiveButton("Khởi động lại") { _, _ ->
                    val (ok, msg) = SystemManagerHelper.rebootBox(this)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        // Đảm bảo BootReceiver luôn được hệ thống kích hoạt tự khởi động
        try {
            val receiver = ComponentName(this, BootReceiver::class.java)
            packageManager.setComponentEnabledSetting(
                receiver,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            AppLogger.i("BOOT", "🟢 [TỰ KHỞI ĐỘNG] BootReceiver đã được kích hoạt thành công (Trạng thái: ENABLED)")
        } catch (e: Exception) {
            AppLogger.e("BOOT", "🔴 [TỰ KHỞI ĐỘNG LỖI] Lỗi kích hoạt BootReceiver: ${e.message}", e)
        }

        // Thử kích hoạt AutoInstall qua shell ngầm nếu có quyền
        AutoInstallService.tryEnableViaShell()

        // Tự động kiểm tra và yêu cầu cấp quyền đọc bộ nhớ / USB
        requestAllStoragePermissions()

        startStationService()
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
    }

    private fun startStationService() {
        val serviceIntent = Intent(this, StationService::class.java).apply {
            action = StationService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            AppLogger.i("SERVICE", "🟢 [DỊCH VỤ] Đã gửi lệnh startForegroundService cho StationService")
        } catch (e: Exception) {
            AppLogger.e("SERVICE", "🔴 [DỊCH VỤ THẤT BẠI] Lỗi khởi động StationService: ${e.message}", e)
        }
    }

    private fun restartStationService() {
        val stopIntent = Intent(this, StationService::class.java).apply {
            action = StationService.ACTION_STOP
        }
        stopService(stopIntent)

        tvServerUrl.postDelayed({
            startStationService()
            updateDashboard()
        }, 1000)
    }

    private fun updateDashboard() {
        val preferredIp = SystemManagerHelper.getPreferredIp()
        tvServerUrl.text = "http://$preferredIp:8888"
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        tvAutoReboot.text = "⏰ Tự động khởi động lại Box: 03:00 sáng mỗi ngày (${AutoRebootHelper.getStatusString(this)})"

        val ips = SystemManagerHelper.getIpAddresses()
        val ipSummary = ips.entries.joinToString("  |  ") { "${it.key}: ${it.value}" }
        tvAllIps.text = if (ipSummary.isNotEmpty()) ipSummary else "Đang ngắt kết nối mạng"

        // Quét ổ cứng
        val drives = StorageManagerHelper.getStorageDrives(this)
        if (drives.isEmpty()) {
            tvDrivesList.text = "Không tìm thấy ổ cứng nào được gắn."
        } else {
            val sb = StringBuilder()
            drives.forEachIndexed { i, d ->
                val icon = if (d.isUsb) "💾" else "📱"
                sb.append("$icon ${d.name}\n")
                sb.append("   • Đường dẫn: ${d.path}\n")
                sb.append("   • Dung lượng: Còn trống ${d.freeFormatted} / Tổng ${d.totalFormatted} (Đã dùng ${d.usedPercent}%)\n")
                if (i < drives.size - 1) sb.append("\n")
            }
            tvDrivesList.text = sb.toString()
        }

        // Trạng thái Auto-Click Trợ Năng
        if (AutoInstallService.checkAccessibilityEnabled(this)) {
            btnAutoClick.text = "✅ ĐÃ BẬT AUTO-CLICK CÀI ĐẶT (KHÔNG CẦN HDMI)"
            btnAutoClick.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            btnAutoClick.text = "⚡ 1-CLICK: BẬT AUTO-CLICK CÀI ĐẶT (TRỢ NĂNG)"
            btnAutoClick.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_orange))
        }

        // Quyền lưu trữ Android 11+
        val hasStorageAccess = try {
            val drives = StorageManagerHelper.getStorageDrives(this)
            drives.isNotEmpty() && drives.any { File(it.path).canRead() }
        } catch (e: Exception) {
            false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager() || hasStorageAccess) {
                btnPermission.text = "✅ Đã Có Toàn Quyền Ổ Cứng"
                btnPermission.isEnabled = true
                btnPermission.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
            } else {
                btnPermission.text = "🔑 Cấp Quyền Đọc Ổ Cứng (MANAGE)"
                btnPermission.isEnabled = true
                btnPermission.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_orange))
            }
        } else {
            btnPermission.text = "✅ Đã Có Quyền Bộ Nhớ"
            btnPermission.isEnabled = false
        }

        // Thông tin thiết bị
        val sys = SystemManagerHelper.getSystemInfo(this)
        tvDeviceInfo.text = "${sys.deviceModel} | ${sys.androidVersion} | RAM: Còn trống ${sys.freeRam} / ${sys.totalRam} | Uptime: ${sys.uptime}"
    }

    private fun openAccessibilitySettings() {
        if (AutoInstallService.checkAccessibilityEnabled(this)) {
            Toast.makeText(this, "Dịch vụ Auto-Click Trợ Năng đã đang hoạt động hoàn hảo!", Toast.LENGTH_SHORT).show()
            return
        }

        val intents = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.system.AccessibilityActivity")),
            Intent().setComponent(ComponentName("com.google.android.tv.settings", "com.google.android.tv.settings.system.AccessibilityActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.AccessibilitySettings"))
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Hãy gạt BẬT 'BoxStation Auto Install' trong mục Trợ năng!", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                // try next
            }
        }
        Toast.makeText(this, "Không thể mở cài đặt Trợ năng.", Toast.LENGTH_SHORT).show()
    }

    private fun requestAllStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 1001)
                AppLogger.i("STORAGE", "📢 Đã gửi yêu cầu cấp quyền Storage: ${needed.joinToString()}")
            }
        }
    }

    private fun checkAndRequestStoragePermission() {
        requestAllStoragePermissions()
        val intents = mutableListOf<Intent>()
        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intents.add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
            intents.add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        intents.add(Intent(Settings.ACTION_SETTINGS))

        for (intent in intents) {
            try {
                startActivity(intent)
                Toast.makeText(this, "Hãy kiểm tra quyền Tệp / Bộ nhớ trong Cài đặt!", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                // try next
            }
        }
        Toast.makeText(this, "Không thể mở cài đặt quyền.", Toast.LENGTH_SHORT).show()
    }
}
