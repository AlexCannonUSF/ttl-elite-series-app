package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PredictionDiffLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface PredictionDiffLogRepository extends JpaRepository<PredictionDiffLog, Long> {

    /** #124 — Retention prune by computedAtUtc. */
    @Modifying
    @Query("DELETE FROM PredictionDiffLog p WHERE p.computedAtUtc < :cutoff")
    int deleteByComputedAtUtcBefore(@Param("cutoff") LocalDateTime cutoff);
}
