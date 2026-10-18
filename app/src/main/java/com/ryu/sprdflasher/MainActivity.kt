package com.ryu.sprdflasher

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ryu.sprdflasher.flash.FlashEngineBridge
import com.ryu.sprdflasher.ui.compose.LiquidGlassScreen

/**
 * Compose 版主界面。
 *
 * 壁纸铺满全屏（视觉连续），内容避开系统栏；USB 插入事件转发给 FlashEngineBridge。
 * SAF onActivityResult 也在这里分发（requestCode 与原工程完全一致）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var flashVm: FlashEngineBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        flashVm = FlashEngineBridge(this)

        // SAF 启动器：Bridge 发起请求，Activity 执行 startActivityForResult
        flashVm.safLauncher = { requestCode, intent ->
            try {
                startActivityForResult(intent, requestCode)
            } catch (t: Throwable) {
                Toast.makeText(this, "无法打开选择器: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT
            )
        )

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent
            ) {
                LiquidGlassScreen(vm = flashVm)
            }
        }

        // 原工程 REQ_STORAGE 运行时存储权限（:386-397）
        requestStoragePermission()
        handleUsbIntent(intent)
    }

    private fun requestStoragePermission() {
        try {
            when {
                // Android 11+ (API 30): 需要 MANAGE_EXTERNAL_STORAGE 才能自由读写
                android.os.Build.VERSION.SDK_INT >= 30 -> {
                    if (!android.os.Environment.isExternalStorageManager()) {
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                        flashVm.appendLog("请授予「所有文件访问权限」后返回")
                    }
                }
                // Android 6~10 (API 23~29): 运行时权限
                android.os.Build.VERSION.SDK_INT >= 23 -> {
                    val perms = arrayOf(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    )
                    for (p in perms) {
                        if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(perms, FlashEngineBridge.REQ_STORAGE)
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        val dev: UsbDevice? =
            intent?.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
        if (dev != null) flashVm.connectDevice(dev)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            FlashEngineBridge.REQ_SELECT_FILE -> flashVm.onWriteFilePicked(uri)
            FlashEngineBridge.REQ_SELECT_FDL -> flashVm.onFdlPicked(uri)
            FlashEngineBridge.REQ_SELECT_FDL2 -> flashVm.onFdl2Picked(uri)
            FlashEngineBridge.REQ_SELECT_PAC -> flashVm.onPacPicked(uri)
            FlashEngineBridge.REQ_SELECT_SAVE_DIR -> flashVm.onSaveDirPicked(uri)
            FlashEngineBridge.REQ_SAVE_LOG_AS -> flashVm.onSaveLogPicked(uri)
            FlashEngineBridge.REQ_SELECT_BATCH_DIR -> flashVm.onBatchDirPicked(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 权限管理器是应用级单例，随进程存活；Activity 销毁不注销，避免刷机中断连
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 原工程 REQ_STORAGE 结果仅记日志（权限失败时写文件功能受限）
        if (requestCode == FlashEngineBridge.REQ_STORAGE) {
            flashVm.appendLog("存储权限请求完成")
        }
    }
}
