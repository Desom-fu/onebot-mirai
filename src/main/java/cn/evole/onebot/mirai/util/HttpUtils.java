package cn.evole.onebot.mirai.util;

import net.mamoe.mirai.utils.MiraiLogger;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 客户端与文件下载工具。
 */
public class HttpUtils {
    public static final int DEFAULT_DOWNLOAD_TIMEOUT_MS = 15_000;
    public static final long DEFAULT_MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    public static ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(8, 8,
            10L, TimeUnit.MINUTES,
            new LinkedBlockingQueue<>());

    public static ExecutorService EXECUTOR_SERVICE1 = new ThreadPoolExecutor(1, 1,
            10L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());

    public record HttpResponseResult(int statusCode, String body) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /**
     * 安全下载远程资源字节数组：带 UA、15s 超时、自动跟随重定向（含 http/https 跨协议）、上限 20MB。
     */
    public static byte[] downloadBytes(
            String urlStr,
            int timeoutMs,
            long maxBytes,
            @Nullable Map<String, String> headers
    ) throws IOException {
        if (urlStr == null || urlStr.isBlank()) {
            throw new IOException("下载地址为空");
        }
        String currentUrl = urlStr.trim();
        int redirects = 0;
        while (true) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "OneBot-Mirai/" + PluginRuntime.getVersion());
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
            try {
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307
                        || code == 308) {
                    if (++redirects > MAX_REDIRECTS) {
                        throw new IOException("HTTP 重定向次数超过上限 (" + MAX_REDIRECTS + ")");
                    }
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("HTTP 重定向缺少 Location 头");
                    }
                    currentUrl = new URL(url, location).toExternalForm();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP 下载返回非 2xx 状态码: " + code);
                }
                long contentLength = conn.getContentLengthLong();
                if (maxBytes > 0 && contentLength > maxBytes) {
                    throw new IOException("文件大小超过限制 (" + contentLength + " > " + maxBytes + " bytes)");
                }
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    long total = 0;
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        total += read;
                        if (maxBytes > 0 && total > maxBytes) {
                            throw new IOException("文件大小超过限制 (" + maxBytes + " bytes)");
                        }
                        baos.write(buf, 0, read);
                    }
                    return baos.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
        }
    }

    public static byte[] downloadBytes(String urlStr) throws IOException {
        return downloadBytes(urlStr, DEFAULT_DOWNLOAD_TIMEOUT_MS, DEFAULT_MAX_DOWNLOAD_BYTES, null);
    }

    /**
     * 探测 URL 可达性（先 HEAD，不支持则降级 GET）。
     */
    public static boolean probeUrlReachable(String urlStr, int timeoutMs) {
        if (urlStr == null || urlStr.isBlank()) {
            return false;
        }
        if (tryProbeMethod(urlStr, "HEAD", timeoutMs)) {
            return true;
        }
        return tryProbeMethod(urlStr, "GET", timeoutMs);
    }

    private static boolean tryProbeMethod(String urlStr, String method, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr.trim());
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "OneBot-Mirai/" + PluginRuntime.getVersion());
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String getStringFromHttpUrl(boolean k, String url) {
        try {
            byte[] data = downloadBytes(url, DEFAULT_DOWNLOAD_TIMEOUT_MS, DEFAULT_MAX_DOWNLOAD_BYTES, null);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    if (k && line.trim().startsWith("#")) {
                        continue;
                    }
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (IOException e) {
            PluginRuntime.getLogger().warning("读取 HTTP 文本失败: " + url, e);
            return null;
        }
    }

    public static String getStringFromHttpUrl(String url) {
        try {
            byte[] data = downloadBytes(url, DEFAULT_DOWNLOAD_TIMEOUT_MS, DEFAULT_MAX_DOWNLOAD_BYTES, null);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static byte[] getBytesFromHttpUrl(String url) {
        try {
            return downloadBytes(url, DEFAULT_DOWNLOAD_TIMEOUT_MS, DEFAULT_MAX_DOWNLOAD_BYTES, null);
        } catch (IOException e) {
            PluginRuntime.getLogger().warning("从 HTTP 下载字节失败: " + url + ", 原因: " + e.getMessage());
            return null;
        }
    }

    public static void downloadFile(String urlStr, String fileName) {
        EXECUTOR_SERVICE.execute(() -> {
            try {
                if (urlStr == null || urlStr.isEmpty() || fileName == null || fileName.isEmpty()) {
                    return;
                }
                byte[] bytes = downloadBytes(urlStr);
                File file = new File(fileName);
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bytes);
                }
            } catch (Exception e) {
                PluginRuntime.getLogger().warning("异步下载文件失败: " + urlStr, e);
            }
        });
    }

    public static void downloadFile(String urlStr, File file) {
        EXECUTOR_SERVICE.execute(() -> {
            try {
                if (file == null || urlStr == null || urlStr.isEmpty()) {
                    return;
                }
                byte[] bytes = downloadBytes(urlStr);
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bytes);
                }
            } catch (Exception e) {
                PluginRuntime.getLogger().warning("异步下载文件失败: " + urlStr, e);
            }
        });
    }

    private static final Pattern PATTERN = Pattern.compile("<title>.+</title>");

    public static String getTitle(String url, String title) throws IOException {
        String s = getStringFromHttpUrl(url);
        Matcher matcher = PATTERN.matcher(s);
        if (matcher.find()) {
            s = matcher.group();
            s = s.substring(7, s.length() - 8);
            return s;
        }
        return title;
    }

    public static String get(String dest, Properties header) {
        HttpURLConnection con = null;
        try {
            URL url = new URL(dest);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(DEFAULT_DOWNLOAD_TIMEOUT_MS);
            con.setReadTimeout(DEFAULT_DOWNLOAD_TIMEOUT_MS);
            if (header != null) {
                for (String s : header.stringPropertyNames()) {
                    con.setRequestProperty(s, header.getProperty(s));
                }
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sbf = new StringBuilder();
                String temp;
                while ((temp = br.readLine()) != null) {
                    sbf.append(temp).append("\r\n");
                }
                return sbf.toString();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    public static String post(String dest, String data, Properties header) {
        HttpURLConnection con = null;
        try {
            URL url = new URL(dest);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(DEFAULT_DOWNLOAD_TIMEOUT_MS);
            con.setReadTimeout(DEFAULT_DOWNLOAD_TIMEOUT_MS);
            if (header != null) {
                for (String s : header.stringPropertyNames()) {
                    con.setRequestProperty(s, header.getProperty(s));
                }
            }
            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) {
                os.write(data.getBytes(StandardCharsets.UTF_8));
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sbf = new StringBuilder();
                String temp;
                while ((temp = br.readLine()) != null) {
                    sbf.append(temp).append("\r\n");
                }
                return sbf.toString();
            }
        } catch (Exception e) {
            PluginRuntime.getLogger().warning("HTTP POST 失败: " + dest, e);
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    /**
     * 发送 JSON POST 请求并返回状态码与响应体，支持配置超时时间。
     */
    public static HttpResponseResult jsonPostWithStatus(
            String dest,
            String data,
            Map<String, String> headers,
            int timeoutMs
    ) throws IOException {
        URL url = new URL(dest);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        int resolvedTimeout = timeoutMs > 0 ? timeoutMs : 5000;
        con.setRequestMethod("POST");
        con.setConnectTimeout(resolvedTimeout);
        con.setReadTimeout(resolvedTimeout);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    con.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        con.setDoOutput(true);
        byte[] requestBodyBytes = data.getBytes(StandardCharsets.UTF_8);
        con.setRequestProperty("Content-Length", Integer.toString(requestBodyBytes.length));
        try {
            try (OutputStream os = con.getOutputStream()) {
                os.write(requestBodyBytes);
                os.flush();
            }
            int statusCode = con.getResponseCode();
            InputStream responseStream = (statusCode >= 200 && statusCode < 400)
                    ? con.getInputStream()
                    : con.getErrorStream();
            String body = "";
            if (responseStream != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
                    StringBuilder sbf = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sbf.append(line).append('\n');
                    }
                    body = sbf.toString().trim();
                }
            }
            return new HttpResponseResult(statusCode, body);
        } finally {
            con.disconnect();
        }
    }

    public static String jsonPost(MiraiLogger miraiLogger, String dest, String data, Properties header) {
        try {
            Map<String, String> map = new java.util.LinkedHashMap<>();
            if (header != null) {
                for (String key : header.stringPropertyNames()) {
                    map.put(key, header.getProperty(key));
                }
            }
            HttpResponseResult res = jsonPostWithStatus(dest, data, map, 5000);
            return res.isSuccess() ? res.body() : null;
        } catch (Exception e) {
            if (miraiLogger != null) {
                miraiLogger.warning("HTTP上报失败: " + e.getMessage());
            }
            return null;
        }
    }
}
