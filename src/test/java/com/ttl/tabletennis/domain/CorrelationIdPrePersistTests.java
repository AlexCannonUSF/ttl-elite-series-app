package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.CorrelationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorrelationIdPrePersistTests {

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void appendStyleEntitiesAdoptScopedCorrelationId() {
        try (CorrelationContext.Scope ignored = CorrelationContext.open("corr-phase-00")) {
            ScrapeRun scrapeRun = new ScrapeRun();
            scrapeRun.prePersist();

            ScrapeError scrapeError = new ScrapeError();
            scrapeError.prePersist();

            OddsQuote oddsQuote = new OddsQuote();
            oddsQuote.prePersist();

            PaperTradeDecisionSample decisionSample = new PaperTradeDecisionSample();
            decisionSample.prePersist();

            PaperTradeLearningSample learningSample = new PaperTradeLearningSample();
            learningSample.prePersist();

            ValueOpportunity valueOpportunity = new ValueOpportunity();
            valueOpportunity.prePersist();

            TrackedMatchObservation observation = new TrackedMatchObservation();
            observation.prePersist();

            SettlementDiffLog settlementDiffLog = new SettlementDiffLog();
            settlementDiffLog.setBetId(42L);
            settlementDiffLog.prePersist();

            assertEquals("corr-phase-00", scrapeRun.getCorrelationId());
            assertEquals("corr-phase-00", scrapeError.getCorrelationId());
            assertEquals("corr-phase-00", oddsQuote.getCorrelationId());
            assertEquals("corr-phase-00", decisionSample.getCorrelationId());
            assertEquals("corr-phase-00", learningSample.getCorrelationId());
            assertEquals("corr-phase-00", valueOpportunity.getCorrelationId());
            assertEquals("corr-phase-00", observation.getCorrelationId());
            assertEquals("corr-phase-00", settlementDiffLog.getCorrelationId());
        }
    }
}
