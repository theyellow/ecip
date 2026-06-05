package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.service.GroupProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Tag(name = "Group Profiles", description = "Manage Telegram group configuration profiles")
public class GroupProfileController {

    private final GroupProfileService service;

    @Operation(summary = "List all group profiles")
    @GetMapping
    public Flux<GroupProfile> listAll() {
        return service.findAll();
    }

    @Operation(summary = "Get a group profile by chat ID")
    @GetMapping("/{chatId}")
    public Mono<ResponseEntity<GroupProfile>> getByChatId(@PathVariable("chatId") Long chatId) {
        return service.findByChatId(chatId).map(ResponseEntity::ok);
    }

    @Operation(summary = "Create a group profile")
    @PostMapping
    public Mono<ResponseEntity<GroupProfile>> create(@Valid @RequestBody GroupProfile profile) {
        return service.create(profile)
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED).body(saved));
    }

    @Operation(summary = "Update a group profile")
    @PutMapping("/{chatId}")
    public Mono<ResponseEntity<GroupProfile>> update(
            @PathVariable("chatId") Long chatId, @Valid @RequestBody GroupProfile update) {
        return service.update(chatId, update).map(ResponseEntity::ok);
    }

    @Operation(summary = "Delete a group profile")
    @DeleteMapping("/{chatId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("chatId") Long chatId) {
        return service.delete(chatId).thenReturn(ResponseEntity.<Void>noContent().<Void>build());
    }

    @Operation(summary = "List accounts watching a group")
    @GetMapping("/{chatId}/watchers")
    public Flux<java.util.Map<String, Object>> getWatchers(@PathVariable("chatId") Long chatId) {
        return service.findWatchersByChatId(chatId);
    }
}
