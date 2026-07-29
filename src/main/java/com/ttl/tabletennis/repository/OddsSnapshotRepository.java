package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.OddsSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OddsSnapshotRepository extends JpaRepository<OddsSnapshot, Long> {

    boolean existsByTrackedEventIdAndSideAndObservedAtAndPriceDecimalAndSourceId(String trackedEventId,
                                                                                String side,
                                                                                LocalDateTime observedAt,
                                                                                double priceDecimal,
                                                                                String sourceId);

    @Modifying
    @Query("delete from OddsSnapshot o where o.observedAt < :cutoff")
    void deleteByObservedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    @Query("""
            select o from OddsSnapshot o
            where o.bookerEventId = :bookerEventId
              and o.side = :side
              and o.observedAt >= :placedAt
              and o.observedAt <= :until
            order by
              case
                when o.marketState = 'CLOSED' then 0
                when o.marketState = 'SUSPENDED' then 1
                else 2
              end,
              o.observedAt desc
            """)
    List<OddsSnapshot> findClosingCandidates(@Param("bookerEventId") String bookerEventId,
                                             @Param("side") String side,
                                             @Param("placedAt") LocalDateTime placedAt,
                                             @Param("until") LocalDateTime until,
                                             Pageable pageable);
}
