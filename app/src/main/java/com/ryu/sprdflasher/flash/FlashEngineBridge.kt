package com.ryu.sprdflasher.flash

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ryu.sprdflasher.UsbFlashEngine
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * FlashEngineBridge —— 引擎桥。
 *
 * 设计原则（用户要求：我的所有逻辑都不能变化）：
 *  所有命令逐字照搬原工程 MainActivity.runCmd / DsdlActivityImpl，
 *  经 execute(String[]) 或直接引擎 API 转发，不新增任何业务逻辑。
 */
class FlashEngineBridge(private val context: Context) {

    companion object {
        const val PERM_ACTION = "com.ryu.sprdflasher.USB_PERMISSION"

        const val REQ_STORAGE = 100
        const val REQ_SAVE_LOG = 200
        const val REQ_SAVE_LOG_AS = 205
        const val REQ_SELECT_FILE = 201
        const val REQ_SELECT_FDL = 202
        const val REQ_SELECT_FDL2 = 208
        const val REQ_SELECT_PAC = 203
        const val REQ_SELECT_APK = 204
        const val REQ_SELECT_PUSH = 205
        const val REQ_SELECT_BATCH_DIR = 207
        const val REQ_SELECT_SAVE_DIR = 209
        const val KEY_LAST_SAVE_DIR = "last_save_dir"
    }

    // ---------------- 引擎（原样） ----------------

    private val callback = object : UsbFlashEngine.Callback {
        override fun onLog(msg: String?) {
            msg?.let { appendLog(it) }
        }

        override fun onProgress(pct: Int, op: String?) {
            main.post {
                progressPct = pct.coerceIn(0, 100)
                progressOp = op ?: ""
            }
        }

        override fun onConnected(ok: Boolean, info: String?) {
            main.post {
                connected = ok
                appendLog(if (ok) "设备已连接: ${info ?: ""}" else "连接失败: ${info ?: ""}")
            }
        }

        override fun onFinished(success: Boolean) {
            main.post {
                appendLog(if (success) "操作完成" else "操作失败")
                busy = false
            }
        }

        override fun onError(msg: String?) {
            appendLog("错误: ${msg ?: "未知"}")
        }
    }

    val engine = UsbFlashEngine(context, callback)

    // ---------------- Compose 状态 ----------------

    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var progressPct by mutableStateOf(0)
        private set
    var progressOp by mutableStateOf("")
        private set
    var speedText by mutableStateOf("")
        private set
    val logs = mutableStateListOf<String>()

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun appendLog(msg: String) {
        main.post {
            logs.add(msg)
            if (logs.size > 800) logs.removeRange(0, logs.size - 800)
        }
    }

    init {
        engine.setBusyCallback { b -> main.post { busy = b } }
        engine.setSpeedCallback { _, formatted ->
            main.post { speedText = formatted ?: "" }
        }
        engine.setPartitionCallback { parts ->
            main.post {
                partitions.clear()
                parts?.forEach { partitions.add(PartitionItem(it.name, it.size, it.selected)) }
                if (parts != null) appendLog("分区表读取完成，共 ${parts.size} 个分区")
            }
        }
    }

    data class PartitionItem(val name: String, val size: Long, val selected: Boolean)

    val partitions = mutableStateListOf<PartitionItem>()

    // ---------------- 芯片 / FDL ----------------

    data class Chip(
        val id: String, val name: String,
        val addr1: String, val addr2: String,
        val fdl1Path: String, val fdl2Path: String,
        val size1: Long, val size2: Long
    )

    var chips by mutableStateOf<List<Chip>>(emptyList())
        private set

    var currentChip by mutableStateOf<Chip?>(null)
        private set

    // 原工程 selectedChipAddr1/2 —— 必须在 init{} 之前声明，
    // 因为 init → loadChips → selectChip / useBuiltinFdl(后台线程) 会写这些委托属性，
    // 若声明在 init 之后，后台线程可能先于初始化执行并写入而触发 NPE。
    var selectedChipAddr1 by mutableStateOf("0x5000")
        private set
    var selectedChipAddr2 by mutableStateOf("0x9EFFFE00")
        private set

    // 用户单独选择的 FDL（原工程 selectedFdlPath）
    var selectedFdlPath by mutableStateOf("")

    // ---------------- 自定义 FDL 绑定（工具页选择 → 连接页发送） ----------------

    /** 用户在工具页显式选定的 FDL1 */
    var customFdl1Path by mutableStateOf("")
        private set

    /** 用户在工具页显式选定的 FDL2 */
    var customFdl2Path by mutableStateOf("")
        private set

    /** FDL1/FDL2 是否都选好，可以绑定发送 */
    fun fdlBound(): Boolean =
        customFdl1Path.isNotBlank() && File(customFdl1Path).exists() &&
            customFdl2Path.isNotBlank() && File(customFdl2Path).exists()

    /** 绑定状态文案，供连接页显示 */
    fun fdlBindStatus(): String = when {
        customFdl1Path.isBlank() && customFdl2Path.isBlank() -> "未选择，点工具页「选择 FDL1 + FDL2」"
        customFdl1Path.isBlank() -> "缺 FDL1，去工具页补选"
        customFdl2Path.isBlank() -> "缺 FDL2，去工具页补选"
        fdlBound() -> "已绑定，可直接一键发送"
        else -> "文件已丢失，请重新选择"
    }

