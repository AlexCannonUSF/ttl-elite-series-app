package com.ttl.tabletennis.service.papertrade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ObservationClassifierTests {

    @Test
    void nullRow_defaultsToMarketBoard() {
        assertEquals(ObservationClassifier.OBSERVATION_SOURCE_MARKET_BOARD,
                ObservationClassifier.inferObservationSourceKind(null));
    }

    @Test
    void hasExplicitCompletionSignal_nullRowIsFalse() {
        assertFalse(ObservationClassifier.hasExplicitCompletionSignal(null));
    }

    @Test
    void constants_areStable() {
        // String literals get inlined, so this is mostly a guard against renames.
        assertEquals("MARKET_BOARD", ObservationClassifier.OBSERVATION_SOURCE_MARKET_BOARD);
        assertEquals("SCORE_FEED", ObservationClassifier.OBSERVATION_SOURCE_SCORE_FEED);
    }

    // Full source-type / source classification cases are exercised through
    // PaperTradingServiceTests, which builds real LiveOddsRecommendationDto
    // instances through the production query path.
}
