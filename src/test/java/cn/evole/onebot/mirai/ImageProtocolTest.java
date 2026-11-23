package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.util.ImgUtils;
import cn.evole.onebot.mirai.util.ImgUtils.ImageSourceKind;
import cn.evole.onebot.mirai.util.ImgUtils.ParsedImageSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图片协议解析、Base64 解码与字节魔数探测测试。
 */
public class ImageProtocolTest {

    @Test
    void testParseAllProtocols() {
        ParsedImageSource b64 = ImgUtils.parseImageSource("base64://aGVsbG8=");
        assertEquals(ImageSourceKind.BASE64, b64.kind());
        assertEquals("aGVsbG8=", b64.value());

        ParsedImageSource dataUri = ImgUtils.parseImageSource("data:image/png;base64,aGVsbG8=");
        assertEquals(ImageSourceKind.DATA_URI_BASE64, dataUri.kind());
        assertEquals("aGVsbG8=", dataUri.value());
        assertEquals("png", dataUri.mimeFormat());

        ParsedImageSource fileUriWin = ImgUtils.parseImageSource("file:///C:/Users/test/a.png");
        assertEquals(ImageSourceKind.FILE_URI, fileUriWin.kind());
        assertEquals("C:/Users/test/a.png", fileUriWin.value());

        ParsedImageSource fileUriUnix = ImgUtils.parseImageSource("file:///tmp/test.jpg");
        assertEquals(ImageSourceKind.FILE_URI, fileUriUnix.kind());
        assertEquals("/tmp/test.jpg", fileUriUnix.value());

        ParsedImageSource winPath = ImgUtils.parseImageSource("C:\\path\\to\\a.png");
        assertEquals(ImageSourceKind.LOCAL_PATH, winPath.kind());
        assertEquals("C:\\path\\to\\a.png", winPath.value());

        ParsedImageSource http = ImgUtils.parseImageSource("http://example.com/a.png");
        assertEquals(ImageSourceKind.HTTP, http.kind());

        ParsedImageSource https = ImgUtils.parseImageSource("https://example.com/a.png");
        assertEquals(ImageSourceKind.HTTP, https.kind());

        ParsedImageSource miraiId = ImgUtils.parseImageSource("{01E9451B-70ED-EAE3-B37C-101F1EEBF5B5}.png");
        assertEquals(ImageSourceKind.MIRAI_ID, miraiId.kind());
    }

    @Test
    void testMagicBytesDetection() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] jpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] gif = new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
        byte[] bmp = new byte[]{0x42, 0x4D, 0x00, 0x00};
        byte[] webp = new byte[]{0x52, 0x49, 0x46, 0x46, 0x10, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};
        byte[] unknown = new byte[]{0x01, 0x02, 0x03, 0x04};

        assertEquals("png", ImgUtils.getImageType(png));
        assertEquals("jpg", ImgUtils.getImageType(jpg));
        assertEquals("gif", ImgUtils.getImageType(gif));
        assertEquals("bmp", ImgUtils.getImageType(bmp));
        assertEquals("webp", ImgUtils.getImageType(webp));
        assertEquals("unknown", ImgUtils.getImageType(unknown));
        assertEquals("png", ImgUtils.detectImageFormatOrDefault(unknown));
    }

    @Test
    void testBase64DecodingSuccessAndFailures() {
        // 标准 Base64
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), ImgUtils.decodeBase64Flexible("aGVsbG8="));
        // MIME 换行 Base64
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), ImgUtils.decodeBase64Flexible("aGVs\r\nbG8="));
        // URL 编码后的 Base64
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), ImgUtils.decodeBase64Flexible("aGVsbG8%3D"));

        // 空/坏 Base64 必须抛异常
        assertThrows(IllegalArgumentException.class, () -> ImgUtils.decodeBase64Flexible(""));
        assertThrows(IllegalArgumentException.class, () -> ImgUtils.decodeBase64Flexible("   "));
        assertThrows(IllegalArgumentException.class, () -> ImgUtils.decodeBase64Flexible(null));
        assertThrows(IllegalArgumentException.class, () -> ImgUtils.decodeBase64Flexible("!!!not_valid_base64@@@"));
    }

    @Test
    void testTruncateFileForLog() {
        String longB64 = "base64://" + "A".repeat(200);
        String truncated = ImgUtils.truncateFileForLog(longB64);
        assertEquals(67, truncated.length());
        assertTrue(truncated.endsWith("..."));
    }
}