    /** 把 FDL1/FDL2 绑定到发送链路（写 fdl1Path/fdl2Path/地址 + engine.setAutoFdl） */
    fun bindCustomFdl() {
        if (!fdlBound()) {
            appendLog("自定义 FDL 未选全: " + fdlBindStatus())
            return
        }
        val a1 = parseHexAddr(fdl1Addr.ifBlank { selectedChipAddr1 }, 0x5000L)
        val a2 = parseHexAddr(fdl2Addr.ifBlank { selectedChipAddr2 }, 0x9EFFFE00L)
        fdl1Path = customFdl1Path
        fdl2Path = customFdl2Path
        selectedFdlPath = customFdl1Path
        engine.setAutoFdl(customFdl1Path, a1, customFdl2Path, a2)
        appendLog("已绑定自定义 FDL1: $customFdl1Path")
        appendLog("已绑定自定义 FDL2: $customFdl2Path")
    }

    /** 清空自定义 FDL 绑定，回到内置 FDL */
    fun clearCustomFdl() {
        customFdl1Path = ""
        customFdl2Path = ""
        selectedFdlPath = ""
        appendLog("已清除自定义 FDL")
    }

    // FDL 输入框状态（供 UI 显示与编辑）
    var fdl1Path by mutableStateOf("")
    var fdl2Path by mutableStateOf("")
    var fdl1Addr by mutableStateOf("0x5000")
    var fdl2Addr by mutableStateOf("0x9efffe00")

    init {
        loadChips()
    }

