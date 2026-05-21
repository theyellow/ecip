package io.emcip.admin.api.dto;

import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = UserResponse.UserResponseBuilder.class)
public class UserResponse {
    Long id;
    String username;
    String email;
    Role role;
    UUID tenantId;
    String tenantName;
    boolean enabled;
    Instant createdAt;

    @JsonPOJOBuilder(withPrefix = "")
    public static final class UserResponseBuilder {}
}
