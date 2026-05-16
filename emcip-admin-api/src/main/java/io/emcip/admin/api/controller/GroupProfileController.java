package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Tag(name = "Group Profiles", description = "Manage Telegram group configuration profiles")
public class GroupProfileController {

    private final GroupProfileRepository repository;

    @Operation(summary = "List all group profiles")
    @GetMapping
    public Flux<GroupProfile> listAll() {
        return repository.findAll();
    }

    @Operation(summary = "Get a group profile by chat ID")
    @GetMapping("/{chatId}")
    public Mono<ResponseEntity<GroupProfile>> getByChatId(@PathVariable("chatId") Long chatId) {
        return repository
                .findByTelegramChatId(chatId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().<GroupProfile>build());
    }

    @Operation(summary = "Create a group profile")
    @PostMapping
    public Mono<ResponseEntity<GroupProfile>> create(@RequestBody GroupProfile profile) {
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        return repository
                .save(profile)
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED).body(saved));
    }

    @Operation(summary = "Update a group profile")
    @PutMapping("/{chatId}")
    public Mono<ResponseEntity<GroupProfile>> update(
            @PathVariable("chatId") Long chatId, @RequestBody GroupProfile update) {
        return repository
                .findByTelegramChatId(chatId)
                .flatMap(
                        existing -> {
                            existing.setName(update.getName());
                            existing.setDescription(update.getDescription());
                            existing.setModerationLevel(update.getModerationLevel());
                            existing.setAutoRespond(update.isAutoRespond());
                            existing.setWelcomeMessage(update.getWelcomeMessage());
                            existing.setUpdatedAt(Instant.now());
                            return repository.save(existing);
                        })
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().<GroupProfile>build());
    }

    @Operation(summary = "Delete a group profile")
    @DeleteMapping("/{chatId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("chatId") Long chatId) {
        return repository
                .findByTelegramChatId(chatId)
                .flatMap(
                        existing ->
                                repository
                                        .delete(existing)
                                        .thenReturn(ResponseEntity.<Void>noContent().<Void>build()))
                .defaultIfEmpty(ResponseEntity.notFound().<Void>build());
    }
}
