package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaperTradeBetRepository extends JpaRepository<PaperTradeBet, Long> {

    List<PaperTradeBet> findByStatusOrderByPlacedAtAsc(String status, Pageable pageable);

    List<PaperTradeBet> findByStatusAndPendingEvidenceNextPollAtLessThanEqualOrderByPendingEvidenceNextPollAtAsc(String status,
                                                                                                                  LocalDateTime pendingEvidenceNextPollAt,
                                                                                                                  Pageable pageable);

    List<PaperTradeBet> findBySessionIdAndStatusOrderByPlacedAtDesc(Long sessionId, String status);

    List<PaperTradeBet> findBySessionIdAndStatusOrderByPlacedAtAsc(Long sessionId, String status);

    List<PaperTradeBet> findBySessionIdAndStatusInOrderByPlacedAtDesc(Long sessionId,
                                                                       Collection<String> statuses,
                                                                       Pageable pageable);

    List<PaperTradeBet> findByStatusInOrderBySettledAtDesc(Collection<String> statuses,
                                                            Pageable pageable);

    List<PaperTradeBet> findBySessionIdAndStatusInOrderBySettledAtAsc(Long sessionId,
                                                                       Collection<String> statuses);

    List<PaperTradeBet> findBySessionIdOrderByPlacedAtDesc(Long sessionId, Pageable pageable);

    boolean existsBySessionIdAndDedupeKey(Long sessionId, String dedupeKey);

    boolean existsBySessionIdAndDedupeKeyAndStatus(Long sessionId, String dedupeKey, String status);

    boolean existsBySessionIdAndEventKeyAndStatus(Long sessionId, String eventKey, String status);

    boolean existsBySessionIdAndResultMatchId(Long sessionId, Long resultMatchId);

    Optional<PaperTradeBet> findFirstBySessionIdAndResultMatchIdOrderByIdAsc(Long sessionId, Long resultMatchId);

    Optional<PaperTradeBet> findFirstByResultMatchIdOrderBySettledAtDesc(Long resultMatchId);

    long countBySessionIdAndStatus(Long sessionId, String status);

    long countByStatusAndPendingEvidenceReasonAndPendingEvidenceUpdatedAtGreaterThanEqual(String status,
                                                                                          String pendingEvidenceReason,
                                                                                          LocalDateTime pendingEvidenceUpdatedAt);
}
