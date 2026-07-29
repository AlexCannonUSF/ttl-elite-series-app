package com.ttl.tabletennis.service;

import com.ttl.tabletennis.analytics.Glicko2;
import com.ttl.tabletennis.analytics.RaterEnsemble;
import com.ttl.tabletennis.analytics.TrueSkill2;
import com.ttl.tabletennis.analytics.WengLin;
import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PlayerRatingTs2;
import com.ttl.tabletennis.domain.PlayerRatingWl;
import com.ttl.tabletennis.domain.RatingSnapshot;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRatingTs2Repository;
import com.ttl.tabletennis.repository.PlayerRatingWlRepository;
import com.ttl.tabletennis.repository.RatingSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional(readOnly = true)
public class FeatureService {

    private static final String SYSTEM_ELO = "ELO";
    private static final String SYSTEM_GLICKO2 = "GLICKO2";

    private final MatchRepository matchRepository;
    private final RatingSnapshotRepository ratingSnapshotRepository;
    private final PlayerRatingTs2Repository playerRatingTs2Repository;
    private final PlayerRatingWlRepository playerRatingWlRepository;
    private final SnapshotIndexCache snapshotIndexCache;

    private final Map<PlayerCacheKey, CacheEntry<PlayerFeature>> playerFeatureCache = new ConcurrentHashMap<>();
    private final Map<H2hCacheKey, CacheEntry<H2hFeature>> h2hCache = new ConcurrentHashMap<>();

    @Value("${ttl.features.cacheTtlMinutes:15}")
    private long cacheTtlMinutes;

    @Value("${ttl.features.recentMatchWindow:60}")
    private int recentMatchWindow;

    @Value("${ttl.features.h2hMatchWindow:40}")
    private int h2hMatchWindow;

    @Value("${ttl.features.formHalfLifeDays:45}")
    private double formHalfLifeDays;

    @Value("${ttl.features.h2hHalfLifeDays:120}")
    private double h2hHalfLifeDays;

    @Value("${ttl.features.recentPriorWeight:6.0}")
    private double recentPriorWeight;

    @Value("${ttl.features.h2hPriorWeight:8.0}")
    private double h2hPriorWeight;

    @Value("${ttl.features.opponentPriorWeight:6.0}")
    private double opponentPriorWeight;

    @Value("${ttl.features.schedulePriorWeight:6.0}")
    private double schedulePriorWeight;

    @Value("${ttl.glicko2.defaultRating:1500.0}")
    private double defaultRating;

    @Value("${ttl.glicko2.defaultRd:350.0}")
    private double defaultRd;

    @Value("${ttl.glicko2.defaultVolatility:0.06}")
    private double defaultVolatility;

    @Value("${ttl.trueskill2.defaultMu:25.0}")
    private double defaultTrueSkill2Mu;

    @Value("${ttl.trueskill2.defaultSigma:8.3333333333}")
    private double defaultTrueSkill2Sigma;

    @Value("${ttl.trueskill2.beta:4.1666666667}")
    private double trueSkill2Beta;

    @Value("${ttl.trueskill2.dynamicFactor:0.0833333333}")
    private double trueSkill2DynamicFactor;

    @Value("${ttl.trueskill2.sigmaFloor:0.75}")
    private double trueSkill2SigmaFloor;

    @Value("${ttl.wenglin.defaultRating:0.0}")
    private double defaultWengLinRating;

    @Value("${ttl.wenglin.defaultUncertainty:1.0}")
    private double defaultWengLinUncertainty;

    @Value("${ttl.wenglin.beta:1.0}")
    private double wengLinBeta;

    @Value("${ttl.wenglin.dynamicFactor:0.015}")
    private double wengLinDynamicFactor;

    @Value("${ttl.wenglin.uncertaintyFloor:0.05}")
    private double wengLinUncertaintyFloor;

