package com.aliyun.autowonder.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求澄清提示词模板目录的编解码与校验。目录整体保存在 CUSTOM_AI_DEFAULTS 的单个键中，
 * 读取时损坏数据只回退为空，写入时非法数据必须拒绝。
 */
@Component
public class ClarificationPromptTemplateCatalog {

    public static final String BUILTIN_BOOTSTRAP = "bootstrap";
    public static final String BUILTIN_RECLARIFY = "reclarify";
    public static final int VERSION = 1;
    public static final int MAX_CUSTOM_TEMPLATES = 100;
    public static final int MAX_NAME_LENGTH = 50;
    public static final int MAX_CONTENT_LENGTH = 10000;

    private static final String CUSTOM_ID_PREFIX = "custom-";
    private static final Set<String> BUILTIN_IDS = Set.of(BUILTIN_BOOTSTRAP, BUILTIN_RECLARIFY);

    private final ObjectMapper objectMapper;

    public ClarificationPromptTemplateCatalog(ObjectMapper objectMapper) {
        // 复制一份并忽略未知字段：目录结构升级/降级时旧版本数据仍可读。
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Catalog(
            int version,
            Map<String, BuiltinTemplate> builtin,
            List<CustomTemplate> custom,
            Defaults defaults) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuiltinTemplate(String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomTemplate(String id, String name, String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Defaults(String first, String reclarify) {
    }

    /** 空白返回 null；损坏 JSON、未知版本或非法字段抛 {@link IllegalArgumentException}。 */
    public Catalog parse(String json) {
        return parse(json, false);
    }

    /**
     * 读取侧解析：其余字段仍严格校验，仅把失效的默认引用丢弃，
     * 让前端回退到对应内置模板，而不是整份目录作废。
     */
    public Catalog parseForRead(String json) {
        return parse(json, true);
    }

    private Catalog parse(String json, boolean lenientDefaults) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Catalog parsed;
        try {
            parsed = objectMapper.readValue(json.trim(), Catalog.class);
        } catch (JsonProcessingException ex) {
            throw new InvalidCatalogException("提示词模板目录无法解析", ex);
        }
        if (parsed == null) {
            return null;
        }
        return normalize(parsed, lenientDefaults);
    }

    /** 校验并输出规范 JSON；非法目录抛 {@link IllegalArgumentException}。 */
    public String serialize(Catalog catalog) {
        try {
            return objectMapper.writeValueAsString(normalize(catalog, false));
        } catch (JsonProcessingException ex) {
            throw new InvalidCatalogException("提示词模板目录无法序列化", ex);
        }
    }

    private Catalog normalize(Catalog source, boolean lenientDefaults) {
        if (source.version() != VERSION) {
            throw new InvalidCatalogException("不支持的提示词模板目录版本：" + source.version(), null);
        }

        Map<String, BuiltinTemplate> builtin = new LinkedHashMap<>();
        if (source.builtin() != null) {
            for (Map.Entry<String, BuiltinTemplate> entry : source.builtin().entrySet()) {
                String id = entry.getKey();
                if (!BUILTIN_IDS.contains(id)) {
                    throw new InvalidCatalogException("未知的内置模板标识：" + id, null);
                }
                String content = entry.getValue() == null ? null : entry.getValue().content();
                if (content == null || content.isBlank()) {
                    throw new InvalidCatalogException("内置模板正文不能为空：" + id, null);
                }
                requireContentLength(content);
                builtin.put(id, new BuiltinTemplate(content));
            }
        }

        List<CustomTemplate> custom = new ArrayList<>();
        Set<String> customIds = new LinkedHashSet<>();
        Set<String> customNames = new LinkedHashSet<>();
        if (source.custom() != null) {
            if (source.custom().size() > MAX_CUSTOM_TEMPLATES) {
                throw new InvalidCatalogException("自定义模板数量超过上限：" + MAX_CUSTOM_TEMPLATES, null);
            }
            for (CustomTemplate item : source.custom()) {
                if (item == null) {
                    throw new InvalidCatalogException("自定义模板不能为空", null);
                }
                String id = item.id() == null ? "" : item.id().trim();
                if (!id.startsWith(CUSTOM_ID_PREFIX) || id.length() == CUSTOM_ID_PREFIX.length()) {
                    throw new InvalidCatalogException("自定义模板标识不合法：" + item.id(), null);
                }
                if (!customIds.add(id)) {
                    throw new InvalidCatalogException("自定义模板标识重复：" + id, null);
                }
                String name = item.name() == null ? "" : item.name().trim();
                if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
                    throw new InvalidCatalogException("自定义模板名称长度不合法：" + item.id(), null);
                }
                if (!customNames.add(name)) {
                    throw new InvalidCatalogException("自定义模板名称重复：" + name, null);
                }
                String content = item.content() == null ? "" : item.content();
                requireContentLength(content);
                custom.add(new CustomTemplate(id, name, content));
            }
        }

        // 内置模板始终存在；builtin 缺省只表示正文使用出厂文案，不影响其可被默认引用。
        Defaults defaults = normalizeDefaults(source.defaults(), BUILTIN_IDS, customIds, lenientDefaults);
        return new Catalog(VERSION,
                Collections.unmodifiableMap(builtin),
                List.copyOf(custom),
                defaults);
    }

    private Defaults normalizeDefaults(Defaults source, Set<String> builtinIds, Set<String> customIds,
            boolean lenientDefaults) {
        if (source == null) {
            return null;
        }
        String first = normalizeDefaultRef(source.first(), builtinIds, customIds, lenientDefaults);
        String reclarify = normalizeDefaultRef(source.reclarify(), builtinIds, customIds, lenientDefaults);
        return first == null && reclarify == null ? null : new Defaults(first, reclarify);
    }

    private String normalizeDefaultRef(String ref, Set<String> builtinIds, Set<String> customIds,
            boolean lenientDefaults) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        if (!builtinIds.contains(trimmed) && !customIds.contains(trimmed)) {
            if (lenientDefaults) {
                return null;
            }
            throw new InvalidCatalogException("默认模板引用了不存在的模板：" + trimmed, null);
        }
        return trimmed;
    }

    private void requireContentLength(String content) {
        if (content.codePointCount(0, content.length()) > MAX_CONTENT_LENGTH) {
            throw new InvalidCatalogException("模板正文超过长度上限：" + MAX_CONTENT_LENGTH, null);
        }
    }

    public static class InvalidCatalogException extends IllegalArgumentException {
        public InvalidCatalogException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
