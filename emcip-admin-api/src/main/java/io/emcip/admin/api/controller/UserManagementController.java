package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.PasswordResetRequest;
import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.dto.UserResponse;
import io.emcip.admin.api.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Manage admin users and their roles")
public class UserManagementController {

    private final UserManagementService userManagementService;

    @Operation(summary = "List all admin users")
    @GetMapping
    @PreAuthorize("hasAuthority('USERS_READ')")
    public Flux<UserResponse> listUsers() {
        return userManagementService.findAll();
    }

    @Operation(summary = "Create a new admin user")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    public Mono<UserResponse> createUser(@Valid @RequestBody UserRequest req) {
        return userManagementService.create(req);
    }

    @Operation(summary = "Update a user's role, tenant, or enabled status")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    public Mono<UserResponse> updateUser(
            @PathVariable Long id, @Valid @RequestBody UserRequest req) {
        return userManagementService.update(id, req);
    }

    @Operation(summary = "Delete a user")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    public Mono<Void> deleteUser(@PathVariable Long id, Mono<Principal> principal) {
        return principal
                .map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(callerUsername -> userManagementService.delete(id, callerUsername));
    }

    @Operation(summary = "Reset a user's password")
    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    public Mono<Void> resetPassword(
            @PathVariable Long id, @Valid @RequestBody PasswordResetRequest req) {
        return userManagementService.resetPassword(id, req.getNewPassword());
    }
}