    @Value("${ttl.wenglin.learningRate:1.0}")
    private double wengLinLearningRate;

    public FeatureService(MatchRepository matchRepository,
                          RatingSnapshotRepository ratingSnapshotRepository,
                          PlayerRatingTs2Repository playerRatingTs2Repository,
                          PlayerRatingWlRepository playerRatingWlRepository,
                          SnapshotIndexCache snapshotIndexCache) {
        this.matchRepository = matchRepository;
        this.ratingSnapshotRepository = ratingSnapshotRepository;
        this.playerRatingTs2Repository = playerRatingTs2Repository;
        this.playerRatingWlRepository = playerRatingWlRepository;
        this.snapshotIndexCache = snapshotIndexCache;
    }

    public MatchupFeatureVectorDto buildMatchupFeatureVector(Long player1Id, Long player2Id, LocalDate asOfDate) {
        if (player1Id == null || player2Id == null) {
            throw new IllegalArgumentException("player1Id and player2Id are required");
        }
        if (player1Id.equals(player2Id)) {
            throw new IllegalArgumentException("Select two different players");
        }

        LocalDate asOf = asOfDate == null ? LocalDate.now() : asOfDate;
        PlayerFeature p1 = getPlayerFeature(player1Id, asOf);
        PlayerFeature p2 = getPlayerFeature(player2Id, asOf);
        H2hFeature h2h = getHeadToHeadFeature(player1Id, player2Id, asOf);
        double h2hReliability = reliabilityFromWeight(h2h.weightSum(), h2hPriorWeight);
        double p1RecentReliability = reliabilityFromWeight(p1.recentFormSampleWeight(), recentPriorWeight);
        double p2RecentReliability = reliabilityFromWeight(p2.recentFormSampleWeight(), recentPriorWeight);
        double p1OpponentReliability = reliabilityFromWeight(p1.opponentAdjustedSampleWeight(), opponentPriorWeight);
        double p2OpponentReliability = reliabilityFromWeight(p2.opponentAdjustedSampleWeight(), opponentPriorWeight);
        double p1ScheduleReliability = reliabilityFromWeight(p1.scheduleStrengthSampleWeight(), schedulePriorWeight);
        double p2ScheduleReliability = reliabilityFromWeight(p2.scheduleStrengthSampleWeight(), schedulePriorWeight);
        double p1RatingStability = ratingStabilityFromRd(p1.glickoRd());
        double p2RatingStability = ratingStabilityFromRd(p2.glickoRd());

        double eloProbabilityP1 = eloProbability(p1.eloRating(), p2.eloRating());
        double glickoProbabilityP1 = glickoProbability(p1.glickoRating(), p1.glickoRd(), p2.glickoRating(), p2.glickoRd());
        double trueSkill2ProbabilityP1 = trueSkill2Probability(p1.trueSkill2Mu(), p1.trueSkill2Sigma(), p2.trueSkill2Mu(), p2.trueSkill2Sigma());
        double wengLinProbabilityP1 = wengLinProbability(p1.wengLinRating(), p1.wengLinUncertainty(), p2.wengLinRating(), p2.wengLinUncertainty());
        double raterEnsembleProbabilityP1 = RaterEnsemble.probability(
                glickoProbabilityP1,
                trueSkill2ProbabilityP1,
                wengLinProbabilityP1
        );
        double raterEnsembleDelta = raterEnsembleProbabilityP1 - 0.5;
        MatchupFeatureVectorDto.ReliabilitySummaryDto reliabilitySummary = new MatchupFeatureVectorDto.ReliabilitySummaryDto(
                overallReliability(
                        h2hReliability,
                        p1RecentReliability,
                        p2RecentReliability,
                        p1OpponentReliability,
                        p2OpponentReliability,
                        p1ScheduleReliability,
                        p2ScheduleReliability,
                        p1RatingStability,
                        p2RatingStability
                ),
                ratingAgreement(eloProbabilityP1, glickoProbabilityP1, trueSkill2ProbabilityP1, wengLinProbabilityP1),
                p1RatingStability,
                p2RatingStability
        );
        MatchupFeatureVectorDto.SignificanceSummaryDto significanceSummary = buildSignificanceSummary(
                h2hReliability,
                p1RecentReliability,
                p2RecentReliability,
                p1OpponentReliability,
                p2OpponentReliability,
                p1ScheduleReliability,
                p2ScheduleReliability,
                p1RatingStability,
                p2RatingStability,
                reliabilitySummary.ratingAgreement()
        );

        return new MatchupFeatureVectorDto(
                player1Id,
                player2Id,
                asOf,
                h2h.player1WinRate(),
                h2h.player2WinRate(),
                h2h.weightSum(),
                h2hReliability,
                new MatchupFeatureVectorDto.PlayerFeatureDto(
                        p1.recentForm(),
                        p1.opponentAdjustedForm(),
                        p1.scheduleStrength(),
                        p1.eloRating(),
                        p1.glickoRating(),
                        p1.glickoRd(),
                        p1.glickoVolatility(),
                        p1.trueSkill2Mu(),
                        p1.trueSkill2Sigma(),
                        p1.wengLinRating(),
                        p1.wengLinUncertainty(),
                        p1.recentFormSampleWeight(),
                        p1.opponentAdjustedSampleWeight(),
                        p1.scheduleStrengthSampleWeight(),
                        p1RecentReliability,
                        p1OpponentReliability,
                        p1ScheduleReliability,
                        p1RatingStability
                ),
                new MatchupFeatureVectorDto.PlayerFeatureDto(
                        p2.recentForm(),
                        p2.opponentAdjustedForm(),
                        p2.scheduleStrength(),
                        p2.eloRating(),
                        p2.glickoRating(),
                        p2.glickoRd(),
                        p2.glickoVolatility(),
                        p2.trueSkill2Mu(),
                        p2.trueSkill2Sigma(),
                        p2.wengLinRating(),
                        p2.wengLinUncertainty(),
                        p2.recentFormSampleWeight(),
                        p2.opponentAdjustedSampleWeight(),
                        p2.scheduleStrengthSampleWeight(),
                        p2RecentReliability,
                        p2OpponentReliability,
                        p2ScheduleReliability,
                        p2RatingStability
                ),
                eloProbabilityP1,
                glickoProbabilityP1,
                trueSkill2ProbabilityP1,
                wengLinProbabilityP1,
                raterEnsembleProbabilityP1,
                raterEnsembleDelta,
                reliabilitySummary,
                significanceSummary,
                intervalFromRd(p1.glickoRating(), p1.glickoRd()),
                intervalFromRd(p2.glickoRating(), p2.glickoRd())
        );
    }

