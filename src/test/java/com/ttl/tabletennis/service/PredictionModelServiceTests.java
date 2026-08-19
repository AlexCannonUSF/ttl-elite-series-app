package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.util.MatchResultParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class PredictionModelServiceTests {

    @Autowired
    private PredictionModelService predictionModelService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PaperTradeLearningSampleRepository learningSampleRepository;

    @Autowired
    private OddsSnapshotRepository oddsSnapshotRepository;

    @Test
    void trainModelsAndPredictByFamilyAndVersion() {
        Player alpha = playerRepository.save(new Player("Alpha", "One"));
        Player beta = playerRepository.save(new Player("Beta", "Two"));
        Player gamma = playerRepository.save(new Player("Gamma", "Three"));
        Player delta = playerRepository.save(new Player("Delta", "Four"));

        LocalDate start = LocalDate.now().minusDays(220);
        for (int i = 0; i < 140; i++) {
            Player p1 = (i % 2 == 0) ? alpha : beta;
            Player p2 = (i % 3 == 0) ? gamma : delta;
            if (p1.getId().equals(p2.getId())) {
                p2 = gamma.getId().equals(p1.getId()) ? delta : gamma;
            }
            boolean p1Wins = (i % 5) != 0;
            String score = p1Wins ? "3:1" : "1:3";
            saveCompletedMatch("pm-" + i, p1, p2, score, start.plusDays(i));
        }

        LocalDateTime settledBase = LocalDateTime.now().minusDays(12);
        for (int i = 0; i < 20; i++) {
            saveLearningSample(
                    1_000L + i,
                    i % 4 == 0 ? PaperTradeBet.STATUS_LOST : PaperTradeBet.STATUS_WON,
                    false,
                    "PREMATCH",
                    0.60 + ((i % 3) * 0.02),
                    0.53,
                    24.0 + i,
                    i % 4 == 0 ? -18.0 : 11.5,
                    settledBase.plusHours(i)
            );
        }
        for (int i = 0; i < 20; i++) {
            saveLearningSample(
                    2_000L + i,
                    i % 3 == 0 ? PaperTradeBet.STATUS_LOST : PaperTradeBet.STATUS_WON,
                    true,
                    i % 2 == 0 ? "LIVE_MID" : "LIVE_LATE",
                    0.62 + ((i % 4) * 0.015),
                    i % 2 == 0 ? 0.41 : 0.58,
                    26.0 + i,
                    i % 3 == 0 ? -21.0 : 13.0,
                    settledBase.plusHours(30 + i)
            );
        }

        ModelTrainingReportDto report = predictionModelService.trainModels(null, null);

        assertNotNull(report);
        assertEquals(4, report.candidates().size());
        assertNotNull(report.championVersion());
        assertFalse(report.calibrationCurve().isEmpty());
        assertFalse(report.validationRegimes().isEmpty());
        assertTrue(report.validationRegimes().stream().anyMatch(regime -> "All Validation".equals(regime.label())));
        assertFalse(report.operationalRegimes().isEmpty());
        assertTrue(report.operationalRegimes().stream().anyMatch(regime -> "Prematch".equals(regime.label())));
        assertTrue(report.operationalRegimes().stream().anyMatch(regime -> "Live".equals(regime.label())));
        var adaptiveRegimes = predictionModelService.currentAdaptiveRegimeProfiles();
        assertFalse(adaptiveRegimes.isEmpty());
        assertTrue(adaptiveRegimes.stream().anyMatch(regime -> "Prematch".equals(regime.label())));
        assertTrue(adaptiveRegimes.stream().anyMatch(regime -> "Live".equals(regime.label())));

        PredictionModelService.PredictionSnapshot byFamily = predictionModelService.predict(
                alpha.getId(), beta.getId(), LocalDate.now(), "LOGISTIC");
        assertEquals("LOGISTIC", byFamily.modelFamily());
        assertTrue(byFamily.player1Probability() >= 0.0 && byFamily.player1Probability() <= 1.0);
        assertTrue(byFamily.player1Probability() >= 0.10 && byFamily.player1Probability() <= 0.90);

        PredictionModelService.PredictionSnapshot swapped = predictionModelService.predict(
                beta.getId(), alpha.getId(), LocalDate.now(), "LOGISTIC");
        assertEquals(1.0, byFamily.player1Probability() + swapped.player1Probability(), 0.000001);
        assertEquals(byFamily.player1Probability(), swapped.player2Probability(), 0.000001);
        Map<String, Double> swappedContributions = new HashMap<>();
        swapped.featureContributions().forEach(item -> swappedContributions.put(item.feature(), item.contribution()));
        byFamily.featureContributions().forEach(item -> {
            if (swappedContributions.containsKey(item.feature())) {
                assertEquals(-item.contribution(), swappedContributions.get(item.feature()), 0.0002,
                        "feature contribution must reverse sign when player order is swapped: " + item.feature());
            }
        });

        PredictionModelService.PredictionSnapshot byVersion = predictionModelService.predict(
                alpha.getId(), beta.getId(), LocalDate.now(), report.championVersion());
        assertEquals(report.championVersion(), byVersion.modelVersion());
        assertTrue(byVersion.player1ConfidenceLow() <= byVersion.player1ConfidenceHigh());
    }

    @Test
    void toBaseFeaturesShrinksThinHeadToHeadSignalsMoreThanDeepSamples() throws Exception {
        PredictionModelService target = AopTestUtils.getTargetObject(predictionModelService);
        Method toBaseFeatures = PredictionModelService.class.getDeclaredMethod(
                "toBaseFeatures",
                com.ttl.tabletennis.dto.MatchupFeatureVectorDto.class
        );
        toBaseFeatures.setAccessible(true);

        com.ttl.tabletennis.dto.MatchupFeatureVectorDto.PlayerFeatureDto player1 =
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.PlayerFeatureDto(
                        0.60, 0.58, 1540.0, 1580.0, 1590.0, 75.0, 0.05,
                        26.5, 2.0, 0.6, 0.35, 10.0, 10.0, 10.0,
                        0.72, 0.70, 0.68, 0.86
                );
        com.ttl.tabletennis.dto.MatchupFeatureVectorDto.PlayerFeatureDto player2 =
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.PlayerFeatureDto(
                        0.40, 0.42, 1490.0, 1510.0, 1505.0, 82.0, 0.06,
                        24.6, 2.3, -0.2, 0.42, 10.0, 10.0, 10.0,
                        0.70, 0.68, 0.66, 0.83
                );
        com.ttl.tabletennis.dto.MatchupFeatureVectorDto.ReliabilitySummaryDto reliabilitySummary =
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.ReliabilitySummaryDto(0.74, 0.92, 0.86, 0.83);
        com.ttl.tabletennis.dto.MatchupFeatureVectorDto.SignificanceSummaryDto thinSignificanceSummary =
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.SignificanceSummaryDto(
                        0.38, 0.11, 0.71, 0.69, 0.67, 0.84, 1, 4, 1,
                        "Baseline Stability", 0.84, "Head-to-Head", 0.11
                );
        com.ttl.tabletennis.dto.MatchupFeatureVectorDto.SignificanceSummaryDto deepSignificanceSummary =
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.SignificanceSummaryDto(
                        0.69, 0.69, 0.71, 0.69, 0.67, 0.84, 2, 5, 0,
                        "Baseline Stability", 0.84, "Schedule Strength", 0.67
                );

        com.ttl.tabletennis.dto.MatchupFeatureVectorDto thinVector = new com.ttl.tabletennis.dto.MatchupFeatureVectorDto(
                1L,
                2L,
                LocalDate.now(),
                1.0,
                0.0,
                1.0,
                0.11,
                player1,
                player2,
                0.64,
                0.66,
                0.59,
                0.57,
                0.6185,
                0.1185,
                reliabilitySummary,
                thinSignificanceSummary,
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.RatingIntervalDto(1440.0, 1740.0),
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.RatingIntervalDto(1340.0, 1670.0)
        );
        com.ttl.tabletennis.dto.MatchupFeatureVectorDto deepVector = new com.ttl.tabletennis.dto.MatchupFeatureVectorDto(
                1L,
                2L,
                LocalDate.now(),
                0.78,
                0.22,
                18.0,
                0.69,
                player1,
                player2,
                0.64,
                0.66,
                0.59,
                0.57,
                0.6185,
                0.1185,
                reliabilitySummary,
                deepSignificanceSummary,
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.RatingIntervalDto(1440.0, 1740.0),
                new com.ttl.tabletennis.dto.MatchupFeatureVectorDto.RatingIntervalDto(1340.0, 1670.0)
        );

        double[] thinFeatures = assertInstanceOf(double[].class, toBaseFeatures.invoke(target, thinVector));
        double[] deepFeatures = assertInstanceOf(double[].class, toBaseFeatures.invoke(target, deepVector));

        assertTrue(Math.abs(thinFeatures[0]) < Math.abs(deepFeatures[0]) * 0.35,
                "thin 1-0 style head-to-head signal should be much more heavily shrunk than a deep sample");
    }

    @Test
    void buildSamplesReturnsChronologicalOrderForWalkForwardTraining() throws Exception {
        PredictionModelService target = AopTestUtils.getTargetObject(predictionModelService);
        Player alpha = playerRepository.save(new Player("Chrono", "Alpha"));
        Player beta = playerRepository.save(new Player("Chrono", "Beta"));

        LocalDate base = LocalDate.now().minusDays(40);
        saveCompletedMatch("chrono-3", alpha, beta, "3:1", base.plusDays(20));
        saveCompletedMatch("chrono-1", alpha, beta, "3:1", base.plusDays(4));
        saveCompletedMatch("chrono-2", beta, alpha, "3:2", base.plusDays(11));

        Method buildSamples = PredictionModelService.class.getDeclaredMethod("buildSamples", LocalDate.class, LocalDate.class);
        buildSamples.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> samples = (List<Object>) buildSamples.invoke(target, base, base.plusDays(25));

        assertTrue(samples.size() >= 6);
        assertEquals(0, samples.size() % 2, "every source outcome must have an AB/BA training pair");
        Method matchDate = samples.get(0).getClass().getDeclaredMethod("matchDate");
        matchDate.setAccessible(true);
        Method baseFeatures = samples.get(0).getClass().getDeclaredMethod("baseFeatures");
        Method label = samples.get(0).getClass().getDeclaredMethod("label");
        Method identity = samples.get(0).getClass().getDeclaredMethod("identity");
        baseFeatures.setAccessible(true);
        label.setAccessible(true);
        identity.setAccessible(true);

        LocalDate first = assertInstanceOf(LocalDate.class, matchDate.invoke(samples.get(0)));
        LocalDate second = assertInstanceOf(LocalDate.class, matchDate.invoke(samples.get(1)));
        LocalDate third = assertInstanceOf(LocalDate.class, matchDate.invoke(samples.get(2)));

        assertTrue(!first.isAfter(second) && !second.isAfter(third),
                "training samples should be sorted chronologically before walk-forward validation");

        for (int i = 0; i < samples.size(); i += 2) {
            double[] forward = assertInstanceOf(double[].class, baseFeatures.invoke(samples.get(i)));
            double[] reverse = assertInstanceOf(double[].class, baseFeatures.invoke(samples.get(i + 1)));
            assertEquals(forward.length, reverse.length);
            for (int j = 0; j < forward.length; j++) {
                assertEquals(-forward[j], reverse[j], 0.0000000001);
            }
            assertEquals(1 - (int) label.invoke(samples.get(i)), (int) label.invoke(samples.get(i + 1)));
            assertTrue(identity.invoke(samples.get(i)).toString().endsWith("|AB"));
            assertTrue(identity.invoke(samples.get(i + 1)).toString().endsWith("|BA"));
        }
    }

    @Test
    void historicalMarketJoinUsesOnlyTheLatestPreStartNoVigSnapshot() throws Exception {
        PredictionModelService target = AopTestUtils.getTargetObject(predictionModelService);
        Player alpha = playerRepository.save(new Player("Market", "Alpha"));
        Player beta = playerRepository.save(new Player("Market", "Beta"));
        LocalDate matchDate = LocalDate.now().minusDays(10);
        saveCompletedMatch("market-asof-1", alpha, beta, "3:1", matchDate);

        String matchKey = "market alpha|market beta|" + matchDate + "T12:00:00Z";
        LocalDateTime preStart = LocalDateTime.of(matchDate, LocalTime.of(11, 55));
        LocalDateTime postStart = LocalDateTime.of(matchDate, LocalTime.of(12, 5));
        saveMarketSnapshot("pre", matchKey, "P1", 0.61, preStart);
        saveMarketSnapshot("pre", matchKey, "P2", 0.39, preStart);
        saveMarketSnapshot("post", matchKey, "P1", 0.80, postStart);
        saveMarketSnapshot("post", matchKey, "P2", 0.20, postStart);

        Method buildSamples = PredictionModelService.class.getDeclaredMethod("buildSamples", LocalDate.class, LocalDate.class);
        buildSamples.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> samples = (List<Object>) buildSamples.invoke(target, matchDate, matchDate);
        Object forward = samples.stream()
                .filter(sample -> {
                    try {
                        Method identity = sample.getClass().getDeclaredMethod("identity");
                        identity.setAccessible(true);
                        return identity.invoke(sample).toString().endsWith("|AB");
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                })
                .findFirst()
                .orElseThrow();
        Method marketProbability = forward.getClass().getDeclaredMethod("marketProbability");
        Method marketObservedAt = forward.getClass().getDeclaredMethod("marketObservedAt");
        marketProbability.setAccessible(true);
        marketObservedAt.setAccessible(true);

        assertEquals(0.61, (double) marketProbability.invoke(forward), 0.0000001);
        assertEquals(preStart, marketObservedAt.invoke(forward));
    }

    private void saveCompletedMatch(String externalId,
                                    Player player1,
                                    Player player2,
                                    String result,
                                    LocalDate date) {
        Match match = new Match();
        match.setExternalId(externalId);
        match.setDate(date);
        match.setPlayer1(player1);
        match.setPlayer2(player2);
        MatchResultParser.applyToMatch(match, result);
        matchRepository.save(match);
    }

    private void saveMarketSnapshot(String idSuffix,
                                    String matchKey,
                                    String side,
                                    double noVigProbability,
                                    LocalDateTime observedAt) {
        OddsSnapshot snapshot = new OddsSnapshot();
        snapshot.setTrackedEventId(String.format("%064d", oddsSnapshotRepository.count() + 1));
        snapshot.setBookerEventId("book-" + idSuffix);
        snapshot.setMatchKey(matchKey);
        snapshot.setSide(side);
        snapshot.setPriceDecimal(1.0 / noVigProbability);
        snapshot.setImpliedProb(noVigProbability);
        snapshot.setNoVigProbability(noVigProbability);
        snapshot.setMarketOverround(0.0);
        snapshot.setMarketState("OPEN");
        snapshot.setSourceId("HR_MKT");
        snapshot.setObservedAt(observedAt);
        oddsSnapshotRepository.save(snapshot);
    }

    private void saveLearningSample(Long betId,
                                    String status,
                                    boolean liveAtPlacement,
                                    String phase,
                                    double modelProbability,
                                    double impliedProbability,
                                    double stake,
                                    double profitLoss,
                                    LocalDateTime settledAt) {
        PaperTradeLearningSample sample = new PaperTradeLearningSample();
        sample.setBetId(betId);
        sample.setSessionId(99L);
        sample.setStatus(status);
        sample.setSource("TEST_SOURCE");
        sample.setStrategy("CONSERVATIVE");
        sample.setModelVersion("ENSEMBLE-test");
        sample.setTopTrigger(liveAtPlacement ? "Head-to-Head (Decayed)" : "Recent Form Delta");
        sample.setLiveAtPlacement(liveAtPlacement);
        sample.setModelProbability(modelProbability);
        sample.setImpliedProbability(impliedProbability);
        sample.setEdge(modelProbability - impliedProbability);
        sample.setStake(stake);
        sample.setProfitLoss(profitLoss);
        sample.setConfidenceWidth(0.18);
        sample.setLastObservedPhase(phase);
        sample.setPlacementPhase(phase);
        sample.setPlacedAt(settledAt.minusMinutes(25));
        sample.setEventOccurredAt(settledAt.minusMinutes(25));
        sample.setSettledAt(settledAt);
        sample.setSettlementConfidence(1.0);
        sample.setCalibrationEligible(true);
        learningSampleRepository.save(sample);
    }
}