    /** 原工程 loadChipList：从 assets/fdl/chips.json 读取 */
    private fun loadChips() {
        try {
            val txt = context.assets.open("fdl/chips.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(txt)
            val list = ArrayList<Chip>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Chip(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        addr1 = o.getString("addr1"),
                        addr2 = o.getString("addr2"),
                        fdl1Path = o.getString("fdl1"),
                        fdl2Path = o.getString("fdl2"),
                        size1 = o.getLong("size1"),
                        size2 = o.getLong("size2")
                    )
                )
            }
            chips = list
            if (list.isNotEmpty()) {
                // 原工程默认选中第一个芯片（SC9820E）
                selectChip(list[0])
            }
        } catch (t: Throwable) {
            appendLog("chips.json 读取失败: ${t.message}")
        }
    }

    fun selectChip(chip: Chip) {
        currentChip = chip
        // 原工程 setChipSelected：addr 默认 + chip 覆盖，再自动用内置 FDL
        val a1 = chip.addr1.ifBlank { "0x5000" }
        val a2 = chip.addr2.ifBlank { "0x9EFFFE00" }
        selectedChipAddr1 = a1
        selectedChipAddr2 = a2
        fdl1Addr = a1
        fdl2Addr = a2
        appendLog("当前: ${chip.name.uppercase()}  FDL1@$a1  FDL2@$a2")
        useBuiltinFdl()
    }

    /**
     * 原工程 useBuiltinFdlForChip：
     * 把 assets 的 fdl1/fdl2 释放到工作目录，然后 engine.setAutoFdl(...)
     */
    fun useBuiltinFdl() {
        val chip = currentChip
        if (chip == null) {
            appendLog("请先选择芯片")
            return
        }
        Thread {
            try {
                val work = File(engine.workDir ?: context.filesDir.absolutePath)
                work.mkdirs()
                val f1 = File(work, "fdl1.bin")
                val f2 = File(work, "fdl2.bin")
                copyAsset(chip.fdl1Path, f1)
                copyAsset(chip.fdl2Path, f2)
                val a1 = parseHexAddr(chip.addr1, 0x5000L)
                val a2 = parseHexAddr(chip.addr2, 0x9efffe00L)
                engine.setAutoFdl(f1.absolutePath, a1, f2.absolutePath, a2)
                main.post {
                    fdl1Path = f1.absolutePath
                    fdl2Path = f2.absolutePath
                    fdl1Addr = chip.addr1
                    fdl2Addr = chip.addr2
                    selectedFdlPath = f1.absolutePath
                }
                appendLog("已选用内置 FDL: ${chip.name.uppercase()}")
            } catch (t: Throwable) {
                appendLog("内置 FDL 释放失败: ${t.message}")
            }
        }.start()
    }

    private fun copyAsset(asset: String, out: File) {
        context.assets.open(asset).use { ins ->
            FileOutputStream(out).use { os -> ins.copyTo(os) }
        }
    }

    /** 原工程 btnSendFdl：一键发送 FDL1+FDL2（逐字照搬 :1152-1211） */
    fun sendFdl() {
        if (!engine.isConnected) {
            appendLog("请先连接设备")
            return
        }

        // ① 工具页已绑定自定义 FDL1+FDL2 时，直接用用户选的文件
        if (fdlBound()) {
            bindCustomFdl()
            val a1 = parseHexAddr(fdl1Addr.ifBlank { selectedChipAddr1 }, 0x5000L)
            val a2 = parseHexAddr(fdl2Addr.ifBlank { selectedChipAddr2 }, 0x9EFFFE00L)
            appendLog("一键发送 FDL1+FDL2（自定义）")
            appendLog("  FDL1: $customFdl1Path @ 0x" + java.lang.Long.toHexString(a1))
            appendLog("  FDL2: $customFdl2Path @ 0x" + java.lang.Long.toHexString(a2))
            Thread {
                val ok = engine.sendFdl1AndFdl2(customFdl1Path, a1, customFdl2Path, a2)
                appendLog(if (ok) "FDL1+FDL2 发送完成" else "FDL1+FDL2 发送失败")
            }.start()
            return
        }

        // ② 否则走内置 / PAC 链路
        // 确保内置 FDL1+FDL2 已释放
        if (fdl1Path.isBlank() || !File(fdl1Path).exists() ||
            fdl2Path.isBlank() || !File(fdl2Path).exists()
        ) {
            useBuiltinFdl()
        }
        // 优先 PAC 提取的 FDL（要求文件存在）
        var p1 = fdl1Path
        var p2 = fdl2Path
        if (!engine.lastPacFdl1Path.isNullOrBlank() && File(engine.lastPacFdl1Path).exists()) {
            p1 = engine.lastPacFdl1Path
        }
        if (!engine.lastPacFdl2Path.isNullOrBlank() && File(engine.lastPacFdl2Path).exists()) {
            p2 = engine.lastPacFdl2Path
        }
        // 用户单独选了自定义 FDL 时仅作 FDL1，FDL2 仍用内置
        if (fdlBound()) {
            p1 = customFdl1Path
            p2 = customFdl2Path
        } else if (selectedFdlPath.isNotBlank() && File(selectedFdlPath).exists() &&
            selectedFdlPath != fdl1Path
        ) {
            val low = selectedFdlPath.lowercase()
            if (low.contains("fdl2")) p2 = selectedFdlPath
            else p1 = selectedFdlPath
        }
        if (p1.isBlank() || !File(p1).exists()) {
            appendLog("无 FDL1，请点「使用内置 FDL」或读 PAC")
            return
        }
        if (p2.isBlank() || !File(p2).exists()) {
            appendLog("无 FDL2，请点「使用内置 FDL」或读 PAC")
            return
        }
        var a1 = parseHexAddr(fdl1Addr.ifBlank { selectedChipAddr1 }, 0x5000L)
        var a2 = parseHexAddr(selectedChipAddr2, 0x9EFFFE00L)
        // PAC 地址优先
        if (engine.lastPacFdl1Addr > 0) a1 = engine.lastPacFdl1Addr
        if (engine.lastPacFdl2Addr > 0) a2 = engine.lastPacFdl2Addr
        appendLog("一键发送 FDL1+FDL2")
        appendLog("  FDL1: $p1 @ 0x" + java.lang.Long.toHexString(a1))
        appendLog("  FDL2: $p2 @ 0x" + java.lang.Long.toHexString(a2))
        Thread {
            val ok = engine.sendFdl1AndFdl2(p1, a1, p2, a2)
            appendLog(if (ok) "FDL1+FDL2 发送完成" else "FDL1+FDL2 发送失败")
        }.start()
    }

    /** 仅发送 FDL1（spd_dump 风格，只用 FDL1 地址） */
    fun sendFdlExec() {
        if (!engine.isConnected) {
            appendLog("请先连接设备")
            return
        }
        var path = fdl1Path
        if (path.isBlank() || !File(path).exists()) {
            useBuiltinFdl()
            path = fdl1Path
        }
        if (path.isBlank() || !File(path).exists()) {
            if (!engine.lastPacFdl1Path.isNullOrBlank()) path = engine.lastPacFdl1Path
        }
        if (path.isBlank() || !File(path).exists()) {
            appendLog("无 FDL1，请点「选择 FDL1」或「使用内置 FDL」")
            return
        }
        appendLog("发送 FDL1: $path")
        try {
            val addrStr = fdl1Addr.ifBlank { "0x5000" }.trim()
            val addr = java.lang.Long.decode(if (addrStr.startsWith("0x") || addrStr.startsWith("0X")) addrStr else "0x$addrStr")
            Thread { engine.sendCustomFdl(path, addr, 0L) }.start()
        } catch (t: Throwable) {
            appendLog("地址解析失败: ${t.message}")
        }
    }

    /** 仅发送 FDL2（只用 FDL2 地址，进 FDL2 模式） */
    fun sendFdl2Only() {
        if (!engine.isConnected) {
            appendLog("请先连接设备")
            return
        }
        var p2 = fdl2Path
        if (p2.isBlank() || !File(p2).exists()) {
            useBuiltinFdl()
            p2 = fdl2Path
        }
        if (p2.isBlank() || !File(p2).exists()) {
            if (!engine.lastPacFdl2Path.isNullOrBlank()) p2 = engine.lastPacFdl2Path
        }
        if (p2.isBlank() || !File(p2).exists()) {
            appendLog("无 FDL2，请点「选择 FDL2」或「使用内置 FDL」")
            return
        }
        val a2 = parseHexAddr(fdl2Addr.ifBlank { "0x9EFFFE00" }, 0x9EFFFE00L)
        appendLog("发送 FDL2: $p2 @ 0x" + java.lang.Long.toHexString(a2))
        Thread {
            val ok = engine.sendCustomFdl(p2, a2, 0L)
            appendLog(if (ok) "FDL2 发送完成，已进入 FDL2 模式" else "FDL2 发送失败")
        }.start()
    }

    /** 原工程 btnFlash：fdl1/fdl2-noverify 一键刷（:855-866） */
    fun flashNoverify() {
        if (!engine.isConnected) {
            appendLog("请先连接设备")
            return
        }
        Thread {
            engine.execute(arrayOf("fdl", "fdl1-noverify.bin", "0x5000", "fdl", "fdl2-noverify.bin", "0x9efffe00"))
        }.start()
    }

    /** 原工程 btnCancel：取消当前操作（:868-872） */
    fun cancelOp() {
        try {
            engine.cancel()
        } catch (_: Throwable) {
        }
    }

    // ---------------- 运行命令 ----------------

    private fun guardBusy(): Boolean {
        if (busy) {
            appendLog("有操作正在进行，请等待")
            return true
        }
        if (!engine.isConnected) {
            appendLog("请先连接设备")
            return true
        }
        return false
    }

    private fun runCmd(vararg args: String) {
        if (guardBusy()) return
        // 原工程 syncAutoFdlToEngine：分区命令前自动同步内置 FDL
        try {
            if (builtinReady()) {
                val a1 = parseHexAddr(selectedChipAddr1, 0x5000L)
                val a2 = parseHexAddr(selectedChipAddr2, 0x9efffe00L)
                engine.setAutoFdl(fdl1Path, a1, fdl2Path, a2)
            }
        } catch (_: Throwable) {
        }
        Thread {
            try {
                engine.execute(args)
            } catch (t: Throwable) {
                appendLog("出错: ${t.message}")
            }
        }.start()
    }

    private fun builtinReady(): Boolean =
        fdl1Path.isNotBlank() && File(fdl1Path).exists() &&
                fdl2Path.isNotBlank() && File(fdl2Path).exists()

    // ---------------- 连接 ----------------

    fun connectDevice(dev: android.hardware.usb.UsbDevice) {
        Thread {
            try {
                val ok = engine.connectDevice(dev)
                if (ok) {
                    // 每次连接成功后强制回到最快档，避免上一次会话残留直通模式
                    ensureFastestSpeedMode()
                }
            } catch (t: Throwable) {
                appendLog("连接异常: ${t.message}")
            }
        }.start()
    }

    fun disconnect() {
        Thread {
            try {
                engine.disconnect()
            } catch (_: Throwable) {
            }
            main.post {
                connected = false
                appendLog("已断开")
            }
        }.start()
    }

    /** 原工程 btnConnect：完整连接逻辑（含 pendingKickto） */
    fun toggleConnect() {
        if (engine.isConnected) {
            engine.disconnect()
            main.post {
                connected = false
                appendLog("已断开连接")
            }
            return
        }
        // 读 kicktoMode，默认 2
        val mode = kicktoMode.trim().toIntOrNull()?.takeIf { it > 0 } ?: 2
        try {
            engine.setKicktoMode(mode)
        } catch (_: Throwable) {
        }
        appendLog("连接, kicktoMode=$mode")
        val boot = try {
            engine.findBootDevice()
        } catch (_: Throwable) {
            null
        }
        if (boot != null) {
            appendLog("已找到 Boot 设备，直接连接")
            doConnectWithPermission(boot)
            return
        }
        // 找 VID 0x1782 的任意展讯设备：先授权再 Kickto
        val any = try {
            val usbManager =
                context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            usbManager.deviceList.values.firstOrNull {
                it.vendorId == 0x1782
            }
        } catch (_: Throwable) {
            null
        }
        if (any != null) {
            appendLog(
                "找到展讯设备 PID=0x" +
                        java.lang.Integer.toHexString(any.productId) + "，授权后 Kickto"
            )
            pendingKickto = true
            doConnectWithPermission(any)
            return
        }
        appendLog("未检测到设备 → Kickto 监听中，请插入/重插（mode=$mode）")
        Thread {
            try {
                engine.startKicktoListen()
            } catch (t: Throwable) {
                appendLog("Kickto 监听失败: ${t.message}")
            }
        }.start()
    }

    // 原工程 pendingKickto：授权展讯设备后走 Kickto 流程而非直接连接
    private var pendingKickto = false

    /** 原工程 doConnectWithPermission：已有权限直接连，否则请求授权 */
    private fun doConnectWithPermission(dev: android.hardware.usb.UsbDevice) {
        if (dev == null) {
            appendLog("设备为空，无法连接")
            return
        }
        try {
            if (permManager.hasPermission(dev)) {
                connectDevice(dev)
                return
            }
            permManager.requestPermission(dev)
        } catch (t: Throwable) {
            appendLog("请求 USB 权限失败: ${t.message}")
        }
    }

    private val permManager: com.ryu.sprdflasher.UsbPermissionManager =
        com.ryu.sprdflasher.UsbPermissionManager.getInstance(context).also { pm ->
            pm.setCallback(object : com.ryu.sprdflasher.UsbPermissionManager.PermissionCallback {
                override fun onPermissionGranted(dev: android.hardware.usb.UsbDevice) {
                    appendLog("USB权限已获取")
                    if (pendingKickto && dev != null && dev.productId != 0x4d00) {
                        pendingKickto = false
                        Thread {
                            try {
                                var mode = engine.getKicktoMode()
                                if (mode <= 0) mode = 2
                                engine.setKicktoMode(mode)
                                // 通过监听流程：先对当前设备 kick
                                engine.startKicktoListen()
                            } catch (t: Throwable) {
                                appendLog("Kickto 失败: ${t.message}")
                            }
                        }.start()
                    } else {
                        pendingKickto = false
                        connectDevice(dev)
                    }
                }

                override fun onPermissionDenied(dev: android.hardware.usb.UsbDevice) {
                    appendLog("USB权限被拒绝")
                }

                override fun onDeviceAttached(dev: android.hardware.usb.UsbDevice) {
                    appendLog("展讯设备已连接: 0x" + java.lang.Integer.toHexString(dev.productId))
                    if (!engine.isConnected) {
                        doConnectWithPermission(dev)
                    }
                }

                override fun onDeviceDetached(dev: android.hardware.usb.UsbDevice) {
                    appendLog("展讯设备已断开")
                    try {
                        engine.disconnect()
                    } catch (_: Throwable) {
                    }
                    main.post { connected = false }
                }
            })
            pm.register()
        }

    fun requestUsbPermission(dev: android.hardware.usb.UsbDevice) {
        permManager.requestPermission(dev)
    }

    // ---------------- 读取页 ----------------

    var readPartName by mutableStateOf("")
    var readFileName by mutableStateOf("")

    /** 原工程 read_part 命令 */
    fun readPart() {
        val part = readPartName.trim()
        if (part.isBlank()) {
            appendLog("请填写分区名")
            return
        }
        val fn = readFileName.ifBlank { "$part.bin" }
        runCmd("read_part", part, "0", "auto", fn)
    }

    fun partSize() {
        val part = readPartName.trim()
        if (part.isBlank()) {
            appendLog("请填写分区名")
            return
        }
        runCmd("part_size", part)
    }

    /** 原工程 MainActivity:1349：直接调 engine.readPartitionTable() */
    fun readPartitionTable() {
        if (guardBusy()) return
        Thread {
            try {
                engine.readPartitionTable()
            } catch (t: Throwable) {
                appendLog("分区表读取异常: ${t.message}")
            }
        }.start()
    }

    fun chipUid() {
        if (guardBusy()) return
        Thread {
            try {
                val uid = engine.readChipUid()
                appendLog("芯片 UID: $uid")
            } catch (t: Throwable) {
                appendLog("UID 读取失败: ${t.message}")
            }
        }.start()
    }

    fun dumpEfuse() {
        if (guardBusy()) return
        Thread {
            try {
                val dir = engine.saveDir
                val fn = "efuse_${System.currentTimeMillis()}.bin"
                val path = if (dir.endsWith("/")) dir + fn else "$dir/$fn"
                engine.dumpEfuse(path)
                appendLog("efuse 已保存: $path")
            } catch (t: Throwable) {
                appendLog("efuse 读取失败: ${t.message}")
            }
        }.start()
    }

    // ---------------- 写入页 ----------------

    var writePartName by mutableStateOf("")
    var writeFileName by mutableStateOf("")

    /** 原工程 write_part 命令（含 :1000-1006 文件存在检查） */
    fun writePart() {
        val part = writePartName.trim()
        val fn = writeFileName.trim()
        if (part.isBlank()) {
            appendLog("请输入分区名")
            return
        }
        if (fn.isBlank()) {
            appendLog("请选择文件")
            return
        }
        if (!File(fn).exists()) {
            appendLog("文件不存在: $fn")
            return
        }
        runCmd("write_part", part, fn)
    }

    fun erasePart() {
        val part = writePartName.trim()
        if (part.isBlank()) {
            appendLog("请填写分区名")
            return
        }
        runCmd("erase_part", part)
    }

    /** 原工程 btnSelectFile 回调：把选中的文件回填到写入文件名（SAF uri → 可读路径） */
    fun onWriteFilePicked(uri: Uri) {
        try {
            val path = resolveUriToPath(uri)
            if (path != null) {
                writeFileName = path
                appendLog("已选择文件: $path")
            } else {
                writeFileName = uri.toString()
                appendLog("已选择文件: ${uri.toString()}")
            }
        } catch (t: Throwable) {
            appendLog("文件解析失败: ${t.message}")
        }
    }

    /** 原工程工具：把 content:// uri 复制到工作目录并返回物理路径（原工程同名逻辑） */
    private fun resolveUriToPath(uri: Uri): String? {
        return try {
            val scheme = uri.scheme
            if (scheme == "file") {
                uri.path
            } else {
                var name = queryFileName(uri)
                if (name.isNullOrBlank()) name = "pick_${System.currentTimeMillis()}.bin"
                val work = File(engine.workDir ?: context.filesDir.absolutePath)
                work.mkdirs()
                val out = File(work, name)
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(out).use { os -> ins.copyTo(os) }
                }
                out.absolutePath
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- 工具页 ----------------

    var kicktoMode by mutableStateOf("0")

    /** 原工程 btnSetKickto：mode<=0 关闭监听，否则开启 */
    fun kicktoToggle() {
        val m = kicktoMode.trim().toIntOrNull() ?: 0
        Thread {
            try {
                if (m <= 0) {
                    engine.setKicktoMode(0)
                    engine.stopKicktoListen()
                    appendLog("Kickto 已关闭（模式=0）")
                } else {
                    engine.setKicktoMode(m)
                    appendLog("Kickto 已开启 mode=$m，请保持接线，目标机关机再开机")
                    engine.startKicktoListen()
                }
            } catch (t: Throwable) {
                appendLog("Kickto 失败: ${t.message}")
            }
        }.start()
    }

    /** 原工程 btnUsePacFdl（:874-903）：读取 PAC 后用其内置 FDL 回填 */
    fun usePacFdl() {
        if (engine.lastPacFdl1Path.isNullOrBlank()) {
            appendLog("请先在工具页读取 PAC，以提取内置 FDL")
            return
        }
        fdl1Path = engine.lastPacFdl1Path
        fdl1Addr = "0x" + java.lang.Long.toHexString(engine.lastPacFdl1Addr)
        selectedFdlPath = engine.lastPacFdl1Path
        // 原工程同时记录 selectedPacPath，供后续逻辑追溯来源
        if (!engine.lastPacPath.isNullOrBlank()) selectedPacPath = engine.lastPacPath
        appendLog("PAC FDL 可用: ${engine.lastPacFdl1Path}")
        appendLog("地址: $fdl1Addr")
    }

    /** 原工程 selectedPacPath：当前来源 PAC 路径 */
    var selectedPacPath by mutableStateOf("")

    // PAC
    var pacPath by mutableStateOf("")

    fun readPac() {
        // PAC 解析是纯本地文件操作，不碰 USB，所以不校验设备连接
        if (busy) {
            appendLog("有操作正在进行，请等待")
            return
        }
        val p = pacPath
        if (p.isBlank()) {
            appendLog("请先选择 PAC 文件")
            return
        }
        if (!File(p).exists()) {
            appendLog("PAC文件不存在: $p")
            return
        }
        Thread {
            try {
                appendLog("=== 获取分区（本地解析 PAC）===")
                engine.readPac(p)
                // engine 解析完会写入 currentPartitions，回填到 UI 状态
                syncPartitionsFromEngine()
            } catch (t: Throwable) {
                appendLog("PAC 解析失败: ${t.message}")
            }
        }.start()
    }

    /** 把 engine.currentPartitions 同步到 UI 的 partitions 状态 */
    private fun syncPartitionsFromEngine() {
        try {
            val src = engine.getPartitions()
            val list = src.map {
                PartitionItem(name = it.name, size = it.size, selected = it.selected)
            }
            main.post {
                partitions.clear()
                partitions.addAll(list)
                if (list.isEmpty()) {
                    appendLog("未解析到分区，请确认这是展讯 PAC 文件")
                } else {
                    appendLog("已获取 ${list.size} 个分区（无需连接设备）")
                }
            }
        } catch (t: Throwable) {
            appendLog("分区同步失败: ${t.message}")
        }
    }

    /**
     * 原工程 REQ_SELECT_PAC（MainActivity.java:1562-1589）：
     * 把选中的 PAC 内容复制到 cacheDir，再记到 selectedPacPath + pacPath。
     * 不复制的话 SAF 的 content:// 路径解析不出来，readPac 会报文件不存在。
     */
    fun onPacPicked(uri: Uri) {
        try {
            var name = queryFileName(uri)
            if (name.isNullOrBlank()) name = "pac_" + System.currentTimeMillis() + ".pac"
            val cacheFile = File(context.cacheDir, name)
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(cacheFile).use { os -> ins.copyTo(os, 32768) }
            }
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                appendLog("无法打开PAC文件")
                return
            }
            selectedPacPath = cacheFile.absolutePath
            pacPath = cacheFile.absolutePath
            appendLog("已选择PAC: ${cacheFile.absolutePath}")
        } catch (t: Throwable) {
            appendLog("选择PAC失败: ${t.message}")
        }
    }

    fun pacSelectAll(on: Boolean) {
        for (i in partitions.indices) {
            partitions[i] = partitions[i].copy(selected = on)
        }
        syncSelectionToEngine()
    }

    fun pacTogglePartition(idx: Int) {
        if (idx !in partitions.indices) return
        val cur = partitions[idx]
        partitions[idx] = cur.copy(selected = !cur.selected)
        syncSelectionToEngine()
    }

    /** 把勾选状态写回 engine.currentPartitions（flashPac 尊重 selected） */
    private fun syncSelectionToEngine() {
        try {
            val list = ArrayList<UsbFlashEngine.PartitionInfo>()
            for (p in partitions) {
                val pi = UsbFlashEngine.PartitionInfo()
                pi.name = p.name
                pi.size = p.size
                pi.selected = p.selected
                list.add(pi)
            }
            engine.setManualPartitions(list)
        } catch (_: Throwable) {
        }
    }

    fun pacClearCache() {
        try {
            engine.currentPartitions.clear()
            main.post { partitions.clear() }
            appendLog("已清除 PAC 分区缓存")
        } catch (t: Throwable) {
            appendLog("缓存清理失败: ${t.message}")
        }
        Thread {
            try {
                val dir = File(engine.workDir ?: context.filesDir.absolutePath)
                dir.listFiles()?.forEach { if (it.name.startsWith("pac_")) it.delete() }
            } catch (_: Throwable) {
            }
        }.start()
    }

    fun flashPac() {
        if (guardBusy()) return
        val p = pacPath
        if (p.isBlank()) {
            appendLog("请先选择 PAC 文件")
            return
        }
        Thread {
            try {
                engine.flashPac(p)
            } catch (t: Throwable) {
                appendLog("刷写 PAC 失败: ${t.message}")
            }
        }.start()
    }

    fun repartition() {
        if (guardBusy()) return
        val p = pacPath
        Thread {
            try {
                engine.repartition("partition.xml")
            } catch (t: Throwable) {
                appendLog("repartition 失败: ${t.message}")
            }
        }.start()
    }

    // 设备操作
    fun reset() = runCmd("reset")
    fun rebootRecovery() = runCmd("reboot-recovery")
    fun rebootFastboot() = runCmd("reboot-fastboot")
    fun factoryReset() = runCmd("factory_reset")
    fun powerOff() = runCmd("power_off")

    // 保存目录
    var saveDirText by mutableStateOf("")
        private set

    init {
        loadLastSaveDir()
    }

    fun setSaveDir(path: String) {
        engine.setSaveDir(path)
        saveDirText = path
    }

    fun setSaveDirUri(uri: Uri) {
        Thread {
            engine.setSaveDirUri(uri.toString())
            main.post { saveDirText = uri.toString() }
        }.start()
    }

    fun clearLog() = main.post { logs.clear() }

    // ---------------- SAF 选择器（原工程 startActivityForResult 一致） ----------------

    /** Activity 注册的 SAF 启动器：requestCode → 启动 intent */
    var safLauncher: ((requestCode: Int, intent: android.content.Intent) -> Unit)? = null
        set(value) {
            field = value
            safReady = value != null
        }
    var safReady by mutableStateOf(false)
        private set

    private fun launch(req: Int, intent: android.content.Intent) {
        if (!safReady) {
            appendLog("选择器未就绪")
            return
        }
        safLauncher?.invoke(req, intent)
    }

    /** 选择 FDL1 文件 */
    fun pickFdl1() {
        launch(
            REQ_SELECT_FDL,
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
        )
    }

    /** 选择 FDL2 文件 */
    fun pickFdl2() {
        launch(
            REQ_SELECT_FDL2,
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
        )
    }

    /** 原工程 btnSelectFile：ACTION_GET_CONTENT + REQ_SELECT_FILE */
    fun pickWriteFile() {
        launch(
            REQ_SELECT_FILE,
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                    .setType("*/*")
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE),
                "选择固件文件"
            )
        )
    }

    /** 原工程 btnSelectPac：ACTION_OPEN_DOCUMENT + REQ_SELECT_PAC */
    fun pickPac() {
        launch(
            REQ_SELECT_PAC,
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
        )
    }

    /** 原工程 btnSetSaveDir：ACTION_OPEN_DOCUMENT_TREE + REQ_SELECT_SAVE_DIR */
    fun pickSaveDir() {
        launch(
            REQ_SELECT_SAVE_DIR,
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        )
    }

    /** 原工程 saveLogWithPicker：ACTION_CREATE_DOCUMENT + REQ_SAVE_LOG_AS */
    fun saveLogAs() {
        launch(
            REQ_SAVE_LOG_AS,
            android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/plain")
                .putExtra(
                    android.content.Intent.EXTRA_TITLE,
                    "sprd_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())}.txt"
                )
        )
    }

    /** REQ_SELECT_FDL 结果：回填 selectedFdlPath + fdl1Path */
    fun onFdlPicked(uri: Uri) {
        try {
            var path = resolveUriToPath(uri)
            if (path == null || !File(path).exists()) {
                var name = queryFileName(uri) ?: "fdl1_ts.bin"
                if (name.contains(":")) name = "fdl1_ts.bin"
                val out = File(context.cacheDir, name)
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(out).use { os -> ins.copyTo(os) }
                }
                path = out.absolutePath
            }
            customFdl1Path = path
            selectedFdlPath = path
            appendLog("已选择FDL1: $path")
            appendLog("绑定状态: " + fdlBindStatus())
        } catch (t: Throwable) {
            appendLog("FDL 解析失败: ${t.message}")
        }
    }

    /** REQ_SELECT_FDL2 结果：回填 fdl2Path */
    fun onFdl2Picked(uri: Uri) {
        try {
            var path = resolveUriToPath(uri)
            if (path == null || !File(path).exists()) {
                var name = queryFileName(uri) ?: "fdl2_ts.bin"
                if (name.contains(":")) name = "fdl2_ts.bin"
                val out = File(context.cacheDir, name)
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(out).use { os -> ins.copyTo(os) }
                }
                path = out.absolutePath
            }
            customFdl2Path = path
            appendLog("已选择FDL2: $path")
            appendLog("绑定状态: " + fdlBindStatus())
        } catch (t: Throwable) {
            appendLog("FDL2 解析失败: ${t.message}")
        }
    }

    /** REQ_SAVE_LOG_AS 结果：写日志全文 */
    fun onSaveLogPicked(uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(logs.joinToString("\n").toByteArray())
            }
            appendLog("日志已保存")
        } catch (t: Throwable) {
            appendLog("保存日志失败: ${t.message}")
        }
    }

    /** 原工程 REQ_SELECT_SAVE_DIR 结果：持久化目录授权 + 引擎保存目录 */
    fun onSaveDirPicked(uri: Uri) {
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Throwable) {
            }
            val dirPath = resolveUriToPath(uri)
            val display = if (!dirPath.isNullOrBlank()) dirPath else uri.toString()
            if (uri.scheme == "content") {
                engine.setSaveDirUri(uri.toString())
                if (dirPath != null) engine.setSaveDir(dirPath)
            } else {
                engine.setSaveDir(display)
            }
            saveDirText = display
            appendLog("保存目录已记住: $display")
            prefs.edit().putString(KEY_LAST_SAVE_DIR, uri.toString()).apply()
        } catch (t: Throwable) {
            appendLog("保存目录设置失败: ${t.message}")
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    private fun loadLastSaveDir() {
        try {
            val s = prefs.getString(KEY_LAST_SAVE_DIR, null) ?: return
            val persisted = context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == s
            }
            if (s.startsWith("content:") && !persisted) return
            val path = resolveUriToPath(Uri.parse(s))
            saveDirText = path ?: s
            if (s.startsWith("content:")) {
                engine.setSaveDirUri(s)
                if (path != null) engine.setSaveDir(path)
            } else {
                engine.setSaveDir(saveDirText)
            }
        } catch (_: Throwable) {
        }
    }

    /** 原工程 REQ_SAVE_LOG：保存日志到保存目录 */
    fun saveLog() {
        try {
            val dir = File(engine.saveDir ?: context.getExternalFilesDir(null)?.absolutePath
                ?: context.filesDir.absolutePath)
            dir.mkdirs()
            val f = File(dir, "log_${System.currentTimeMillis()}.txt")
            f.writeText(logs.joinToString("\n"))
            appendLog("日志已保存: ${f.absolutePath}")
        } catch (t: Throwable) {
            appendLog("保存日志失败: ${t.message}")
        }
    }

    /** 原工程 parseHexAddr */
    private fun parseHexAddr(s: String, def: Long): Long {
        return try {
            if (s.isBlank()) def
            else java.lang.Long.parseLong(s.trim().removePrefix("0x").removePrefix("0X"), 16)
        } catch (_: Throwable) {
            def
        }
    }

    fun destroy() {
        try {
            engine.disconnect()
        } catch (_: Throwable) {
        }
    }

    // ---------------- 批量刷机（原工程 :1394-1418 / :1421 / :1490） ----------------

    var batchFlashDir by mutableStateOf("")

    /** 原工程 btnBatchFlash */
    fun batchFlash() {
        if (!engine.isConnected) {
            appendLog("请先连接设备")
            return
        }
        val dir = batchFlashDir.trim()
        if (dir.isBlank()) {
            appendLog("请先选择批量刷机目录")
            return
        }
        val saveDir = engine.saveDir ?: ""
        val xmlPath = if (saveDir.endsWith("/")) saveDir + "partition_from_pac.xml" else "$saveDir/partition_from_pac.xml"
        if (!File(xmlPath).exists()) {
            appendLog("未找到分区表文件: $xmlPath，请先读取 PAC")
            return
        }
        appendLog("开始批量刷写...")
        Thread {
            try {
                engine.batchFlash(dir, xmlPath)
            } catch (t: Throwable) {
                appendLog("批量刷写失败: ${t.message}")
            }
        }.start()
    }

    /** 原工程 btnSelectBatchDir + REQ_SELECT_BATCH_DIR 回调（:1490） */
    fun pickBatchDir() {
        safLauncher?.invoke(REQ_SELECT_BATCH_DIR, android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE))
    }

    fun onBatchDirPicked(uri: Uri) {
        try {
            var dirPath = resolveUriToPath(uri)
            if (dirPath == null) dirPath = uri.toString()
            batchFlashDir = dirPath
            appendLog("批量目录: $dirPath")
        } catch (t: Throwable) {
            appendLog("设置批量目录失败: ${t.message}")
        }
    }

    // ---------------- 速率模式（固定为最快档，界面不再暴露切换） ----------------
    //
    // 原工程 btnSpeedToggle 可以在「转码模式 / 直通模式」间切换。
    // 现在固定用最快档：转码 + CRC16（引擎默认值，也是原工程的推荐档）。
    // 不再提供开关，避免误切到直通模式导致兼容性问题。
    //
    // 代价：每 512 字节多 1 字节帧头 + 2 字节 CRC，约 0.6% 的带宽开销，
    // 换来的是固件要求转码的设备不会刷挂。

    /** 保持 true，仅用于日志/状态展示 */
    val speedTranscoding: Boolean get() = true

    /** 连接后确保引擎处于最快档 */
    private fun ensureFastestSpeedMode() {
        try {
            engine.setTranscode(UsbFlashEngine.FLAGS_TRANSCODE or UsbFlashEngine.FLAGS_CRC16)
        } catch (t: Throwable) {
            appendLog("设置速率模式失败: ${t.message}")
        }
    }

    /** 原工程 btnReadPartitionTable（连接检查版） */
    fun readPartitionTableChecked() {
        if (!engine.isConnected) {
            appendLog("请先连接设备")
            return
        }
        appendLog("开始读取设备分区表...")
        readPartitionTable()
    }
}
