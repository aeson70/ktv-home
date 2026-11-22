package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 系统设置读写（P2.6，详设§8 ADM-03）。settings 表 key→JSONB value。
 */
@Service
public class SettingService {

    public static final String LIBRARY_WATCH_ENABLED = "library_watch_enabled";
    public static final String DELETE_SOURCE_AFTER_TRANSCODE = "delete_source_after_transcode";
    /** 自定义扫描源路径（宿主机写法，如 /vol2/1000/mv；空表示用默认路径）。 */
    public static final String CUSTOM_SOURCE_PATH = "custom_source_path";

    public static final Map<String, Object> TRANSCODE_DEFAULTS = Map.of(
            "direct_copy_containers", List.of("mp4", "m4v", "mkv"),
            "direct_copy_video_codecs", List.of("h264", "hevc"),
            "direct_copy_audio_codecs", List.of("aac", "mp3"),
            "transcode_audio_only", false,
            "transcode_output_container", "mkv",
            "transcode_video_codec", "h264",
            "transcode_audio_codec", "aac",
            "transcode_hardware_acceleration", false,
            "transcode_hardware_auto_configured", false
    );
    private static final Map<String, Object> GENERAL_DEFAULTS = Map.ofEntries(
            Map.entry(LIBRARY_WATCH_ENABLED, false), Map.entry("tv_video_scale_mode", "zoom"),
            Map.entry("qr_address", ""), Map.entry("standby_carousel", true), Map.entry("anti_burn", true),
            Map.entry("mini_qr", true), Map.entry("standby_welcome", "今晚开唱"),
            Map.entry("standby_subtitle", "手机点歌，电视欢唱\n一家人的客厅 KTV"), Map.entry("standby_source", "mixed"),
            Map.entry("standby_song_ids", List.of()), Map.entry("standby_interval_sec", 8),
            Map.entry("display_address", ""), Map.entry("standby_logo_path", ""),
            Map.entry(DELETE_SOURCE_AFTER_TRANSCODE, false), Map.entry("room_host_user_id", 0L),
            Map.entry(CUSTOM_SOURCE_PATH, ""));
    private static final Set<String> TRANSCODE_KEYS = TRANSCODE_DEFAULTS.keySet();
    private static final Set<String> ALLOWED_KEYS = new HashSet<>();
    static { ALLOWED_KEYS.addAll(TRANSCODE_DEFAULTS.keySet()); ALLOWED_KEYS.addAll(GENERAL_DEFAULTS.keySet()); }

    private final SettingRepository repo;
    private final ObjectMapper mapper;

