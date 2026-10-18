package com.ryu.sprdflasher;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;

public class UsbFlashEngine {
    private static final int VID_SPREADTRUM = 0x1782;
    private static final int PID_BOOT = 0x4d00, PID_NORMAL = 0x4d01, PID_DIAG = 0x4d02;
    public static final long ADDR_FDL1 = 0x5000L, ADDR_FDL2 = 0x9EFFFE00L;
    public static final int FLAGS_CRC16 = 1, FLAGS_TRANSCODE = 2, CHK_FIXZERO = 1, CHK_ORIG = 2;
    private static final int RECV_BUF_LEN = 1024;

    private final Context ctx; private final UsbManager usbManager;
    private UsbDeviceConnection conn; private UsbEndpoint epIn, epOut;
    private int endpInBlk = 0, endpOutBlk = 0;
    private volatile boolean cancelled = false, connected = false, busy = false;
    public boolean isBusy() { return busy; }
    private void setBusy(boolean v) {
        busy = v;
        fireBusyChanged(v);
    }
    private final byte[] recvBuf = new byte[RECV_BUF_LEN];
    private final byte[] rawBuf = new byte[4 + 0x10000 + 2];
    private final byte[] encBuf = new byte[4 + 0x10000 + 2 + 2];
    private final byte[] tempBuf = new byte[4 + 0x10000 + 2];
    private int recvLen = 0, recvPos = 0, rawLen = 0, encLen = 0;
    private int flags = FLAGS_TRANSCODE, timeout = 1000, verbose = 0;
    private int fdlLoaded = 0, fdl2Executed = 0, keepCharge = 0, endData = 1, blkSize = 0, t117ExecDist = 0;
    private boolean fdl2Skipped = false;
    private long chipId = 0, fwAddr = ~0L, ramAddr = ~0L;
    private boolean secureBoot = false;
    private Callback callback; private String workDir; private int kicktoMode = 0;
    private long operationStartTime = 0; // 操作开始时间
    private long totalBytes = 0; // 总字节数
    private int maxRetry = 3; // 最大重试次数
    private int usbTimeout = 10000; // USB超时时间(ms)
    private boolean kicktoListening = false;
    private Thread kicktoThread = null;
    private String saveDir = null; // 自定义保存目录
    private String saveDirUri = null; // Content URI 保存目录
    private boolean supportPartitionTable = false; // 是否支持分区表
    private PartitionCallback partitionCallback; // 分区表回调
    private java.io.OutputStream outputStream = null; // URI 输出流
    private java.io.FileOutputStream cachedOutput = null; // 缓存文件输出流
    private java.io.File cacheFile = null; // 缓存文件
    private String autoFdl1Path = null, autoFdl2Path = null;
    private boolean kickDoneThisSession = false;
    private long autoFdl1Addr = 0x5000L, autoFdl2Addr = 0x9EFFFE00L;
    private long speedLastTime = 0;
    private long speedLastBytes = 0;
    private long speedAccumBytes = 0;

    /**
     * 分区表回调接口
     */
    public interface PartitionCallback {
        void onPartitionTableReady(java.util.List<PartitionInfo> partitions);
    }

    public void setPartitionCallback(PartitionCallback cb) { partitionCallback = cb; }

    public interface Callback {
        void onLog(String msg);
        void onProgress(int pct, String op);
        void onConnected(boolean ok, String info);
        void onFinished(boolean success);
        void onError(String msg);
    }

    // 忙碌状态回调接口
    public interface BusyCallback {
        void onBusyChanged(boolean busy);
    }

    private BusyCallback busyCallback;

    public void setBusyCallback(BusyCallback cb) { this.busyCallback = cb; }

