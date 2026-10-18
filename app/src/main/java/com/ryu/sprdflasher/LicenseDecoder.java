package com.ryu.sprdflasher;

import java.security.MessageDigest;
import android.util.Base64;

public class LicenseDecoder {

    private static final String LICENSE_KEY = "SprdFlasherKey2025!@#更强密钥";
    private static final long TIME_WINDOW_MS = 3 * 60 * 1000L;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java -jar LicenseDecoder.jar <卡密>");
            System.out.println();
            System.out.println("示例: java -jar LicenseDecoder.jar YrxbmrEcTg6m6kJAWguylyfgSpuoQBZp+bAcEQpA9Nx/u0KD9BRNBq7oQHiMubU=");
            return;
        }

        String code = args[0].trim();
        decodeLicense(code);
    }

    public static void decodeLicense(String encryptedCode) {
        try {
            byte[] data = Base64.decode(encryptedCode, Base64.DEFAULT);

            if (data.length <= 4) {
                System.out.println("错误: 数据太短");
                return;
            }

            byte[] encrypted = new byte[data.length - 4];
            System.arraycopy(data, 0, encrypted, 0, encrypted.length);

            int storedChecksum = ((data[data.length - 4] & 0xFF) << 24) |
                                  ((data[data.length - 3] & 0xFF) << 16) |
                                  ((data[data.length - 2] & 0xFF) << 8) |
                                  (data[data.length - 1] & 0xFF);

            int calculatedChecksum = 0;
            for (byte b : encrypted) {
                calculatedChecksum = (calculatedChecksum * 31 + (b & 0xFF)) & 0xFFFFFFFF;
            }

            if (storedChecksum != calculatedChecksum) {
                System.out.println("错误: 校验失败 (存储:" + storedChecksum + " 计算:" + calculatedChecksum + ")");
                return;
            }

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyFull = sha256.digest(LICENSE_KEY.getBytes("UTF-8"));
            byte[] key = new byte[16];
            System.arraycopy(keyFull, 0, key, 0, 16);

            byte[] decrypted = new byte[encrypted.length];
            for (int i = 0; i < encrypted.length; i++) {
                decrypted[i] = (byte) (encrypted[i] ^ key[i % key.length]);
            }

            String plainText = new String(decrypted, "UTF-8");
            String[] parts = plainText.split("\\|");

            if (parts.length < 4) {
                System.out.println("错误: 格式不正确 (部分数量: " + parts.length + ")");
                return;
            }

            String type = parts[0];
            long expireTime = Long.parseLong(parts[1]);
            String fingerprint = parts[2];
            long storedTimeWindow = Long.parseLong(parts[3]);
            long currentTimeWindow = System.currentTimeMillis() / TIME_WINDOW_MS;

            long expireDate = new java.util.Date(expireTime).getTime();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String expireStr = sdf.format(new java.util.Date(expireTime));
            String createTimeStr = sdf.format(new java.util.Date(storedTimeWindow * TIME_WINDOW_MS));

            boolean timeValid = Math.abs(currentTimeWindow - storedTimeWindow) <= 1;

            System.out.println("========================================");
            System.out.println("      卡密解密结果");
            System.out.println("========================================");
            System.out.println();
            System.out.println("【类型】" + type);
            System.out.println("【过期时间】" + expireStr);
            System.out.println("【设备指纹】" + fingerprint);
            System.out.println("【生成时间窗口】" + createTimeStr);
            System.out.println("【当前时间窗口】" + sdf.format(new java.util.Date(currentTimeWindow * TIME_WINDOW_MS)));
            System.out.println();
            System.out.println("【校验状态】" + (timeValid ? "✅ 时间窗口有效" : "❌ 时间窗口已过期"));
            System.out.println();
            System.out.println("原始数据: " + plainText);
            System.out.println("========================================");

        } catch (Exception e) {
            System.out.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
