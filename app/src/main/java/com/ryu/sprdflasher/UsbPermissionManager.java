package com.ryu.sprdflasher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;

/**
 * 甲壳虫ADB风格 USB权限管理器 - AIDE兼容版
 * 
 * 特性:
 * 1. Application级别生命周期管理，不依赖Activity
 * 2. Android 12+ 使用 FLAG_MUTABLE PendingIntent
 * 3. 自动检测设备插入并请求权限
 * 4. 支持权限持久化回调
 * 5. 单例模式，全局统一管理
 * 
 * AIDE兼容: 使用数字常量替代高版本API符号
 * 
 * @author ryu (refactored)
 */
public class UsbPermissionManager {
    private static final String ACTION_USB_PERMISSION = "com.ryu.sprdflasher.USB_PERMISSION";
    private static final int VID_SPREADTRUM = 0x1782;
    private static final int PID_BOOT = 0x4d00;
    private static final int PID_CALI = 0x4d01;
    private static final int PID_DL = 0x4d02;

    // AIDE兼容: 硬编码高版本API常量
    private static final int API_TIRAMISU = 33;  // Build.VERSION_CODES.TIRAMISU
    private static final int RECEIVER_EXPORTED = 0x00000002;  // Context.RECEIVER_EXPORTED

    private static UsbPermissionManager instance;
    private final Context appContext;
    private final UsbManager usbManager;
    private final Handler mainHandler;
    private PermissionCallback callback;
    private boolean isRegistered = false;

    public interface PermissionCallback {
        void onPermissionGranted(UsbDevice device);
        void onPermissionDenied(UsbDevice device);
        void onDeviceAttached(UsbDevice device);
        void onDeviceDetached(UsbDevice device);
    }

    private UsbPermissionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.usbManager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized UsbPermissionManager getInstance(Context context) {
        if (instance == null) {
            instance = new UsbPermissionManager(context);
        }
        return instance;
    }

    public void setCallback(PermissionCallback cb) {
        this.callback = cb;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = getUsbDevice(intent);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (device != null) {
                        if (granted) {
                            if (callback != null) {
                                final android.hardware.usb.UsbDevice dev = device;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        callback.onPermissionGranted(dev);
                                    }
                                });
                            }
                        } else {
                            if (callback != null) {
                                final android.hardware.usb.UsbDevice dev = device;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        callback.onPermissionDenied(dev);
                                    }
                                });
                            }
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                if (device != null && isSpreadtrum(device)) {
                    if (callback != null) {
                        final android.hardware.usb.UsbDevice dev = device;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onDeviceAttached(dev);
                            }
                        });
                    }
                    if (!usbManager.hasPermission(device)) {
                        requestPermission(device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                if (device != null && isSpreadtrum(device)) {
                    if (callback != null) {
                        final android.hardware.usb.UsbDevice dev = device;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onDeviceDetached(dev);
                            }
                        });
                    }
                }
            }
        }
    };

    public void register() {
        if (isRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        // AIDE兼容: 使用数字常量判断Android 13+
        try {
            if (Build.VERSION.SDK_INT >= API_TIRAMISU) {
                // RECEIVER_NOT_EXPORTED = 0x4，应用内广播更安全
                appContext.registerReceiver(usbReceiver, filter, 0x4);
            } else {
                appContext.registerReceiver(usbReceiver, filter);
            }
            isRegistered = true;
        } catch (Throwable e) {
            try {
                appContext.registerReceiver(usbReceiver, filter);
                isRegistered = true;
            } catch (Throwable e2) {
                isRegistered = false;
            }
        }
    }

    public void unregister() {
        if (!isRegistered) return;
        try {
            appContext.unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            // ignore
        }
        isRegistered = false;
    }

    /**
     * 请求USB权限 - 甲壳虫ADB风格
     * Android 12+ 必须使用 FLAG_MUTABLE，系统需要填充 EXTRA_PERMISSION_GRANTED
     */
    public void requestPermission(UsbDevice device) {
        if (device == null || usbManager == null) return;
        if (usbManager.hasPermission(device)) {
            if (callback != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onPermissionGranted(device);
                    }
                });
            }
            return;
        }

        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(appContext.getPackageName());
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);

        int flags;
        // AIDE兼容: Build.VERSION_CODES.S = 31
        if (Build.VERSION.SDK_INT >= 31) {
            flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags);
        usbManager.requestPermission(device, pendingIntent);
    }

    public UsbDevice findSpreadtrumDevice() {
        HashMap<String, UsbDevice> list = usbManager.getDeviceList();
        for (UsbDevice dev : list.values()) {
            if (isSpreadtrum(dev)) {
                return dev;
            }
        }
        return null;
    }

    public boolean hasPermission(UsbDevice device) {
        return usbManager.hasPermission(device);
    }


    private UsbDevice getUsbDevice(Intent intent) {
        if (intent == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= API_TIRAMISU) {
                try {
                    java.lang.reflect.Method m = Intent.class.getMethod(
                        "getParcelableExtra", String.class, Class.class);
                    Object o = m.invoke(intent, UsbManager.EXTRA_DEVICE, UsbDevice.class);
                    if (o instanceof UsbDevice) return (UsbDevice) o;
                } catch (Throwable ignored) {}
            }
            Object o = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (o instanceof UsbDevice) return (UsbDevice) o;
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isSpreadtrum(UsbDevice dev) {
        return dev.getVendorId() == VID_SPREADTRUM &&
               (dev.getProductId() == PID_BOOT || dev.getProductId() == PID_CALI || dev.getProductId() == PID_DL);
    }

    public boolean isSpreadtrumDevice(UsbDevice dev) {
        return isSpreadtrum(dev);
    }
}
