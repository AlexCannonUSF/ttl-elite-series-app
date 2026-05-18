package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.OddsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface OddsSnapshotRepository extends JpaRepository<OddsSnapshot, Long> {

    boolean existsByTrackedEventIdAndSideAndObservedAtAndPriceDecimalAndSourceId(String trackedEventId,
                                                                                String side,
                                                                                LocalDateTime observedAt,
                                                                                double priceDecimal,
                                                                                String sourceId);

    @Modifying
    @Query("delete from OddsSnapshot o where o.observedAt < :cutoff")
    void deleteByObservedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
