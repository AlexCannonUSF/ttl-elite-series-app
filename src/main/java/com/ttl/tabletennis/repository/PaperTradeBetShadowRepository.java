package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeBetShadow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaperTradeBetShadowRepository extends JpaRepository<PaperTradeBetShadow, Long> {

    Optional<PaperTradeBetShadow> findBySourceBetId(Long sourceBetId);
}
