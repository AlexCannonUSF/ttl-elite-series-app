package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeBetShadow;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.PaperTradeSessionShadow;
import com.ttl.tabletennis.repository.PaperTradeBetShadowRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionShadowRepository;
import com.ttl.tabletennis.util.CorrelationContext;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class PaperTradingShadowService {

    private final PaperTradeSessionShadowRepository sessionShadowRepository;
    private final PaperTradeBetShadowRepository betShadowRepository;

    public PaperTradingShadowService(PaperTradeSessionShadowRepository sessionShadowRepository,
                                     PaperTradeBetShadowRepository betShadowRepository) {
        this.sessionShadowRepository = sessionShadowRepository;
        this.betShadowRepository = betShadowRepository;
    }

    public PaperTradeSessionShadow mirrorSession(PaperTradeSession session) {
        if (session == null || session.getId() == null) {
            return null;
        }
        PaperTradeSessionShadow shadow = sessionShadowRepository.findBySourceSessionId(session.getId())
                .orElseGet(PaperTradeSessionShadow::new);
        BeanUtils.copyProperties(session, shadow, "id", "sourceSessionId", "mirroredAt", "correlationId");
        shadow.setSourceSessionId(session.getId());
        shadow.setMirroredAt(LocalDateTime.now());
        shadow.setCorrelationId(CorrelationContext.currentOrCreate());
        return sessionShadowRepository.save(shadow);
    }

    public void mirrorSessions(Iterable<PaperTradeSession> sessions) {
        if (sessions == null) {
            return;
        }
        for (PaperTradeSession session : sessions) {
            mirrorSession(session);
        }
    }

    public PaperTradeBetShadow mirrorBet(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null) {
            return null;
        }
        PaperTradeBetShadow shadow = betShadowRepository.findBySourceBetId(bet.getId())
                .orElseGet(PaperTradeBetShadow::new);
        BeanUtils.copyProperties(bet, shadow, "id", "sourceBetId", "mirroredAt", "correlationId");
        shadow.setSourceBetId(bet.getId());
        shadow.setMirroredAt(LocalDateTime.now());
        shadow.setCorrelationId(CorrelationContext.currentOrCreate());
        return betShadowRepository.save(shadow);
    }

    public void clearAll() {
        betShadowRepository.deleteAllInBatch();
        sessionShadowRepository.deleteAllInBatch();
    }
}
