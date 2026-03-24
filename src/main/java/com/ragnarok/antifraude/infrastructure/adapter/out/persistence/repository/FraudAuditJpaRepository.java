package com.ragnarok.antifraude.infrastructure.adapter.out.persistence.repository;

import com.ragnarok.antifraude.infrastructure.adapter.out.persistence.entity.FraudAuditEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface FraudAuditJpaRepository extends JpaRepository<FraudAuditEntity, Long> {

    List<FraudAuditEntity> findByPlayerIdOrderByCreatedAtDesc(Long playerId, Pageable pageable);

    @Query("SELECT COUNT(a) FROM FraudAuditEntity a WHERE a.playerId = :playerId AND a.verdict = 'BLOCKED' AND a.createdAt >= :since")
    long countBlocksByPlayerSince(Long playerId, Instant since);
}
