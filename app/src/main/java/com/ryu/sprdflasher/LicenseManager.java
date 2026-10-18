package com.ryu.sprdflasher;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.MessageDigest;
import android.util.Base64;

public class LicenseManager {
    
    private static final String PREFS_NAME = "sprd_license";
    private static final String KEY_LICENSE_DATA = "license_data";
    private static final String KEY_DEVICE_ID = "device_id";
    
    private static final String LICENSE_KEY = "SprdFlasherKey2025!@#DynamicID";
    
    public static String generateLicense(String type, long expireTime, String deviceId) {
        try {
            String data = type + "|" + expireTime + "|" + deviceId;
            return encrypt(data);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public static LicenseInfo verifyLicense(String code, String deviceId) {
        try {
            String decrypted = decrypt(code);
            if (decrypted == null) return null;
            
            String[] parts = decrypted.split("\\|");
            if (parts.length < 3) return null;
            
            String type = parts[0];
            long expireTime = Long.parseLong(parts[1]);
            String storedDeviceId = parts[2];
            
            if ("time".equals(type) && System.currentTimeMillis() > expireTime) {
                return new LicenseInfo(null, false, "卡密已过期");
            }
            
            if (!storedDeviceId.equals(deviceId)) {
                return new LicenseInfo(null, false, "此卡密绑定其他设备");
            }
            
            return new LicenseInfo(type, true, "授权成功");
            
        } catch (Exception e) {
            return new LicenseInfo(null, false, "卡密无效");
        }
    }
    
    public static void saveLicense(Context ctx, String code, String deviceId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_LICENSE_DATA, code)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply();
    }
    
    public static boolean isLicensed(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String storedCode = prefs.getString(KEY_LICENSE_DATA, "");
        String storedDeviceId = prefs.getString(KEY_DEVICE_ID, "");
        
        if (storedCode.isEmpty()) return false;
        
        LicenseInfo info = verifyLicense(storedCode, storedDeviceId);
        return info != null && info.is_valid;
    }
    
    public static void clearLicense(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }
    
    private static String encrypt(String plainText) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] key = md.digest(LICENSE_KEY.getBytes("UTF-8"));
            
            byte[] text = plainText.getBytes("UTF-8");
            byte[] encrypted = new byte[text.length];
            for (int i = 0; i < text.length; i++) {
                encrypted[i] = (byte) (text[i] ^ key[i % key.length]);
            }
            
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static String decrypt(String encryptedText) {
        try {
            byte[] encrypted = Base64.decode(encryptedText, Base64.DEFAULT);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] key = md.digest(LICENSE_KEY.getBytes("UTF-8"));
            
            byte[] decrypted = new byte[encrypted.length];
            for (int i = 0; i < encrypted.length; i++) {
                decrypted[i] = (byte) (encrypted[i] ^ key[i % key.length]);
            }
            
            return new String(decrypted, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
    
    public static class LicenseInfo {
        public String type;
        public boolean is_valid;
        public String message;
        
        public LicenseInfo(String type, boolean isValid, String message) {
            this.type = type;
            this.is_valid = isValid;
            this.message = message;
        }
    }
}