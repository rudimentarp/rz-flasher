# RZ-Flasher

**展讯（Spreadtrum / UNISOC）刷机工具 —— Jetpack Compose 重写版 + 液态玻璃 UI**

> 把一个基于 Java + XML View + AIDE 构建的老式刷机工具，完整重写为 Jetpack Compose，
> 并接入 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 2.0.1 官方液态玻璃库。

---

## 特性

### 🧊 液态玻璃界面

基于官方 `io.github.kyant0:backdrop` 库，实现真正的实时光学折射，不是简单的半透明背景：

- **SDF 折射着色器** — AGSL(`RuntimeShader`) 片元着色器，用有符号距离场推导玻璃表面法线，按法线方向偏移采样背景纹理，模拟凸透镜折射
- **色散（Chromatic Aberration）** — R/G/B 三通道用不同折射率采样，边缘出现真实彩虹光晕
- **Fresnel 高光** — 根据法线角度叠加边缘高光
- **Backdrop 录制机制** — 壁纸层被录制成 GPU 纹理，所有玻璃组件共享采样

### 📱 能力分级降级

不同的 Android 版本的能力不一样，自动选择最优方案：

| Android 版本 | API | 渲染方式 |
|---|---|---|
| 13+ | SDK 33+ | 完整 AGSL 实时折射 + 色散 + 高光 |
| 12 | SDK 31-32 | `RenderEffect` GPU 模糊 |
| 6.0 - 11 | SDK 23-30 | 半透明磨砂降级 |

### 🔧 刷机功能

基于 **BSDL 协议**（Spreadtrum Bootloader Download Protocol）：

- 设备连接 / Kickto 开机踢入下载模式
- FDL1 / FDL2 两段式引导加载（内置 + 自定义 + PAC 提取三种来源）
- PAC 固件解析、分区勾选刷写
- 分区表读取 / Repartition / 批量刷机
- 设备操作：重启 / Recovery / Fastboot / 关机 / 恢复出厂
- 实时进度与速率显示、滚动日志

---

## 界面结构

底部 4 个 Tab（液态玻璃胶囊导航）：

```
┌─────────────────────────────┐
│  连接   刷写   日志   工具   │  ← LiquidBottomTabs
└─────────────────────────────┘
```

| 页面 | 内容 |
|---|---|
| **连接** | 设备状态、一键发送 FDL、USB 权限申请 |
| **刷写** | PAC 选择、分区列表勾选、进度条、刷写控制 |
| **日志** | 滚动日志、清空 / 保存 |
| **工具** | 7 张玻璃卡：FDL 选择 / PAC 固件 / 分区表批量刷机 / 设备操作 / 保存路径 / Kickto |

---

## 技术栈

| 项 | 版本 |
|---|---|
| 语言 | Kotlin + Java 混合 |
| UI 框架 | Jetpack Compose `1.12.0` |
| Material | Material3 `1.4.0` |
| 液态玻璃 | `io.github.kyant0:backdrop:2.0.1` |
| 形状库 | `io.github.kyant0:shapes:1.2.1` |
| minSdk | 23 (Android 6.0) |
| targetSdk | 35 |
| compileSdk | 37 |
| 构建 | Gradle 9.7.1 + Kotlin 2.x |

---

## 源码结构

```
app/src/main/java/com/ryu/sprdflasher/
├── BslProtocol.java              BSDL 协议编解码
├── UsbFlashEngine.java           核心刷机引擎（USB 通信 / FDL 加载 / 分区读写）
├── UsbPermissionManager.java     USB 权限管理
├── License*.java                 授权模块
├── MainActivity.kt               入口
├── flash/
│   ├── FlashEngineBridge.kt      UI ⇄ 引擎桥接层（状态 / 日志 / 进度）
│   └── FlashViewModel.kt
└── ui/compose/
    ├── LiquidGlassScreen.kt      主界面（全部页面）
    ├── GlassSurface.kt           玻璃面板基础组件
    ├── GlassButton.kt            玻璃按钮
    ├── AuroraBackground.kt       壁纸背景（实时高斯模糊）
    ├── DeviceRefreshRate.kt      高刷适配
    ├── SlidingGlassTabs.kt
    ├── Pressable.kt
    ├── AppIconBadge.kt
    └── catalog/                  从官方 Catalog 移植的组件
        ├── LiquidBottomTabs.kt   底部液态玻璃胶囊导航
        ├── LiquidBottomTab.kt
        ├── LiquidButton.kt
        ├── LiquidSlider.kt
        ├── DampedDragAnimation.kt  阻尼拖拽物理动画
        └── InteractiveHighlight.kt 交互高光
```

---

## 构建

### 前置要求

- JDK 17+
- Android SDK（compileSdk 37 + build-tools）
- Gradle 9.x（项目未内置 wrapper，可用系统 Gradle）

### 命令行构建

```bash
export ANDROID_HOME=/path/to/android/sdk
gradle assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

### ⚠️ 关于中文路径

**aapt2 / javac / Gradle 均不支持中文路径**。如果项目路径含中文，构建会直接失败。
解决办法是把整个项目镜像到纯 ASCII 路径再构建，例如：

```powershell
robocopy "D:\我的项目\刷机工具" "D:\build-mirror" /E
cd D:\build-mirror
gradle assembleDebug
```

### ⚠️ 关于 AIDE

原项目使用 AIDE（手机端 IDE）构建。**AIDE 不支持 Jetpack Compose 和 Gradle Kotlin DSL**，
所以重写版必须在 PC 上构建。

---

## 性能说明

液态玻璃的 AGSL 着色器是**实时计算**的，每一层玻璃都要跑一次片元着色器。
工具页有 7 张玻璃卡 + 20 多个玻璃按钮，滚动时每帧要跑 20+ 个着色器实例。

**这意味着中低端机上滚动会掉帧。** 这是效果的必然代价，不是 bug。

试过但**放弃**的优化方案：

| 方案 | 结果 |
|---|---|
| 降低卡片数量（7→4） | 破坏原布局，用户不接受 |
| 降采样预烘焙壁纸 | **视觉变成马赛克色块**，失败 |
| 滚动时临时关闭折射 | 画面闪烁，观感差 |
| 降低 blurRadius / lens 参数 | 玻璃厚度感明显变弱 |

**最终选择：保留完整效果，接受掉帧。**

> 想要 iOS 那种液态玻璃质感，就必须付着色器成本。要么牺牲效果，要么接受帧率。
> 这个取舍由使用者自己判断。

---

## 致谢

- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — 液态玻璃库，本项目的 UI 基础
- [ilyakurdyukov/spreadtrum_flash](https://github.com/ilyakurdyukov/spreadtrum_flash) — BSDL 协议实现参考（spd_dump）
- 原版刷机工具作者 **rudimentary**

---

## 免责声明

**刷机有风险，变砖后果自负。**

本项目仅供学习和研究使用。使用本工具造成的任何设备损坏、数据丢失，作者不承担任何责任。
请确保你了解自己在做什么，并在操作前备份重要数据。

---

## 许可证

本项目未指定开源许可证。如需使用、分发或修改，请联系作者。
