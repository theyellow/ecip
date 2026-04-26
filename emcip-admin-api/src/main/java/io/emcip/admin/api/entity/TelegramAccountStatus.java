package io.emcip.admin.api.entity;

public enum TelegramAccountStatus {
    UNCONFIGURED,
    AWAITING_CODE,
    AWAITING_PASSWORD,
    ACTIVE,
    DISCONNECTED
}
