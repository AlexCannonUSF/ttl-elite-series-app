package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PaperTradeLearningSampleRepository extends JpaRepository<PaperTradeLearningSample, Long> {

    boolean existsByBetId(Long betId);

    List<PaperTradeLearningSample> findByStatusInOrderBySettledAtDesc(Collection<String> statuses, Pageable pageable);
}
