package com.ttl.tabletennis.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertNull;

class LiveTransactionBoundaryTests {

    @Test
    void paperSyncDoesNotPinAConnectionAcrossRemoteMarketWork() throws Exception {
        assertNull(PaperTradingService.class
                .getMethod("syncLiveSession", String.class, String.class, Integer.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void liveScoreScrapesDoNotPinAConnectionAcrossRemoteIo() throws Exception {
        assertNull(OddsValueEngineService.class
                .getMethod("liveScoreSnapshots", int.class, boolean.class)
                .getAnnotation(Transactional.class));
        assertNull(OddsValueEngineService.class
                .getMethod("liveScoreSnapshotsForEventIds", Collection.class, int.class, boolean.class)
                .getAnnotation(Transactional.class));
    }
}
