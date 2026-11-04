package com.aliyun.autowonder.integration.wecom;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;

/** 企微智能机器人图片下载与解密；下载链接五分钟有效，内容用 aeskey 加密。 */
@Component
public class WecomImageDownloader {
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private final OkHttpClient http;

    public WecomImageDownloader() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .callTimeout(Duration.ofSeconds(20))
                .build();
    }

    public byte[] download(String url, String aesKey) {
        validate(url);
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("企业微信图片下载失败 status=" + response.code());
            }
            validate(response.request().url().toString());
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("企业微信图片内容为空");
            }
            byte[] bytes = readBounded(body);
            return aesKey == null || aesKey.isBlank() ? bytes : decrypt(bytes, aesKey);
        } catch (IOException e) {
            throw new IllegalStateException("企业微信图片下载失败", e);
        }
    }

    private static byte[] readBounded(ResponseBody body) throws IOException {
        long length = body.contentLength();
        if (length > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("企业微信图片超过大小上限");
        }
        try (InputStream input = body.byteStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(length > 0 ? (int) length : 8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (out.size() + read > MAX_IMAGE_BYTES) {
                    throw new IllegalStateException("企业微信图片超过大小上限");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    static byte[] decrypt(byte[] encrypted, String aesKey) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(aesKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("企业微信图片密钥无效", e);
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("企业微信图片密钥长度无效");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(key, 0, 16));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("企业微信图片解密失败", e);
        }
    }

    private static void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("企业微信图片地址无效", e);
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equals("qq.com") || host.endsWith(".qq.com"))) {
            throw new IllegalArgumentException("企业微信图片地址无效");
        }
    }
}
