package com.ryu.sprdflasher.flash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ryu.sprdflasher.UsbFlashEngine

/**
 * FlashViewModel —— 把原工程的 UsbFlashEngine（Java）接到 Compose UI。
 *
 * 线程约定：引擎回调可能来自引擎线程，统一 post 到主线程再改状态。
 */
class FlashViewModel(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.ryu.sprdflasher.USB_PERMISSION"
    }

    /** 引擎回调接口（真实签名来自 UsbFlashEngine.Callback） */
    private val engineCallback = object : UsbFlashEngine.Callback {
        override fun onLog(msg: String?) {
            postLog(msg ?: return)
        }

        override fun onProgress(pct: Int, op: String?) {
            mainHandler.post {
                progress = (pct.coerceIn(0, 100)) / 100f
                currentOp = op ?: ""
            }
        }

        override fun onConnected(ok: Boolean, info: String?) {
            mainHandler.post {
                connected = ok
                postLog(if (ok) "设备已连接: ${info ?: ""}" else "连接失败: ${info ?: ""}")
            }
        }

        override fun onFinished(success: Boolean) {
            mainHandler.post {
                postLog(if (success) "操作完成" else "操作失败")
            }
        }

        override fun onError(msg: String?) {
            postLog("错误: ${msg ?: "未知"}")
        }
    }

    val engine = UsbFlashEngine(context, engineCallback)

    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var speed by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var currentOp by mutableStateOf("")
        private set
    val logs = mutableStateListOf<String>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun postLog(msg: String) {
        mainHandler.post {
            logs.add(msg)
            if (logs.size > 500) logs.removeRange(0, logs.size - 500)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && dev != null) {
                postLog("USB 权限已授予，连接设备…")
                connectDevice(dev)
            } else {
                postLog("USB 权限被拒绝")
            }
        }
    }

    init {
        engine.setBusyCallback { b -> mainHandler.post { busy = b } }
        engine.setSpeedCallback { _, formatted ->
            mainHandler.post { speed = formatted ?: "" }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    /** 系统插入设备时调用（Activity 转发） */
    fun onUsbAttached(dev: UsbDevice) = requestPermissionAndConnect(dev)

    fun requestPermissionAndConnect(dev: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(dev, intent)
    }

    fun findBootDevice(): UsbDevice? = engine.findBootDevice()

    fun connectDevice(dev: UsbDevice) {
        Thread {
            try {
                engine.connectDevice(dev)
            } catch (t: Throwable) {
                postLog("连接异常: ${t.message}")
            }
        }.start()
    }

    fun disconnect() {
        Thread {
            try {
                engine.disconnect()
            } catch (_: Throwable) {
            }
            mainHandler.post {
                connected = false
                postLog("已断开")
            }
        }.start()
    }

    private fun guardBusy(): Boolean {
        if (busy) {
            postLog("有操作正在进行，请等待")
            return true
        }
        if (!engine.isConnected) {
            postLog("设备未连接")
            return true
        }
        return false
    }

    private fun run(name: String, block: () -> Unit) {
        if (guardBusy()) return
        postLog("开始: $name")
        Thread {
            try {
                block()
            } catch (t: Throwable) {
                postLog("出错: ${t.message}")
            }
        }.start()
    }

    // ================= 页面操作 =================

    fun connectOrDisconnect() {
        if (connected) {
            disconnect()
        } else {
            val dev = findBootDevice()
            if (dev != null) {
                requestPermissionAndConnect(dev)
            } else {
                postLog("未发现刷机设备，请插入并进入下载模式")
            }
        }
    }

    fun sendFdl(fdl1: String, addr1: Long, fdl2: String, addr2: Long) = run("发送 FDL") {
        val ok = engine.sendFdl1AndFdl2(fdl1, addr1, fdl2, addr2)
        postLog(if (ok) "FDL 发送完成" else "FDL 发送失败")
    }

    fun readPartition(name: String, filename: String) = run("读取分区 $name") {
        if (name.isBlank() || filename.isBlank()) {
            postLog("分区名或文件名为空")
            return@run
        }
        val dir = engine.saveDir
        val path = if (dir.endsWith("/")) dir + filename else "$dir/$filename"
        engine.dumpPartition(name, -1, -1, path, 0x10000)
        postLog("分区读取完成: $path")
    }

    fun getPartitionSize(name: String) = run("查询分区大小") {
        if (name.isBlank()) {
            postLog("分区名为空")
            return@run
        }
        val size = engine.findPartitionSize(name)
        postLog("分区 $name 大小: ${UsbFlashEngine.formatSize(size)}")
    }

    fun writePartition(name: String, filename: String) = run("写入分区 $name") {
        if (name.isBlank() || filename.isBlank()) {
            postLog("分区名或文件名为空")
            return@run
        }
        engine.loadPartition(name, filename, 0x10000)
        postLog("分区写入完成")
    }

    fun erasePartition(name: String) = run("擦除分区 $name") {
        if (name.isBlank()) {
            postLog("分区名为空")
            return@run
        }
        engine.erasePartition(name)
        postLog("分区擦除完成")
    }

    fun setKickto(mode: Int) = run("设置 Kickto=$mode") {
        engine.setKicktoMode(mode)
        engine.startKicktoListen()
        postLog("Kickto 已启动，模式 $mode")
    }

    fun reboot() = run("重启") { engine.reset() }
    fun rebootRecovery() = run("重启到 Recovery") { engine.rebootRecovery() }
    fun rebootFastboot() = run("重启到 Fastboot") { engine.rebootFastboot() }
    fun powerOff() = run("关机") { engine.powerOff() }
    fun factoryReset() = run("恢复出厂") { engine.factoryReset() }

    fun clearLog() = mainHandler.post { logs.clear() }

    fun saveLog(): String? {
        return try {
            val dir = engine.saveDir.ifEmpty {
                context.getExternalFilesDir(null)?.absolutePath ?: ""
            }
            val f = java.io.File(dir, "log_${System.currentTimeMillis()}.txt")
            f.parentFile?.mkdirs()
            f.writeText(logs.joinToString("\n"))
            postLog("日志已保存: ${f.absolutePath}")
            f.absolutePath
        } catch (t: Throwable) {
            postLog("保存日志失败: ${t.message}")
            null
        }
    }

    fun destroy() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        try {
            engine.disconnect()
        } catch (_: Throwable) {
        }
    }
}
