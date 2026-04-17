package io.emcip.tdlib.adapter.model;

public sealed interface AuthRequest {
    record PhoneNumber(String phoneNumber) implements AuthRequest {}

    record Code(String code) implements AuthRequest {}

    record Password(String password) implements AuthRequest {}
}
