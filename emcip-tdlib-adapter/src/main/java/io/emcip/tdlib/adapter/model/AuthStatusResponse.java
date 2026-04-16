package io.emcip.tdlib.adapter.model;

public record AuthStatusResponse(boolean initialized, boolean authorized, String status) {}
