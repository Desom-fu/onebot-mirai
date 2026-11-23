package cn.evole.onebot.mirai.util;

import net.mamoe.mirai.contact.AudioSupported;
import net.mamoe.mirai.contact.Contact;
import net.mamoe.mirai.message.data.Audio;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.utils.ExternalResource;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static cn.evole.onebot.mirai.util.BaseUtils.getDataFile;

/**
 * 语音缓存与发送工具：支持 file://、本地路径、http(s)://、base64://、data URI 及本地缓存。
 */
public class VoiceUtils {

    public static File getCachedRecordFile(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.endsWith(".cqrecord")
                ? getDataFile("record", name)
                : getDataFile("record", name + ".cqrecord");
    }

    public static Audio tryResolveCachedRecord(String name, @Nullable Contact contact) {
        return BaseUtils.safeRun(() -> {
            File cacheFile = getCachedRecordFile(name);
            if (cacheFile != null && cacheFile.canRead()) {
                PluginRuntime.getLogger().info("此语音已缓存, 如需删除缓存请至 " + cacheFile.getAbsolutePath());
                if (contact instanceof AudioSupported audioSupported) {
                    try (ExternalResource resource = ExternalResource.create(cacheFile)) {
                        return audioSupported.uploadAudio(resource);
                    }
                }
            } else if (cacheFile != null) {
                PluginRuntime.getLogger().error("语音缓存文件不可读: " + ImgUtils.truncateFileForLog(name));
            } else {
                PluginRuntime.getLogger().debug("未找到语音缓存文件: " + ImgUtils.truncateFileForLog(name));
            }
            return null;
        }, null);
    }

    public static Message sendRecordMsg(String fileStr, Contact contact) {
        if (!(contact instanceof AudioSupported audioSupported)) {
            throw new IllegalStateException("当前会话类型不支持发送语音: " + contact);
        }
        if (fileStr == null || fileStr.isBlank()) {
            throw new IllegalArgumentException("语音 file 参数不能为空");
        }
        try {
            byte[] audioBytes;
            ImgUtils.ParsedImageSource parsed = ImgUtils.parseImageSource(fileStr);
            switch (parsed.kind()) {
                case BASE64, DATA_URI_BASE64 -> audioBytes = ImgUtils.decodeBase64Flexible(parsed.value());
                case HTTP -> audioBytes = HttpUtils.downloadBytes(parsed.value());
                case FILE_URI -> {
                    File f = new File(parsed.value());
                    if (!f.isFile() || !f.canRead()) {
                        throw new IOException("语音本地文件不存在或不可读: " + ImgUtils.truncateFileForLog(parsed.value()));
                    }
                    audioBytes = Files.readAllBytes(f.toPath());
                }
                case LOCAL_PATH, MIRAI_ID -> {
                    File f = new File(parsed.value());
                    if (f.isFile() && f.canRead()) {
                        audioBytes = Files.readAllBytes(f.toPath());
                    } else {
                        File cached = getCachedRecordFile(parsed.value());
                        if (cached != null && cached.isFile() && cached.canRead()) {
                            audioBytes = Files.readAllBytes(cached.toPath());
                        } else {
                            throw new IOException("语音文件不存在: " + ImgUtils.truncateFileForLog(fileStr));
                        }
                    }
                }
                default -> throw new IOException("不支持的语音路径格式: " + ImgUtils.truncateFileForLog(fileStr));
            }

            if (audioBytes == null || audioBytes.length == 0) {
                throw new IllegalStateException("语音数据为空 (file=" + ImgUtils.truncateFileForLog(fileStr) + ")");
            }

            try (ExternalResource resource = ExternalResource.create(audioBytes)) {
                return audioSupported.uploadAudio(resource);
            }
        } catch (Exception e) {
            throw new IllegalStateException("发送语音失败 (file=" + ImgUtils.truncateFileForLog(fileStr) + "): " + e.getMessage(), e);
        }
    }
}
