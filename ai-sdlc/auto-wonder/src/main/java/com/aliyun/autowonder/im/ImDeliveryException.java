package com.aliyun.autowonder.im;

public final class ImDeliveryException extends RuntimeException {
    private final boolean retryable;
    private final boolean unknown;
    private final String providerCode;
    private final String providerRequestId;

    public ImDeliveryException(String provider, boolean retryable, String providerCode,
                               String providerRequestId, Throwable cause) {
        this(provider, retryable, false, providerCode, providerRequestId, cause);
    }

    private ImDeliveryException(String provider, boolean retryable, boolean unknown,
                                String providerCode, String providerRequestId, Throwable cause) {
        super(message(provider, retryable, providerCode, providerRequestId), cause);
        this.retryable = retryable;
        this.unknown = unknown;
        this.providerCode = safe(providerCode);
        this.providerRequestId = safe(providerRequestId);
    }

    /** 帧已写出但未收到确认：结果未知，禁止自动重发。 */
    public static ImDeliveryException outcomeUnknown(String provider, String providerCode) {
        return new ImDeliveryException(provider, false, true, providerCode, null, null);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isUnknown() {
        return unknown;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    private static String message(String provider, boolean retryable, String code, String requestId) {
        return "IM delivery failed provider=" + safeOrUnknown(provider)
                + " retryable=" + retryable
                + " providerCode=" + safeOrUnknown(code)
                + " providerRequestId=" + safeOrUnknown(requestId);
    }

    private static String safeOrUnknown(String value) {
        String safe = safe(value);
        return safe == null ? "unknown" : safe;
    }

    private static String safe(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9_.:-]{1,128}$")) {
            return null;
        }
        return value;
    }
}
