package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.ScrapeError;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScrapeErrorRepository extends JpaRepository<ScrapeError, Long> {

    @Query("""
           SELECT e FROM ScrapeError e
           WHERE (:runNumber IS NULL OR e.runNumber = :runNumber)
           ORDER BY e.occurredAt DESC, e.id DESC
           """)
    List<ScrapeError> findRecent(@Param("runNumber") Integer runNumber, Pageable pageable);
}
