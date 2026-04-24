package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.TelegramConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface TelegramConfigRepository extends ReactiveCrudRepository<TelegramConfig, Long> {}
