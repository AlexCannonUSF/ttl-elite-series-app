package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PredictionModelRegistryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PredictionModelRegistryRepository extends JpaRepository<PredictionModelRegistryEntry, Long> {

    Optional<PredictionModelRegistryEntry> findByModelVersion(String modelVersion);

    @Query("""
           SELECT m FROM PredictionModelRegistryEntry m
           WHERE (:family IS NULL OR UPPER(m.modelFamily) = UPPER(:family))
           ORDER BY m.createdAt DESC, m.id DESC
           """)
    List<PredictionModelRegistryEntry> findRecentByFamily(@Param("family") String family, Pageable pageable);

    @Query("""
           SELECT m FROM PredictionModelRegistryEntry m
           WHERE UPPER(m.modelFamily) = UPPER(:family) AND m.active = true
           ORDER BY m.createdAt DESC, m.id DESC
           """)
    List<PredictionModelRegistryEntry> findActiveByFamily(@Param("family") String family, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE PredictionModelRegistryEntry m
              SET m.active = false
            WHERE UPPER(m.modelFamily) = UPPER(:family)
           """)
    int deactivateFamily(@Param("family") String family);
}
