package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.AdminUser;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface AdminUserRepository extends ReactiveCrudRepository<AdminUser, Long> {

    Mono<AdminUser> findByUsername(String username);
}
