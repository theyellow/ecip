package io.emcip.tdlib.adapter.model;

public sealed interface AuthRequest {
    record Initialize(String phoneNumber, Integer apiId, String apiHash, String sessionString)
            implements AuthRequest {}

    record PhoneNumber(String phoneNumber) implements AuthRequest {}

    record Code(String code) implements AuthRequest {}

    record Password(String password) implements AuthRequest {}
}