    private void fireBusyChanged(final boolean isBusy) {
        if (busyCallback == null) return;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() { busyCallback.onBusyChanged(isBusy); }
            });
        } catch (Exception e) {}
    }

    // 速率回调接口
    public interface SpeedCallback {
        void onSpeed(long bytesPerSecond, String formattedSpeed);
    }

    private SpeedCallback speedCallback;

    public UsbFlashEngine(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.usbManager = (UsbManager) this.ctx.getSystemService(Context.USB_SERVICE);
        this.callback = cb;
        // 使用外部存储公共目录
        File extDir = this.ctx.getExternalFilesDir(null);
        if (extDir == null) {
            extDir = new File(android.os.Environment.getExternalStorageDirectory(), "SprdFlasher");
        }
        this.workDir = extDir.getAbsolutePath() + "/flash";
        new File(workDir).mkdirs();
    }

    public void setSpeedCallback(SpeedCallback cb) { this.speedCallback = cb; }
    public void setWorkDir(String d) { workDir = d; }
    public void setSaveDir(String d) {
        saveDir = d;
        // 切换保存路径时清空旧分区表缓存，避免混用上一路径数据
        try {
            if (currentPartitions != null) currentPartitions.clear();
            java.io.File oldXml = new java.io.File(d, "partition_from_pac.xml");
            // 不强制删用户文件；仅清空内存表，由下次读 PAC/设备再生成
            log("保存路径已切换: " + d + "（分区表已清空，请重新读取）");
        } catch (Throwable ignored) {}
    }
    public void setSaveDirUri(String uri) { saveDirUri = uri; }
    public String getSaveDir() {
        // 如果有 Content URI，返回内部工作目录（文件通过 URI 保存）
        if (saveDirUri != null && saveDirUri.startsWith("content://")) {
            log("使用 Content URI 保存目录: " + saveDirUri);
            return workDir;
        }
        if (saveDir != null) {
            File dir = new File(saveDir);
            if (!dir.exists()) { dir.mkdirs(); }
            if (dir.canWrite()) { return saveDir; }
            log("保存目录不可写，使用应用内目录");
        }
        return workDir;
    }
    /**
     * 通过 Content URI 保存文件到外部目录（小数据）
     */
    public String saveFileToUri(String filename, byte[] data) {
        if (saveDirUri == null || !saveDirUri.startsWith("content://")) {
            return null;
        }
        try {
            android.net.Uri treeUri = android.net.Uri.parse(saveDirUri);
            android.net.Uri fileUri = android.provider.DocumentsContract.createDocument(
                ctx.getContentResolver(),
                treeUri,
                "application/octet-stream",
                filename
            );
            if (fileUri != null) {
                java.io.OutputStream os = ctx.getContentResolver().openOutputStream(fileUri);
                if (os != null) {
                    os.write(data);
                    os.close();
                    log("文件已保存到外部目录: " + filename);
                    return null;
                }
            }
        } catch (Exception e) {
            log("保存到外部目录失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 通过 Content URI 流式保存文件（大数据）
     * 返回内部缓存路径，用于后续操作
     */
    public String openOutputStreamToUri(String filename) {
        if (saveDirUri == null || !saveDirUri.startsWith("content://")) {
            return null;
        }
        try {
            android.net.Uri treeUri = android.net.Uri.parse(saveDirUri);
            // 尝试解析为真实路径
            String realPath = DocumentsTreeHelper.uriToPath(treeUri);
            if (realPath != null) {
                java.io.File dir = new java.io.File(realPath);
                if (!dir.exists()) dir.mkdirs();
                cacheFile = new java.io.File(dir, filename);
                cachedOutput = new java.io.FileOutputStream(cacheFile);
                log("已开始保存到目录: " + cacheFile.getAbsolutePath());
                return cacheFile.getAbsolutePath();
            }
            // 备用方案：使用 createDocument
            android.net.Uri fileUri = android.provider.DocumentsContract.createDocument(
                ctx.getContentResolver(),
                treeUri,
                "application/octet-stream",
                filename
            );
            if (fileUri != null) {
                outputStream = ctx.getContentResolver().openOutputStream(fileUri);
                if (outputStream != null) {
                    cacheFile = new java.io.File(workDir, filename);
                    cachedOutput = new java.io.FileOutputStream(cacheFile);
                    log("已开始流式保存到外部目录: " + filename);
                    return cacheFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            log("打开URI输出流失败: " + e.getMessage());
        }
        return null;
    }

    public void writeUriChunk(byte[] data, int offset, int length) throws java.io.IOException {
        if (outputStream != null) {
            outputStream.write(data, offset, length);
        }
        if (cachedOutput != null) {
            cachedOutput.write(data, offset, length);
        }
    }

    public void closeUriOutputStream() {
        try { if (outputStream != null) { outputStream.close(); outputStream = null; } } catch (Exception e) {}
        try { if (cachedOutput != null) { cachedOutput.close(); cachedOutput = null; } } catch (Exception e) {}
        log("流式保存完成");
    }
    /**
     * 通过 Content URI 打开文件输入流
     */
    public java.io.InputStream openFileFromUri(String filename) {
        if (saveDirUri == null || !saveDirUri.startsWith("content://")) {
            return null;
        }
        try {
            android.net.Uri treeUri = android.net.Uri.parse(saveDirUri);
            String treeId = DocumentsTreeHelper.getTreeId(treeUri);
            if (treeId == null) {
                log("无法获取树ID");
                return null;
            }
            // 直接构造文件 URI
            String docId = treeId + ":" + filename;
            android.net.Uri fileUri = android.net.Uri.parse(
                "content://" + treeUri.getAuthority() + "/document/" + docId.replace(" ", "%20")
            );
            return ctx.getContentResolver().openInputStream(fileUri);
        } catch (Exception e) {
            log("从外部目录打开文件失败: " + e.getMessage());
            return null;
        }
    }
    /**
     * Content URI 树辅助类
     */
    static class DocumentsTreeHelper {
        static String getTreeId(android.net.Uri treeUri) {
            if (treeUri == null) return null;
            String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            if (docId != null) {
                int sep = docId.indexOf(':');
                return sep > 0 ? docId.substring(0, sep) : docId;
            }
            return null;
        }

        /**
         * 将 Content URI 树解析为真实文件路径（兼容 Android 10+ / 11+ 作用域存储）
         */
        static String uriToPath(android.net.Uri treeUri) {
            if (treeUri == null) return null;
            try {
                String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                if (docId == null) return null;

                String[] split = docId.split(":", 2);
                if (split.length < 2) return null;

                String type = split[0];
                String relPath = split[1];

                if ("primary".equalsIgnoreCase(type)) {
                    String[] candidates = {
                        "/storage/emulated/0/" + relPath,
                        "/sdcard/" + relPath,
                        "/mnt/sdcard/" + relPath,
                        "/storage/self/primary/" + relPath
                    };
                    for (String p : candidates) {
                        java.io.File f = new java.io.File(p);
                        if (f.exists() && f.isDirectory()) return p;
                    }
                } else {
                    // 外置 SD 卡 UUID 形式
                    String[] candidates = {
                        "/storage/" + type + "/" + relPath,
                        "/mnt/media_rw/" + type + "/" + relPath
                    };
                    for (String p : candidates) {
                        java.io.File f = new java.io.File(p);
                        if (f.exists() && f.isDirectory()) return p;
                    }
                }
            } catch (Exception ignored) {}
            return null;
        }
    }

    public String getWorkDir() { return workDir; }
    public void setKicktoMode(int m) { kicktoMode = m; }
    public int getKicktoMode() { return kicktoMode; }
    public void setMaxRetry(int r) { maxRetry = r; }
    public void setUsbTimeout(int t) { usbTimeout = t; }
    public void startOperation() { operationStartTime = System.currentTimeMillis(); totalBytes = 0; }
    public void recordBytes(long bytes) { totalBytes += bytes; }
    public long getElapsedMs() { return System.currentTimeMillis() - operationStartTime; }
    public long getTotalBytes() { return totalBytes; }
    /**
     * 开始kickto监听模式：等待设备连接，一连接就自动踢入深刷
     */


    /**
     * Kickto = 目标机「开机/重启」过程中，USB 短暂枚举时踢入下载模式。
     * 用法：先点启动监听 → 目标机关机再开机（或重启）→ 线不要拔。
     */
    public void startKicktoListen() {
        if (kicktoMode <= 0) {
            log("Kickto 模式为 0，不启动监听");
            return;
        }
        stopKicktoListen();
        kicktoListening = true;
        final int mode = kicktoMode;
        // 记录启动时已存在的设备，只踢「新出现」的（开机枚举）
        final java.util.HashSet<String> baseline = new java.util.HashSet<String>();
        try {
            java.util.HashMap<String, UsbDevice> map = usbManager.getDeviceList();
            if (map != null) {
                for (UsbDevice d : map.values()) {
                    if (d.getVendorId() == VID_SPREADTRUM)
                        baseline.add(d.getDeviceName());
                }
            }
        } catch (Throwable ignored) {}

        kicktoThread = new Thread(new Runnable() {
            public void run() {
                log("======== Kickto 开机踢入 ========");
                log("模式 mode=" + mode + "（推荐 2=下载）");
                log("请保持 USB 连接，对目标机【关机再开机】或【重启】");
                log("开机瞬间出现的展讯设备会被自动踢入下载模式");
                log("监听 60 秒...");
                long startTime = System.currentTimeMillis();
                java.util.HashSet<String> kicked = new java.util.HashSet<String>();

                while (kicktoListening && (System.currentTimeMillis() - startTime) < 60000) {
                    try {
                        java.util.HashMap<String, UsbDevice> map = usbManager.getDeviceList();
                        if (map != null) {
                            for (UsbDevice d : map.values()) {
                                if (!kicktoListening) break;
                                if (d.getVendorId() != VID_SPREADTRUM) continue;
                                String name = d.getDeviceName();
                                int pid = d.getProductId();

                                // 有 Kickto 就一律踢，不管 PID 是 4d00/4d01/4d02 还是别的
                                String key = name + ":" + pid;
                                boolean isNew = !baseline.contains(name);

                                if (!usbManager.hasPermission(d)) {
                                    if (!kicked.contains(key + ":noperm")) {
                                        kicked.add(key + ":noperm");
                                        log("设备 PID=0x" + Integer.toHexString(pid) + " 无权限，请点允许");
                                        if (callback != null)
                                            callback.onError("Kickto: 请授权 USB");
                                    }
                                    continue;
                                }

                                // 同一 name+pid 只踢一次，避免狂发；设备重插/换 PID 会再踢
                                if (kicked.contains(key)) continue;
                                kicked.add(key);

                                log("Kickto 强制踢 PID=0x" + Integer.toHexString(pid)
                                    + (isNew ? " (新枚举)" : " (已在线)") + " mode=" + mode);
                                boolean ok = performKickto(d, mode);
                                if (ok) {
                                    log("Kickto 已发送，等待设备稳定...");
                                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                                    UsbDevice boot = waitForBootDevice(12000);
                                    if (boot == null) boot = waitForSpreadtrumDevice(3000);
                                    if (boot != null) {
                                        if (!usbManager.hasPermission(boot)) {
                                            log("踢后设备需授权，请点允许后再连");
                                            if (callback != null) callback.onError("请授权 USB 后点连接");
                                            kicktoListening = false;
                                            return;
                                        }
                                        if (openDevice(boot)) {
                                            log("Kickto 完成并已连接 PID=0x"
                                                + Integer.toHexString(boot.getProductId()));
                                            kicktoListening = false;
                                            return;
                                        }
                                    }
                                    log("已踢，未自动连上，请点「连接设备」");
                                    kicktoListening = false;
                                    return;
                                } else {
                                    log("Kickto 发送失败，稍后重试");
                                    kicked.remove(key);
                                }
                            }
                            // 设备拔掉后从 baseline 移除，允许再次枚举当「新」
                            java.util.HashSet<String> alive = new java.util.HashSet<String>();
                            for (UsbDevice d : map.values()) {
                                if (d.getVendorId() == VID_SPREADTRUM) alive.add(d.getDeviceName());
                            }
                            java.util.Iterator<String> it = baseline.iterator();
                            while (it.hasNext()) {
                                if (!alive.contains(it.next())) it.remove();
                            }
                        }
                        Thread.sleep(80); // 开机窗口短，轮询要快
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable t) {
                        log("Kickto: " + t.getMessage());
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    }
                }
                if (kicktoListening) {
                    log("Kickto 监听结束（超时）。可再点启动 Kickto 后让目标机重启");
                }
                kicktoListening = false;
            }
        });
        kicktoThread.setDaemon(true);
        kicktoThread.start();
    }

    /** 供 Activity 在 USB 插入广播里唤醒：正在监听则立刻处理该设备 */
    public void onUsbDeviceAttached(UsbDevice d) {
        if (!kicktoListening || d == null) return;
        if (d.getVendorId() != VID_SPREADTRUM) return;
        // 监听线程会扫到；这里额外 log
        log("USB 插入广播: PID=0x" + Integer.toHexString(d.getProductId()) + "（Kickto 监听中）");
    }

    public boolean isKicktoListening() { return kicktoListening; }

    public void stopKicktoListen() {
        kicktoListening = false;
        if (kicktoThread != null) {
            try { kicktoThread.interrupt(); } catch (Throwable ignored) {}
            kicktoThread = null;
        }
    }

    /**
     * Kickto（spd_dump 风格）
     * 对已在系统枚举的展讯设备发控制传输 + 0x7e 包，强制切到下载模式。
     * 必须先 hasPermission，并 claimInterface。
     */

    /**
     * sfd_tool / spd_dump ChangeMode 同款逻辑：
     * 对 cali_diag / boot_diag / dl_diag 发
     * payload = {7e 00 00 00 00 08 00 fe (mode+0x80) 7e}
     * mode=0: 先发 10 个 7e 握手，再 payload[8]=0x82
     * mode=1: cali_diag (0x81)
     * mode=2: dl_diag 下载 (0x82)  — 常用
     * 响应异常时发 AT+SPREF="AUTODOADER"
     */
    private boolean performKickto(UsbDevice device, int mode) {
        if (device == null) return false;
        if (mode < 0) mode = 2;
        if (mode >= 0x80) { log("Kickto mode 非法"); return false; }
        if (!usbManager.hasPermission(device)) {
            log("Kickto: 无 USB 权限");
            return false;
        }
        // 无论 PID（含 0x4d00），只要启用 Kickto 就强制发踢包
        log("强制 Kickto，当前 PID=0x" + Integer.toHexString(device.getProductId()));

        UsbDeviceConnection c = null;
        UsbInterface claimIfc = null;
        try {
            synchronized (usbLock) {
                // 踢模式时先不要占着正式 conn
                if (conn != null) {
                    try { conn.close(); } catch (Throwable ignored) {}
                    conn = null;
                    connected = false;
                }
            }
            c = usbManager.openDevice(device);
            if (c == null) {
                log("Kickto: openDevice 失败");
                return false;
            }
            log("=== Kickto(sfd_tool) PID=0x" + Integer.toHexString(device.getProductId())
                + " mode=" + mode + " ===");

            UsbEndpoint epOut = null, epIn = null;
            for (int ii = 0; ii < device.getInterfaceCount(); ii++) {
                UsbInterface ifc = device.getInterface(ii);
                UsbEndpoint out = null, in = null;
                for (int jj = 0; jj < ifc.getEndpointCount(); jj++) {
                    UsbEndpoint ep = ifc.getEndpoint(jj);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if ((ep.getAddress() & 0x80) != 0) { if (in == null) in = ep; }
                    else { if (out == null) out = ep; }
                }
                if (out != null) {
                    claimIfc = ifc; epOut = out; epIn = in; break;
                }
            }
            try {
                if (device.getConfigurationCount() > 0)
                    c.setConfiguration(device.getConfiguration(0));
            } catch (Throwable ignored) {}
            if (claimIfc != null) {
                if (!c.claimInterface(claimIfc, true)) {
                    log("Kickto: claimInterface 失败");
                    try { c.close(); } catch (Throwable ignored) {}
                    return false;
                }
            }

            // sfd_tool: control_transfer 在 Initialize 时做；ChangeMode 主要靠 bulk
            try {
                int r = c.controlTransfer(0x21, 34, 0x601, 0, null, 0, 3000);
                log("controlTransfer(0x21,34,0x601)=" + r);
            } catch (Throwable t) {
                log("controlTransfer 跳过: " + t.getMessage());
            }

            if (epOut == null) {
                log("Kickto: 无 OUT 端点");
                try { if (claimIfc != null) c.releaseInterface(claimIfc); } catch (Throwable ignored) {}
                try { c.close(); } catch (Throwable ignored) {}
                return false;
            }

            // mode==0: hello 10x 0x7e
            if (mode == 0) {
                byte[] hello = new byte[] {
                    (byte)0x7e,(byte)0x7e,(byte)0x7e,(byte)0x7e,(byte)0x7e,
                    (byte)0x7e,(byte)0x7e,(byte)0x7e,(byte)0x7e,(byte)0x7e
                };
                int th = c.bulkTransfer(epOut, hello, hello.length, 5000);
                log("hello 7e*10 => " + th);
                if (epIn != null) {
                    byte[] rb = new byte[64];
                    int rr = c.bulkTransfer(epIn, rb, rb.length, 2000);
                    log("hello 响应 " + rr);
                }
            }

            // payload: 7e 00 00 00 00 08 00 fe XX 7e
            byte[] payload = new byte[] {
                (byte)0x7e, 0, 0, 0, 0, 8, 0, (byte)0xfe, 0, (byte)0x7e
            };
            if (mode == 0) payload[8] = (byte)0x82;
            else payload[8] = (byte)(mode + 0x80); // 1->0x81 cali, 2->0x82 dl

            int tw = c.bulkTransfer(epOut, payload, payload.length, 5000);
            log("kick payload mode=" + mode + " byte=0x" + Integer.toHexString(payload[8] & 0xff)
                + " => " + tw + "  " + bytesToHex(payload));
            if (tw < 0) {
                log("bulk 发送失败");
                try { if (claimIfc != null) c.releaseInterface(claimIfc); } catch (Throwable ignored) {}
                try { c.close(); } catch (Throwable ignored) {}
                return false;
            }

            byte[] response = new byte[64];
            int received = 0;
            if (epIn != null) {
                received = c.bulkTransfer(epIn, response, response.length, 3000);
                log("kick 响应 len=" + received
                    + (received > 2 ? (" type=0x" + Integer.toHexString(response[2] & 0xff)) : ""));
            }

            // 响应不是 0x7e 帧时发 AUTODOADER（与 sfd_tool 一致）
            boolean needAuto = (received <= 0) || (received > 2 && (response[2] & 0xff) != 0x7e
                && (response[2] & 0xff) != 0x81 && (response[2] & 0xff) != 0x8e
                && (response[2] & 0xff) != 0xfe);
            // BSL_REP_VER etc may start with 0x7e
            if (received > 0 && (response[0] & 0xff) == 0x7e) needAuto = false;
            if (needAuto) {
                log("发送 AT+SPREF=AUTODOADER");
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                byte[] autod = new byte[] {
                    (byte)0x7e,0,0,0,0,(byte)0x20,0,(byte)0x68,0,
                    0x41,0x54,0x2b,0x53,0x50,0x52,0x45,0x46,0x3d,0x22,
                    0x41,0x55,0x54,0x4f,0x44,0x4c,0x4f,0x41,0x44,0x45,0x52,
                    0x22,0x0d,0x0a,(byte)0x7e
                };
                int ta = c.bulkTransfer(epOut, autod, autod.length, 5000);
                log("autod => " + ta);
            }

            try { if (claimIfc != null) c.releaseInterface(claimIfc); } catch (Throwable ignored) {}
            try { c.close(); } catch (Throwable ignored) {}
            c = null;

            log("Kickto 已发送，等待设备重枚举为下载模式...");
            // sfd_tool 等设备断开再出现
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            UsbDevice boot = waitForBootDevice(15000);
            if (boot != null) {
                log("检测到下载模式 PID=0x4d00");
                return true;
            }
            // 部分机型仍是 1782 其它 PID
            UsbDevice any = waitForSpreadtrumDevice(5000);
            if (any != null) {
                log("重枚举 PID=0x" + Integer.toHexString(any.getProductId()));
                return any.getProductId() == PID_BOOT;
            }
            log("Kickto 后未检测到下载模式（可再点连接）");
            return false;
        } catch (Throwable t) {
            log("Kickto 异常: " + t.getMessage());
            if (c != null) {
                try { if (claimIfc != null) c.releaseInterface(claimIfc); } catch (Throwable ignored) {}
                try { c.close(); } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
    
    private String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(len, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }
    private UsbDevice waitForBootDevice(int to) {
        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < to) {
            for (UsbDevice d : usbManager.getDeviceList().values())
                if (d.getVendorId() == VID_SPREADTRUM && d.getProductId() == PID_BOOT) return d;
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        } return null;
    }
    private UsbDevice waitForDiagDevice(int to) {
        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < to) {
            for (UsbDevice d : usbManager.getDeviceList().values())
                if (d.getVendorId() == VID_SPREADTRUM && d.getProductId() == PID_DIAG) return d;
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        } return null;
    }
    private UsbDevice waitForSpreadtrumDevice(int to) {
        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < to) {
            for (UsbDevice d : usbManager.getDeviceList().values())
                if (d.getVendorId() == VID_SPREADTRUM) return d;
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        } return null;
    }
    private final Object usbLock = new Object();

    private boolean openDevice(UsbDevice dev) {
        if (dev == null) return false;
        synchronized (usbLock) {
            try {
                if (connected && conn != null) {
                    log("已连接，跳过重复 open");
                    if (callback != null) callback.onConnected(true, "already");
                    return true;
                }
                if (conn != null) {
                    try { conn.close(); } catch (Throwable ignored) {}
                    conn = null;
                }
                if (!usbManager.hasPermission(dev)) {
                    if (callback != null) callback.onConnected(false, "无 USB 权限");
                    return false;
                }
                UsbDeviceConnection c = usbManager.openDevice(dev);
                if (c == null) {
                    if (callback != null) callback.onConnected(false, "无法打开USB设备");
                    return false;
                }
                try {
                    if (dev.getConfigurationCount() > 0)
                        c.setConfiguration(dev.getConfiguration(0));
                } catch (Throwable ignored) {}

                UsbInterface iface = null;
                UsbEndpoint in = null, out = null;
                for (int ii = 0; ii < dev.getInterfaceCount(); ii++) {
                    UsbInterface ifc = dev.getInterface(ii);
                    UsbEndpoint tin = null, tout = null;
                    for (int i = 0; i < ifc.getEndpointCount(); i++) {
                        UsbEndpoint ep = ifc.getEndpoint(i);
                        if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                        if ((ep.getAddress() & 0x80) != 0) { if (tin == null) tin = ep; }
                        else { if (tout == null) tout = ep; }
                    }
                    if (tin != null && tout != null) {
                        iface = ifc; in = tin; out = tout;
                        endpInBlk = tin.getMaxPacketSize();
                        endpOutBlk = tout.getMaxPacketSize();
                        break;
                    }
                }
                if (iface == null || in == null || out == null) {
                    try { c.close(); } catch (Throwable ignored) {}
                    if (callback != null) callback.onConnected(false, "未找到Bulk端点");
                    return false;
                }
                if (!c.claimInterface(iface, true)) {
                    try { c.close(); } catch (Throwable ignored) {}
                    if (callback != null) callback.onConnected(false, "claimInterface失败");
                    return false;
                }
                conn = c;
                epIn = in;
                epOut = out;
                connected = true;
                cancelled = false;
                // 连上后本会话不再自动 Kickto，防止写分区时踢断
                kickDoneThisSession = true;
                if (callback != null) callback.onConnected(true, dev.getDeviceName());
                log("已连接 [VID=0x" + Integer.toHexString(dev.getVendorId())
                    + " PID=0x" + Integer.toHexString(dev.getProductId()) + "]");
                return true;
            } catch (Throwable t) {
                log("openDevice 异常: " + t.getMessage());
                conn = null; connected = false;
                if (callback != null) callback.onConnected(false, t.getMessage());
                return false;
            }
        }
    }

    public void disconnect() {
        cancelled = true;
        stopKicktoListen();
        synchronized (usbLock) {
            connected = false;
            if (conn != null) {
                try { conn.close(); } catch (Throwable ignored) {}
                conn = null;
            }
            epIn = null; epOut = null;
            fdlLoaded = 0; chipId = 0; fwAddr = ~0L; ramAddr = ~0L; secureBoot = false;
            kickDoneThisSession = false;
        }
    }

    public boolean isConnected() {
        return connected && conn != null;
    }

    public void cancel() {
        cancelled = true;
        stopKicktoListen();
        log("操作已取消");
    }

    public UsbDevice findBootDevice() {
        try {
            java.util.HashMap<String, UsbDevice> map = usbManager.getDeviceList();
            if (map == null) return null;
            for (UsbDevice dev : map.values()) {
                if (dev.getVendorId() == VID_SPREADTRUM && dev.getProductId() == PID_BOOT)
                    return dev;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public boolean connectDevice(UsbDevice device) {
        if (device == null) return false;
        cancelled = false;
        try {
            // 与 sfd_tool 对齐：仅「Kickto 监听中」才踢；普通连接不踢
            if (kicktoListening && kicktoMode > 0 && !kickDoneThisSession) {
                int mode = kicktoMode;
                log("Kickto 监听中，尝试踢 mode=" + mode
                    + " PID=0x" + Integer.toHexString(device.getProductId()));
                if (usbManager.hasPermission(device)) {
                    performKickto(device, mode);
                    kickDoneThisSession = true;
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                    UsbDevice boot = waitForBootDevice(10000);
                    if (boot == null) boot = waitForSpreadtrumDevice(4000);
                    if (boot != null) device = boot;
                } else {
                    log("无 USB 权限，跳过 Kickto，直接打开");
                }
            } else if (device.getProductId() == PID_BOOT) {
                log("设备已在 Boot 模式 PID=0x4d00");
            }
            return openDevice(device);
        } catch (Throwable t) {
            log("connectDevice 异常: " + t.getMessage());
            if (callback != null) callback.onConnected(false, t.getMessage());
            return false;
        }
    }

    private void resetSpeedCounter() {
        speedLastTime = System.currentTimeMillis();
        speedLastBytes = 0;
        speedAccumBytes = 0;
        totalBytes = 0;
        operationStartTime = speedLastTime;
    }

    private void updateSpeed(int nbytes) {
        if (nbytes <= 0) return;
        totalBytes += nbytes;
        speedAccumBytes += nbytes;
        long now = System.currentTimeMillis();
        long dt = now - speedLastTime;
        if (dt >= 200) {
            long bps = speedAccumBytes * 1000L / Math.max(1, dt);
            speedLastTime = now;
            speedAccumBytes = 0;
            if (speedCallback != null) {
                try {
                    final long fbps = bps;
                    final String fmt = formatSize(bps) + "/s";
                    speedCallback.onSpeed(fbps, fmt);
                } catch (Throwable ignored) {}
            } else if (callback != null) {
                // 无独立 SpeedCallback 时塞进进度文案
                try { callback.onProgress(-1, "速率 " + formatSize(bps) + "/s"); } catch (Throwable ignored) {}
            }
        }
    }

    

    public void setTimeout(int t) { this.timeout = t; this.usbTimeout = t; }
    public void setEndData(int v) { this.endData = v; }
    public void setVerbose(int v) { this.verbose = v; }
    public void setT117ExecDist(int v) { this.t117ExecDist = v; }
    public void setKeepCharge(int v) { this.keepCharge = v; }
    public void setBlkSize(int v) { this.blkSize = v; }



    // ===== USB传输 =====
    private boolean usbSend(byte[] data, int len) {
        if (conn == null || epOut == null) { log("usbSend: 未连接"); return false; }
        // 重试机制
        for (int retry = 0; retry < maxRetry; retry++) {
            if (cancelled) return false;
            int ret = conn.bulkTransfer(epOut, data, len, usbTimeout);
            if (ret == len) {
                if (endpOutBlk == 512 && (len % 512) == 0) conn.bulkTransfer(epOut, null, 0, usbTimeout);
                return true;
            }
            log("usb_send failed (尝试 " + (retry + 1) + "/" + maxRetry + ") ret=" + ret + "/" + len);
            if (retry >= maxRetry - 1) {
                log("USB发送超时，请检查连接");
                if (callback != null) callback.onError("USB发送超时，请检查连接");
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return false;
    }
    private int usbRecv(byte[] buf, int max, int to) {
        if (conn == null || epIn == null) return -1;
        if (cancelled) return -1;
        // 使用传入的超时时间，但不低于usbTimeout
        int actualTimeout = Math.max(to, usbTimeout);
        return conn.bulkTransfer(epIn, buf, max, actualTimeout);
    }

    // ===== 协议编码 =====
    private static int spdTranscode(byte[] dst, int dOff, byte[] src, int sOff, int len) {
        int n = 0;
        for (int i = 0; i < len; i++) {
            int a = src[sOff + i] & 0xFF;
            if (a == BslProtocol.HDLC_HEADER || a == BslProtocol.HDLC_ESCAPE) { if (dst != null) dst[dOff + n] = BslProtocol.HDLC_ESCAPE; n++; a ^= 0x20; }
            if (dst != null) dst[dOff + n] = (byte)a; n++;
        } return n;
    }
    private static int spdCrc16(int crc, byte[] d, int off, int len) {
        crc &= 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (d[off + i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) crc = (crc << 1) ^ (((0 - (crc >>> 15)) & 0x11021));
        } return crc & 0xFFFF;
    }
    private static int spdChecksum(int crc, byte[] d, int off, int len, int fin) {
        while (len > 1) { crc += ((d[off + 1] & 0xFF) << 8) | (d[off] & 0xFF); off += 2; len -= 2; }
        if (len > 0) crc += d[off] & 0xFF;
        if (fin != 0) { crc = (crc >> 16) + (crc & 0xFFFF); crc += crc >> 16; crc = ~crc & 0xFFFF; if (len < fin) crc = (crc >> 8) | ((crc & 0xFF) << 8); }
        return crc;
    }
    private static void w16be(byte[] p, int o, int v) { p[o] = (byte)((v >> 8) & 0xFF); p[o + 1] = (byte)(v & 0xFF); }
    private static void w16le(byte[] p, int o, int v) { p[o] = (byte)(v & 0xFF); p[o + 1] = (byte)((v >> 8) & 0xFF); }
    private static void w32be(byte[] p, int o, long v) { p[o] = (byte)((v >> 24) & 0xFF); p[o + 1] = (byte)((v >> 16) & 0xFF); p[o + 2] = (byte)((v >> 8) & 0xFF); p[o + 3] = (byte)(v & 0xFF); }
    private static void w32le(byte[] p, int o, long v) { p[o] = (byte)(v & 0xFF); p[o + 1] = (byte)((v >> 8) & 0xFF); p[o + 2] = (byte)((v >> 16) & 0xFF); p[o + 3] = (byte)((v >> 24) & 0xFF); }
    private static int r16be(byte[] p, int o) { return ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF); }
    private static int r16le(byte[] p, int o) { return (p[o] & 0xFF) | ((p[o + 1] & 0xFF) << 8); }
    private static long r32be(byte[] p, int o) { return ((p[o] & 0xFFL) << 24) | ((p[o + 1] & 0xFFL) << 16) | ((p[o + 2] & 0xFFL) << 8) | (p[o + 3] & 0xFFL); }
    private static long r32le(byte[] p, int o) { return (p[o] & 0xFFL) | ((p[o + 1] & 0xFFL) << 8) | ((p[o + 2] & 0xFFL) << 16) | ((p[o + 3] & 0xFFL) << 24); }

    private void encodeMsg(int type, byte[] data, int dlen) {
        if (dlen > 0xFFFF) { encLen = 0; return; }
        if (type == BslProtocol.CMD_CHECK_BAUD) { java.util.Arrays.fill(encBuf, 0, dlen, BslProtocol.HDLC_HEADER); encLen = dlen; return; }
        int pos = 0; w16be(rawBuf, pos, type); pos += 2; w16be(rawBuf, pos, dlen); pos += 2;
        if (dlen > 0 && data != null) { System.arraycopy(data, 0, rawBuf, pos, dlen); pos += dlen; }
        int rlen = pos;
        int chk = (flags & FLAGS_CRC16) != 0 ? spdCrc16(0, rawBuf, 0, rlen) : spdChecksum(0, rawBuf, 0, rlen, CHK_FIXZERO);
        w16be(rawBuf, pos, chk); pos += 2; rlen = pos;
        encBuf[0] = BslProtocol.HDLC_HEADER;
        int elen = (flags & FLAGS_TRANSCODE) != 0 ? spdTranscode(encBuf, 1, rawBuf, 0, rlen) : rlen;
        if ((flags & FLAGS_TRANSCODE) == 0) System.arraycopy(rawBuf, 0, encBuf, 1, rlen);
        encBuf[elen + 1] = BslProtocol.HDLC_HEADER; encLen = elen + 2; rawLen = rlen;
    }
    private void encodeMsg(int type, byte[] data, int off, int dlen) { byte[] tmp = new byte[dlen]; System.arraycopy(data, off, tmp, 0, dlen); encodeMsg(type, tmp, dlen); }
    private boolean sendMsg() {
        if (encLen == 0) { log("empty message"); return false; }
        if (verbose >= 2) log("send (" + encLen + "): " + toHex(encBuf, 0, encLen));
        else if (verbose >= 1) {
            if (encLen == 1 && encBuf[0] == BslProtocol.HDLC_HEADER) log("send: check baud");
            else if (rawLen >= 4) log("send: type=0x" + Integer.toHexString(r16be(rawBuf, 0)) + ", size=" + r16be(rawBuf, 2));
            else log("send: unknown");
        } return usbSend(encBuf, encLen);
    }
    private int recvMsg1(int to) {
        if (cancelled) return -1;
        int a, pos, len, chk, esc = 0, nread = 0, head = 0, plen = 6;
        len = recvLen; pos = recvPos;
        for (;;) {
            if (cancelled) return -1;
            if (pos >= len) {
                int n = usbRecv(recvBuf, recvBuf.length, to);
                if (n < 0) {
                    log("usb_recv failed, ret=" + n + ", timeout=" + to + "ms");
                    if (callback != null && n == -1) {
                        callback.onError("USB接收超时，请检查设备连接");
                    }
                    return -1;
                }
                if (n == 0) break; pos = 0; len = n;
                if (verbose >= 2) log("recv (" + n + "): " + toHex(recvBuf, 0, n));
            }
            a = recvBuf[pos++] & 0xFF;
            if ((flags & FLAGS_TRANSCODE) != 0) {
                if (esc != 0 && a != (BslProtocol.HDLC_HEADER ^ 0x20) && a != (BslProtocol.HDLC_ESCAPE ^ 0x20)) { log("unexpected escaped byte 0x" + Integer.toHexString(a)); return -1; }
                if (a == BslProtocol.HDLC_HEADER) { if (head == 0) head = 1; else if (nread == 0) continue; else if (nread < plen) { log("msg too short"); return -1; } else break; }
                else if (a == BslProtocol.HDLC_ESCAPE) esc = 0x20;
                else { if (head == 0) continue; if (nread >= plen) { log("msg too long"); return -1; } rawBuf[nread++] = (byte)(a ^ esc); esc = 0; }
            } else {
                if (head == 0 && a == BslProtocol.HDLC_HEADER) { head = 1; continue; }
                if (nread == plen) { if (a != BslProtocol.HDLC_HEADER) { log("expected end of msg"); return -1; } break; }
                rawBuf[nread++] = (byte)a;
            }
            if (nread == 4) { a = r16be(rawBuf, 2); plen = a + 6; }
        }
        recvLen = len; recvPos = pos; rawLen = nread;
        if (nread == 0) return 0;
        if (nread < 6) { log("msg too short"); return -1; }
        if (nread != plen) { log("bad length (" + nread + "/" + plen + ")"); return -1; }
        chk = (flags & FLAGS_CRC16) != 0 ? spdCrc16(0, rawBuf, 0, plen - 2) : spdChecksum(0, rawBuf, 0, plen - 2, CHK_ORIG);
        a = r16be(rawBuf, plen - 2);
        if (a != chk) { log("bad checksum (0x" + Integer.toHexString(a) + ", expected 0x" + Integer.toHexString(chk) + ")"); return -1; }
        if (verbose == 1) log("recv: type=0x" + Integer.toHexString(r16be(rawBuf, 0)) + ", size=" + r16be(rawBuf, 2));
        return nread;
    }
    private int recvType() { return rawLen < 6 ? -1 : r16be(rawBuf, 0); }
    private int recvMsg(int to) {
        int ret;
        for (;;) { ret = recvMsg1(to); if (ret <= 0 || recvType() != BslProtocol.REP_LOG) break; int ll = r16be(rawBuf, 2); log("BSL_REP_LOG: " + bytesToStr(rawBuf, 4, ll)); }
        return ret;
    }
    private int recvMsgTimeout(int to) { int old = timeout; timeout = Math.max(old, to); int ret = recvMsg(to); timeout = old; return ret; }
    private boolean sendAndCheck(int to) { if (!sendMsg()) return false; int ret = recvMsg(to); if (ret <= 0) { log("timeout"); return false; } ret = recvType(); if (ret != BslProtocol.REP_ACK) { log("unexpected response 0x" + Integer.toHexString(ret)); return false; } return true; }

    // ===== Bootloader握手 =====
    public boolean bootloaderHandshake() {
        flags = FLAGS_TRANSCODE | FLAGS_CRC16; recvLen = 0; recvPos = 0;
        log("=== Bootloader握手 ===");
        if (conn != null) {
            try {
                int ret = conn.controlTransfer(0x21, 34, 0x601, 0, null, 0, Math.max(timeout, 3000));
                log("controlTransfer=" + ret);
                // Android 上偶发 <0，不直接失败（与部分机型兼容）
            } catch (Throwable t) { log("controlTransfer: " + t.getMessage()); }
        }
        encodeMsg(BslProtocol.CMD_CHECK_BAUD, null, 1); if (!sendMsg()) return false;
        int ret = recvMsgTimeout(30000); if (ret <= 0 || recvType() != BslProtocol.REP_VER) { log("ver expected from bootloader"); return false; }
        int vl = r16be(rawBuf, 2); log("BSL_REP_VER: " + bytesToStr(rawBuf, 4, vl));
        // SPRD3/SPRD4 bootloader 都可能支持安全启动，需要检查版本格式
        // vl=6 且以 SPRD3 开头表示旧版无安全启动
        // 其他情况（SPRD4等）视为可能有安全启动
        String verStr = bytesToStr(rawBuf, 4, vl);
        if (vl == 6 && startsWith(rawBuf, 4, "SPRD3")) {
            secureBoot = false;
            log("Bootloader版本: 无安全启动");
        } else {
            secureBoot = true;
            log("Bootloader版本: " + verStr + " (可能启用安全启动)");
        }
        encodeMsg(BslProtocol.CMD_CONNECT, null, 0); if (!sendAndCheck(5000)) return false;
        log("Bootloader握手成功");

        return true;
    }

    public boolean bootloaderHandshakeWithRetry() {
        for (int a = 0; a < 2; a++) {
            if (a > 0) { log("重试..."); disconnect(); try { Thread.sleep(1500); } catch (InterruptedException ignored) {} UsbDevice d = waitForBootDevice(8000); if (d == null || !openDevice(d)) continue; try { Thread.sleep(500); } catch (InterruptedException ignored) {} }
            if (bootloaderHandshake()) return true;
        } return false;
    }

    // ===== FDL1握手 =====
    public boolean fdl1Handshake() {
        flags = FLAGS_TRANSCODE; recvLen = 0; recvPos = 0;
        log("=== FDL1握手 ===");
        encodeMsg(BslProtocol.CMD_CHECK_BAUD, null, 4); int ret = 0;
        for (int i = 0; i < 10; i++) { log("checkbaud " + (i + 1)); if (!sendMsg()) break; ret = recvMsg(3000); if (ret > 0) break; if (i == 9) { log("FDL1 timeout"); return false; } }
        if (recvType() != BslProtocol.REP_VER) { log("ver expected from FDL1"); return false; }
        int vl = r16be(rawBuf, 2); String vs = bytesToStr(rawBuf, 4, vl); log("FDL1 VER: " + vs);
        
        // SPRD4 仅记录，不跳过 FDL2（多数手机仍需 FDL2）
        if (vs != null && vs.startsWith("SPRD4")) {
            log("FDL1 VER 为 SPRD4（仍会发送 FDL2）");
        }
        
        if (vs.contains("CHIP ID = 0x")) { int idx = vs.indexOf("CHIP ID = 0x") + 12; String cid = vs.substring(idx); int sp = cid.indexOf(' '); if (sp > 0) cid = cid.substring(0, sp); try { chipId = Long.parseLong(cid, 16); log("CHIP ID: 0x" + Long.toHexString(chipId)); if ((chipId ^ 0x65300000L) >> 17 == 0) ramAddr = (fwAddr = 0x30000000L) | 0x04000000L; else if ((chipId ^ 0x65620000L) >> 16 == 0) ramAddr = (fwAddr = 0x10000000L) | 0x04000000L; else if ((chipId ^ 0x98180000L) >> 16 == 0) ramAddr = 0x80000000L; } catch (Exception e) {} }
        encodeMsg(BslProtocol.CMD_CONNECT, null, 0); if (!sendAndCheck(3000)) return false;
        
        // 发送 KEEP_CHARGE（参考 spd_dump.c:667-670）
        if (keepCharge != 0) {
            log("发送KEEP_CHARGE...");
            encodeMsg(BslProtocol.CMD_KEEP_CHARGE, null, 0);
            sendAndCheck(3000);
        }
        
        log("FDL1握手成功"); return true;
    }

    // ===== 发送文件 =====
    private void sendBuf(long saddr, boolean ed, int step, byte[] mem, int size) {
        resetSpeedCounter();
        byte[] data = new byte[8]; w32be(data, 0, saddr); w32be(data, 4, size);
        encodeMsg(BslProtocol.CMD_START_DATA, data, 8); if (!sendAndCheck(5000)) throw new RuntimeException("START_DATA失败");
        for (int i = 0; i < size; ) { if (cancelled) throw new RuntimeException("已取消"); int n = Math.min(size - i, step); encodeMsg(BslProtocol.CMD_MIDST_DATA, mem, i, n); if (!sendAndCheck(5000)) throw new RuntimeException("MIDST_DATA失败"); i += n; updateSpeed(n); progress((int)((i * 100L) / size), "发送中..."); }
        if (!ed) return; encodeMsg(BslProtocol.CMD_END_DATA, null, 0); if (!sendAndCheck(5000)) throw new RuntimeException("END_DATA失败");
    }
    private void sendFile(String fn, long addr, int step) {
        File f = new File(getSaveDir(), fn);
        if (!f.exists()) {
            // 尝试从assets加载
            try {
                android.content.res.AssetManager assets = ctx.getAssets();
                java.io.InputStream is = assets.open(fn);
                byte[] data = new byte[is.available()];
                is.read(data);
                is.close();
                log("从assets加载: " + fn);
                sendBuf(addr, true, step, data, data.length);
                return;
            } catch (Exception e) {
                log("文件不存在: " + fn);
                throw new RuntimeException("文件不存在: " + fn);
            }
        }
        byte[] data; try { FileInputStream fis = new FileInputStream(f); data = new byte[(int)f.length()]; fis.read(data); fis.close(); } catch (IOException e) { log("读取失败: " + e.getMessage()); throw new RuntimeException("读取失败"); }
        sendBuf(addr, true, step, data, data.length);
    }
    private void sendFile(String fn, long addr, int step, int srcOffs, int srcSize) {
        File f = new File(getSaveDir(), fn);
        if (!f.exists()) {
            // 尝试从assets加载
            try {
                android.content.res.AssetManager assets = ctx.getAssets();
                java.io.InputStream is = assets.open(fn);
                byte[] all = new byte[is.available()];
                is.read(all);
                is.close();
                log("从assets加载: " + fn);
                if (srcOffs >= all.length) { log("数据超出范围"); throw new RuntimeException("超出范围"); }
                int size = srcSize > 0 ? srcSize : all.length - srcOffs; if (srcOffs + size > all.length) size = all.length - srcOffs;
                byte[] data = new byte[size]; System.arraycopy(all, srcOffs, data, 0, size); sendBuf(addr, true, step, data, size);
                return;
            } catch (Exception e) {
                log("文件不存在: " + fn);
                throw new RuntimeException("文件不存在: " + fn);
            }
        }
        byte[] all; try { FileInputStream fis = new FileInputStream(f); all = new byte[(int)f.length()]; fis.read(all); fis.close(); } catch (IOException e) { log("读取失败"); throw new RuntimeException("读取失败"); }
        if (srcOffs >= all.length) { log("数据超出范围"); throw new RuntimeException("超出范围"); }
        int size = srcSize > 0 ? srcSize : all.length - srcOffs; if (srcOffs + size > all.length) size = all.length - srcOffs;
        byte[] data = new byte[size]; System.arraycopy(all, srcOffs, data, 0, size); sendBuf(addr, true, step, data, size);
    }

    // ===== 读取Flash =====
    private int readFlash(long addr, long start, long len, byte[] mem, FileOutputStream fos, int step) {
        resetSpeedCounter();
        long offset; int total = 0;
        for (offset = start; offset < start + len; ) {
            if (cancelled) break;
            int n = (int)Math.min(start + len - offset, step);
            byte[] data = new byte[12];
            w32be(data, 0, addr); w32be(data, 4, n); w32be(data, 8, offset);
            encodeMsg(BslProtocol.CMD_READ_FLASH, data, 12);
            if (!sendMsg()) break;
            int ret = recvMsgTimeout(30000);
            if (ret <= 0 || recvType() != BslProtocol.REP_READ_FLASH) { log("unexpected response 0x" + Integer.toHexString(recvType())); break; }
            int nr = r16be(rawBuf, 2);
            if (n < nr) { log("unexpected length"); break; }
            if (mem != null) System.arraycopy(rawBuf, 4, mem, total, nr);
            if (fos != null) try { fos.write(rawBuf, 4, nr); } catch (IOException e) { log("写入失败"); break; }
            total += nr; offset += nr; updateSpeed(nr);
            if (n != nr) break;
            long readed = offset - start;
            progress((int)((readed * 100L) / len), "读取中...");
        }
        return total;
    }
    public int dumpFlash(long addr, long start, long len, String fn, int step, int mode) {
        boolean useUri = (saveDirUri != null && saveDirUri.startsWith("content://"));
        try {
            if (useUri) {
                // 通过 Content URI 打开输出流
                android.net.Uri treeUri = android.net.Uri.parse(saveDirUri);
                android.net.Uri fileUri = android.provider.DocumentsContract.createDocument(
                    ctx.getContentResolver(),
                    treeUri,
                    "application/octet-stream",
                    fn
                );
                if (fileUri == null) {
                    log("无法创建文件URI: " + fn);
                    return 0;
                }
                outputStream = ctx.getContentResolver().openOutputStream(fileUri);
                if (outputStream == null) {
                    log("无法打开输出流: " + fn);
                    return 0;
                }
                log("开始流式保存到外部目录: " + fn);
            }
            int nread = 0;
            if (mode == 1) {
                byte[] buf = new byte[0x34];
                nread = readFlash(addr, start, 0x34, buf, useUri ? null : new java.io.FileOutputStream(new java.io.File(getSaveDir(), fn)), step);
                if (nread != 0x34) { log("无法读取分区头"); return 0; }
                long dm = r32le(buf, 0), vm = r32le(buf, 0);
                if (dm == 0x42544844L && r32le(buf, 4) == 1) { len = r32le(buf, 0x30) + 0x200; log("DHTB头, 大小=0x" + Long.toHexString(len)); }
                else if (vm == 0x53544e56L) { int nb = r16le(buf, 12), bk = r16le(buf, 14); len = (long)nb * bk; log("VNTS头, 大小=0x" + Long.toHexString(len)); }
                else { log("无法确定分区大小"); return 0; }
            }
            if (useUri) {
                // 流式读取并通过 Content URI 写入
                nread = readFlashStreaming(addr, start + nread, len - nread, step);
            } else {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(getSaveDir(), fn));
                nread += readFlash(addr, start + nread, len - nread, null, fos, step);
                fos.close();
            }
            log("dumpFlash: 0x" + Integer.toHexString(nread) + "/0x" + Long.toHexString(len));
            if (useUri) closeUriOutputStream();
            return nread;
        } catch (IOException e) { log("文件错误: " + e.getMessage()); return 0; } finally {
            outputStream = null; cachedOutput = null; cacheFile = null;
        }
    }

    private int readFlashStreaming(long addr, long start, long len, int step) throws java.io.IOException {
        resetSpeedCounter();
        long offset; int total = 0;
        for (offset = start; offset < start + len; ) {
            if (cancelled) break;
            int n = (int)Math.min(start + len - offset, step);
            byte[] data = new byte[12];
            w32be(data, 0, addr); w32be(data, 4, n); w32be(data, 8, offset);
            encodeMsg(BslProtocol.CMD_READ_FLASH, data, 12);
            if (!sendMsg()) break;
            int ret = recvMsgTimeout(30000);
            if (ret <= 0 || recvType() != BslProtocol.REP_READ_FLASH) { log("unexpected response 0x" + Integer.toHexString(recvType())); break; }
            int nr = r16be(rawBuf, 2);
            if (n < nr) { log("unexpected length"); break; }
            if (outputStream != null) outputStream.write(rawBuf, 4, nr);
            total += nr; offset += nr; updateSpeed(nr);
            if (n != nr) break;
            long readed = offset - start;
            progress((int)((readed * 100L) / len), "读取中...");
        }
        return total;
    }
    public int dumpMem(long start, long len, String fn, int step) {
        boolean useUri = (saveDirUri != null && saveDirUri.startsWith("content://"));
        try {
            if (useUri) {
                android.net.Uri treeUri = android.net.Uri.parse(saveDirUri);
                android.net.Uri fileUri = android.provider.DocumentsContract.createDocument(
                    ctx.getContentResolver(), treeUri, "application/octet-stream", fn);
                if (fileUri == null) return 0;
                outputStream = ctx.getContentResolver().openOutputStream(fileUri);
                if (outputStream == null) return 0;
                log("开始流式保存到外部目录: " + fn);
            }
            int total = 0;
            long offset;
            for (offset = start; offset < start + len; ) {
                if (cancelled) break;
                int n = (int)Math.min(start + len - offset, step);
                byte[] data = new byte[12];
                w32be(data, 0, offset);
                w32be(data, 4, n);
                encodeMsg(BslProtocol.CMD_READ_MEM, data, 12);
                if (!sendMsg()) break;
                int ret = recvMsgTimeout(30000);
                if (ret <= 0 || recvType() != BslProtocol.REP_READ_MEM) break;
                int nr = r16be(rawBuf, 2);
                if (n < nr) break;
                if (outputStream != null) outputStream.write(rawBuf, 4, nr);
                total += nr;
                offset += nr;
                updateSpeed(nr);
                if (n != nr) break;
                long readed = offset - start;
                progress((int)((readed * 100L) / len), "读取内存中...");
            }
            log("dumpMem: 0x" + Integer.toHexString(total) + "/0x" + Long.toHexString(len));
            if (useUri) closeUriOutputStream();
            return total;
        } catch (IOException e) {
            log("文件错误: " + e.getMessage());
            return 0;
        } finally {
            outputStream = null;
            cachedOutput = null;
            cacheFile = null;
        }
    }

    // ===== 分区操作 =====
    private void copyToWstr(byte[] dst, int off, int max, String s) { for (int i = 0; i < max && i < s.length(); i++) w16le(dst, off + i * 2, s.charAt(i)); }
    private String copyFromWstr(byte[] src, int off, int max) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < max; i++) { int c = r16le(src, off + i * 2); if (c == 0 || c > 0xFF) break; sb.append((char)c); } return sb.toString(); }
    /** @return true 若设备 ACK */
    private boolean selectPartition(String name, long size, boolean mode64, int cmd) {
        byte[] pkt = new byte[36 * 2 + 16]; // 清零
        copyToWstr(pkt, 0, 36, name);
        w32le(pkt, 72, size);
        if (mode64) w32le(pkt, 76, size >> 32);
        encodeMsg(cmd, pkt, 72 + (mode64 ? 16 : 4));
        if (!sendAndCheck(15000)) {
            log("selectPartition 失败: " + name + " cmd=0x" + Integer.toHexString(cmd)
                + " size=0x" + Long.toHexString(size) + " (需 FDL2 且分区名正确)");
            return false;
        }
        return true;
    }
    public int dumpPartition(String name, long start, long len, String fn, int step) {
        if (busy) { log("正在执行操作哦~"); return 0; }
        setBusy(true);
        try {
        if (step <= 0) step = 4096;
        if (step > 0xF000) step = 0xF000;
        resetSpeedCounter();
        if (len == 0) {
            log("分区大小未知，自动获取...");
            len = findPartitionSize(name);
            if (len == 0) {
                log("无法获取大小，请先「读取分区表」或手动指定大小");
                return 0;
            }
        }
        if (len <= 0) {
            log("分区大小无效");
            return 0;
        }
        // spd_dump: select(name, start+len, mode64, READ_START)
        boolean mode64 = ((start + len) >> 32) != 0;
        log("开始读取分区: " + name + " +0x" + Long.toHexString(start)
            + " len=0x" + Long.toHexString(len) + " (" + formatSize(len) + ")");
        if (!selectPartition(name, start + len, mode64, BslProtocol.CMD_READ_START)) {
            String low = name.toLowerCase();
            if (low.equals(name) || !selectPartition(low, start + len, mode64, BslProtocol.CMD_READ_START)) {
                log("SELECT 分区失败，无法读取");
                try { encodeMsg(BslProtocol.CMD_READ_END, null, 0); sendAndCheck(2000); } catch (Throwable ignored) {}
                return 0;
            }
            name = low;
        }
        File f = new File(getSaveDir(), fn);
        FileOutputStream fos = null;
        boolean useUri = (saveDirUri != null && saveDirUri.startsWith("content://"));
        long offset;
        try {
            if (useUri) {
                String cachedPath = openOutputStreamToUri(fn);
                if (cachedPath != null) f = new File(cachedPath);
                fos = new FileOutputStream(f);
            } else {
                fos = new FileOutputStream(f);
            }
            for (offset = start; offset < start + len; ) {
                if (cancelled) break;
                int n = (int)Math.min(start + len - offset, step);
                byte[] data = new byte[12];
                w32le(data, 0, n);
                w32le(data, 4, offset);
                w32le(data, 8, offset >> 32);
                encodeMsg(BslProtocol.CMD_READ_MIDST, data, mode64 ? 12 : 8);
                if (!sendMsg()) break;
                int ret = recvMsgTimeout(30000);
                if (ret <= 0 || recvType() != BslProtocol.REP_READ_FLASH) {
                    log("unexpected response 0x" + Integer.toHexString(recvType()));
                    break;
                }
                int nr = r16be(rawBuf, 2);
                if (n < nr) {
                    log("unexpected length");
                    break;
                }
                fos.write(rawBuf, 4, nr);
                offset += nr;
                if (n != nr) break;
                updateSpeed(nr);
                long readed = offset - start;
                progress((int)((readed * 100L) / len), "读取分区中...");
            }
            encodeMsg(BslProtocol.CMD_READ_END, null, 0);
            sendAndCheck(3000);
            log("dumpPartition: 0x" + Long.toHexString(offset - start) + "/0x" + Long.toHexString(len));
            if (useUri) closeUriOutputStream();
            return (int)(offset - start);
        } catch (IOException e) {
            log("文件错误: " + e.getMessage());
            return 0;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
            outputStream = null; cachedOutput = null; cacheFile = null;
        }
        } finally {
            setBusy(false);
        }
    }
    public void loadPartition(String name, String fn, int step) {
        if (busy) { log("正在执行操作哦~"); return; }
        setBusy(true);
        try {
        // 支持完整路径或相对路径
        File f = new File(fn);
        if (!f.isAbsolute()) {
            f = new File(getSaveDir(), fn);
        }
        if (!f.exists()) { log("文件不存在: " + f.getAbsolutePath()); throw new RuntimeException("文件不存在"); }
        long len = f.length();
        if (fdlLoaded < 2 && fdl2Executed <= 0) {
            log("警告: fdlLoaded=" + fdlLoaded + "，分区写需要 FDL2");
        }
        log("======== 写入分区 " + name + " ======== ");
        log("文件: " + f.getAbsolutePath());
        log("大小: 0x" + Long.toHexString(len) + " (" + formatSize(len) + ")");
        resetSpeedCounter();
        progress(0, "开始写入 " + name);
        boolean mode64 = (len >> 32) != 0;
        if (!selectPartition(name, len, mode64, BslProtocol.CMD_START_DATA)) {
            // 再试小写分区名
            String low = name.toLowerCase();
            if (low.equals(name) || !selectPartition(low, len, mode64, BslProtocol.CMD_START_DATA))
                throw new RuntimeException("selectPartition 写失败: " + name + "（需先成功发 FDL1+FDL2）");
            name = low;
        }
        log("已选定分区，开始 MIDST_DATA...");
        FileInputStream fis = null;
        long offset = 0;
        int consecutiveErrors = 0;
        try {
            fis = new FileInputStream(f);
            while (offset < len) {
                if (cancelled) break;
                int n = (int)Math.min(len - offset, step);
                int r = fis.read(tempBuf, 0, n);
                if (r != n) { log("读取文件失败"); break; }
                // 发送数据，带重试
                boolean sent = false;
                for (int trySend = 0; trySend < 3 && !sent; trySend++) {
                    if (cancelled) break;
                    encodeMsg(BslProtocol.CMD_MIDST_DATA, tempBuf, 0, n);
                    if (sendMsg()) {
                        int ret = recvMsgTimeout(20000);
                        if (ret > 0 && recvType() == BslProtocol.REP_ACK) {
                            sent = true;
                            consecutiveErrors = 0;
                        } else {
                            log("响应异常 0x" + Integer.toHexString(ret > 0 ? recvType() : 0) + "，重试 " + (trySend+1) + "/3");
                            try { Thread.sleep(500); } catch (InterruptedException e) {}
                        }
                    } else {
                        log("发送失败，重试 " + (trySend+1) + "/3");
                        try { Thread.sleep(300); } catch (InterruptedException e) {}
                    }
                }
                if (!sent) {
                    log("连续错误，跳过此块");
                    consecutiveErrors++;
                    if (consecutiveErrors >= 3) { log("太多错误，停止写入"); break; }
                    offset += n; // 跳过有问题的块
                    continue;
                }
                offset += n;
                updateSpeed(n);
                progress((int)((offset * 100L) / len), "写入分区中...");
            }
        } catch (IOException e) { log("文件错误: " + e.getMessage()); }
        finally {
            if (fis != null) try { fis.close(); } catch (IOException ignored) {}
        }
        log("loadPartition: 0x" + Long.toHexString(offset) + "/0x" + Long.toHexString(len) + " (实际写入" + (offset < len ? "不完整，缺少0x" + Long.toHexString(len - offset) : "完整") + ")");
        // 只有写入完整才发END_DATA
        if (offset >= len) {
            encodeMsg(BslProtocol.CMD_END_DATA, null, 0);
            sendAndCheck(3000);
            log("分区 \"" + name + "\" 写入完成");
        } else {
            log("警告: 写入不完整，缺少0x" + Long.toHexString(len - offset) + "字节");
            throw new RuntimeException("写入不完整");
        }
        } finally {
            setBusy(false);
        }
    }
    public void erasePartition(String name) {
        if (busy) { log("正在执行操作哦~"); return; }
        if (!selectPartition(name, 0, false, BslProtocol.CMD_ERASE_FLASH))
            throw new RuntimeException("擦除失败: " + name);
        log("分区 \"" + name + "\" 已擦除");
    }
    public void eraseFlash(long addr, long size) {
        if (secureBoot && fwAddr != ~0L && (addr - fwAddr) < 0x10000) { log("安全启动保护: 操作已取消"); return; }
        byte[] data = new byte[8]; w32be(data, 0, addr); w32be(data, 4, size); encodeMsg(BslProtocol.CMD_ERASE_FLASH, data, 8); if (!sendAndCheck(5000)) throw new RuntimeException("擦除失败"); log("Flash 0x" + Long.toHexString(addr) + "-0x" + Long.toHexString(addr + size) + " 已擦除");
    }

    // ===== 分区列表 =====
    public void partitionList(String fn, int sh) {
        if (busy) { log("正在执行操作哦~"); return; }
        setBusy(true);
        try {
        log("开始读取分区表...");
        encodeMsg(BslProtocol.CMD_READ_PARTITION, null, 0); if (!sendMsg()) { log("发送读取分区表命令失败"); return; }
        int ret = recvMsgTimeout(100000); // 100秒超时
        if (ret <= 0 || recvType() != BslProtocol.REP_READ_PARTITION) {
            log("读取分区表失败或设备不支持 (ret=" + ret + ", type=0x" + Integer.toHexString(recvType()) + ")");
            return;
        }
        log("收到分区表响应");
        int size = r16be(rawBuf, 2); if (size % 0x4c != 0) { log("分区表大小不对 (size=" + size + "不是0x4c的倍数)"); return; } int n = size / 0x4c;
        log("分区表大小: " + size + " bytes, 共 " + n + " 个分区");
        log("开始解析分区...");
        currentPartitions.clear();
        int p = 4; long mask = 0;
        for (int i = 0; i < n; i++, p += 0x4c) {
            if (i % 5 == 0) log("解析进度: " + i + "/" + n);
            String name = copyFromWstr(rawBuf, p, 36);
            long ps = r32le(rawBuf, p + 0x48) & 0xFFFFFFFFL;
            mask |= ps;
            PartitionInfo part = new PartitionInfo();
            part.name = name;
            part.size = ps << sh;  // 统一存储为字节数
            part.selected = true;
            currentPartitions.add(part);
            log("[" + i + "] " + name + ", " + formatSize(part.size));
        }
        if ((mask & ((1L << sh) - 1)) != 0) { log("警告: 单元大小可能不对!"); if ((mask & 0x7f) == 0) log("可能使用4KB扇区，请用 partition_list_4k"); }
        log("分区表解析完成，共 " + n + " 个分区");
        if (!fn.equals("-")) {
            try {
                FileOutputStream fos = new FileOutputStream(new File(getSaveDir(), fn));
                fos.write("<Partitions>\n".getBytes());
                for (PartitionInfo part : currentPartitions) {
                    fos.write(("    <Partition id=\"" + part.name + "\" size=\"" + (part.size >> sh) + "\"/>\n").getBytes());
                }
                fos.write("</Partitions>\n".getBytes());
                fos.close();
                log("分区表已保存到 " + fn + " (共" + n + "个分区)");
            } catch (IOException e) { log("文件错误: " + e.getMessage()); }
        }
        } finally { busy = false; }
    }

    // ===== 格式化大小显示 =====
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ===== 分区信息类 =====
    public static class PartitionInfo {
        public int index = 0;
        public String name = "";
        public long offset = 0;
        public long size = 0;
        public int flags = 0;
        public int type = 0;
        public boolean selected = true;
    }
    public java.util.List<PartitionInfo> currentPartitions = new java.util.ArrayList<>();
    public int getPartitionCount() { return currentPartitions != null ? currentPartitions.size() : 0; }
    public java.util.List<PartitionInfo> getPartitions() { return currentPartitions; }
    public void repartition(String fn) {
        if (busy) { log("正在执行操作哦~"); return; }
        File f = new File(getSaveDir(), fn); if (!f.exists()) { log("文件不存在: " + fn); return; } String xml; try { FileInputStream fis = new FileInputStream(f); byte[] buf = new byte[(int)f.length()]; fis.read(buf); fis.close(); xml = new String(buf, "UTF-8"); } catch (IOException e) { log("读取失败"); return; }
        int n = scanXmlPartitions(xml); if (n <= 0) { log("分区表解析失败"); return; } encodeMsg(BslProtocol.CMD_REPARTITION, tempBuf, 0, n * 0x4c); if (sendAndCheck(5000)) log("重分区成功"); else log("重分区失败");
    }
    private int scanXmlPartitions(String xml) {
        String part1 = "Partitions>"; int found = 0, stage = 0, pos = 0, bufPos = 0;
        while (pos < xml.length()) { char c = xml.charAt(pos); if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { pos++; continue; } if (c != '<') { if (stage != 1) { pos++; continue; } log("xml: unexpected symbol"); return 0; } pos++;
            if (pos + 3 <= xml.length() && xml.substring(pos, pos + 3).equals("!--")) { int end = xml.indexOf("-->", pos + 3); if (end < 0) { log("xml: comment not closed"); return 0; } pos = end + 3; continue; }
            if (stage != 1) { if (pos + part1.length() <= xml.length() && xml.substring(pos, pos + part1.length()).equals(part1)) stage++; if (stage > 2) { log("xml: more than one partition lists"); return 0; } int gt = xml.indexOf('>', pos); if (gt < 0) { log("xml: unexpected syntax"); return 0; } pos = gt + 1; continue; }
            if (xml.charAt(pos) == '/' && pos + 1 + part1.length() <= xml.length() && xml.substring(pos + 1, pos + 1 + part1.length()).equals(part1)) { stage++; pos += 1 + part1.length(); continue; }
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("Partition id=\"([^\"]*)\" size=\"([^\"]*)\"/>"); java.util.regex.Matcher m = pat.matcher(xml.substring(pos)); if (!m.find()) { log("xml: unexpected syntax"); return 0; }
            String name = m.group(1); long size = parseNumber(m.group(2)); if (bufPos + 0x4c > tempBuf.length) { log("xml: too many partitions"); return 0; } java.util.Arrays.fill(tempBuf, bufPos, bufPos + 72, (byte)0); for (int i = 0; i < name.length() && i < 36; i++) tempBuf[bufPos + i * 2] = (byte)name.charAt(i); w32le(tempBuf, bufPos + 0x48, size); bufPos += 0x4c; found++; pos += m.end();
        }
        if (stage != 2) { log("xml: unexpected syntax (stage=" + stage + ")"); return 0; } return found;
    }

    /**
     * sfd_tool check_partition / find_partition_size_new
     * 1) 试读 name_size 字符串
     * 2) 试读 8 字节确认分区存在
     * 3) 二分探测大小
     */

    /**
     * 分区大小：优先已缓存分区表，再 name_size，再 spd_dump 经典二分。
     * spd_dump: select(name, 1<<48, mode64=1) + READ_MIDST 探测 i=47..20
     */
    public long findPartitionSize(String name) {
        if (name == null || name.length() == 0) return 0;
        String n = name.trim();
        // 1) 缓存的分区表
        if (currentPartitions != null) {
            for (PartitionInfo p : currentPartitions) {
                if (p != null && p.name != null && p.name.equalsIgnoreCase(n) && p.size > 0) {
                    log("partition_size(" + n + ") = 0x" + Long.toHexString(p.size)
                        + " (" + formatSize(p.size) + ") [分区表缓存]");
                    return p.size;
                }
            }
        }
        // 2) name_size 字符串
        long via = findPartitionSizeByNameSize(n);
        if (via > 0) {
            log("partition_size(" + n + ") = 0x" + Long.toHexString(via)
                + " (" + formatSize(via) + ") [name_size]");
            return via;
        }
        // 3) spd_dump 经典算法
        log("二分探测分区大小: " + n);
        int startBit = 47;
        if (!selectPartition(n, 1L << (startBit + 1), true, BslProtocol.CMD_READ_START)) {
            // 再试小写
            String low = n.toLowerCase();
            if (!low.equals(n) && selectPartition(low, 1L << (startBit + 1), true, BslProtocol.CMD_READ_START)) {
                n = low;
            } else {
                log("无法 SELECT 分区: " + name + "（确认 FDL2 已加载、分区名正确）");
                try {
                    encodeMsg(BslProtocol.CMD_READ_END, null, 0);
                    sendAndCheck(2000);
                } catch (Throwable ignored) {}
                return 0;
            }
        }
        long offset = 0;
        for (int i = startBit; i >= 20; i--) {
            long n64 = offset + (1L << i) - (1 << 20);
            byte[] data = new byte[12];
            w32le(data, 0, 4);
            w32le(data, 4, n64);
            w32le(data, 8, n64 >> 32);
            encodeMsg(BslProtocol.CMD_READ_MIDST, data, 12);
            if (!sendMsg()) break;
            int ret = recvMsgTimeout(10000);
            if (ret <= 0) continue;
            if (recvType() != BslProtocol.REP_READ_FLASH) continue;
            offset = n64 + (1 << 20);
        }
        encodeMsg(BslProtocol.CMD_READ_END, null, 0);
        sendAndCheck(3000);
        if (offset <= 0) {
            log("探测结果为 0");
            return 0;
        }
        log("partition_size(" + n + ") = 0x" + Long.toHexString(offset)
            + " (" + formatSize(offset) + ")");
        return offset;
    }

    private long findPartitionSizeByNameSize(String name) {
        String sizeName = name + "_size";
        if (!selectPartition(sizeName, 0x80, false, BslProtocol.CMD_READ_START)) {
            try { encodeMsg(BslProtocol.CMD_READ_END, null, 0); sendAndCheck(1500); } catch (Throwable ignored) {}
            return 0;
        }
        byte[] data = new byte[8];
        w32le(data, 0, 0x80);
        w32le(data, 4, 0);
        encodeMsg(BslProtocol.CMD_READ_MIDST, data, 8);
        long result = 0;
        if (sendMsg()) {
            int ret = recvMsgTimeout(8000);
            if (ret > 0 && recvType() == BslProtocol.REP_READ_FLASH) {
                int nn = r16be(rawBuf, 2);
                String s = bytesToStr(rawBuf, 4, Math.min(nn, 160));
                try {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(?i)0x([0-9a-fA-F]+)")
                        .matcher(s);
                    // 取最后一个 0x 数，通常是 size
                    while (m.find()) {
                        try { result = Long.parseLong(m.group(1), 16); } catch (Exception ignored) {}
                    }
                    if (result > 0) log("name_size 原文: " + s.trim());
                } catch (Exception ignored) {}
            }
        }
        encodeMsg(BslProtocol.CMD_READ_END, null, 0);
        sendAndCheck(2000);
        return result;
    }

    // ===== 其他命令 =====
    public void readPactime() {
        if (!selectPartition("miscdata", 0x81400 + 8, false, BslProtocol.CMD_READ_START)) { log("select miscdata 失败"); return; } byte[] data = new byte[8]; w32le(data, 0, 8); w32le(data, 4, 0x81400); encodeMsg(BslProtocol.CMD_READ_MIDST, data, 8); if (!sendMsg()) return; int ret = recvMsgTimeout(30000); if (ret <= 0 || recvType() != BslProtocol.REP_READ_FLASH) { log("unexpected response 0x" + Integer.toHexString(recvType())); return; } int n = r16be(rawBuf, 2); if (n != 8) { log("unexpected length"); return; } long time = r32le(rawBuf, 4) | (r32le(rawBuf, 8) << 32); long unix = time != 0 ? time / 10000000 - 11644473600L : 0; log("pactime = 0x" + Long.toHexString(time) + " (unix = " + unix + ")"); encodeMsg(BslProtocol.CMD_READ_END, null, 0); sendAndCheck(3000);
    }
    public void dumpEfuse(String fn) {
        if (chipId == 0) { log("dump_efuse: 需要先加载FDL1"); return; } int n;
        if ((chipId ^ 0x65300000L) >> 17 == 0) n = 8; else if ((chipId ^ 0x65620000L) >> 16 == 0) n = 16; else if ((chipId ^ 0x98180000L) >> 16 == 0) n = 64; else { log("dump_efuse: 未知芯片"); return; }
        encodeMsg(0x6566, null, 0); if (!sendMsg()) return; int ret = recvMsgTimeout(30000); if (ret <= 0 || recvType() != BslProtocol.REP_READ_FLASH) { log("unexpected response 0x" + Integer.toHexString(recvType())); return; } int n2 = r16be(rawBuf, 2); if (n2 != n * 4) { log("unexpected size 0x" + Integer.toHexString(n2)); return; }
        if (fn.equals("-")) { log("efuse data:"); for (int i = 0; i < n; i++) log("  [" + i + "] 0x" + Long.toHexString(r32le(rawBuf, 4 + i * 4))); }
        else try { FileOutputStream fos = new FileOutputStream(new File(getSaveDir(), fn)); fos.write(rawBuf, 4, n * 4); fos.close(); log("efuse已保存到 " + fn); } catch (IOException e) { log("保存失败: " + e.getMessage()); }
    }
    public void writeWord(long addr, long data) {
        if (secureBoot && fwAddr != ~0L && (addr - fwAddr) < 0x10000) { log("安全启动保护: 操作已取消"); return; }
        byte[] buf = new byte[4]; w32le(buf, 0, data); sendBuf(addr, endData == 1, 528, buf, 4);
    }
    public void writeData(String fn, long addr, int srcOffs, int srcSize) {
        if (secureBoot && fwAddr != ~0L && (addr - fwAddr) < 0x10000) { log("安全启动保护: 操作已取消"); return; }
        sendFile(fn, addr, blkSize > 0 ? blkSize : 528, srcOffs, srcSize);
    }
    /**
     * 发送自定义FDL文件到指定地址
     * @param fn FDL文件名
     * @param addr 加载地址
     * @param execAddr 执行地址 (0 = 自动，使用加载地址)
     */

    /**
     * 一键发送 FDL1 + FDL2（sfd_tool / spd_dump 标准流程）
     * 1. BootROM 握手
     * 2. 发 FDL1 @ addr1 并执行
     * 3. FDL1 握手
     * 4. 发 FDL2 @ addr2 并执行
     * 5. FDL2 握手
     */

    /**
     * 一键发送 FDL1 + FDL2 —— 严格按 ilyakurdyukov/spd_dump + sfd_tool 流程：
     *
     * FDL1 (BROM):
     *   controlTransfer(0x21,34,0x601)
     *   CRC16 + CHECK_BAUD(1) → VER → CONNECT
     *   send_file(fdl1, addr1, end_data=1, step=528)
     *   EXEC_DATA → ACK
     *   sum checksum + CHECK_BAUD(4)×重试 → VER → CONNECT [+ KEEP_CHARGE]
     *
     * FDL2 (FDL1 阶段):
     *   send_file(fdl2, addr2, end_data, step=528)
     *   EXEC_DATA → ACK 或 INCOMPATIBLE_PARTITION(0x96)
     */

    /**
     * 一键 FDL1+FDL2 —— 始终发送两个文件（不因 SPRD4 跳过）
     * 流程对齐 spd_dump / sfd_tool
     */
    public boolean sendFdl1AndFdl2(String fdl1Path, long addr1, String fdl2Path, long addr2) {
        if (!connected || conn == null) { log("未连接设备"); return false; }
        File f1 = new File(fdl1Path);
        File f2 = new File(fdl2Path);
        if (!f1.exists()) { log("FDL1 不存在: " + fdl1Path); return false; }
        if (!f2.exists()) { log("FDL2 不存在: " + fdl2Path); return false; }

        log("======== 一键发送 FDL1 + FDL2 ========");
        log("FDL1: " + f1.getName() + " (" + f1.length() + " B) @ 0x" + Long.toHexString(addr1));
        log("FDL2: " + f2.getName() + " (" + f2.length() + " B) @ 0x" + Long.toHexString(addr2));

        fdlLoaded = 0;
        fdl2Executed = 0;
        fdl2Skipped = false; // 强制发 FDL2
        cancelled = false;

        log("--- [1/2] 发送 FDL1 ---");
        if (!sendFdlStage(f1, addr1, true)) {
            log("FDL1 失败，中止");
            return false;
        }
        log("FDL1 OK, fdlLoaded=" + fdlLoaded);

        log("--- [2/2] 发送 FDL2（强制，不跳过）---");
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        if (!sendFdlStage(f2, addr2, false)) {
            log("FDL2 失败");
            return false;
        }
        log("FDL2 OK, fdlLoaded=" + fdlLoaded + " fdl2Executed=" + fdl2Executed);
        log("======== FDL1+FDL2 全部完成，可读写分区 ========");
        return true;
    }

    private boolean sendFdlStage(File f, long addr, boolean isFdl1) {
        if (!connected || conn == null) return false;
        if (!f.exists()) { log("文件不存在: " + f.getAbsolutePath()); return false; }
        int step = blkSize > 0 ? blkSize : 528;
        if (step < 64) step = 528;
        if (step > 0xF800) step = 0xF800;

        try {
            if (isFdl1) {
                log("=== BROM 握手 ===");
                try {
                    int ct = conn.controlTransfer(0x21, 34, 0x601, 0, null, 0, Math.max(timeout, 3000));
                    log("controlTransfer(0x21,34,0x601)=" + ct);
                } catch (Throwable t) {
                    log("controlTransfer: " + t.getMessage());
                }
                flags = FLAGS_TRANSCODE | FLAGS_CRC16;
                recvLen = 0; recvPos = 0;

                encodeMsg(BslProtocol.CMD_CHECK_BAUD, null, 1);
                if (!sendMsg()) { log("CHECK_BAUD 发送失败"); return false; }
                int ret = recvMsgTimeout(30000);
                if (ret <= 0 || recvType() != BslProtocol.REP_VER) {
                    log("BROM 期望 VER，得到 0x" + Integer.toHexString(ret > 0 ? recvType() : 0));
                    return false;
                }
                int vl = r16be(rawBuf, 2);
                String verStr = bytesToStr(rawBuf, 4, vl);
                log("BSL_REP_VER: " + verStr);
                secureBoot = !(vl == 6 && startsWith(rawBuf, 4, "SPRD3"));

                encodeMsg(BslProtocol.CMD_CONNECT, null, 0);
                if (!sendAndCheck(5000)) { log("BROM CONNECT 失败"); return false; }
                log("BROM 已连接，发送 FDL1...");
            } else {
                flags = (flags & ~FLAGS_CRC16) | FLAGS_TRANSCODE;
                log("FDL1 阶段，发送 FDL2...");
            }

            // 读文件
            byte[] data;
            FileInputStream fis = new FileInputStream(f);
            try {
                long sz = f.length();
                if (sz <= 0 || sz > 0x7FFFFFFF) { log("文件大小异常"); return false; }
                data = new byte[(int) sz];
                int off = 0;
                while (off < data.length) {
                    int n = fis.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off != data.length) {
                    log("读取不完整 " + off + "/" + data.length);
                    return false;
                }
            } finally {
                try { fis.close(); } catch (Exception ignored) {}
            }

            log("send_file " + f.getName() + " " + data.length + " bytes @ 0x"
                + Long.toHexString(addr) + " step=" + step);
            // FDL1 必须 END_DATA；FDL2 也默认 END_DATA（更兼容）
            boolean doEnd = true;
            try {
                sendBuf(addr, doEnd, step, data, data.length);
            } catch (RuntimeException re) {
                log("send_file 失败: " + re.getMessage());
                return false;
            }
            log("send_file 完成");

            // EXEC
            encodeMsg(BslProtocol.CMD_EXEC_DATA, null, 0);
            if (isFdl1) {
                if (!sendAndCheck(8000)) {
                    log("FDL1 EXEC 未收到 ACK");
                    return false;
                }
                log("FDL1 EXEC OK → FDL1 握手");
                flags = FLAGS_TRANSCODE; // sum checksum
                recvLen = 0; recvPos = 0;
                encodeMsg(BslProtocol.CMD_CHECK_BAUD, null, 4);
                int got = 0;
                for (int i = 0; i < 12; i++) {
                    log("checkbaud " + (i + 1));
                    if (!sendMsg()) continue;
                    got = recvMsg(3000);
                    if (got > 0) break;
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                if (got <= 0 || recvType() != BslProtocol.REP_VER) {
                    log("FDL1 握手失败（无 VER）");
                    return false;
                }
                int vl2 = r16be(rawBuf, 2);
                String vs = bytesToStr(rawBuf, 4, vl2);
                log("FDL1 VER: " + vs);
                if (vs != null && vs.contains("CHIP ID = 0x")) {
                    try {
                        int idx = vs.indexOf("CHIP ID = 0x") + 12;
                        String cid = vs.substring(idx).trim();
                        int sp = cid.indexOf(' ');
                        if (sp > 0) cid = cid.substring(0, sp);
                        chipId = Long.parseLong(cid, 16);
                        log("CHIP ID: 0x" + Long.toHexString(chipId));
                    } catch (Exception ignored) {}
                }
                encodeMsg(BslProtocol.CMD_CONNECT, null, 0);
                if (!sendAndCheck(5000)) {
                    log("FDL1 CONNECT 失败");
                    return false;
                }
                if (keepCharge != 0) {
                    encodeMsg(BslProtocol.CMD_KEEP_CHARGE, null, 0);
                    sendAndCheck(3000);
                }
                fdlLoaded = 1;
                log("FDL1 已就绪");
            } else {
                if (!sendMsg()) {
                    log("FDL2 EXEC 发送失败");
                    return false;
                }
                int got = recvMsgTimeout(20000);
                if (got <= 0) {
                    log("FDL2 EXEC 超时（部分机型正常），继续");
                } else {
                    int typ = recvType();
                    if (typ == BslProtocol.REP_INCOMPATIBLE_PARTITION) {
                        log("FDL2: 0x96 incompatible partition — 正常");
                    } else if (typ == BslProtocol.REP_ACK) {
                        log("FDL2 EXEC ACK");
                    } else {
                        log("FDL2 EXEC 响应 0x" + Integer.toHexString(typ) + "（继续）");
                    }
                }
                fdlLoaded = 2;
                fdl2Executed = 1;
                log("FDL2 已就绪");
            }
            return true;
        } catch (Throwable t) {
            log("sendFdlStage 异常: " + t.getMessage());
            return false;
        }
    }

    public boolean sendCustomFdl(String fn, long addr, long execAddr) {
        if (!connected || conn == null) { log("未连接设备"); return false; }
        File f = new File(fn);
        if (!f.exists() || !f.isAbsolute()) f = new File(workDir, fn);
        if (!f.exists()) f = new File(fn);
        if (!f.exists()) { log("FDL文件不存在: " + fn); return false; }
        boolean isFdl1 = (fdlLoaded == 0);
        log("sendCustomFdl stage=" + (isFdl1 ? "FDL1/BROM" : "FDL2") + " addr=0x" + Long.toHexString(addr));
        return sendFdlStage(f, addr, isFdl1);
    }

    public void setAutoFdl(String fdl1, long a1, String fdl2, long a2) {
        autoFdl1Path = fdl1; autoFdl1Addr = a1;
        autoFdl2Path = fdl2; autoFdl2Addr = a2;
    }

    public int getFdlLoaded() { return fdlLoaded; }

    /**
     * 分区读写前确保已进入 FDL2；未加载则自动发内置 FDL1+FDL2。
     */
    public boolean ensureFdl2Ready() {
        if (fdlLoaded >= 2 || fdl2Executed > 0) return true;
        if (!connected || conn == null) {
            log("未连接，无法加载 FDL");
            return false;
        }
        log("当前 fdlLoaded=" + fdlLoaded + "，分区操作需要 FDL2，自动发送...");
        String p1 = autoFdl1Path, p2 = autoFdl2Path;
        long a1 = autoFdl1Addr, a2 = autoFdl2Addr;
        try {
            if (p1 == null || !new File(p1).exists()) {
                p1 = extractAssetToWork("fdl/sc9820e/fdl1.bin", "auto_fdl1.bin");
                a1 = 0x5000L;
            }
            if (p2 == null || !new File(p2).exists()) {
                p2 = extractAssetToWork("fdl/sc9820e/fdl2.bin", "auto_fdl2.bin");
                a2 = 0x9EFFFE00L;
            }
        } catch (Throwable t) {
            log("提取内置 FDL 失败: " + t.getMessage());
            return false;
        }
        if (p1 == null || p2 == null) {
            log("无可用 FDL，请先在刷机页一键发送 FDL1+FDL2");
            return false;
        }
        boolean ok = sendFdl1AndFdl2(p1, a1, p2, a2);
        if (!ok) log("自动 FDL 失败，请手动发送后再操作分区");
        return ok;
    }

    private String extractAssetToWork(String asset, String outName) throws Exception {
        File dir = new File(workDir != null ? workDir : ctx.getFilesDir().getAbsolutePath());
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, outName);
        java.io.InputStream is = ctx.getAssets().open(asset);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close(); is.close();
        log("已提取: " + asset + " -> " + out.getAbsolutePath());
        return out.getAbsolutePath();
    }

    public void disableTranscode() { encodeMsg(BslProtocol.CMD_DISABLE_TRANSCODE, null, 0); if (sendAndCheck(3000)) flags &= ~FLAGS_TRANSCODE; }
    public void setTranscode(int a) { if (a >> 1 != 0) { log("bad command"); return; } flags = (flags & ~FLAGS_TRANSCODE) | (a != 0 ? FLAGS_TRANSCODE : 0); }
    public int getFlags() { return flags; }


    /**
     * 发送并执行代码命令
     * spd_dump中称为"exec_addr"功能，用于跳转到指定地址执行代码
     */
    public boolean sendExddr(String fn, long loadAddr, long execAddr) {
        if (!connected || conn == null) { log("未连接设备"); return false; }
        File f = new File(workDir, fn);
        if (!f.exists()) { f = new java.io.File(fn); }
        if (!f.exists()) { log("文件不存在: " + fn); return false; }
        long size = f.length();
        log("=== 执行代码 ===");
        log("文件: " + fn + " (大小: " + size + ")");
        log("加载地址: 0x" + Long.toHexString(loadAddr));
        log("执行地址: 0x" + Long.toHexString(execAddr));

        // 确保已连接bootloader
        if (!bootloaderHandshakeWithRetry()) {
            log("Bootloader握手失败");
            return false;
        }

        // 发送文件
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[(int)size];
            fis.read(data);
            fis.close();
            sendBuf(loadAddr, true, blkSize > 0 ? blkSize : 528, data, (int)size);
            log("文件发送完成: " + size + " bytes");
        } catch (Exception e) {
            log("发送失败: " + e.getMessage());
            return false;
        }

        // 执行代码
        if (execAddr == 0) execAddr = loadAddr;
        log("跳转到执行地址: 0x" + Long.toHexString(execAddr));
        encodeMsg(BslProtocol.CMD_EXEC_DATA, null, 0);
        if (!sendAndCheck(5000)) {
            log("执行命令发送失败");
            return false;
        }

        log("执行代码发送成功，设备将执行跳转代码");
        return true;
    }

    /**
     * 跳转到执行地址 (类似spd_dump exec_addr命令)
     */
    public boolean sendExecAddr(long execAddr) {
        if (!connected || conn == null) { log("未连接设备"); return false; }
        log("=== exec_addr ===");
        log("执行地址: 0x" + Long.toHexString(execAddr));

        // 发送exec命令跳转到指定地址
        encodeMsg(BslProtocol.CMD_EXEC_DATA, null, 0);
        if (!sendAndCheck(5000)) {
            log("执行命令发送失败");
            return false;
        }

        log("已跳转到地址: 0x" + Long.toHexString(execAddr));
        return true;
    }

    /**
     * 读取PAC文件并解析分区信息
     * PAC (Programmer Access Client) 是Spreadtrum的打包固件格式
     */
    public void readPac(String pacPath) {
        if (busy) { log("正在执行操作哦~"); return; }
        if (pacPath == null || pacPath.isEmpty()) { log("PAC文件路径为空"); return; }
        setBusy(true);
        try {
            java.io.File f = new java.io.File(pacPath);
                        if (!f.exists()) { log("PAC文件不存在: " + pacPath); return; }

            log("=== 读取PAC文件 ===");
            log("文件: " + pacPath);
            log("文件大小: " + f.length() + " bytes");

            // 解析PAC文件（XML格式）
            long startTime = System.currentTimeMillis();
                        java.util.List<PacPartition> partitions = parsePacFile(f.getAbsolutePath());
            long elapsed = System.currentTimeMillis() - startTime;
            log("解析完成，耗时: " + elapsed + "ms, 找到 " + partitions.size() + " 个分区");
                        if (partitions.isEmpty()) {
                log("未找到分区信息");
                return;
            }

            // 清空旧的分区表
            currentPartitions.clear();

            log("找到 " + partitions.size() + " 个分区:");
            for (PacPartition part : partitions) {
                log("  [" + part.index + "] " + part.name + " -> " + part.file + " (0x" + Long.toHexString(part.offset) + ")");
                PartitionInfo pi = new PartitionInfo();
                pi.name = part.name;
                pi.size = part.size;
                String n = part.name != null ? part.name.toLowerCase() : "";
                // FDL 默认不勾选（不刷入，只当加载文件用）
                pi.selected = !(n.contains("fdl"));
                currentPartitions.add(pi);
            }

            // 保存到文件
            String saveName = "partition_from_pac.xml";
            try {
                java.io.File saveDir = new java.io.File(getSaveDir());
                if (!saveDir.exists()) { saveDir.mkdirs(); }
                java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(saveDir, saveName));
                fos.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".getBytes());
                fos.write("<Partitions>\n".getBytes());
                for (PacPartition part : partitions) {
                    long szOut = part.size >= 1024 ? (part.size >> 10) : part.size;
                    String fn = part.file != null ? part.file : "";
                    String line = "    <Partition id=\"" + part.name + "\" size=\"" + szOut
                        + "\" file=\"" + fn + "\" offset=\"" + Long.toHexString(part.offset) + "\"/>\n";
                    fos.write(line.getBytes());
                }
                fos.write("</Partitions>\n".getBytes());
                fos.close();
                log("分区表已保存到: " + saveName);
            } catch (Exception e) {
                log("保存分区表失败: " + e.getMessage());
            }

            // 从 PAC 提取 FDL1/FDL2 到工作目录，供一键发送使用
            try {
                lastPacPath = pacPath;
                lastPacFdl1Path = null;
                lastPacFdl2Path = null;
                lastPacFdl1Addr = ADDR_FDL1;
                lastPacFdl2Addr = ADDR_FDL2;
                java.io.File workDir = new java.io.File(getSaveDir(), "pac_fdl");
                if (!workDir.exists()) workDir.mkdirs();
                java.util.Map<String, java.io.File> fmap = extractFilesFromPac(pacPath, workDir);
                for (PacPartition part : partitions) {
                    String n = part.name != null ? part.name.toLowerCase() : "";
                    String fn = part.file != null ? part.file : "";
                    java.io.File bin = fmap.get(fn);
                    if (bin == null || !bin.exists()) continue;
                    if (n.contains("fdl1") || n.equals("fdl") || fn.toLowerCase().contains("fdl1")) {
                        lastPacFdl1Path = bin.getAbsolutePath();
                        if (part.loadAddr >= 0) lastPacFdl1Addr = part.loadAddr;
                        log("PAC FDL1: " + lastPacFdl1Path + " @ 0x" + Long.toHexString(lastPacFdl1Addr));
                    } else if (n.contains("fdl2") || n.contains("nand_fdl") || fn.toLowerCase().contains("fdl2")) {
                        lastPacFdl2Path = bin.getAbsolutePath();
                        if (part.loadAddr >= 0) lastPacFdl2Addr = part.loadAddr;
                        log("PAC FDL2: " + lastPacFdl2Path + " @ 0x" + Long.toHexString(lastPacFdl2Addr));
                    }
                }
            } catch (Exception e) {
                log("提取 PAC FDL 失败: " + e.getMessage());
            }

            // 通知UI更新分区表显示
            if (partitionCallback != null) {
                log("readPac: 调用回调, currentPartitions.size=" + currentPartitions.size());
                partitionCallback.onPartitionTableReady(currentPartitions);
            } else {
                log("readPac: 分区回调为null，跳过UI更新");
            }
        } catch (Exception e) {
            log("读取PAC失败: " + e.getMessage());
            log("异常类型: " + e.getClass().getName());
            e.printStackTrace();
        } finally {
            setBusy(false);
                    }
    }

    /**
     * PAC分区信息类
     */
    public static class PacPartition {
        public int index = 0;
        public String name = "";
        public String file = "";
        public long offset = 0;
        public long size = 0;
        public long loadAddr = -1; // FDL 加载地址，-1 表示未知
    }

    // 最近一次 PAC 中解析出的 FDL
    public String lastPacFdl1Path = null;
    public String lastPacFdl2Path = null;
    public long lastPacFdl1Addr = ADDR_FDL1;
    public long lastPacFdl2Addr = ADDR_FDL2;
    public String lastPacPath = null;


    /**
     * 解析PAC文件（二进制固件包）
     * PAC结构: 2124字节头 + FILE_T目录(2580字节/条目) + XML配置 + 固件数据
     */
    private java.util.List<PacPartition> parsePacFile(String pacPath) {
        java.util.List<PacPartition> parts = new java.util.ArrayList<>();
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(pacPath, "r");
            long fileSize = raf.length();
            
            byte[] header = new byte[2124];
            if (raf.read(header) != 2124) {
                log("PAC文件头读取失败");
                return parts;
            }

            int magic = (int) r32le(header, 2120);
            if (magic != 0xFFFAFFFA) {
                log("PAC Magic不匹配: 0x" + Integer.toHexString(magic));
            } else {
                log("PAC Magic验证通过");
            }

            String version = readUtf16le(header, 0, 22);
            String productName = readUtf16le(header, 44, 32);
            String productVer = readUtf16le(header, 108, 32);
            int fileCount = (int) r32le(header, 0x434);
            int fileOffset = (int) r32le(header, 0x438);

            log("产品: " + productName + " | 版本: " + productVer + " | 文件数: " + fileCount);

            if (fileCount <= 0 || fileCount > 1000) {
                log("文件数量异常: " + fileCount);
                return parts;
            }

            raf.seek(fileOffset);
            for (int i = 0; i < fileCount; i++) {
                byte[] entry = new byte[2580];
                if (raf.read(entry) != 2580) {
                    log("FILE_T[" + i + "] 读取失败");
                    break;
                }

                PacPartition part = new PacPartition();
                part.index = i;

                String fileId = readUtf16le(entry, 0x004, 256).trim();
                String fileName = readUtf16le(entry, 0x204, 256).trim();

                int sizeHigh = (int) r32le(entry, 0x5FC);
                int offsetHigh = (int) r32le(entry, 0x600);
                int entryFileSize = (int) r32le(entry, 0x604);
                int fileFlag = (int) r32le(entry, 0x608);
                int dataOffset = (int) r32le(entry, 0x610);

                long fullSize = ((long) sizeHigh << 32) | (entryFileSize & 0xFFFFFFFFL);
                long fullOffset = ((long) offsetHigh << 32) | (dataOffset & 0xFFFFFFFFL);

                part.name = fileId.isEmpty() ? fileName : fileId;
                part.file = fileName;
                part.size = fullSize;
                part.offset = fullOffset;
                // 按名称推断 FDL 加载地址
                String nlow = part.name != null ? part.name.toLowerCase() : "";
                String flow = part.file != null ? part.file.toLowerCase() : "";
                if (nlow.contains("fdl1") || flow.contains("fdl1") || nlow.equals("fdl")) {
                    part.loadAddr = ADDR_FDL1;
                } else if (nlow.contains("fdl2") || flow.contains("fdl2") || nlow.contains("nand_fdl") || nlow.contains("fdl_nand")) {
                    part.loadAddr = ADDR_FDL2;
                }

                // 有数据的条目都纳入（含 fileFlag!=1 的镜像），避免漏文件
                if (fullSize > 0 && part.name != null && part.name.length() > 0
                        && !part.name.equalsIgnoreCase("XML")
                        && !(part.file != null && part.file.toLowerCase().endsWith(".xml"))) {
                    parts.add(part);
                    log("  文件[" + i + "]: " + part.name + " file=" + part.file
                        + " size=" + formatSize(fullSize) + " flag=" + fileFlag);
                }

                if ((fileId.equalsIgnoreCase("XML") || fileName.toLowerCase().endsWith(".xml"))
                        && fullSize > 0 && fullSize < 10 * 1024 * 1024) {
                    log("发现XML配置: " + fileName);
                    byte[] xmlData = new byte[(int) fullSize];
                    raf.seek(fullOffset);
                    if (raf.read(xmlData) == fullSize) {
                        String xmlStr = readXmlString(xmlData);
                        if (xmlStr != null && xmlStr.length() > 10) {
                            parseXmlPartitions(xmlStr, parts);
                        }
                    }
                }
            }
            log("解析完成，共 " + parts.size() + " 个分区");
        } catch (Exception e) {
            log("解析PAC失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception e) {}
        }
        return parts;
    }

    private String readUtf16le(byte[] buf, int offset, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxChars && offset + i * 2 + 1 < buf.length; i++) {
            char c = (char) ((buf[offset + i * 2 + 1] & 0xFF) << 8 | (buf[offset + i * 2] & 0xFF));
            if (c == 0) break;
            sb.append(c);
        }
        String result = sb.toString().trim();
        if (isGarbled(result)) {
            try {
                int len = 0;
                for (int i = 0; i < maxChars && offset + i < buf.length; i++) {
                    if (buf[offset + i] == 0) break;
                    len++;
                }
                result = new String(buf, offset, len, "GBK").trim();
            } catch (Exception e) {}
        }
        return result;
    }

    private boolean isGarbled(String s) {
        if (s == null || s.isEmpty()) return true;
        int ascii = 0, chinese = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c <= 0x7E) ascii++;
            else if (c >= 0x4E00 && c <= 0x9FFF) chinese++;
        }
        return ascii == 0 && chinese == 0;
    }

    private String readXmlString(byte[] data) {
        String[] encodings = {"UTF-16LE", "UTF-8", "GBK", "ISO-8859-1"};
        for (String enc : encodings) {
            try {
                String s = new String(data, enc);
                if (s.contains("<") && s.contains(">")) {
                                        return s;
                }
            } catch (Exception e) {}
        }
        try { return new String(data, "UTF-16LE"); } catch (Exception e) { return null; }
    }
    
    /**
     * 解析XML中的分区信息
     */
    /**
     * 从设备读取分区表
     */

    /**
     * 读取设备分区表。SC9820E/SC9832E 等机型标准命令可能失败，
     * 依次尝试: 标准读表 -> partition_list -> partition_list_4k。
     * 仍失败时请先发送内置 FDL1/FDL2，或从 PAC 读取分区表。
     */
    public void readPartitionTable() {
        if (!connected || conn == null) { log("设备未连接"); return; }
        log("开始读取分区表...");
        if (tryReadPartitionCmd()) return;
        log("标准读表失败，尝试 partition_list...");
        try {
            String tmp = new java.io.File(getSaveDir(), "part_list_try.xml").getAbsolutePath();
            partitionList(tmp, 10);
            if (currentPartitions != null && !currentPartitions.isEmpty()) {
                log("partition_list 成功: " + currentPartitions.size());
                if (partitionCallback != null) partitionCallback.onPartitionTableReady(currentPartitions);
                return;
            }
        } catch (Throwable t) {
            log("partition_list 异常: " + t.getMessage());
        }
        log("尝试 partition_list_4k...");
        try {
            String tmp = new java.io.File(getSaveDir(), "part_list_4k.xml").getAbsolutePath();
            partitionList(tmp, 7);
            if (currentPartitions != null && !currentPartitions.isEmpty()) {
                log("partition_list_4k 成功: " + currentPartitions.size());
                if (partitionCallback != null) partitionCallback.onPartitionTableReady(currentPartitions);
                return;
            }
        } catch (Throwable t) {
            log("partition_list_4k 异常: " + t.getMessage());
        }
        log("设备分区表读取失败（SC9820E/SC9832E 常见）。请：");
        log("  1) 先用内置 FDL 发送 FDL1@0x5000 与 FDL2@0x9EFFFE00");
        log("  2) 或在工具页「读取 PAC 分区」用 PAC 里的分区表");
    }

    private boolean tryReadPartitionCmd() {
        try {
            encodeMsg(BslProtocol.CMD_READ_PARTITION, null, 0);
            if (!sendMsg()) { log("发送读表命令失败"); return false; }
            int ret = recvMsgTimeout(15000);
            if (ret <= 0 || recvType() != BslProtocol.REP_READ_PARTITION) {
                log("读表响应异常 type=0x" + Integer.toHexString(recvType()));
                return false;
            }
            int size = r16be(rawBuf, 2);
            int n = size / 0x4c;
            if (n <= 0 || n > 128) {
                log("分区数量异常: " + n);
                return false;
            }
            java.util.List<PartitionInfo> list = new java.util.ArrayList<>();
            int offset = 4;
            for (int i = 0; i < n; i++) {
                PartitionInfo info = new PartitionInfo();
                info.index = i;
                byte[] nameBytes = new byte[20];
                System.arraycopy(rawBuf, offset, nameBytes, 0, 20);
                String nm = new String(nameBytes).trim();
                int z = nm.indexOf(0);
                if (z >= 0) nm = nm.substring(0, z);
                info.name = nm.trim();
                info.offset = r32le(rawBuf, offset + 20);
                info.size = r32le(rawBuf, offset + 24);
                info.flags = r16le(rawBuf, offset + 28);
                info.type = r16le(rawBuf, offset + 30);
                info.selected = true;
                list.add(info);
                offset += 0x4c;
                log("  分区" + i + ": " + info.name + " size=0x" + Long.toHexString(info.size));
            }
            currentPartitions = list;
            log("分区表读取完成，共 " + list.size() + " 个");
            if (partitionCallback != null) partitionCallback.onPartitionTableReady(list);
            return true;
        } catch (Throwable t) {
            log("tryReadPartitionCmd: " + t.getMessage());
            return false;
        }
    }



    private void parseXmlPartitions(String xmlStr, java.util.List<PacPartition> parts) {
        if (xmlStr == null || xmlStr.length() < 10) return;
        // 标准格式: <Partition id="BOOT" size="12765" file="boot" offset="395350"/>
        java.util.regex.Pattern partPat = java.util.regex.Pattern.compile(
            "<\\s*Partition\\b([^>]*)/?>",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher m = partPat.matcher(xmlStr);
        int added = 0;
        while (m.find()) {
            String attrs = m.group(1);
            String id = attrOf(attrs, "id");
            if (id == null || id.length() == 0) id = attrOf(attrs, "name");
            String file = attrOf(attrs, "file");
            String sizeS = attrOf(attrs, "size");
            String offS = attrOf(attrs, "offset");
            String baseS = attrOf(attrs, "base");
            if (baseS == null) baseS = attrOf(attrs, "addr");
            if (id == null || id.length() == 0) continue;

            long size = 0, offset = 0, load = -1;
            try {
                if (sizeS != null) {
                    sizeS = sizeS.trim();
                    if (sizeS.startsWith("0x") || sizeS.startsWith("0X"))
                        size = Long.parseLong(sizeS.substring(2), 16);
                    else
                        size = Long.parseLong(sizeS);
                    // XML 里常见为 KB，若数值较小则按 KB 转字节（与写出 partition_from_pac 一致）
                    // 保留原始数值，由刷写时再解释；这里存为「原始 size 字段」
                }
            } catch (Exception ignored) {}
            try {
                if (offS != null) {
                    offS = offS.trim().replace("0x", "").replace("0X", "");
                    offset = Long.parseLong(offS, 16);
                }
            } catch (Exception ignored) {}
            try {
                if (baseS != null) {
                    String av = baseS.trim();
                    if (av.startsWith("0x") || av.startsWith("0X"))
                        load = Long.parseLong(av.substring(2), 16);
                    else
                        load = Long.parseLong(av);
                }
            } catch (Exception ignored) {}

            // 合并：同名则更新 XML 信息（不重复添加）
            PacPartition exist = null;
            for (PacPartition p : parts) {
                if (p.name != null && p.name.equalsIgnoreCase(id)) { exist = p; break; }
                if (file != null && p.file != null && p.file.equalsIgnoreCase(file)) { exist = p; break; }
            }
            if (exist != null) {
                if (file != null && file.length() > 0) exist.file = file;
                if (size > 0 && exist.size <= 0) exist.size = size;
                if (offset > 0) exist.offset = offset;
                if (load >= 0) exist.loadAddr = load;
            } else {
                PacPartition part = new PacPartition();
                part.index = parts.size();
                part.name = id;
                part.file = file != null ? file : "";
                part.size = size;
                part.offset = offset;
                part.loadAddr = load;
                String nlow = id.toLowerCase();
                if (part.loadAddr < 0) {
                    if (nlow.contains("fdl1") || nlow.equals("fdl")) part.loadAddr = ADDR_FDL1;
                    else if (nlow.contains("fdl2") || nlow.contains("nand_fdl")) part.loadAddr = ADDR_FDL2;
                }
                parts.add(part);
                added++;
            }
        }
        log("XML 分区合并完成, 新增 " + added + " 项, 合计 " + parts.size());
    }

    private String attrOf(String attrs, String key) {
        if (attrs == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            key + "\\s*=\\s*\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(attrs);
        return m.find() ? m.group(1) : null;
    }


    /**
     * 从字节数组中提取 UTF-16LE 字符串
     */
    private String u16toString(byte[] buf, int offset, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxChars * 2 && offset + i < buf.length; i += 2) {
            char c = (char)((buf[offset + i + 1] & 0xFF) << 8 | (buf[offset + i] & 0xFF));
            if (c == 0) break;
            sb.append(c);
        }
        return sb.toString();
    }
    


    /** sfd_tool: 写 misc 引导命令后 NORMAL_RESET */
    public void rebootRecovery() {
        if (!ensureFdl2Ready()) { log("需先加载 FDL2"); return; }
        log("写入 misc → boot-recovery");
        try {
            writeMiscBootCommand(null);
        } catch (Throwable t) {
            log("写 misc 失败: " + t.getMessage() + "，仍尝试 RESET");
        }
        encodeMsg(BslProtocol.CMD_NORMAL_RESET, null, 0);
        sendMsg();
        recvMsgTimeout(2000);
        connected = false;
        log("已请求重启到 Recovery");
    }

    public void rebootFastboot() {
        if (!ensureFdl2Ready()) { log("需先加载 FDL2"); return; }
        log("写入 misc → recovery --fastboot");
        try {
            writeMiscBootCommand("recovery\n--fastboot\n");
        } catch (Throwable t) {
            log("写 misc 失败: " + t.getMessage() + "，仍尝试 RESET");
        }
        encodeMsg(BslProtocol.CMD_NORMAL_RESET, null, 0);
        sendMsg();
        recvMsgTimeout(2000);
        connected = false;
        log("已请求重启到 Fastboot");
    }

    /**
     * 双清：只清 data + cache（不碰 persist / 其他分区）
     * misc: boot-recovery + recovery\n--wipe_data\n--wipe_cache\n → NORMAL_RESET
     */
    public void factoryReset() {
        if (!ensureFdl2Ready()) { log("需先加载 FDL2"); return; }
        log("======== 双清 (data + cache) ========");
        try {
            writeMiscBootCommand("recovery\n--wipe_data\n--wipe_cache\n");
        } catch (Throwable t) {
            log("写 misc 失败: " + t.getMessage());
            return;
        }
        encodeMsg(BslProtocol.CMD_NORMAL_RESET, null, 0);
        sendMsg();
        recvMsgTimeout(2000);
        connected = false;
        log("已请求双清并重启");
    }

    /**
     * 普通重启 —— sfd_tool: 需已进 FDL，发 BSL_CMD_NORMAL_RESET(0x05)
     * 设备收到后会马上断开，不一定回 ACK，超时也算成功。
     */
    public void reset() {
        if (!connected || conn == null) {
            log("未连接设备，无法重启");
            return;
        }
        // 至少需要 FDL1；没有则尝试自动发 FDL
        if (fdlLoaded < 1) {
            log("尚未加载 FDL，尝试自动加载后再重启...");
            if (!ensureFdl2Ready()) {
                log("FDL 未就绪，仍尝试发送 RESET");
            }
        }
        log("发送 NORMAL_RESET (0x05)...");
        encodeMsg(BslProtocol.CMD_NORMAL_RESET, null, 0);
        boolean sent = sendMsg();
        if (!sent) {
            log("RESET 发送失败（USB 已断开？）");
            connected = false;
            return;
        }
        // 等 ACK，超时也视为设备已重启
        int ret = recvMsgTimeout(3000);
        if (ret > 0 && recvType() == BslProtocol.REP_ACK) {
            log("设备已确认重启");
        } else {
            log("设备已重启（无 ACK 属正常，USB 会断开）");
        }
        connected = false;
        try {
            if (conn != null) {
                try { conn.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        conn = null;
        epIn = null;
        epOut = null;
        fdlLoaded = 0;
        log("重启命令完成");
    }

    public void powerOff() {
        if (!connected || conn == null) {
            log("未连接设备");
            return;
        }
        if (fdlLoaded < 1) {
            log("尝试加载 FDL 后关机...");
            ensureFdl2Ready();
        }
        log("发送 POWER_OFF...");
        encodeMsg(BslProtocol.CMD_POWER_OFF, null, 0);
        sendMsg();
        recvMsgTimeout(2000);
        connected = false;
        log("关机命令已发送");
    }


    /**
     * 写 0x800 字节到 misc 分区开头（sfd_tool reboot-* 同款）
     * @param recoveryCmd 放在 offset 0x40，可为 null
     */
    private void writeMiscBootCommand(String recoveryCmd) {
        byte[] miscbuf = new byte[0x800];
        byte[] bootRec = "boot-recovery".getBytes();
        System.arraycopy(bootRec, 0, miscbuf, 0, bootRec.length);
        if (recoveryCmd != null && recoveryCmd.length() > 0) {
            byte[] cmd = recoveryCmd.getBytes();
            System.arraycopy(cmd, 0, miscbuf, 0x40, Math.min(cmd.length, 0x800 - 0x40));
        }
        writeMemToPartitionOffset("misc", 0, miscbuf, 0x800);
    }

    private void writeMemToPartitionOffset(String name, long offset, byte[] src, int length) {
        // 仅写小块到分区开头：START_DATA 长度为写入长度
        long total = offset + length;
        log("写分区偏移 " + name + " @0x" + Long.toHexString(offset) + " len=0x" + Integer.toHexString(length));
        if (!selectPartition(name, total, false, BslProtocol.CMD_START_DATA))
            throw new RuntimeException("select " + name + " 失败");
        int blockSize = 0x1000;
        int pos = 0;
        // 若 offset>0 需要先 pad（简化：只支持 offset==0）
        if (offset != 0) {
            log("警告: 当前仅完整支持 offset=0 的 misc 写入");
        }
        while (pos < length) {
            if (cancelled) throw new RuntimeException("已取消");
            int n = Math.min(length - pos, blockSize);
            encodeMsg(BslProtocol.CMD_MIDST_DATA, src, pos, n);
            if (!sendAndCheck(15000)) throw new RuntimeException("写 " + name + " 失败");
            pos += n;
        }
        encodeMsg(BslProtocol.CMD_END_DATA, null, 0);
        if (!sendAndCheck(10000)) throw new RuntimeException("END_DATA 失败");
        log("写 " + name + " 完成");
    }

    public String readChipUid() {
        encodeMsg(BslProtocol.CMD_READ_CHIP_UID, null, 0); if (!sendMsg()) return null; int ret = recvMsgTimeout(30000); if (ret <= 0 || recvType() != BslProtocol.REP_READ_CHIP_UID) { log("unexpected response 0x" + Integer.toHexString(recvType())); return null; } int uidLen = r16be(rawBuf, 2); String uid = bytesToStr(rawBuf, 4, uidLen); log("CHIP_UID: " + uid); return uid;
    }
    private void t117ExecWorkaround(long addr) {
        int dist = t117ExecDist, n = 252, skip = 3; addr += 0x200;
        if (addr >= 0x40000 || ((addr & 0xFF) - 0x7d < 2) || (((addr >> 8) & 0xFF) - 0x7d < 2)) { log("unsupported exec address"); return; }
        if (dist >> 16 != 0) { log("unsupported dist"); return; }
        if (((dist & 0xFF) - 0x7d) < 2) skip--; if ((((dist >> 8) & 0xFF) - 0x7d) < 2) skip--;
        int p = 0; for (int i = 0; i < skip; i++) tempBuf[p++] = 0; for (int i = 0; i < n; i++, p += 4) w32le(tempBuf, p, addr); if ((skip & 1) != 0) tempBuf[p++] = 0; encodeMsg(dist, tempBuf, 0, p); sendMsg();
    }

    // ===== 命令执行器 =====
    public void execute(String[] args) {
        cancelled = false; int i = 0;
        while (i < args.length) {
            if (cancelled) { log("已取消"); break; }
            String cmd = args[i];
            try {
                if (cmd.equals("fdl")) {
                    if (i + 2 >= args.length) { log("bad fdl"); break; }
                    String fn = args[++i]; long addr = strToAddr(args[++i], "ram", ramAddr);
                    log("加载FDL: " + fn + " -> 0x" + Long.toHexString(addr));
                    if (fdlLoaded == 0) {
                        if (!bootloaderHandshakeWithRetry()) { log("bootloader握手失败"); break; }
                        sendFile(fn, addr, blkSize > 0 ? blkSize : 528);
                        if (t117ExecDist != 0) t117ExecWorkaround(addr);
                        else { encodeMsg(BslProtocol.CMD_EXEC_DATA, null, 0); if (!sendAndCheck(5000)) { log("FDL1执行失败"); break; } }
                        if (!fdl1Handshake()) { log("FDL1握手失败"); break; }
                        if (keepCharge != 0) { encodeMsg(BslProtocol.CMD_KEEP_CHARGE, null, 0); sendAndCheck(3000); }
                    } else {
                        // FDL2模式：增加等待和重试
                        // 始终发送 FDL2
                        {
                            log("发送 FDL2 文件...");
                            sendFile(fn, addr, blkSize > 0 ? blkSize : 528);
                            encodeMsg(BslProtocol.CMD_EXEC_DATA, null, 0);
                            sendMsg();
                            // 延长等待时间让FDL2稳定
                            int ret = recvMsgTimeout(30000);
                            if (ret <= 0) {
                                log("FDL2响应超时，设备可能已重启");
                                // 等待设备重启
                                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                                // 尝试重新连接设备
                                UsbDevice d = waitForBootDevice(20000);
                                if (d != null) {
                                    log("设备重新连接成功，重新握手...");
                                    if (!openDevice(d)) {
                                        log("重新打开设备失败");
                                        break;
                                    }
                                    if (!bootloaderHandshake()) {
                                        log("重新握手失败");
                                        break;
                                    }
                                    log("重新连接成功，可以继续操作");
                                } else {
                                    log("FDL2: 设备未重新连接，请检查USB连接");
                                    break;
                                }
                            } else {
                                ret = recvType();
                                if (ret == BslProtocol.REP_INCOMPATIBLE_PARTITION) {
                                    log("FDL2: incompatible partition - 正常，等待设备稳定...");
                                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                                } else if (ret != BslProtocol.REP_ACK) {
                                    log("FDL2响应异常: 0x" + Integer.toHexString(ret));
                                    break;
                                }
                            }
                            fdl2Executed = 1;
                        }
                    }
                    fdlLoaded++;
                    i++;
                } else if (cmd.equals("keep_charge")) { keepCharge = Integer.parseInt(args[++i]); i++; }
                else if (cmd.equals("blk_size")) { blkSize = (int)parseNumber(args[++i]); blkSize = blkSize < 0 ? 0 : blkSize > 0xFFFF ? 0xFFFF : blkSize; i++; }
                else if (cmd.equals("disable_transcode")) { disableTranscode(); i++; }
                else if (cmd.equals("transcode")) { setTranscode(Integer.parseInt(args[++i])); i++; }
                else if (cmd.equals("reset")) { reset(); i++; }
                else if (cmd.equals("factory_reset")) { factoryReset(); i++; }
                else if (cmd.equals("reboot-recovery")) { rebootRecovery(); i++; }
                else if (cmd.equals("reboot-fastboot")) { rebootFastboot(); i++; }
                else if (cmd.equals("power_off")) { powerOff(); i++; }
                else if (cmd.equals("chip_uid")) { readChipUid(); i++; }
                else if (cmd.equals("timeout")) { setTimeout(Integer.parseInt(args[++i])); i++; }
                else if (cmd.equals("end_data")) { setEndData(Integer.parseInt(args[++i])); i++; }
                else if (cmd.equals("verbose")) { setVerbose(Integer.parseInt(args[++i])); i++; }
                else if (cmd.equals("t117_exec_dist")) { setT117ExecDist(Integer.parseInt(args[++i])); i++; }
                else if (cmd.equals("read_flash")) {
                    if (i + 4 >= args.length) { log("bad read_flash"); break; }
                    long addr = parseNumber(args[++i]);
                    long offset = parseNumber(args[++i]);
                    long size;
                    int mode = 0;
                    if (offset == 0 && i + 1 < args.length && args[i + 1].equals("auto")) {
                        mode = 1;
                        size = 0;
                        i++;
                    } else {
                        size = parseNumber(args[++i]);
                    }
                    String fn = args[++i];
                    String savePath = getSaveDir() + File.separator + fn;
                    log("读Flash: 0x" + Long.toHexString(addr) + " offset=0x" + Long.toHexString(offset) + " size=0x" + Long.toHexString(size) + " -> " + savePath);
                    resetSpeedCounter();
                    int result = dumpFlash(addr, offset, size, fn, blkSize > 0 ? blkSize : 4096, mode);
                    log("读取完成: " + result + " bytes");
                    i++;
                }
                else if (cmd.equals("read_mem")) {
                    if (i + 3 >= args.length) { log("bad read_mem"); break; }
                    long addr = parseNumber(args[++i]);
                    long size = parseNumber(args[++i]);
                    String fn = args[++i];
                    String savePath = getSaveDir() + File.separator + fn;
                    log("读内存: 0x" + Long.toHexString(addr) + " size=0x" + Long.toHexString(size) + " -> " + savePath);
                    resetSpeedCounter();
                    int result = dumpMem(addr, size, fn, blkSize > 0 ? blkSize : 4096);
                    log("读取完成: " + result + " bytes");
                    i++;
                }
                else if (cmd.equals("read_part")) {
                    if (i + 4 >= args.length) { log("bad read_part"); break; }
                    String name = args[++i];
                    long offset = parseNumber(args[++i]);
                    long size;
                    String sizeStr = args[++i];
                    String fn = args[++i];
                    // 支持 auto 模式自动获取分区大小
                    if ("auto".equalsIgnoreCase(sizeStr)) {
                        size = 0; // 标记为自动获取
                        log("读分区: " + name + " offset=0x" + Long.toHexString(offset) + " size=auto -> " + fn);
                    } else {
                        size = parseNumber(sizeStr);
                        log("读分区: " + name + " offset=0x" + Long.toHexString(offset) + " size=0x" + Long.toHexString(size) + " -> " + fn);
                    }
                    // 检查连接状态，如果断开则尝试重新连接
                    if (!connected || conn == null) {
                        log("设备未连接，尝试重新连接...");
                        UsbDevice d = waitForBootDevice(15000);
                        if (d == null) {
                            log("无法重新连接设备");
                            break;
                        }
                        if (!openDevice(d)) {
                            log("打开设备失败");
                            break;
                        }
                        if (!bootloaderHandshake()) {
                            log("重新握手失败");
                            break;
                        }
                        log("重新连接成功");
                    }
                    if (!ensureFdl2Ready()) break;
                    resetSpeedCounter();
                    int step = blkSize > 0 ? blkSize : 4096;
                    if (step < 256) step = 256;
                    int result = dumpPartition(name, offset, size, fn, step);
                    log("读取完成: " + result + " bytes (" + formatSize(result) + ")");
                    i++;
                }
                else if (cmd.equals("write_part")) {
                    if (i + 2 >= args.length) { log("bad write_part"); break; }
                    String name = args[++i];
                    String fn = args[++i];
                    // 检查连接状态
                    if (!connected || conn == null) {
                        log("设备未连接，尝试重新连接...");
                        UsbDevice d = waitForBootDevice(15000);
                        if (d == null) {
                            log("无法重新连接设备");
                            break;
                        }
                        if (!openDevice(d)) {
                            log("打开设备失败");
                            break;
                        }
                        if (!bootloaderHandshake()) {
                            log("重新握手失败");
                            break;
                        }
                        log("重新连接成功");
                    }
                    if (!ensureFdl2Ready()) break;
                    // sfd_tool: 直接 write_part，不先 erase（erase 会弄挂很多机型）
                    log("写入分区: " + name + "（不擦除，直接刷入）");
                    int step = blkSize > 0 ? blkSize : 4096;
                    if (step < 512) step = 512;
                    if (step > 65535) step = 65535;
                    loadPartition(name, fn, step);
                    i++;
                }
                else if (cmd.equals("write_word")) { if (i + 2 >= args.length) { log("bad write_word"); break; } long addr = strToAddr(args[++i], "fw", fwAddr); long data = parseNumber(args[++i]); writeWord(addr, data); i++; }
                else if (cmd.equals("write_data")) { if (i + 4 >= args.length) { log("bad write_data"); break; } long addr = strToAddr(args[++i], "fw", fwAddr); int srcOffs = (int)parseNumber(args[++i]); int srcSize = (int)parseNumber(args[++i]); String fn = args[++i]; writeData(fn, addr, srcOffs, srcSize); i++; }
                else if (cmd.equals("erase_flash")) { if (i + 2 >= args.length) { log("bad erase_flash"); break; } long addr = strToAddr(args[++i], "fw", fwAddr); long size = parseNumber(args[++i]); eraseFlash(addr, size); i++; }
                else if (cmd.equals("erase_part")) { if (i + 1 >= args.length) { log("bad erase_part"); break; } if (!ensureFdl2Ready()) break; erasePartition(args[++i]); i++; }
                else if (cmd.equals("partition_list")) { if (i + 1 >= args.length) { log("bad partition_list"); break; } partitionList(args[++i], 10); i++; }
                else if (cmd.equals("partition_list_4k")) { if (i + 1 >= args.length) { log("bad partition_list_4k"); break; } partitionList(args[++i], 7); i++; }
                else if (cmd.equals("repartition")) { if (i + 1 >= args.length) { log("bad repartition"); break; } repartition(args[++i]); i++; }
                else if (cmd.equals("part_size")) { if (i + 1 >= args.length) { log("bad part_size"); break; } if (!ensureFdl2Ready()) break; findPartitionSize(args[++i]); i++; }
                else if (cmd.equals("read_pactime")) { readPactime(); i++; }
                else if (cmd.equals("read_efuse")) { if (i + 1 >= args.length) { log("bad read_efuse"); break; } dumpEfuse(args[++i]); i++; }
                else { log("未知命令: " + cmd); break; }
            } catch (Exception e) { log("命令异常: " + e.getMessage()); break; }
        }
        if (callback != null) callback.onFinished(!cancelled);
    }

    // ===== 工具方法 =====
    private static long parseNumber(String s) {
        s = s.trim().toLowerCase(); int shl = 0;
        if (s.startsWith("0x")) return Long.parseLong(s.substring(2), 16);
        if (s.endsWith("k")) { shl = 10; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { shl = 20; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("g")) { shl = 30; s = s.substring(0, s.length() - 1); }
        return Long.parseLong(s.trim()) << shl;
    }
    private long strToAddr(String str, String name, long base) {
        int i = 0;
        while (i < name.length() && i < str.length() && name.charAt(i) == str.charAt(i)) i++;
        if (i == name.length()) {
            str = str.substring(i); if (base == ~0L) { log(name + " address unknown"); return 0; }
            if (str.isEmpty()) return base; if (str.charAt(0) != '+') { log("bad args"); return 0; }
            long ret = parseNumber(str.substring(1)); if (ret >> 26 != 0) { log("address limit"); return 0; } return ret + base;
        } return parseNumber(str);
    }
    private static String bytesToStr(byte[] b, int off, int len) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < len && off + i < b.length; i++) { char c = (char)(b[off + i] & 0xFF); if (c >= 32 && c < 127) sb.append(c); else sb.append('.'); } return sb.toString(); }
    private static boolean startsWith(byte[] b, int off, String s) { for (int i = 0; i < s.length(); i++) if (off + i >= b.length || (b[off + i] & 0xFF) != s.charAt(i)) return false; return true; }
    private static String toHex(byte[] b, int off, int n) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < n && off + i < b.length; i++) sb.append(String.format("%02x ", b[off + i] & 0xFF)); return sb.toString().trim(); }
    private void log(String msg) { if (callback != null) callback.onLog(msg); }
    private void progress(int p, String op) { if (callback != null) callback.onProgress(p, op); }

    /**
     * PAC 刷写：从 PAC 文件直接提取固件并刷写分区
     */
    public void flashPac(String pacPath) {
        if (busy) { log("正在执行操作哦~"); return; }
        if (pacPath == null || pacPath.isEmpty()) { log("PAC文件路径为空"); return; }
        setBusy(true);
        try {
            java.io.File pacFile = new java.io.File(pacPath);
            if (!pacFile.exists()) { log("PAC文件不存在: " + pacPath); return; }
            
            log("=== PAC刷写开始 ===");
            log("PAC文件: " + pacPath);
            
            // 1. 解析PAC获取分区信息
            log("步骤1: 解析PAC文件...");
            java.util.List<PacPartition> partitions = parsePacFile(pacPath);
            if (partitions.isEmpty()) { log("无法解析PAC分区信息"); return; }
            log("找到 " + partitions.size() + " 个分区");
            
            // 2. 创建工作目录提取固件
            java.io.File workDir = new java.io.File(getSaveDir(), "pac_extract");
            if (!workDir.exists()) { workDir.mkdirs(); }
            log("工作目录: " + workDir.getAbsolutePath());
            
            // 3. 提取固件文件
            log("步骤2: 提取固件文件...");
            java.util.Map<String, java.io.File> fileMap = extractFilesFromPac(pacPath, workDir);
            log("提取完成: " + fileMap.size() + " 个文件");
            
            // 4. 加载FDL1
            log("步骤3: 加载FDL1...");
            if (!bootloaderHandshakeWithRetry()) { log("bootloader握手失败"); return; }
            
            String fdl1File = "fdl1-sign.bin";
            for (PacPartition part : partitions) {
                if (part.name != null && part.name.toLowerCase().contains("fdl1")) {
                    fdl1File = part.file;
                    break;
                }
            }
            
            java.io.File fdl1Bin = fileMap.get(fdl1File);
            if (fdl1Bin == null || !fdl1Bin.exists()) {
                fdl1Bin = new java.io.File(getSaveDir(), fdl1File);
            }
            
            if (fdl1Bin != null && fdl1Bin.exists()) {
                log("加载FDL1: " + fdl1Bin.getName());
                sendFile(fdl1Bin.getName(), ADDR_FDL1, 528);
                encodeMsg(BslProtocol.CMD_EXEC_DATA, null, 0);
                if (!sendAndCheck(5000)) { log("FDL1执行失败"); return; }
                if (!fdl1Handshake()) { log("FDL1握手失败"); return; }
                log("FDL1加载成功");
            } else {
                log("警告: FDL1文件不存在，尝试继续...");
            }
            
            // 5. 刷写分区（尊重勾选：若 currentPartitions 有记录则只刷 selected）
            log("步骤4: 开始刷写分区...");
            int success = 0, failed = 0;
            for (PacPartition part : partitions) {
                if (cancelled) { log("用户取消"); break; }
                if (part.size <= 0 || part.file.isEmpty()) continue;
                // FDL 只用于加载，不要当分区刷入
                String pn = part.name != null ? part.name.toLowerCase() : "";
                String pf = part.file != null ? part.file.toLowerCase() : "";
                if (pn.contains("fdl") || pf.contains("fdl") || pn.equals("uboot") && pf.contains("fdl")) {
                    log("跳过 FDL 文件(仅加载用，不刷入): " + part.name);
                    continue;
                }
                // 分区勾选过滤
                if (currentPartitions != null && !currentPartitions.isEmpty()) {
                    boolean found = false;
                    boolean want = true;
                    for (PartitionInfo pi : currentPartitions) {
                        if (pi.name != null && pi.name.equalsIgnoreCase(part.name)) {
                            found = true;
                            want = pi.selected;
                            break;
                        }
                    }
                    if (found && !want) {
                        log("跳过未勾选分区: " + part.name);
                        continue;
                    }
                }
                
                java.io.File binFile = fileMap.get(part.file);
                if (binFile == null || !binFile.exists()) {
                    log("跳过: " + part.name + " (文件不存在: " + part.file + ")");
                    continue;
                }
                
                log("刷写分区: " + part.name + " (" + part.file + ", " + formatSize(part.size) + ")");
                try {
                    erasePartition(part.name);
                    loadPartition(part.name, binFile.getAbsolutePath(), 4096);
                    success++;
                    log("分区 " + part.name + " 刷写成功");
                } catch (Exception e) {
                    log("分区 " + part.name + " 刷写失败: " + e.getMessage());
                    failed++;
                }
            }
            
            log("PAC刷写完成: 成功 " + success + ", 失败 " + failed);
            
            // 6. 清理临时文件
            deleteRecursive(workDir);
            log("临时文件已清理");
            
        } catch (Exception e) {
            log("PAC刷写异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            setBusy(false);
            log("flashPac: 操作完成");
        }
    }
    
    /**
     * 从PAC提取固件文件到工作目录
     */
    private java.util.Map<String, java.io.File> extractFilesFromPac(String pacPath, java.io.File workDir) {
        java.util.Map<String, java.io.File> fileMap = new java.util.HashMap<>();
        try {
            java.io.File pacFile = new java.io.File(pacPath);
            long fileSize = pacFile.length();
            byte[] buf = new byte[(int)Math.min(fileSize, 200 * 1024 * 1024)];
            java.io.FileInputStream fis = new java.io.FileInputStream(pacFile);
            int bytesRead = fis.read(buf);
            fis.close();
            
            if (bytesRead < 2124) return fileMap;
            
            int fileCount = (int) r32le(buf, 0x434);
            log("FILE_T条目数量: " + fileCount);
            
            int dirOffset = 2124;
            for (int i = 0; i < fileCount && dirOffset + 2580 <= bytesRead; i++) {
                int sizeHigh = (int) r32le(buf, dirOffset + 0x5FC);
                int offsetHigh = (int) r32le(buf, dirOffset + 0x600);
                int size = (int) r32le(buf, dirOffset + 0x604);
                int type = (int) r32le(buf, dirOffset + 0x608);
                int pacOffset = (int) r32le(buf, dirOffset + 0x610);
                
                if (type != 1 || size <= 0 || pacOffset <= 0) {
                    dirOffset += 2580;
                    continue;
                }
                
                long fullSize = ((long)sizeHigh << 32) | (size & 0xFFFFFFFFL);
                long fullOffset = ((long)offsetHigh << 32) | ((long)pacOffset & 0xFFFFFFFFL);
                
                if (fullOffset + fullSize > bytesRead) {
                    dirOffset += 2580;
                    continue;
                }
                
                String name = u16toString(buf, dirOffset + 0x204, 256);
                if (name.isEmpty()) {
                    dirOffset += 2580;
                    continue;
                }
                
                log("提取: " + name + " (" + fullSize + " bytes)");
                
                java.io.File outFile = new java.io.File(workDir, name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                long remaining = fullSize;
                long currentOffset = fullOffset;
                
                while (remaining > 0 && currentOffset + 1048576 <= bytesRead) {
                    int n = (int)Math.min(1048576, remaining);
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, (int)currentOffset, chunk, 0, n);
                    fos.write(chunk);
                    currentOffset += n;
                    remaining -= n;
                }
                fos.close();
                
                fileMap.put(name, outFile);
                dirOffset += 2580;
            }
            
            log("成功提取 " + fileMap.size() + " 个固件文件");
            
        } catch (Exception e) {
            log("提取固件失败: " + e.getMessage());
            e.printStackTrace();
        }
        return fileMap;
    }
    
    /**
     * 递归删除目录
     */
    private void deleteRecursive(java.io.File f) {
        if (f.isDirectory()) {
            for (java.io.File child : f.listFiles()) {
                deleteRecursive(child);
            }
        }
        f.delete();
    }

    // ===== 批量刷写 =====
    public void batchFlash(String dir, String xmlPath) {
        java.io.File dirFile = new java.io.File(dir);
        java.io.File[] files = dirFile.listFiles();
        if (files == null || files.length == 0) { log("目录为空: " + dir); return; }

        // 解析分区XML
        java.util.List<String> partitions = parsePartitionXml(xmlPath);
        if (partitions.isEmpty()) { log("无法解析分区表: " + xmlPath); return; }

        log("分区表包含 " + partitions.size() + " 个分区");
        log("目录包含 " + files.length + " 个文件");

        int matched = 0, skipped = 0;
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName().replace(".bin", "");
            // 尝试匹配分区名
            for (String part : partitions) {
                if (name.equalsIgnoreCase(part) || part.equalsIgnoreCase(name)) {
                    log("匹配: " + name + " -> " + part);
                    try {
                        erasePartition(part);
                        loadPartition(part, f.getAbsolutePath(), 4096);
                        matched++;
                    } catch (Exception e) {
                        log("刷写 " + part + " 失败: " + e.getMessage());
                    }
                    break;
                }
            }
            skipped++;
        }
        log("批量刷写完成: 成功" + matched + ", 跳过" + (files.length - matched) + ", 忽略" + (skipped - files.length + matched));
    }

    private java.util.List<String> parsePartitionXml(String xmlPath) {
        java.util.List<String> parts = new java.util.ArrayList<String>();
        java.io.File f = new java.io.File(xmlPath);
        if (!f.exists()) { log("分区表文件不存在: " + xmlPath); return parts; }
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int)f.length()];
            fis.read(buf);
            fis.close();
            String xml = new String(buf, "UTF-8");
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("Partition\\s+id\\s*=\\s*\"([^\"]+)\"");
            java.util.regex.Matcher m = pat.matcher(xml);
            while (m.find()) parts.add(m.group(1));
            log("解析分区表: " + parts.size() + " 个分区");
        } catch (Exception e) { log("解析分区表失败: " + e.getMessage()); }
        return parts;
    }

    /**
     * 设置手动分区表
     */
    public void setManualPartitions(java.util.List<PartitionInfo> parts) {
        currentPartitions.clear();
        if (parts != null) {
            for (PartitionInfo p : parts) {
                PartitionInfo pi = new PartitionInfo();
                pi.name = p.name;
                pi.size = p.size;
                pi.selected = p.selected;
                currentPartitions.add(pi);
            }
        }
        log("手动分区表已设置: " + currentPartitions.size() + " 个分区");
    }
}