    public SettingService(SettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** 读取全部设置为 map（value 反序列化为对象） */
    @Transactional(readOnly = true)
    public Map<String, Object> getAll() {
        Map<String, Object> out = new HashMap<>(TRANSCODE_DEFAULTS);
        out.putAll(GENERAL_DEFAULTS);
        for (Setting s : repo.findAll()) {
            if (!s.getKey().startsWith("ai.") && !s.getKey().startsWith("music_sources.")) {
                out.put(s.getKey(), parse(s.getValue()));
            }
        }
        return out;
    }

    public boolean isLibraryWatchEnabled() {
        return Boolean.TRUE.equals(getAll().get(LIBRARY_WATCH_ENABLED));
    }

    /** 宿主机路径 → 容器内路径：/vol2/1000/... 对应只读挂载的 /host-data/... */
    public static String toContainerPath(String raw) {
        if (raw == null) return "";
        String p = raw.trim();
        if (p.equals("/vol2/1000")) return "/host-data";
        if (p.startsWith("/vol2/1000/")) return "/host-data/" + p.substring("/vol2/1000/".length());
        return p;
    }

    /** 容器内路径 → 宿主机路径（展示用）：/host-data/... 对应 /vol2/1000/... */
    public static String toHostPath(String containerPath) {
        if (containerPath == null) return "";
        String p = containerPath.trim();
        if (p.equals("/host-data")) return "/vol2/1000";
        if (p.startsWith("/host-data/")) return "/vol2/1000/" + p.substring("/host-data/".length());
        return p;
    }

    /** 当前自定义源路径（宿主机写法，原样返回；空表示未设置） */
    public String getCustomSourcePath() {
        Object v = getAll().get(CUSTOM_SOURCE_PATH);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 有效扫描源根目录（容器内路径）：自定义路径优先，否则用默认路径 */
    public String resolveSourceRoot(String defaultRoot) {
        String custom = getCustomSourcePath();
        if (custom.isBlank()) return defaultRoot;
        return toContainerPath(custom);
    }

    /** 校验自定义源路径并返回容器内路径；空白表示恢复默认；非法时抛错 */
    public static String validateCustomSourcePath(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String container = toContainerPath(raw);
        Path p = Path.of(container).toAbsolutePath().normalize();
        boolean allowed = p.equals(Path.of("/host-data")) || p.startsWith("/host-data/")
                || p.equals(Path.of("/source-music")) || p.startsWith("/source-music/");
        if (!allowed) throw new com.homektv.web.ApiException("SOURCE_PATH_NOT_ALLOWED",
                "曲库路径只允许在 /host-data（宿主机 /vol2/1000）或 /source-music 下");
        if (!Files.isDirectory(p)) throw new com.homektv.web.ApiException("SOURCE_PATH_MISSING",
                "目录不存在：" + p);
        return p.toString();
    }

    /** 批量写入设置 */
    @Transactional
    public void putAll(Map<String, Object> settings) {
        settings.forEach((k, v) -> {
            validateKeyValue(k, v);
            Setting s = repo.findById(k).orElseGet(() -> {
                Setting ns = new Setting();
                ns.setKey(k);
                return ns;
            });
            s.setValue(write(v));
            repo.save(s);
        });
    }

    private void validateKeyValue(String key, Object value) {
        if (!ALLOWED_KEYS.contains(key)) throw new com.homektv.web.ApiException("SETTING_NOT_ALLOWED", "不允许修改设置：" + key);
        if (value instanceof String text && text.length() > 1000)
            throw new com.homektv.web.ApiException("SETTING_INVALID_RANGE", key + " 文本过长");
        if (Set.of(LIBRARY_WATCH_ENABLED, "standby_carousel", "anti_burn", "mini_qr", "transcode_audio_only",
                "transcode_hardware_acceleration", "transcode_hardware_auto_configured", DELETE_SOURCE_AFTER_TRANSCODE).contains(key)) {
            if (!(value instanceof Boolean)) throw new com.homektv.web.ApiException("SETTING_INVALID_TYPE", key + " 必须是布尔值");
        }
        if (key.endsWith("_interval_sec")) {
            if (!(value instanceof Number number) || number.intValue() < 3 || number.intValue() > 60)
                throw new com.homektv.web.ApiException("SETTING_INVALID_RANGE", key + " 必须在 3 到 60 之间");
        }
        if (key.startsWith("direct_copy_") && (key.contains("codec") || key.contains("container"))) {
            if (!(value instanceof List<?>)) throw new com.homektv.web.ApiException("SETTING_INVALID_TYPE", key + " 必须是数组或选项值");
        }
        if ("transcode_output_container".equals(key) && !Set.of("mkv", "mp4").contains(String.valueOf(value)))
            throw new com.homektv.web.ApiException("SETTING_INVALID_VALUE", "输出容器无效");
        if ("tv_video_scale_mode".equals(key) && !Set.of("zoom", "fit", "fill").contains(String.valueOf(value)))
            throw new com.homektv.web.ApiException("SETTING_INVALID_VALUE", "视频画面模式无效");
        if ("standby_source".equals(key) && !Set.of("mixed", "hot", "new", "custom").contains(String.valueOf(value)))
            throw new com.homektv.web.ApiException("SETTING_INVALID_VALUE", "待机轮播来源无效");
    }

    @Transactional
    public Map<String, Object> resetTranscodeDefaults() {
        repo.deleteAllById(TRANSCODE_KEYS);
        return getAll();
    }

    public TranscodePolicy transcodePolicy() {
        Map<String, Object> settings = getAll();
        return new TranscodePolicy(
                strings(settings.get("direct_copy_containers"), List.of("mp4", "m4v", "mkv")),
                strings(settings.get("direct_copy_video_codecs"), List.of("h264", "hevc")),
                strings(settings.get("direct_copy_audio_codecs"), List.of("aac", "mp3")),
                Boolean.TRUE.equals(settings.get("transcode_audio_only")),
                option(settings, "transcode_output_container", Set.of("mkv", "mp4"), "mkv"),
                option(settings, "transcode_video_codec", Set.of("h264", "hevc"), "h264"),
                option(settings, "transcode_audio_codec", Set.of("aac", "mp3", "opus"), "aac"),
                Boolean.TRUE.equals(settings.get("transcode_hardware_acceleration"))
        );
    }

    public record TranscodePolicy(List<String> directCopyContainers, List<String> directCopyVideoCodecs,
                                  List<String> directCopyAudioCodecs, boolean transcodeAudioOnly,
                                  String outputContainer, String videoCodec, String audioCodec,
                                  boolean hardwareAcceleration) {}

    private static List<String> strings(Object value, List<String> fallback) {
        if (!(value instanceof List<?> list)) return fallback;
        List<String> result = list.stream().map(String::valueOf).map(String::toLowerCase).distinct().toList();
        return result.isEmpty() ? fallback : result;
    }

    private static String option(Map<String, Object> settings, String key, Set<String> allowed, String fallback) {
        String value = String.valueOf(settings.getOrDefault(key, fallback)).toLowerCase();
        return allowed.contains(value) ? value : fallback;
    }

    private Object parse(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String write(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return "null";
        }
    }
}
