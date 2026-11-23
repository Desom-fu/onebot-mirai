package cn.evole.onebot.mirai.util;

import net.mamoe.mirai.message.data.Image;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片协议解析、魔数识别与本地缓存工具。
 */
public class ImgUtils {

    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern DATA_URI_BASE64_PATTERN = Pattern.compile("^data:([^;,]*);base64,(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MIRAI_SLASH_ID_PATTERN = Pattern.compile("^/[0-9a-fA-F\\-]{8,}$");

    public enum ImageSourceKind {
        BASE64,
        DATA_URI_BASE64,
        HTTP,
        FILE_URI,
        LOCAL_PATH,
        MIRAI_ID
    }

    public record ParsedImageSource(
            ImageSourceKind kind,
            String raw,
            String value,
            @Nullable String mimeFormat
    ) {
    }

    public record CachedImage(File file, String fileName, String path,
                              String md5, int size, String url,
                              long addTime, @Nullable String imageType) {
    }

    /**
     * 截断 file 参数用于日志或异常信息，避免将整段 base64 输出到日志。
     */
    public static String truncateFileForLog(@Nullable String fileStr) {
        if (fileStr == null) {
            return "<null>";
        }
        if (fileStr.length() <= 64) {
            return fileStr;
        }
        return fileStr.substring(0, 64) + "...";
    }

    /**
     * 解析 OneBot 图片/文件资源的 file 协议形式：
     * base64://、data:image/...;base64,、http(s)://、file://、Windows/Unix/相对路径、mirai 图片 ID。
     */
    public static ParsedImageSource parseImageSource(String fileStr) {
        if (fileStr == null || fileStr.isBlank()) {
            throw new IllegalArgumentException("file 参数不能为空");
        }
        String trimmed = fileStr.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.startsWith("base64://")) {
            return new ParsedImageSource(ImageSourceKind.BASE64, trimmed, trimmed.substring("base64://".length()), null);
        }

        Matcher dataUriMatcher = DATA_URI_BASE64_PATTERN.matcher(trimmed);
        if (dataUriMatcher.matches()) {
            String mime = dataUriMatcher.group(1);
            String fmt = extractFormatFromMime(mime);
            return new ParsedImageSource(ImageSourceKind.DATA_URI_BASE64, trimmed, dataUriMatcher.group(2), fmt);
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return new ParsedImageSource(ImageSourceKind.HTTP, trimmed, trimmed, null);
        }

        if (lower.startsWith("file://")) {
            String localPath = resolveFileUriToPath(trimmed);
            return new ParsedImageSource(ImageSourceKind.FILE_URI, trimmed, localPath, null);
        }

        // Windows 盘符路径（例如 C:\path\a.png 或 C:/path/a.png）或 UNC 路径
        if (WINDOWS_PATH_PATTERN.matcher(trimmed).matches() || trimmed.startsWith("\\\\")) {
            return new ParsedImageSource(ImageSourceKind.LOCAL_PATH, trimmed, trimmed, null);
        }

        // mirai 群/私聊图片 ID：{UUID}.jpg 或 /xxxx
        if (trimmed.startsWith("{") && trimmed.contains("}")) {
            return new ParsedImageSource(ImageSourceKind.MIRAI_ID, trimmed, trimmed, null);
        }
        if (MIRAI_SLASH_ID_PATTERN.matcher(trimmed).matches() && !new File(trimmed).isFile()) {
            return new ParsedImageSource(ImageSourceKind.MIRAI_ID, trimmed, trimmed, null);
        }

        return new ParsedImageSource(ImageSourceKind.LOCAL_PATH, trimmed, trimmed, null);
    }

