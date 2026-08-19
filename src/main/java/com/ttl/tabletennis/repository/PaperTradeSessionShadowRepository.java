package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeSessionShadow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaperTradeSessionShadowRepository extends JpaRepository<PaperTradeSessionShadow, Long> {

    Optional<PaperTradeSessionShadow> findBySourceSessionId(Long sourceSessionId);
}
