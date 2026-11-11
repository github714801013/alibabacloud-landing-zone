package com.aliyun.autowonder.integration.wecom;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WecomImageDownloaderTest {
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void decryptsPayloadProducedByTheDocumentedAes256CbcScheme() {
        byte[] encrypted = Base64.getDecoder().decode("nZExwBxwdlrRpqZOGSt+Bg9CMkwuf970Qk3W10b4hDI=");
        assertEquals("企业微信图片 test",
                new String(WecomImageDownloader.decrypt(encrypted, KEY), StandardCharsets.UTF_8));
    }

    @Test
    void officialImageUrlPassesValidation() {
        assertDoesNotThrow(() -> WecomImageDownloader.validate(
                "https://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com/BHoPdA3/7571665296904772241?sign=test"));
    }

    @Test
    void rejectsInvalidKeysAndNonWecomUrls() {
        assertThrows(IllegalArgumentException.class, () -> WecomImageDownloader.decrypt(
                new byte[16], Base64.getEncoder().encodeToString(new byte[16])));
        assertThrows(IllegalArgumentException.class,
                () -> new WecomImageDownloader().download(
                        "http://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com/a.png", null));
        assertThrows(IllegalArgumentException.class,
                () -> new WecomImageDownloader().download("https://evil.example/a.png", null));
        assertThrows(IllegalArgumentException.class,
                () -> WecomImageDownloader.validate(
                        "https://other-bucket.cos.ap-guangzhou.myqcloud.com/a.png"));
    }
}