    private PlayerFeature getPlayerFeature(Long playerId, LocalDate asOf) {
        PlayerCacheKey key = new PlayerCacheKey(playerId, toDateBucket(asOf));
        CacheEntry<PlayerFeature> cached = playerFeatureCache.get(key);
        if (isCacheValid(cached)) {
            return cached.value();
        }

        List<Match> matches = matchRepository.findCompletedRecentMatchesByPlayerUpToDate(
                playerId,
                asOf,
                PageRequest.of(0, Math.max(1, recentMatchWindow))
        );
        SnapshotBundle playerRatings = resolveSnapshotBundle(playerId, asOf);
        WeightedRate recentForm = computeWeightedWinRate(matches, playerId, asOf, formHalfLifeDays);
        WeightedSignal opponentAdjustedForm = computeOpponentAdjustedForm(matches, playerId, playerRatings.glickoRating(), asOf);
        WeightedSignal scheduleStrength = computeScheduleStrength(matches, playerId, asOf);

        PlayerFeature feature = new PlayerFeature(
                recentForm.rate(),
                opponentAdjustedForm.value(),
                scheduleStrength.value(),
                playerRatings.eloRating(),
                playerRatings.glickoRating(),
                playerRatings.glickoRd(),
                playerRatings.glickoVolatility(),
                playerRatings.trueSkill2Mu(),
                playerRatings.trueSkill2Sigma(),
                playerRatings.wengLinRating(),
                playerRatings.wengLinUncertainty(),
                recentForm.weightSum(),
                opponentAdjustedForm.weightSum(),
                scheduleStrength.weightSum()
        );
        playerFeatureCache.put(key, new CacheEntry<>(feature, LocalDateTime.now()));
        return feature;
    }

