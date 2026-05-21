package io.emcip.admin.api.dto;

import io.emcip.admin.api.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
public class UserRequest {
    @NotBlank private String username;

    @NotBlank @Email private String email;

    private String password; // required on create, optional on update

    @NotNull private Role role;

    private UUID tenantId; // required when role == TENANT_ADMIN

    private Boolean enabled; // optional; only applied on update when non-null
}