    /**
     * 将 file:// URI 转换为本地文件路径，兼容 file:///C:/...、file://C:/...、file:///path/... 及百分号编码。
     */
    public static String resolveFileUriToPath(String fileUri) {
        String rest = fileUri.substring("file://".length());
        if (rest.toLowerCase(Locale.ROOT).startsWith("localhost/")) {
            rest = rest.substring("localhost".length());
        }
        // 尝试用标准 URI 解析（优先处理合法 RFC 8089 URI）
        try {
            URI uri = URI.create(fileUri);
            if (uri.getPath() != null && !uri.getPath().isEmpty()) {
                String path = uri.getPath();
                // Windows: /C:/foo -> C:/foo
                if (path.length() >= 3 && path.charAt(0) == '/' && Character.isLetter(path.charAt(1)) && path.charAt(2) == ':') {
                    return path.substring(1);
                }
                if (uri.getHost() != null && !uri.getHost().isEmpty() && !"localhost".equalsIgnoreCase(uri.getHost())) {
                    return "//" + uri.getHost() + path;
                }
                return path;
            }
        } catch (IllegalArgumentException ignored) {
            // 含未转义空格或反斜杠时走手工解析
        }
        String decoded = rest.replace('\\', '/');
        if (decoded.contains("%")) {
            try {
                decoded = URLDecoder.decode(decoded.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
            }
        }
        // file:///C:/... -> /C:/... -> C:/...
        if (decoded.length() >= 3 && decoded.charAt(0) == '/' && Character.isLetter(decoded.charAt(1)) && decoded.charAt(2) == ':') {
            return decoded.substring(1);
        }
        return decoded;
    }

    @Nullable
    private static String extractFormatFromMime(@Nullable String mime) {
        if (mime == null || mime.isBlank()) {
            return null;
        }
        String lower = mime.trim().toLowerCase(Locale.ROOT);
        int slash = lower.indexOf('/');
        String sub = slash >= 0 ? lower.substring(slash + 1) : lower;
        if ("jpeg".equals(sub)) {
            return "jpg";
        }
        return switch (sub) {
            case "png", "jpg", "gif", "bmp", "webp" -> sub;
            default -> null;
        };
    }

    /**
     * 宽松解码 Base64：支持标准 Base64、MIME 换行/空白、URL 编码后的 Base64、URL-Safe Base64；
     * 空串或非法字符必须抛出 IllegalArgumentException（不使用静默跳过非法字符的 getMimeDecoder）。
     */
    public static byte[] decodeBase64Flexible(String rawBase64) {
        if (rawBase64 == null || rawBase64.isBlank()) {
            throw new IllegalArgumentException("Base64 数据为空");
        }
        String stripped = stripWhitespace(rawBase64);
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("Base64 数据为空");
        }

        // 1. 标准 Base64
        try {
            byte[] decoded = Base64.getDecoder().decode(stripped);
            if (decoded.length > 0) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
        }

        // 2. 尝试 URL 解码（保留原有 '+' 不被转为空格）后再解标准 Base64
        String candidate = stripped;
        if (rawBase64.contains("%")) {
            try {
                String urlDecoded = URLDecoder.decode(rawBase64.replace("+", "%2B"), StandardCharsets.UTF_8);
                candidate = stripWhitespace(urlDecoded);
                if (!candidate.isEmpty()) {
                    byte[] decoded = Base64.getDecoder().decode(candidate);
                    if (decoded.length > 0) {
                        return decoded;
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        // 3. 尝试 URL-Safe Base64（包含 '-' 或 '_' 时）
        if (candidate.indexOf('-') >= 0 || candidate.indexOf('_') >= 0) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(candidate);
                if (decoded.length > 0) {
                    return decoded;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        throw new IllegalArgumentException("Base64 解码失败 (内容前缀=" + truncateFileForLog(rawBase64) + ")");
    }

    private static String stripWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 按字节魔数判断图片格式：PNG / JPG / GIF / BMP / WEBP；无法识别返回 "unknown"。
     */
    public static String getImageType(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return "unknown";
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;

        // PNG: 89 50 4E 47
        if (bytes.length >= 4 && b0 == 0x89 && b1 == 0x50
                && (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47) {
            return "png";
        }
        // JPG: FF D8
        if (b0 == 0xFF && b1 == 0xD8) {
            return "jpg";
        }
        // GIF: 47 49 46 38
        if (bytes.length >= 4 && b0 == 0x47 && b1 == 0x49
                && (bytes[2] & 0xFF) == 0x46 && (bytes[3] & 0xFF) == 0x38) {
            return "gif";
        }
        // BMP: 42 4D
        if (b0 == 0x42 && b1 == 0x4D) {
            return "bmp";
        }
        // WEBP: RIFF....WEBP (52 49 46 46 .... 57 45 42 50)
        if (bytes.length >= 12
                && b0 == 0x52 && b1 == 0x49
                && (bytes[2] & 0xFF) == 0x46 && (bytes[3] & 0xFF) == 0x46
                && (bytes[8] & 0xFF) == 0x57 && (bytes[9] & 0xFF) == 0x45
                && (bytes[10] & 0xFF) == 0x42 && (bytes[11] & 0xFF) == 0x50) {
            return "webp";
        }
        return "unknown";
    }

    /**
     * 按魔数判断图片格式，无法识别时默认返回 "png"。
     */
    public static String detectImageFormatOrDefault(byte[] bytes) {
        String detected = getImageType(bytes);
        return "unknown".equals(detected) ? "png" : detected;
    }

    /**
     * 读取通用二进制资源（支持 base64://、data:...;base64,、http(s)://、file://、本地路径）。
     */
    public static byte[] resolveBinaryBytes(String fileStr) throws IOException {
        ParsedImageSource parsed = parseImageSource(fileStr);
        return switch (parsed.kind()) {
            case BASE64, DATA_URI_BASE64 -> decodeBase64Flexible(parsed.value());
            case HTTP -> HttpUtils.downloadBytes(parsed.value());
            case FILE_URI, LOCAL_PATH -> {
                File f = new File(parsed.value());
                if (!f.isFile() || !f.canRead()) {
                    throw new IOException("本地文件不存在或不可读: " + truncateFileForLog(parsed.value()));
                }
                yield Files.readAllBytes(f.toPath());
            }
            case MIRAI_ID -> throw new IOException("不支持以 mirai 资源 ID 读取通用二进制文件: " + truncateFileForLog(fileStr));
        };
    }

    public static String constructCacheImageMeta(String md5, long size, String url, String imageType) {
        return """
                [image]
                md5=%s
                size=%s
                url=%s
                addtime=%s
                type=%s
            """.formatted(md5, size, url, System.currentTimeMillis(), imageType);
    }

    public static String getImageType(Image image) {
        String[] parts = image.getImageId().split("\\.", 2);
        if (parts.length == 2) {
            return parts[1];
        }
        return "unknown";
    }

    public static CachedImage getCachedImageFile(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        CachedImage cacheImage = null;
        File cacheFile;
        if (name.endsWith(".cqimg")) {
            cacheFile = BaseUtils.getDataFile("image", name);
        } else if (name.endsWith(".image")) {
            cacheFile = BaseUtils.getDataFile("image", name.toLowerCase(Locale.ROOT));
        } else {
            File cqimg = BaseUtils.getDataFile("image", name + ".cqimg");
            cacheFile = cqimg != null ? cqimg : BaseUtils.getDataFile("image", name.toLowerCase(Locale.ROOT) + ".image");
        }

        if (cacheFile != null) {
            if (cacheFile.canRead()) {
                PluginRuntime.getLogger().info("此链接图片已缓存, 如需删除缓存请至 " + cacheFile.getAbsolutePath());
                final String[] md5 = {""};
                final int[] size = {0};
                final String[] url = {""};
                final long[] addTime = {0L};
                final String[] imageType = {null};

                String ext = StringUtils.substringBeforeLast(cacheFile.getName(), ".");
                switch (ext) {
                    case "cqimg" -> {
                        try {
                            for (String line : Files.readAllLines(cacheFile.toPath(), StandardCharsets.UTF_8)) {
                                String[] parts = line.trim().split("=", 2);
                                if (parts.length == 2) {
                                    switch (parts[0]) {
                                        case "md5" -> md5[0] = parts[1];
                                        case "size" -> {
                                            try {
                                                size[0] = Integer.parseInt(parts[1]);
                                            } catch (NumberFormatException ignored) {
                                            }
                                        }
                                        case "url" -> url[0] = parts[1];
                                        case "addtime" -> {
                                            try {
                                                addTime[0] = Long.parseLong(parts[1]);
                                            } catch (NumberFormatException ignored) {
                                            }
                                        }
                                        case "type" -> imageType[0] = parts[1];
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    }
                    case "image" -> {
                        try {
                            byte[] bytes = Files.readAllBytes(cacheFile.toPath());
                            if (bytes.length >= 20) {
                                byte[] b1 = new byte[16];
                                System.arraycopy(bytes, 0, b1, 0, 16);
                                md5[0] = BaseUtils.bytesToHexString(b1);

                                byte[] b2 = new byte[4];
                                System.arraycopy(bytes, 16, b2, 0, 4);
                                size[0] = BaseUtils.byteToInt(b2);

                                url[0] = "https://c2cpicdw.qpic.cn/offpic_new//0/0-00-" + md5[0] + "/0?term=2";
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }

                if (!md5[0].isEmpty() && size[0] != 0) {
                    cacheImage = new CachedImage(
                            cacheFile,
                            name,
                            cacheFile.getAbsolutePath(),
                            md5[0],
                            size[0],
                            url[0],
                            addTime[0],
                            imageType[0]
                    );
                } else {
                    cacheFile.delete();
                }
            } else {
                PluginRuntime.getLogger().error("图片缓存文件不可读: " + truncateFileForLog(name));
            }
        } else {
            PluginRuntime.getLogger().debug("未找到图片缓存文件: " + truncateFileForLog(name));
        }
        return cacheImage;
    }
}