    private H2hFeature getHeadToHeadFeature(Long player1Id, Long player2Id, LocalDate asOf) {
        long low = Math.min(player1Id, player2Id);
        long high = Math.max(player1Id, player2Id);
        H2hCacheKey key = new H2hCacheKey(low, high, toDateBucket(asOf));
        CacheEntry<H2hFeature> cached = h2hCache.get(key);
        if (isCacheValid(cached)) {
            return player1Id.equals(low)
                    ? cached.value()
                    : new H2hFeature(cached.value().player2WinRate(), cached.value().player1WinRate(), cached.value().weightSum());
        }

        List<Match> h2hMatches = matchRepository.findCompletedRecentMatchesByPlayersUpToDate(
                low,
                high,
                asOf,
                PageRequest.of(0, Math.max(1, h2hMatchWindow))
        );

        double weightedWinsLow = 0.0;
        double weightTotal = 0.0;
        for (Match match : h2hMatches) {
            if (match.getWinnerPlayerId() == null || match.getDate() == null) {
                continue;
            }
            double w = timeDecayWeight(match.getDate(), asOf, h2hHalfLifeDays);
            weightTotal += w;
            if (match.getWinnerPlayerId().equals(low)) {
                weightedWinsLow += w;
            }
        }

        double lowRate = weightTotal == 0.0 ? 0.5 : clamp01(weightedWinsLow / weightTotal);
        lowRate = stabilizeProbability(lowRate, weightTotal, h2hPriorWeight);
        H2hFeature lowPerspective = new H2hFeature(lowRate, 1.0 - lowRate, weightTotal);
        h2hCache.put(key, new CacheEntry<>(lowPerspective, LocalDateTime.now()));
        return player1Id.equals(low)
                ? lowPerspective
                : new H2hFeature(lowPerspective.player2WinRate(), lowPerspective.player1WinRate(), lowPerspective.weightSum());
    }

    private WeightedRate computeWeightedWinRate(List<Match> matches, Long playerId, LocalDate asOf, double halfLifeDays) {
        double weightedWins = 0.0;
        double weightTotal = 0.0;

        for (Match match : matches) {
            if (match.getWinnerPlayerId() == null || match.getDate() == null) {
                continue;
            }
            double w = timeDecayWeight(match.getDate(), asOf, halfLifeDays);
            weightTotal += w;
            if (match.getWinnerPlayerId().equals(playerId)) {
                weightedWins += w;
            }
        }
        double rate = weightTotal == 0.0 ? 0.5 : clamp01(weightedWins / weightTotal);
        rate = stabilizeProbability(rate, weightTotal, recentPriorWeight);
        return new WeightedRate(rate, weightTotal);
    }

