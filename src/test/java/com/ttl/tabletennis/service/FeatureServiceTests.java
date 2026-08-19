package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.PlayerRatingTs2;
import com.ttl.tabletennis.domain.PlayerRatingWl;
import com.ttl.tabletennis.domain.RatingSnapshot;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRatingTs2Repository;
import com.ttl.tabletennis.repository.PlayerRatingWlRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.repository.RatingSnapshotRepository;
import com.ttl.tabletennis.util.MatchResultParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class FeatureServiceTests {

    @Autowired
    private FeatureService featureService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private RatingSnapshotRepository ratingSnapshotRepository;

    @Autowired
    private PlayerRatingTs2Repository playerRatingTs2Repository;

    @Autowired
    private PlayerRatingWlRepository playerRatingWlRepository;

    @BeforeEach
    void clearFeatureCachesBeforeRolledBackFixtureIdsCanBeReused() {
        // This class deliberately verifies cache reuse inside individual
        // tests. Spring then rolls each transaction back while the singleton
        // service survives, and H2 may reuse those rolled-back identity values.
        // Clear once between fixtures so a previous test's synthetic players
        // cannot masquerade as the next test's players.
        featureService.invalidateForFreshMatchData(java.util.Set.of());
    }

    @Test
    void buildMatchupFeatureVectorProducesCoreSignals() {
        LocalDate asOf = LocalDate.now();
        Player p1 = playerRepository.save(new Player("Alex", "Cannon"));
        Player p2 = playerRepository.save(new Player("Nima", "Alamian"));
        Player p3 = playerRepository.save(new Player("Liam", "Pitchford"));

        seedRatings(p1, 1610, 70, 0.05, asOf.minusDays(1));
        seedRatings(p2, 1490, 85, 0.06, asOf.minusDays(1));
        seedRatings(p3, 1535, 95, 0.07, asOf.minusDays(1));

        saveCompletedMatch("fv-1", p1, p2, "3:1", asOf.minusDays(2));
        saveCompletedMatch("fv-2", p2, p1, "3:2", asOf.minusDays(70));
        saveCompletedMatch("fv-3", p1, p3, "3:0", asOf.minusDays(6));
        saveCompletedMatch("fv-4", p3, p2, "3:2", asOf.minusDays(8));

        MatchupFeatureVectorDto vector = featureService.buildMatchupFeatureVector(p1.getId(), p2.getId(), asOf);

        assertEquals(p1.getId(), vector.player1Id());
        assertEquals(p2.getId(), vector.player2Id());
        assertTrue(vector.headToHeadWinRatePlayer1() >= 0.0 && vector.headToHeadWinRatePlayer1() <= 1.0);
        assertTrue(vector.headToHeadWinRatePlayer2() >= 0.0 && vector.headToHeadWinRatePlayer2() <= 1.0);
        assertTrue(Math.abs((vector.headToHeadWinRatePlayer1() + vector.headToHeadWinRatePlayer2()) - 1.0) < 0.0001);
        assertTrue(vector.player1().recentForm() >= 0.0 && vector.player1().recentForm() <= 1.0);
        assertTrue(vector.player2().recentForm() >= 0.0 && vector.player2().recentForm() <= 1.0);
        assertTrue(vector.player1().glickoVolatility() > 0.0);
        assertTrue(vector.player1().glickoRatingDeviation() > 0.0);
        assertTrue(vector.player1().trueSkill2Mu() > 0.0);
        assertTrue(vector.player1().trueSkill2Sigma() > 0.0);
        assertTrue(vector.player1().wengLinUncertainty() > 0.0);
        assertTrue(vector.headToHeadReliability() >= 0.0 && vector.headToHeadReliability() <= 1.0);
        assertTrue(vector.player1().recentFormReliability() >= 0.0 && vector.player1().recentFormReliability() <= 1.0);
        assertTrue(vector.player1().ratingStability() >= 0.0 && vector.player1().ratingStability() <= 1.0);
        assertTrue(vector.reliabilitySummary().overallReliability() >= 0.0 && vector.reliabilitySummary().overallReliability() <= 1.0);
        assertTrue(vector.reliabilitySummary().ratingAgreement() >= 0.0 && vector.reliabilitySummary().ratingAgreement() <= 1.0);
        assertTrue(vector.significanceSummary().sampleDepth() >= 0.0 && vector.significanceSummary().sampleDepth() <= 1.0);
        assertTrue(vector.significanceSummary().strongSignalCount() >= 0);
        assertTrue(vector.significanceSummary().usableSignalCount() >= vector.significanceSummary().strongSignalCount());
        assertTrue(vector.significanceSummary().thinSignalCount() >= 0);
        assertTrue(vector.significanceSummary().strongestSupportValue() >= vector.significanceSummary().weakestSupportValue());
        assertTrue(vector.player1Rating95PctInterval().low() < vector.player1Rating95PctInterval().high());
        assertTrue(vector.eloProbabilityPlayer1() >= 0.0 && vector.eloProbabilityPlayer1() <= 1.0);
        assertTrue(vector.glickoProbabilityPlayer1() >= 0.0 && vector.glickoProbabilityPlayer1() <= 1.0);
        assertTrue(vector.trueSkill2ProbabilityPlayer1() >= 0.0 && vector.trueSkill2ProbabilityPlayer1() <= 1.0);
        assertTrue(vector.wengLinProbabilityPlayer1() >= 0.0 && vector.wengLinProbabilityPlayer1() <= 1.0);
        assertTrue(vector.raterEnsembleProbabilityPlayer1() >= 0.0 && vector.raterEnsembleProbabilityPlayer1() <= 1.0);
        assertEquals(vector.raterEnsembleProbabilityPlayer1() - 0.5, vector.raterEnsembleDelta(), 0.0001);
    }

    @Test
    void playerFeatureCacheIsReusedAcrossOpponentsWithinDateBucket() {
        LocalDate asOf = LocalDate.now();
        Player p1 = playerRepository.save(new Player("Cached", "One"));
        Player p2 = playerRepository.save(new Player("Cached", "Two"));
        Player p3 = playerRepository.save(new Player("Cached", "Three"));

        RatingSnapshot p1Glicko = seedRatings(p1, 1550, 60, 0.04, asOf.minusDays(1));
        seedRatings(p2, 1480, 90, 0.06, asOf.minusDays(1));
        seedRatings(p3, 1500, 90, 0.06, asOf.minusDays(1));

        saveCompletedMatch("cache-1", p1, p2, "3:1", asOf.minusDays(2));
        saveCompletedMatch("cache-2", p1, p3, "3:2", asOf.minusDays(3));

        MatchupFeatureVectorDto first = featureService.buildMatchupFeatureVector(p1.getId(), p2.getId(), asOf);
        double cachedRating = first.player1().glickoRating();

        p1Glicko.setRating(1999.0);
        ratingSnapshotRepository.save(p1Glicko);

        MatchupFeatureVectorDto second = featureService.buildMatchupFeatureVector(p1.getId(), p3.getId(), asOf);

        assertEquals(cachedRating, second.player1().glickoRating(), 0.0001);
    }

    @Test
    void headToHeadWeightingUsesSampleSignificance() {
        LocalDate asOf = LocalDate.now();
        Player p1 = playerRepository.save(new Player("Significance", "One"));
        Player p2 = playerRepository.save(new Player("Significance", "Two"));

        seedRatings(p1, 1520, 80, 0.06, asOf.minusDays(1));
        seedRatings(p2, 1490, 85, 0.06, asOf.minusDays(1));

        saveCompletedMatch("sig-1", p1, p2, "3:2", asOf.minusDays(2));
        MatchupFeatureVectorDto singleSample = featureService.buildMatchupFeatureVector(p1.getId(), p2.getId(), asOf);
        double singleRate = singleSample.headToHeadWinRatePlayer1();

        for (int i = 0; i < 18; i++) {
            String result = i < 14 ? "3:1" : "1:3";
            saveCompletedMatch("sig-many-" + i, p1, p2, result, asOf.minusDays(3 + i));
        }

        MatchupFeatureVectorDto largerSample = featureService.buildMatchupFeatureVector(p1.getId(), p2.getId(), asOf.plusDays(7));

        assertTrue(singleRate > 0.5 && singleRate < 0.70, "single 1-0 sample should be shrunk toward 50/50");
        assertTrue(largerSample.headToHeadWinRatePlayer1() > singleRate, "larger positive sample should move farther from 50/50");
        assertTrue(largerSample.headToHeadSampleWeight() > singleSample.headToHeadSampleWeight());
        assertTrue(largerSample.headToHeadReliability() > singleSample.headToHeadReliability());
        assertTrue(largerSample.significanceSummary().sampleDepth() > singleSample.significanceSummary().sampleDepth());
        assertTrue(largerSample.significanceSummary().headToHeadSupport() > singleSample.significanceSummary().headToHeadSupport());
        assertTrue(largerSample.significanceSummary().strongestSupportValue() >= largerSample.significanceSummary().headToHeadSupport());
    }

    private RatingSnapshot seedRatings(Player player,
                                       double glickoRating,
                                       double glickoRd,
                                       double glickoVolatility,
                                       LocalDate date) {
        RatingSnapshot elo = new RatingSnapshot();
        elo.setPlayer(player);
        elo.setSnapshotDate(date);
        elo.setRating(glickoRating - 20);
        elo.setRatingDeviation(null);
        elo.setVolatility(null);
        elo.setRatingSystem("ELO");
        ratingSnapshotRepository.save(elo);

        RatingSnapshot glicko = new RatingSnapshot();
        glicko.setPlayer(player);
        glicko.setSnapshotDate(date);
        glicko.setRating(glickoRating);
        glicko.setRatingDeviation(glickoRd);
        glicko.setVolatility(glickoVolatility);
        glicko.setRatingSystem("GLICKO2");
        RatingSnapshot saved = ratingSnapshotRepository.save(glicko);

        PlayerRatingTs2 ts2 = new PlayerRatingTs2();
        ts2.setPlayerId(player.getId());
        ts2.setSnapshotDate(date);
        ts2.setMu(25.0 + ((glickoRating - 1500.0) / 60.0));
        ts2.setSigma(Math.max(1.0, glickoRd / 42.0));
        ts2.setConservativeSkill(ts2.getMu() - (3.0 * ts2.getSigma()));
        ts2.setMatchesSeen(12);
        ts2.setWins(7);
        ts2.setLosses(5);
        ts2.setLastMatchDate(date);
        playerRatingTs2Repository.save(ts2);

        PlayerRatingWl wl = new PlayerRatingWl();
        wl.setPlayerId(player.getId());
        wl.setSnapshotDate(date);
        wl.setRating((glickoRating - 1500.0) / 120.0);
        wl.setUncertainty(Math.max(0.08, glickoRd / 180.0));
        wl.setConservativeRating(wl.getRating() - (2.0 * wl.getUncertainty()));
        wl.setMatchesSeen(12);
        wl.setWins(7);
        wl.setLosses(5);
        wl.setLastMatchDate(date);
        playerRatingWlRepository.save(wl);

        return saved;
    }

    private void saveCompletedMatch(String externalId,
                                    Player p1,
                                    Player p2,
                                    String result,
                                    LocalDate date) {
        Match match = new Match();
        match.setExternalId(externalId);
        match.setDate(date);
        match.setPlayer1(p1);
        match.setPlayer2(p2);
        MatchResultParser.applyToMatch(match, result);
        matchRepository.save(match);
    }
}
