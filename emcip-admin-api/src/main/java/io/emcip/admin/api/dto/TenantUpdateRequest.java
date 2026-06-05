package io.emcip.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "Editable tenant fields (name is immutable)")
@Getter
@Setter
public class TenantUpdateRequest {

    @Size(max = 1000, message = "description must be 1000 characters or fewer")
    private String description;

    @Size(max = 100, message = "llmModelOverride must be 100 characters or fewer")
    private String llmModelOverride;
}
