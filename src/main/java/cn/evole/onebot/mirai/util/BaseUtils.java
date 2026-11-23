package cn.evole.onebot.mirai.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Description: 通用基础工具
 * Author: cnlimiter
 * Date: 2022/10/11 18:46
 * Version: 1.0
 */
public class BaseUtils {

    private static final ExecutorService SHARED_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "OneBot-AsyncTask");
        t.setDaemon(true);
        return t;
    });

    public static void safeRun(Runnable runnable) {
        SHARED_EXECUTOR.execute(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                PluginRuntime.getLogger().warning("后台任务执行异常", t);
            }
        });
    }

    public static <T> T safeRun(Callable<T> callable, T defaultValue) {
        try {
            return callable.call();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    public static <T> List<T> copyIterator(Iterator<T> iter) {
        List<T> copy = new ArrayList<>();
        while (iter.hasNext()) {
            copy.add(iter.next());
        }
        return copy;
    }

    public static File getDataFile(String type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        File miraiDataDir = new File(PluginRuntime.getDataFolder(), type);
        File miraiFile = new File(miraiDataDir, name).getAbsoluteFile();
        if (miraiFile.exists()) {
            return miraiFile;
        }
        File localFile = new File("data" + File.separatorChar + type + File.separatorChar + name).getAbsoluteFile();
        if (localFile.exists()) {
            return localFile;
        }
        String libPath = System.getProperty("java.library.path");
        if (libPath != null && !libPath.isEmpty()) {
            String firstPath = libPath.split(File.pathSeparator)[0];
            File sysFile = new File(firstPath + File.separatorChar + "data" + File.separatorChar + type + File.separatorChar + name).getAbsoluteFile();
            if (sysFile.exists()) {
                return sysFile;
            }
        }
        return null;
    }

    /**
     * 字节数组转大写十六进制字符串
     */
    public static String bytesToHexString(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        final char[] hexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String bytesToString(byte[] str) {
        String keyword = null;
        try {
            keyword = new String(str, "GBK");
        } catch (UnsupportedEncodingException e) {
            PluginRuntime.getLogger().warning("字节转字符串失败", e);
        }
        return keyword;
    }

    public static int byteToInt(byte[] b) {
        int s0 = b[0] & 0xff;
        int s1 = b[1] & 0xff;
        int s2 = b[2] & 0xff;
        int s3 = b[3] & 0xff;
        s3 <<= 24;
        s2 <<= 16;
        s1 <<= 8;
        return s0 | s1 | s2 | s3;
    }

    /**
     * 计算标准 OneBot v11 HTTP POST 上报使用的 HMAC-SHA1 小写十六进制签名。
     */
    public static String hmacSha1Hex(String plainText, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            mac.init(keySpec);
            byte[] raw = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return bytesToHexString(raw).toLowerCase(Locale.ROOT);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HMAC-SHA1 签名计算失败", e);
        }
    }

    public static String getSha(String plainText, String secret, String algorithm, boolean uppercase) {
        byte[] bytes = (plainText + secret).getBytes(StandardCharsets.UTF_8);
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance(algorithm);
            messageDigest.update(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA签名过程中出现错误,算法异常", e);
        }
        byte[] digest = messageDigest.digest();
        String result = bytesToHexString(digest);
        return uppercase ? result.toUpperCase(Locale.ROOT) : result.toLowerCase(Locale.ROOT);
    }
}
