package com.ryu.sprdflasher.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ryu.sprdflasher.flash.FlashEngineBridge
import com.ryu.sprdflasher.ui.compose.catalog.LiquidBottomTab
import com.ryu.sprdflasher.ui.compose.catalog.LiquidBottomTabs
import com.ryu.sprdflasher.ui.compose.catalog.LiquidSlider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 主界面 —— 1:1 复刻 ref_src2 activity_main.xml 布局，视觉全部液态玻璃。
 *
 * 结构（对照 activity_main.xml）：
 *  顶栏 52dp（标题 + 状态胶囊 + 速率）
 *  进度条区（官方 LiquidSlider，可拖动 + 松手自动回正）
 *  日志区 weight 0.85
 *  页面容器 weight 1.35（刷写/读取/写入/工具）
 *  底部 LiquidBottomTabs
 */
@Composable
fun LiquidGlassScreen(
    modifier: Modifier = Modifier,
    vm: FlashEngineBridge
) {
    val backdrop = rememberLayerBackdrop()

    var page by remember { mutableIntStateOf(0) }

    // 进度条拖动覆盖值：拖动时显示手指位置，松手 1.2s 后自动回正到真实进度
    var scrubOverride by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(scrubOverride) {
        if (scrubOverride != null) {
            delay(1200)
            scrubOverride = null
        }
    }

    val connected = vm.connected

    // 顶栏右侧：百分比 + 速率（原工程 updateProgressDisplay 把两者都写进 tvSpeed）
    val speedText = buildString {
        append(vm.progressPct)
        append("%")
        if (vm.speedText.isNotBlank()) {
            append("  ")
            append(vm.speedText)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ===== 壁纸背景（铺满全屏，含状态栏区域） =====
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            WallpaperBackground(blurRadius = 6f, dim = Color(0x140B1220))
        }

        // ===== 内容区 =====
        Column(modifier = Modifier.fillMaxSize()) {

            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Spacer(Modifier.height(6.dp))

                // ===== 顶栏 52dp：标题 + 状态 + 速率 =====
                GlassSurface(
                    backdrop = backdrop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    blurRadius = 10.dp,
                    refractionHeight = 8.dp,
                    refractionAmount = 10.dp,
                    highlightAlpha = 0.5f
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 18.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "人渣工具",
                            color = Color(0xFF2B5BE8),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        StatusPill(
                            text = if (connected) "● 已连接" else "● 未连接",
                            connected = connected
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = speedText,
                            color = Color(0xFF334155),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // ===== 进度条区（bg_header_glass + 官方 LiquidSlider，可拖动自动回正） =====
                GlassSurface(
                    backdrop = backdrop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    blurRadius = 8.dp,
                    refractionHeight = 8.dp,
                    refractionAmount = 8.dp,
                    highlightAlpha = 0.4f
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        LiquidSlider(
                            value = { scrubOverride ?: (vm.progressPct / 100f) },
                            onValueChange = { scrubOverride = it },
                            valueRange = 0f..1f,
                            visibilityThreshold = 0.001f,
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ===== 日志区 weight 0.85（log_glass 浅色卡 + #0F172A 等宽字） =====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.85f)
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xF2FFFFFF))
                ) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(vm.logs.size) {
                        if (vm.logs.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(10.dp)
                    ) {
                        vm.logs.forEach { line ->
                            Text(
                                text = line,
                                color = Color(0xFF0F172A),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ===== 页面容器 weight 1.35 =====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.35f)
                ) {
                    when (page) {
                        0 -> FlashPage(backdrop, vm)
                        1 -> BackupPage(backdrop, vm)
                        2 -> WritePage(backdrop, vm)
                        else -> ToolPage(backdrop, vm)
                    }
                }
            }

            // ===== 底部导航：官方 LiquidBottomTabs =====
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                val tabs = listOf("连接", "读取", "写入", "工具")
                androidx.compose.runtime.CompositionLocalProvider(
                    com.ryu.sprdflasher.ui.compose.catalog.LocalLiquidBottomTabScale
                        provides { 1f }
                ) {
                    LiquidBottomTabs(
                        selectedTabIndex = { page },
                        onTabSelected = { page = it },
                        backdrop = backdrop,
                        tabsCount = tabs.size,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tabs.forEachIndexed { index, title ->
                            LiquidBottomTab(
                                onClick = { page = index }
                            ) {
                                Text(
                                    text = title,
                                    color = Color(0xFF0B1220),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

// ======================================================================
// 页面 1：连接（pageFlash）
// 布局：连接 → 芯片 → 内置FDL状态 → 一键发送 → 高级（折叠）
// ======================================================================

@Composable
private fun FlashPage(
    backdrop: Backdrop,
    vm: FlashEngineBridge
) {
    GlassCard(backdrop) {
        GlassButton(
            backdrop = backdrop,
            text = if (vm.connected) "🔌 断开连接" else "🔌 连接设备",
            onClick = { vm.toggleConnect() },
            modifier = Modifier.fillMaxWidth(),
            role = GlassRole.Primary,
            height = 52.dp,
            fontSize = 16f,
            bold = true
        )

        Spacer(Modifier.height(16.dp))

        // ---------- 芯片选择 ----------
        Text(
            text = "选择芯片",
            color = Color(0xFF2B5BE8),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            vm.chips.forEach { chip ->
                val selected = vm.currentChip?.id == chip.id
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Color(0xFF2B5BE8) else Color(0xE6FFFFFF))
                        .clickable { vm.selectChip(chip) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = chip.name,
                        color = if (selected) Color.White else Color(0xFF334155),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- 内置 FDL 状态卡：让用户知道文件已内置，不用自己准备 ----------
        BuiltinFdlCard(backdrop, vm)

        Spacer(Modifier.height(14.dp))

        // ---------- 一键发送（主操作） ----------
        GlassButton(
            backdrop = backdrop,
            text = if (vm.fdlBound()) "一键发送（自定义 FDL）" else "一键发送 FDL1+FDL2",
            onClick = { vm.sendFdl() },
            modifier = Modifier.fillMaxWidth(),
            role = GlassRole.Success,
            height = 54.dp,
            fontSize = 16f,
            bold = true
        )
        Spacer(Modifier.height(8.dp))

        // ---------- 自定义 FDL 绑定状态（工具页选择，这里显示 + 改地址） ----------
        CustomFdlBindCard(backdrop, vm)

        Spacer(Modifier.height(12.dp))

        // ---------- 引导：手动选 FDL 请去工具页 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x0F0F172A))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = "要手动选择并发送 FDL 文件，请到「工具」页选择 FDL1 和 FDL2 并填入地址；" +
                    "选好后自动绑定到这里，按上方「一键发送」即可直接发送你选的 FDL。",
                color = Color(0xFF475569),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        GlassButton(
            backdrop = backdrop,
            text = "取消当前操作",
            onClick = { vm.cancelOp() },
            modifier = Modifier.fillMaxWidth(),
            role = GlassRole.Danger,
            height = 44.dp
        )
    }
}

/**
 * 自定义 FDL 绑定状态卡：
 * 显示工具页选定的 FDL1/FDL2 是否齐全，并提供绑定/解绑/地址输入。
 */
@Composable
private fun CustomFdlBindCard(
    backdrop: Backdrop,
    vm: FlashEngineBridge
) {
    val bound = vm.fdlBound()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (bound) Color(0x1F15803D) else Color(0x0F0F172A))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = if (bound) "自定义 FDL · 已绑定" else "自定义 FDL · 未绑定",
            color = if (bound) Color(0xFF15803D) else Color(0xFF475569),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = vm.fdlBindStatus(),
            color = Color(0xFF64748B),
            fontSize = 11.sp
        )

        if (vm.customFdl1Path.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "FDL1: " + vm.customFdl1Path,
                color = Color(0xFF334155),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (vm.customFdl2Path.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "FDL2: " + vm.customFdl2Path,
                color = Color(0xFF334155),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        if (bound) {
            Spacer(Modifier.height(8.dp))
            GlassButton(
                backdrop, "解绑，改回内置 FDL", { vm.clearCustomFdl() },
                Modifier.fillMaxWidth(), GlassRole.Secondary, 40.dp
            )
        }
    }
}

/**
 * 内置 FDL 状态卡：
 * 明确告诉用户内置文件已经打进 APK，点一下就释放到手机，无需自己准备 bin。
 */
@Composable
private fun BuiltinFdlCard(
    backdrop: Backdrop,
    vm: FlashEngineBridge
) {
    val chip = vm.currentChip
    val f1 = vm.fdl1Path
    val f2 = vm.fdl2Path
    val ready1 = f1.isNotBlank() && java.io.File(f1).exists()
    val ready2 = f2.isNotBlank() && java.io.File(f2).exists()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x1F2B5BE8))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = "内置 FDL · 无需自备文件",
            color = Color(0xFF2B5BE8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))

        FdlStatusLine(
            label = "FDL1",
            chipFile = chip?.fdl1Path,
            ready = ready1,
            addr = vm.fdl1Addr
        )
        Spacer(Modifier.height(4.dp))
        FdlStatusLine(
            label = "FDL2",
            chipFile = chip?.fdl2Path,
            ready = ready2,
            addr = vm.fdl2Addr
        )

        if (chip == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "请先在上方选择芯片",
                color = Color(0xFFB45309),
                fontSize = 11.sp
            )
        }
    }
}

/** 一行 FDL 状态：● FDL1  assets/fdl/sc9820e/fdl1.bin  @0x5000 */
@Composable
private fun FdlStatusLine(
    label: String,
    chipFile: String?,
    ready: Boolean,
    addr: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (ready) "●" else "○",
            color = if (ready) Color(0xFF15803D) else Color(0xFF94A3B8),
            fontSize = 11.sp
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = Color(0xFF0F172A),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = chipFile ?: "—",
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = "@$addr", color = Color(0xFF2B5BE8), fontSize = 11.sp)
    }
}

// ======================================================================
// 页面 2：读取（pageBackup）
// ======================================================================

@Composable
private fun BackupPage(
    backdrop: Backdrop,
    vm: FlashEngineBridge
) {
    GlassCard(backdrop) {
        SectionTitle("读取分区")
        GlassInput(
            backdrop, vm.readPartName,
            { vm.readPartName = it },
            "分区名  如 boot / system"
        )
        Spacer(Modifier.height(8.dp))
        GlassInput(
            backdrop, vm.readFileName,
            { vm.readFileName = it },
            "保存文件名  例 boot.bin"
        )
        Spacer(Modifier.height(12.dp))
        GlassButton(
            backdrop, "读取分区", { vm.readPart() },
            Modifier.fillMaxWidth(), GlassRole.Primary, 48.dp, bold = true
        )
        Spacer(Modifier.height(8.dp))
        GlassButton(
            backdrop, "获取分区大小", { vm.partSize() },
            Modifier.fillMaxWidth(), GlassRole.Secondary, 44.dp
        )
    }
}

// ======================================================================
// 页面 3：写入（pageWrite）
// ======================================================================

/** 工具页 FDL 行：● FDL1  已选文件名 / 未选 */
@Composable
private fun FdlPickRow(
    label: String,
    path: String
) {
    val exists = path.isNotBlank() && java.io.File(path).exists()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0F0F172A))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (exists) "●" else "○",
            color = if (exists) Color(0xFF15803D) else Color(0xFF94A3B8),
            fontSize = 11.sp
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = Color(0xFF0F172A),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                path.isBlank() -> "未选择"
                !exists -> "文件已丢失"
                else -> java.io.File(path).name
            },
            color = if (exists) Color(0xFF334155) else Color(0xFF94A3B8),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun WritePage(
    backdrop: Backdrop,
    vm: FlashEngineBridge
) {
    GlassCard(backdrop) {
        SectionTitle("写入分区")
        GlassInput(
            backdrop, vm.writePartName,
            { vm.writePartName = it },
            "分区名  如 boot"
        )
        Spacer(Modifier.height(8.dp))
        GlassInput(
            backdrop, vm.writeFileName,
            { vm.writeFileName = it },
            "镜像文件路径"
        )
        Spacer(Modifier.height(8.dp))
        GlassButton(
            backdrop, "选择文件", { vm.pickWriteFile() },
            Modifier.fillMaxWidth(), GlassRole.Secondary, 44.dp
        )
        Spacer(Modifier.height(8.dp))
        GlassButton(
            backdrop, "写入分区（不擦除）", { vm.writePart() },
            Modifier.fillMaxWidth(), GlassRole.Success, 48.dp, bold = true
        )
        Spacer(Modifier.height(8.dp))
        GlassButton(
            backdrop, "擦除分区", { vm.erasePart() },
            Modifier.fillMaxWidth(), GlassRole.Danger, 44.dp
        )
    }
}

// ======================================================================
// 页面 4：工具（pageTool，4 张玻璃卡）
// ======================================================================

@Composable
private fun ToolPage(
    backdrop: Backdrop,
    vm: FlashEngineBridge
) {
    // 工具页是长列表：用 LazyColumn，离屏卡片不再参与绘制与布局
    val scroll = rememberLazyListState()

    // 滚动期间只降级按钮的 lens，减少着色器批次
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (scrolling) {
                    CheapGlass.enabled = true
                } else {
                    delay(180)
                    CheapGlass.enabled = false
                }
            }
    }

    LazyColumn(
        state = scroll,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
        // ⓪ FDL 选择（自定义 FDL 的唯一入口，选好自动绑定到连接页）
        GlassCard(backdrop) {
            SectionTitle("FDL 选择")
            Text(
                "支持两种来源：① 选 PAC 后提取内置 FDL  ② 自己选 FDL1 和 FDL2 文件。" +
                    "两项都齐了才会绑定到「连接」页的一键发送。",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))

            // FDL1 / FDL2 当前状态
            FdlPickRow(
                label = "FDL1",
                path = vm.customFdl1Path
            )
            Spacer(Modifier.height(6.dp))
            FdlPickRow(
                label = "FDL2",
                path = vm.customFdl2Path
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    backdrop, "选择 FDL1", { vm.pickFdl1() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
                GlassButton(
                    backdrop, "选择 FDL2", { vm.pickFdl2() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    backdrop, "用内置 FDL", { vm.useBuiltinFdl() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
                GlassButton(
                    backdrop, "解绑", { vm.clearCustomFdl() },
                    Modifier.weight(1f), GlassRole.Danger, 44.dp
                )
            }

            Spacer(Modifier.height(12.dp))

            // 地址：发送 FDL 必须要有地址，所以放在工具页一并填写
            Text(
                "FDL 加载地址",
                color = Color(0xFF2B5BE8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            GlassInput(
                backdrop = backdrop,
                value = vm.fdl1Addr,
                onValueChange = { vm.fdl1Addr = it },
                placeholder = "FDL1 地址  例 0x5000"
            )
            Spacer(Modifier.height(8.dp))
            GlassInput(
                backdrop = backdrop,
                value = vm.fdl2Addr,
                onValueChange = { vm.fdl2Addr = it },
                placeholder = "FDL2 地址  例 0x9EFFFE00"
            )

            Spacer(Modifier.height(12.dp))
            // 绑定按钮：只有 FDL1+FDL2 都齐才可用
            GlassButton(
                backdrop = backdrop,
                text = if (vm.fdlBound()) "绑定到连接页" else "需选好 FDL1 + FDL2 才能绑定",
                onClick = { vm.bindCustomFdl() },
                modifier = Modifier.fillMaxWidth(),
                role = if (vm.fdlBound()) GlassRole.Success else GlassRole.Secondary,
                height = 48.dp,
                bold = vm.fdlBound()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = vm.fdlBindStatus(),
                color = if (vm.fdlBound()) Color(0xFF15803D) else Color(0xFFB45309),
                fontSize = 11.sp
            )
        }
        }

        item {
        // ② PAC 固件
        GlassCard(backdrop) {
            SectionTitle("PAC 固件")
            GlassInput(
                backdrop, vm.pacPath,
                { vm.pacPath = it },
                "PAC 文件路径"
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    backdrop, "选择 PAC", { vm.pickPac() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
                GlassButton(
                    backdrop, "获取分区", { vm.readPac() },
                    Modifier.weight(1f), GlassRole.Primary, 44.dp
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    backdrop, "全选", { vm.pacSelectAll(true) },
                    Modifier.weight(1f), GlassRole.Secondary, 40.dp
                )
                GlassButton(
                    backdrop, "全不选", { vm.pacSelectAll(false) },
                    Modifier.weight(1f), GlassRole.Secondary, 40.dp
                )
                GlassButton(
                    backdrop, "清缓存", { vm.pacClearCache() },
                    Modifier.weight(1f), GlassRole.Secondary, 40.dp
                )
            }
            Spacer(Modifier.height(6.dp))

            // PAC 分区勾选列表
            if (vm.partitions.isEmpty()) {
                Text(
                    "请先选择 PAC 后点「获取分区」",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                vm.partitions.forEachIndexed { idx, p ->
                    PacPartitionRow(
                        backdrop = backdrop,
                        label = "${p.name}  (${formatSize(p.size)})",
                        checked = p.selected,
                        onToggle = { vm.pacTogglePartition(idx) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            GlassButton(
                backdrop, "刷写勾选分区", { vm.flashPac() },
                Modifier.fillMaxWidth(), GlassRole.Success, 48.dp, bold = true
            )
        }
        }

        item {
        // ③ 刷写控制：分区表 / 批量刷机
        GlassCard(backdrop) {
            SectionTitle("分区表 / 批量刷机")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    backdrop, "读取分区表", { vm.readPartitionTableChecked() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
                GlassButton(
                    backdrop, "Repartition", { vm.repartition() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
            }
            Spacer(Modifier.height(8.dp))
            GlassInput(
                backdrop, vm.batchFlashDir,
                { vm.batchFlashDir = it },
                "批量刷机目录（各分区 bin 所在文件夹）"
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    backdrop, "选目录", { vm.pickBatchDir() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
                GlassButton(
                    backdrop, "批量刷写", { vm.batchFlash() },
                    Modifier.weight(1f), GlassRole.Success, 44.dp
                )
            }
        }
        }

        item {
        // ④ 设备操作
        GlassCard(backdrop) {
            SectionTitle("设备操作")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(backdrop, "重启", { vm.reset() }, Modifier.weight(1f), GlassRole.Secondary, 44.dp)
                GlassButton(
                    backdrop, "Recovery", { vm.rebootRecovery() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    backdrop, "Fastboot", { vm.rebootFastboot() },
                    Modifier.weight(1f), GlassRole.Secondary, 44.dp
                )
                GlassButton(backdrop, "关机", { vm.powerOff() }, Modifier.weight(1f), GlassRole.Secondary, 44.dp)
            }
            Spacer(Modifier.height(8.dp))
            GlassButton(
                backdrop, "恢复出厂（双清）", { vm.factoryReset() },
                Modifier.fillMaxWidth(), GlassRole.Danger, 44.dp
            )
        }
        }

        item {
        // ⑤ 保存与日志
        GlassCard(backdrop) {
            SectionTitle("保存路径")
            GlassInput(
                backdrop, vm.saveDirText,
                { },
                "读取/刷写文件的保存目录"
            )
            Spacer(Modifier.height(8.dp))
            GlassButton(
                backdrop, "选择保存目录", { vm.pickSaveDir() },
                Modifier.fillMaxWidth(), GlassRole.Secondary, 44.dp
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    backdrop, "清空日志", { vm.clearLog() },
                    Modifier.weight(1f), GlassRole.Secondary, 40.dp
                )
                GlassButton(
                    backdrop, "保存日志", { vm.saveLogAs() },
                    Modifier.weight(1f), GlassRole.Secondary, 40.dp
                )
            }
        }
        }

        item {
        // ⑥ Kickto（开机踢入下载）
        GlassCard(backdrop) {
            SectionTitle("Kickto（开机踢入下载）")
            Text(
                "默认关闭(0)。填 2 后点启动，目标机保持接线关机再开机",
                color = Color(0xFF64748B),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            GlassInput(
                backdrop, vm.kicktoMode,
                { vm.kicktoMode = it },
                "0=关闭  2=下载"
            )
            Spacer(Modifier.height(10.dp))
            GlassButton(
                backdrop, "启动 / 关闭 Kickto", { vm.kicktoToggle() },
                Modifier.fillMaxWidth(), GlassRole.Primary, 48.dp
            )
        }
        }

        item {
        Text(
            text = "制作人 rudimentary(1142954178)\nQQ群 1045586900",
            color = Color(0xFF64748B),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        }
    }
}

// ======================================================================
// 小组件
// ======================================================================

/** 页面玻璃卡（bg_glass_card 对应物，padding16） */
@Composable
private fun GlassCard(
    backdrop: Backdrop,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    GlassSurface(
        backdrop = backdrop,
        // 工具页是长滚动列表，卡片会整卡移出屏幕边界，裁掉可避免离屏着色器开销
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(20.dp),
        blurRadius = 8.dp,
        refractionHeight = 10.dp,
        refractionAmount = 10.dp,
        showHighlight = false,
        showShadow = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Text(
            text = text, color = Color(0xFF2B5BE8), fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** PAC 分区勾选行 */
@Composable
private fun PacPartitionRow(
    backdrop: Backdrop,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) Color(0x142B5BE8) else Color.Transparent)
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) Color(0xFF2B5BE8) else Color(0x1A0F172A)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color(0xFF0F172A), fontSize = 14.sp)
    }
}

/** 玻璃输入框：值由外部状态驱动（1:1 对应 input_glass EditText） */
@Composable
private fun GlassInput(
    backdrop: Backdrop,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = shape,
        blurRadius = 8.dp,
        refractionHeight = 8.dp,
        refractionAmount = 5.dp,
        chromaticAberration = false,
        showHighlight = false,
        showShadow = false,
        showInnerShadow = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color(0x2E0B1220))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(placeholder, color = Color(0xFF94A3B8), fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = Color(0xFF0F172A), fontSize = 13.sp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
