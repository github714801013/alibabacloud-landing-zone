package com.aliyun.autowonder.setting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClarificationPromptTemplateCatalogTest {

    private final ClarificationPromptTemplateCatalog catalog =
            new ClarificationPromptTemplateCatalog(new ObjectMapper());

    @Test
    void parseReadsFullCatalog() {
        ClarificationPromptTemplateCatalog.Catalog parsed = catalog.parse("""
                {
                  "version": 1,
                  "builtin": {
                    "bootstrap": { "content": "首次正文" },
                    "reclarify": { "content": "再次正文" }
                  },
                  "custom": [
                    { "id": "custom-1", "name": "  盘问清单  ", "content": "自定义正文" }
                  ],
                  "defaults": { "first": "custom-1", "reclarify": "reclarify" }
                }
                """);

        assertEquals(1, parsed.version());
        assertEquals("首次正文", parsed.builtin().get("bootstrap").content());
        assertEquals("再次正文", parsed.builtin().get("reclarify").content());
        assertEquals(1, parsed.custom().size());
        assertEquals("custom-1", parsed.custom().get(0).id());
        assertEquals("盘问清单", parsed.custom().get(0).name());
        assertEquals("自定义正文", parsed.custom().get(0).content());
        assertEquals("custom-1", parsed.defaults().first());
        assertEquals("reclarify", parsed.defaults().reclarify());
    }

    @Test
    void parseReturnsNullForBlankValue() {
        assertNull(catalog.parse(null));
        assertNull(catalog.parse(""));
        assertNull(catalog.parse("   "));
    }

    @Test
    void parseRejectsBrokenJson() {
        assertThrows(IllegalArgumentException.class, () -> catalog.parse("{not-json"));
    }

    @Test
    void parseRejectsUnknownOrMissingVersion() {
        assertThrows(IllegalArgumentException.class, () -> catalog.parse("{\"version\":2}"));
        assertThrows(IllegalArgumentException.class, () -> catalog.parse("{}"));
    }

    @Test
    void parseAcceptsCatalogWithoutBuiltinCustomOrDefaults() {
        ClarificationPromptTemplateCatalog.Catalog parsed = catalog.parse("{\"version\":1}");

        assertTrue(parsed.builtin().isEmpty());
        assertTrue(parsed.custom().isEmpty());
        assertNull(parsed.defaults());
    }

    @Test
    void parseRejectsUnknownBuiltinKey() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.parse("{\"version\":1,\"builtin\":{\"first\":{\"content\":\"x\"}}}"));
    }

    @Test
    void parseRejectsBlankOrOversizedBuiltinContent() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.parse("{\"version\":1,\"builtin\":{\"bootstrap\":{\"content\":\"  \"}}}"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.parse("{\"version\":1,\"builtin\":{\"bootstrap\":{\"content\":\""
                        + "x".repeat(10001) + "\"}}}"));
    }

    @Test
    void parseAllowsEmptyCustomContent() {
        ClarificationPromptTemplateCatalog.Catalog parsed = catalog.parse(
                "{\"version\":1,\"custom\":[{\"id\":\"custom-1\",\"name\":\"空白\",\"content\":\"\"}]}");

        assertEquals("", parsed.custom().get(0).content());
    }

    @Test
    void parseRejectsCustomIdWithoutPrefixOrDuplicate() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.parse("{\"version\":1,\"custom\":[{\"id\":\"abc\",\"name\":\"A\",\"content\":\"\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> catalog.parse(
                "{\"version\":1,\"custom\":["
                        + "{\"id\":\"custom-1\",\"name\":\"A\",\"content\":\"\"},"
                        + "{\"id\":\"custom-1\",\"name\":\"B\",\"content\":\"\"}]}"));
    }

    @Test
    void parseRejectsDuplicateNameAfterTrim() {
        assertThrows(IllegalArgumentException.class, () -> catalog.parse(
                "{\"version\":1,\"custom\":["
                        + "{\"id\":\"custom-1\",\"name\":\" 同名 \",\"content\":\"\"},"
                        + "{\"id\":\"custom-2\",\"name\":\"同名\",\"content\":\"\"}]}"));
    }

    @Test
    void parseRejectsInvalidCustomName() {
        assertThrows(IllegalArgumentException.class, () -> catalog.parse(
                "{\"version\":1,\"custom\":[{\"id\":\"custom-1\",\"name\":\"   \",\"content\":\"\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> catalog.parse(
                "{\"version\":1,\"custom\":[{\"id\":\"custom-1\",\"name\":\""
                        + "x".repeat(51) + "\",\"content\":\"\"}]}"));
    }

    @Test
    void parseRejectsOversizedCustomContent() {
        assertThrows(IllegalArgumentException.class, () -> catalog.parse(
                "{\"version\":1,\"custom\":[{\"id\":\"custom-1\",\"name\":\"A\",\"content\":\""
                        + "x".repeat(10001) + "\"}]}"));
    }

    @Test
    void parseRejectsMoreThanHundredCustomTemplates() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            items.add("{\"id\":\"custom-" + i + "\",\"name\":\"N" + i + "\",\"content\":\"\"}");
        }

        assertThrows(IllegalArgumentException.class,
                () -> catalog.parse("{\"version\":1,\"custom\":[" + String.join(",", items) + "]}"));
    }

    @Test
    void parseRejectsDefaultReferencingUnknownTemplate() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.parse("{\"version\":1,\"defaults\":{\"first\":\"missing\"}}"));
    }

    @Test
    void parseAcceptsBuiltinDefaultReferenceWithoutContentOverride() {
        ClarificationPromptTemplateCatalog.Catalog parsed =
                catalog.parse("{\"version\":1,\"defaults\":{\"first\":\"reclarify\"}}");

        assertEquals("reclarify", parsed.defaults().first());
        assertNull(parsed.defaults().reclarify());

        ClarificationPromptTemplateCatalog.Catalog read =
                catalog.parseForRead("{\"version\":1,\"defaults\":{\"first\":\"reclarify\"}}");
        assertEquals("reclarify", read.defaults().first());
    }

    @Test
    void parseForReadDropsUnknownDefaultReferenceButKeepsCatalog() {
        ClarificationPromptTemplateCatalog.Catalog parsed = catalog.parseForRead(
                "{\"version\":1,\"custom\":[{\"id\":\"custom-1\",\"name\":\"A\",\"content\":\"\"}],"
                        + "\"defaults\":{\"first\":\"missing\",\"reclarify\":\"custom-1\"}}");

        assertEquals(1, parsed.custom().size());
        assertNull(parsed.defaults().first());
        assertEquals("custom-1", parsed.defaults().reclarify());
    }

    @Test
    void parseDropsBlankDefaults() {
        ClarificationPromptTemplateCatalog.Catalog parsed =
                catalog.parse("{\"version\":1,\"defaults\":{\"first\":\"  \",\"reclarify\":null}}");

        assertNull(parsed.defaults());
    }

    @Test
    void serializeRoundTripsNormalizedCatalog() {
        ClarificationPromptTemplateCatalog.Catalog parsed = catalog.parse("""
                {
                  "version": 1,
                  "builtin": { "bootstrap": { "content": "首次正文" } },
                  "custom": [ { "id": "custom-1", "name": "清单", "content": "" } ],
                  "defaults": { "first": "custom-1" }
                }
                """);

        String json = catalog.serialize(parsed);
        ClarificationPromptTemplateCatalog.Catalog again = catalog.parse(json);

        assertEquals(parsed, again);
        assertFalse(json.contains("null"));
    }

    @Test
    void serializeRejectsInvalidCatalog() {
        ClarificationPromptTemplateCatalog.Catalog invalid = new ClarificationPromptTemplateCatalog.Catalog(
                1, Map.of(), List.of(),
                new ClarificationPromptTemplateCatalog.Defaults("missing", null));

        assertThrows(IllegalArgumentException.class, () -> catalog.serialize(invalid));
    }
}