    private WeightedSignal computeOpponentAdjustedForm(List<Match> matches, Long playerId, double playerRating, LocalDate asOf) {
        double weightedDelta = 0.0;
        double weightTotal = 0.0;

        for (Match match : matches) {
            if (match.getWinnerPlayerId() == null || match.getDate() == null) continue;
            Long opponentId = resolveOpponentId(match, playerId);
            if (opponentId == null) continue;

            SnapshotBundle opp = resolveSnapshotBundle(opponentId, asOf);
            double expected = eloProbability(playerRating, opp.glickoRating());
            double actual = match.getWinnerPlayerId().equals(playerId) ? 1.0 : 0.0;
            double w = timeDecayWeight(match.getDate(), asOf, formHalfLifeDays);

            weightedDelta += w * (actual - expected);
            weightTotal += w;
        }

        if (weightTotal == 0.0) return new WeightedSignal(0.5, 0.0);
        double meanDelta = weightedDelta / weightTotal;
        double reliability = reliabilityFromWeight(weightTotal, opponentPriorWeight);
        double adjusted = clamp01(0.5 + (meanDelta * 0.5 * reliability));
        return new WeightedSignal(adjusted, weightTotal);
    }

    private WeightedSignal computeScheduleStrength(List<Match> matches, Long playerId, LocalDate asOf) {
        double weightedOppRating = 0.0;
        double weightTotal = 0.0;

        for (Match match : matches) {
            if (match.getDate() == null) continue;
            Long opponentId = resolveOpponentId(match, playerId);
            if (opponentId == null) continue;

            SnapshotBundle opp = resolveSnapshotBundle(opponentId, asOf);
            double w = timeDecayWeight(match.getDate(), asOf, formHalfLifeDays);

            weightedOppRating += w * opp.glickoRating();
            weightTotal += w;
        }
        if (weightTotal == 0.0) {
            return new WeightedSignal(defaultRating, 0.0);
        }
        double raw = weightedOppRating / weightTotal;
        double reliability = reliabilityFromWeight(weightTotal, schedulePriorWeight);
        double adjusted = defaultRating + ((raw - defaultRating) * reliability);
        return new WeightedSignal(adjusted, weightTotal);
    }

    private Long resolveOpponentId(Match match, Long playerId) {
        if (match.getPlayer1() != null && playerId.equals(match.getPlayer1().getId())) {
            return match.getPlayer2() == null ? null : match.getPlayer2().getId();
        }
        if (match.getPlayer2() != null && playerId.equals(match.getPlayer2().getId())) {
            return match.getPlayer1() == null ? null : match.getPlayer1().getId();
        }
        return null;
    }

