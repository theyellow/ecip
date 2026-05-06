package io.emcip.admin.api.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("account_watched_groups")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountWatchedGroup {

    @Id private Long id;

    @Column("account_id")
    private UUID accountId;

    @Column("group_profile_id")
    private Long groupProfileId;

    @Column("created_at")
    private Instant createdAt;
}
