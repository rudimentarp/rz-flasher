package com.ryu.sprdflasher;

import java.security.MessageDigest;
import android.util.Base64;

public class LicenseGenerator {

    private static final String LICENSE_KEY = "SprdFlasherKey2025!@#更强密钥";
    private static final long TIME_WINDOW_MS = 3 * 60 * 1000L;

    public static final int DAY_1 = 1;
    public static final int DAY_3 = 3;
    public static final int WEEK_1 = 7;
    public static final int MONTH_1 = 30;
    public static final int MONTH_3 = 90;
    public static final int PERMANENT = 0;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java LicenseGenerator <动态ID> [天数]");
            System.out.println();
            System.out.println("示例:");
            System.out.println("  java LicenseGenerator abc123def456");
            System.out.println("  java LicenseGenerator abc123def456 7");
            System.out.println();
            System.out.println("天数选项: 1, 3, 7, 30, 90, 0(永久)");
            return;
        }

        String dynamicId = args[0];
        int days = (args.length > 1) ? Integer.parseInt(args[1]) : 0;

        long now = System.currentTimeMillis();
        long timeWindow = now / TIME_WINDOW_MS;
        long expireTime;
        String type;

        if (days == 0) {
            type = "permanent";
            expireTime = 0;
        } else {
            type = "time";
            expireTime = now + (long) days * 24 * 60 * 60 * 1000;
        }

        String code = generateLicense(type, expireTime, dynamicId, timeWindow);
        System.out.println("=== 卡密生成结果 ===");
        System.out.println("类型: " + type);
        System.out.println("动态ID: " + dynamicId);
        System.out.println("时间窗口: " + timeWindow);
        if (expireTime > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            System.out.println("过期时间: " + sdf.format(new java.util.Date(expireTime)));
        } else {
            System.out.println("过期时间: 永久");
        }
        System.out.println();
        System.out.println("卡密:");
        System.out.println(code);
    }

    public static String generateLicense(String type, long expireTimeMillis, String dynamicId, long timeWindow) {
        String data = type + "|" + expireTimeMillis + "|" + dynamicId + "|" + timeWindow;
        return encrypt(data);
    }

    private static String encrypt(String plainText) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyFull = sha256.digest(LICENSE_KEY.getBytes("UTF-8"));
            byte[] key = new byte[16];
            System.arraycopy(keyFull, 0, key, 0, 16);

            byte[] text = plainText.getBytes("UTF-8");
            byte[] encrypted = new byte[text.length];
            for (int i = 0; i < text.length; i++) {
                encrypted[i] = (byte) (text[i] ^ key[i % key.length]);
            }

            int checksum = 0;
            for (byte b : encrypted) {
                checksum = (checksum * 31 + (b & 0xFF)) & 0xFFFFFFFF;
            }

            byte[] withChecksum = new byte[encrypted.length + 4];
            System.arraycopy(encrypted, 0, withChecksum, 0, encrypted.length);
            withChecksum[withChecksum.length - 4] = (byte) (checksum >> 24);
            withChecksum[withChecksum.length - 3] = (byte) (checksum >> 16);
            withChecksum[withChecksum.length - 2] = (byte) (checksum >> 8);
            withChecksum[withChecksum.length - 1] = (byte) checksum;

            return Base64.encodeToString(withChecksum, Base64.NO_WRAP);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}