    private SnapshotBundle resolveSnapshotBundle(Long playerId, LocalDate asOf) {
        if (playerId == null) {
            return defaultSnapshotBundle(defaultRating);
        }

        // Fast path: hit the bulk-loaded in-memory index if it's warmed.
        if (snapshotIndexCache != null && snapshotIndexCache.isWarmed()) {
            SnapshotIndexCache.RatingRow elo = snapshotIndexCache
                    .findTopRating(playerId, SYSTEM_ELO, asOf).orElse(null);
            SnapshotIndexCache.RatingRow glicko = snapshotIndexCache
                    .findTopRating(playerId, SYSTEM_GLICKO2, asOf).orElse(null);
            SnapshotIndexCache.Ts2Row ts2 = snapshotIndexCache
                    .findTopTs2(playerId, asOf).orElse(null);
            SnapshotIndexCache.WlRow wl = snapshotIndexCache
                    .findTopWl(playerId, asOf).orElse(null);

            Double eloR = elo == null ? null : elo.rating();
            if (glicko == null) {
                double fallback = eloR == null ? defaultRating : eloR;
                return new SnapshotBundle(
                        fallback,
                        fallback,
                        defaultRd,
                        defaultVolatility,
                        ts2 == null ? defaultTrueSkill2Mu : ts2.mu(),
                        ts2 == null ? defaultTrueSkill2Sigma : ts2.sigma(),
                        wl == null ? defaultWengLinRating : wl.rating(),
                        wl == null ? defaultWengLinUncertainty : wl.uncertainty()
                );
            }
            double effectiveElo = eloR == null ? glicko.rating() : eloR;
            return new SnapshotBundle(
                    effectiveElo,
                    glicko.rating(),
                    glicko.ratingDeviation() == null ? defaultRd : glicko.ratingDeviation(),
                    glicko.volatility() == null ? defaultVolatility : glicko.volatility(),
                    ts2 == null ? defaultTrueSkill2Mu : ts2.mu(),
                    ts2 == null ? defaultTrueSkill2Sigma : ts2.sigma(),
                    wl == null ? defaultWengLinRating : wl.rating(),
                    wl == null ? defaultWengLinUncertainty : wl.uncertainty()
            );
        }

        // Slow path: fall back to repository queries (used by tests, by code
        // paths invoked before ApplicationReadyEvent, or if the cache was
        // disabled via configuration).
        RatingSnapshot eloSnapshot = ratingSnapshotRepository
                .findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(playerId, SYSTEM_ELO, asOf)
                .orElse(null);

        RatingSnapshot glicko = ratingSnapshotRepository
                .findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(playerId, SYSTEM_GLICKO2, asOf)
                .orElse(null);

        PlayerRatingTs2 trueSkill2 = playerRatingTs2Repository
                .findTopByPlayerIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(playerId, asOf)
                .orElse(null);

        PlayerRatingWl wengLin = playerRatingWlRepository
                .findTopByPlayerIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(playerId, asOf)
                .orElse(null);

        Double elo = eloSnapshot == null ? null : eloSnapshot.getRating();
        if (glicko == null) {
            double fallback = elo == null ? defaultRating : elo;
            return new SnapshotBundle(
                    fallback,
                    fallback,
                    defaultRd,
                    defaultVolatility,
                    trueSkill2 == null ? defaultTrueSkill2Mu : trueSkill2.getMu(),
                    trueSkill2 == null ? defaultTrueSkill2Sigma : trueSkill2.getSigma(),
                    wengLin == null ? defaultWengLinRating : wengLin.getRating(),
                    wengLin == null ? defaultWengLinUncertainty : wengLin.getUncertainty()
            );
        }

        double effectiveElo = elo == null ? glicko.getRating() : elo;
        return new SnapshotBundle(
                effectiveElo,
                glicko.getRating(),
                glicko.getRatingDeviation() == null ? defaultRd : glicko.getRatingDeviation(),
                glicko.getVolatility() == null ? defaultVolatility : glicko.getVolatility(),
                trueSkill2 == null ? defaultTrueSkill2Mu : trueSkill2.getMu(),
                trueSkill2 == null ? defaultTrueSkill2Sigma : trueSkill2.getSigma(),
                wengLin == null ? defaultWengLinRating : wengLin.getRating(),
                wengLin == null ? defaultWengLinUncertainty : wengLin.getUncertainty()
        );
    }

    private SnapshotBundle defaultSnapshotBundle(double fallbackRating) {
        return new SnapshotBundle(
                fallbackRating,
                fallbackRating,
                defaultRd,
                defaultVolatility,
                defaultTrueSkill2Mu,
                defaultTrueSkill2Sigma,
                defaultWengLinRating,
                defaultWengLinUncertainty
        );
    }

    private MatchupFeatureVectorDto.RatingIntervalDto intervalFromRd(double rating, double rd) {
        double spread = Math.max(0.0, rd * 2.0);
        return new MatchupFeatureVectorDto.RatingIntervalDto(rating - spread, rating + spread);
    }

