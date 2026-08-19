package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.ScrapeRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScrapeRunRepository extends JpaRepository<ScrapeRun, Long> {

    Optional<ScrapeRun> findByRunNumber(Integer runNumber);

    @Query("SELECT COALESCE(MAX(r.runNumber), 0) FROM ScrapeRun r")
    int findMaxRunNumber();

    @Query("""
           SELECT r FROM ScrapeRun r
           WHERE (:status IS NULL OR UPPER(r.status) = UPPER(:status))
             AND (:mode IS NULL OR UPPER(r.mode) = UPPER(:mode))
           ORDER BY r.startedAt DESC, r.id DESC
           """)
    List<ScrapeRun> findRecent(@Param("status") String status,
                               @Param("mode") String mode,
                               Pageable pageable);

    /**
     * Used by {@code ScrapeRunOrphanCleanup} to find rows whose JVM died
     * mid-run (status stuck in {@code RUNNING}). The derived query is
     * case-sensitive — callers should pass the exact value
     * {@link ScrapeRun} stores (uppercase {@code RUNNING}).
     */
    List<ScrapeRun> findByStatus(String status);
}
