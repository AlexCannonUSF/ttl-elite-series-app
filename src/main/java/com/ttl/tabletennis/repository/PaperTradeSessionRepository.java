package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface PaperTradeSessionRepository extends JpaRepository<PaperTradeSession, Long> {

    Optional<PaperTradeSession> findFirstByStatusOrderByIdAsc(String status);

    Optional<PaperTradeSession> findFirstByStatusOrderByIdDesc(String status);

    List<PaperTradeSession> findByStatusOrderByIdDesc(String status);

    List<PaperTradeSession> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * Serializes placement/settlement syncs on the active ledger row. The
     * database lock is held until the surrounding transaction commits, so a
     * scheduled sync and a manual sync cannot both price exposure from the
     * same stale bankroll snapshot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from PaperTradeSession s
            where s.id = (
                select max(s2.id) from PaperTradeSession s2 where s2.status = :status
            )
            """)
    Optional<PaperTradeSession> findLatestByStatusForUpdate(@Param("status") String status);
}