    private LocalDate toDateBucket(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private boolean isCacheValid(CacheEntry<?> entry) {
        if (entry == null) return false;
        long ttl = Math.max(1, cacheTtlMinutes);
        return entry.createdAt().isAfter(LocalDateTime.now().minusMinutes(ttl));
    }

    private double timeDecayWeight(LocalDate eventDate, LocalDate asOf, double halfLifeDays) {
        if (eventDate == null) return 1.0;
        long days = Math.max(0, asOf.toEpochDay() - eventDate.toEpochDay());
        double halfLife = Math.max(1.0, halfLifeDays);
        return Math.pow(0.5, days / halfLife);
    }

    private double eloProbability(double ratingA, double ratingB) {
        return 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / 400.0));
    }

    private double glickoProbability(double ratingA, double rdA, double ratingB, double rdB) {
        return Glicko2.expectedScore(ratingA, rdA, ratingB, rdB);
    }

    private double trueSkill2Probability(double muA, double sigmaA, double muB, double sigmaB) {
        return TrueSkill2.winProbability(
                new TrueSkill2.Rating(muA, Math.max(trueSkill2SigmaFloor, sigmaA)),
                new TrueSkill2.Rating(muB, Math.max(trueSkill2SigmaFloor, sigmaB)),
                new TrueSkill2.Parameters(
                        trueSkill2Beta,
                        Math.max(0.0, trueSkill2DynamicFactor),
                        trueSkill2SigmaFloor,
                        Math.max(defaultTrueSkill2Sigma, trueSkill2SigmaFloor)
                )
        );
    }

    private double wengLinProbability(double ratingA, double uncertaintyA, double ratingB, double uncertaintyB) {
        return WengLin.winProbability(
                new WengLin.Rating(ratingA, Math.max(wengLinUncertaintyFloor, uncertaintyA)),
                new WengLin.Rating(ratingB, Math.max(wengLinUncertaintyFloor, uncertaintyB)),
                new WengLin.Parameters(
                        wengLinBeta,
                        Math.max(0.0, wengLinDynamicFactor),
                        wengLinUncertaintyFloor,
                        Math.max(defaultWengLinUncertainty, wengLinUncertaintyFloor),
                        wengLinLearningRate
                )
        );
    }

    private double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private double stabilizeProbability(double observedRate, double weightSum, double priorWeight) {
        if (weightSum <= 0.0) {
            return 0.5;
        }
        double prior = sanitizePriorWeight(priorWeight);
        double posterior = ((observedRate * weightSum) + (0.5 * prior)) / (weightSum + prior);
        return clamp01(posterior);
    }

    private double reliabilityFromWeight(double weightSum, double priorWeight) {
        if (weightSum <= 0.0) {
            return 0.0;
        }
        double prior = sanitizePriorWeight(priorWeight);
        return clamp01(weightSum / (weightSum + prior));
    }

    private double sanitizePriorWeight(double priorWeight) {
        return Math.max(0.5, Math.min(priorWeight, 80.0));
    }

    private double ratingStabilityFromRd(double rd) {
        double bounded = Math.max(30.0, Math.min(rd, defaultRd));
        double normalized = (bounded - 30.0) / Math.max(1.0, defaultRd - 30.0);
        return clamp01(1.0 - normalized);
    }

    private double ratingAgreement(double eloProbability,
                                   double glickoProbability,
                                   double trueSkill2Probability,
                                   double wengLinProbability) {
        double max = Math.max(
                Math.max(eloProbability, glickoProbability),
                Math.max(trueSkill2Probability, wengLinProbability)
        );
        double min = Math.min(
                Math.min(eloProbability, glickoProbability),
                Math.min(trueSkill2Probability, wengLinProbability)
        );
        double gap = max - min;
        return clamp01(1.0 - (gap / 0.25));
    }

    private double overallReliability(double h2hReliability,
                                      double p1RecentReliability,
                                      double p2RecentReliability,
                                      double p1OpponentReliability,
                                      double p2OpponentReliability,
                                      double p1ScheduleReliability,
                                      double p2ScheduleReliability,
                                      double p1RatingStability,
                                      double p2RatingStability) {
        return clamp01(
                (h2hReliability * 0.16)
                        + (((p1RecentReliability + p2RecentReliability) / 2.0) * 0.22)
                        + (((p1OpponentReliability + p2OpponentReliability) / 2.0) * 0.24)
                        + (((p1ScheduleReliability + p2ScheduleReliability) / 2.0) * 0.14)
                        + (((p1RatingStability + p2RatingStability) / 2.0) * 0.24)
        );
    }

    private MatchupFeatureVectorDto.SignificanceSummaryDto buildSignificanceSummary(double h2hReliability,
                                                                                    double p1RecentReliability,
                                                                                    double p2RecentReliability,
                                                                                    double p1OpponentReliability,
                                                                                    double p2OpponentReliability,
                                                                                    double p1ScheduleReliability,
                                                                                    double p2ScheduleReliability,
                                                                                    double p1RatingStability,
                                                                                    double p2RatingStability,
                                                                                    double ratingAgreement) {
        double recentSupport = (p1RecentReliability + p2RecentReliability) / 2.0;
        double opponentSupport = (p1OpponentReliability + p2OpponentReliability) / 2.0;
        double scheduleSupport = (p1ScheduleReliability + p2ScheduleReliability) / 2.0;
        double baselineSupport = clamp01((0.65 * Math.min(p1RatingStability, p2RatingStability)) + (0.35 * ratingAgreement));
        double sampleDepth = clamp01(
                (0.28 * h2hReliability)
                        + (0.30 * recentSupport)
                        + (0.18 * opponentSupport)
                        + (0.12 * scheduleSupport)
                        + (0.12 * baselineSupport)
        );

        String[] labels = new String[]{
                "Head-to-Head",
                "Recent Form",
                "Opponent-Adjusted Form",
                "Schedule Strength",
                "Baseline Stability"
        };
        double[] values = new double[]{
                clamp01(h2hReliability),
                clamp01(recentSupport),
                clamp01(opponentSupport),
                clamp01(scheduleSupport),
                clamp01(baselineSupport)
        };

        int strongSignalCount = 0;
        int usableSignalCount = 0;
        int thinSignalCount = 0;
        int strongestIndex = 0;
        int weakestIndex = 0;

        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            if (value >= 0.70) {
                strongSignalCount++;
            }
            if (value >= 0.45) {
                usableSignalCount++;
            }
            if (value < 0.30) {
                thinSignalCount++;
            }
            if (value > values[strongestIndex]) {
                strongestIndex = i;
            }
            if (value < values[weakestIndex]) {
                weakestIndex = i;
            }
        }

        return new MatchupFeatureVectorDto.SignificanceSummaryDto(
                sampleDepth,
                values[0],
                values[1],
                values[2],
                values[3],
                values[4],
                strongSignalCount,
                usableSignalCount,
                thinSignalCount,
                labels[strongestIndex],
                values[strongestIndex],
                labels[weakestIndex],
                values[weakestIndex]
        );
    }

    private record PlayerCacheKey(Long playerId, LocalDate bucket) {
    }

    private record H2hCacheKey(Long player1Id, Long player2Id, LocalDate bucket) {
    }

    private record CacheEntry<T>(T value, LocalDateTime createdAt) {
    }

    private record WeightedRate(double rate, double weightSum) {
    }

    private record WeightedSignal(double value, double weightSum) {
    }

    private record H2hFeature(double player1WinRate, double player2WinRate, double weightSum) {
    }

    private record SnapshotBundle(double eloRating,
                                  double glickoRating,
                                  double glickoRd,
                                  double glickoVolatility,
                                  double trueSkill2Mu,
                                  double trueSkill2Sigma,
                                  double wengLinRating,
                                  double wengLinUncertainty) {
    }

    private record PlayerFeature(double recentForm,
                                 double opponentAdjustedForm,
                                 double scheduleStrength,
                                 double eloRating,
                                 double glickoRating,
                                 double glickoRd,
                                 double glickoVolatility,
                                 double trueSkill2Mu,
                                 double trueSkill2Sigma,
                                 double wengLinRating,
                                 double wengLinUncertainty,
                                 double recentFormSampleWeight,
                                 double opponentAdjustedSampleWeight,
                                 double scheduleStrengthSampleWeight) {
    }
}
