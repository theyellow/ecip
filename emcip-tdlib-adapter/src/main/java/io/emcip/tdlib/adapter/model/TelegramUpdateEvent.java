package io.emcip.tdlib.adapter.model;

import java.time.Instant;

public record TelegramUpdateEvent(
        String eventId, String updateType, int constructor, Instant ingestedAt) {}
