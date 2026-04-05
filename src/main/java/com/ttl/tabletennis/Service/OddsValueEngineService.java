package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.OddsQuote;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.ValueOpportunity;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.LiveScoreSnapshotDto;
import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.dto.OddsRefreshResultDto;
import com.ttl.tabletennis.dto.ValueOpportunityDto;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.repository.OddsQuoteRepository;
import com.ttl.tabletennis.repository.ValueOpportunityRepository;
import com.ttl.tabletennis.scrape.HardRockOddsScraper;
import com.ttl.tabletennis.util.NameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OddsValueEngineService {
    private static final Pattern SCORE_PAIR_PATTERN = Pattern.compile("(\\d{1,2})\\s*[-:]\\s*(\\d{1,2})");
    private static final Logger log = LoggerFactory.getLogger(OddsValueEngineService.class);

    public static final String STRATEGY_CONSERVATIVE = "CONSERVATIVE";
    public static final String STRATEGY_AGGRESSIVE = "AGGRESSIVE";

    private final PredictionModelService predictionModelService;
    private final PlayerIdentityService playerIdentityService;
    private final HardRockOddsScraper hardRockOddsScraper;
    private final OddsQuoteRepository oddsQuoteRepository;
    private final ValueOpportunityRepository valueOpportunityRepository;

    @Value("${ttl.odds.defaultModelFamily:ENSEMBLE}")
    private String defaultModelFamily;

    @Value("${ttl.odds.conservativeThreshold:0.055}")
    private double conservativeThreshold;

    @Value("${ttl.odds.aggressiveThreshold:0.030}")
    private double aggressiveThreshold;

    @Value("${ttl.odds.retentionDays:30}")
    private int retentionDays;

    @Value("${ttl.odds.maxRecommendedAmericanOdds:220}")
    private int maxRecommendedAmericanOdds;

    public OddsValueEngineService(PredictionModelService predictionModelService,
                                  PlayerIdentityService playerIdentityService,
                                  HardRockOddsScraper hardRockOddsScraper,
                                  OddsQuoteRepository oddsQuoteRepository,
                                  ValueOpportunityRepository valueOpportunityRepository) {
        this.predictionModelService = predictionModelService;
        this.playerIdentityService = playerIdentityService;
        this.hardRockOddsScraper = hardRockOddsScraper;
        this.oddsQuoteRepository = oddsQuoteRepository;
        this.valueOpportunityRepository = valueOpportunityRepository;
    }

    @Transactional
    public OddsRefreshResultDto refresh(String strategyRaw, String modelFamilyRaw) {
        List<MatchOdds> fetched = hardRockOddsScraper.fetch();
        return refreshFromQuotes(strategyRaw, modelFamilyRaw, fetched, "HARD_ROCK");
    }

    @Transactional
    OddsRefreshResultDto refreshFromQuotes(String strategyRaw,
                                           String modelSelectorRaw,
                                           List<MatchOdds> fetchedQuotes,
                                           String source) {
        String strategy = normalizeStrategy(strategyRaw);
        String modelSelector = StringUtils.hasText(modelSelectorRaw)
                ? modelSelectorRaw.trim()
                : defaultModelFamily;
        String quoteSource = StringUtils.hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
        List<MatchOdds> fetched = fetchedQuotes == null ? List.of() : fetchedQuotes;

        int resolved = 0;
        int opportunities = 0;
        LocalDateTime now = LocalDateTime.now();
        String effectiveModelVersion = modelSelector;

        for (MatchOdds odds : fetched) {
            if (odds.getOddsA() <= 1.0 || odds.getOddsB() <= 1.0) {
                continue;
            }

            OddsQuote quote = persistQuote(odds, quoteSource, now);

            Optional<Player> p1Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerA());
            Optional<Player> p2Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerB());
            if (p1Opt.isEmpty() || p2Opt.isEmpty()) {
                continue;
            }
            Player p1 = p1Opt.get();
            Player p2 = p2Opt.get();
            if (p1.getId() == null || p2.getId() == null || p1.getId().equals(p2.getId())) {
                continue;
            }
            resolved++;

            double implied1Raw = 1.0 / odds.getOddsA();
            double implied2Raw = 1.0 / odds.getOddsB();
            double total = implied1Raw + implied2Raw;
            if (total <= 0.0) {
                continue;
            }
            double implied1 = implied1Raw / total;
            double implied2 = implied2Raw / total;

            PredictionModelService.PredictionSnapshot prediction = predictionModelService.predict(
                    p1.getId(),
                    p2.getId(),
                    LocalDate.now(),
                    modelSelector
            );
            if (StringUtils.hasText(prediction.modelVersion())) {
                effectiveModelVersion = prediction.modelVersion();
            }

            LiveAdjustedProbability tunedSnapshot = applyRegimeTuning(
                    new LiveAdjustedProbability(
                            prediction.player1Probability(),
                            prediction.player2Probability(),
                            prediction.player1ConfidenceLow(),
                            prediction.player1ConfidenceHigh(),
                            1.0 - prediction.player1ConfidenceHigh(),
                            1.0 - prediction.player1ConfidenceLow()
                    ),
                    predictionModelService.currentAdaptiveRegimeTuning(odds.isLive(), odds.getMatchPhase(), implied1),
                    predictionModelService.currentAdaptiveRegimeTuning(odds.isLive(), odds.getMatchPhase(), implied2)
            );

            double threshold = strategyThreshold(strategy);
            opportunities += maybePersistOpportunity(
                    strategy,
                    prediction.modelVersion(),
                    threshold,
                    quote.getSource(),
                    p1,
                    p2,
                    p1,
                    tunedSnapshot.player1Probability(),
                    tunedSnapshot.player1ConfidenceLow(),
                    tunedSnapshot.player1ConfidenceHigh(),
                    implied1,
                    quote.getAmericanOddsPlayer1(),
                    now
            );

            opportunities += maybePersistOpportunity(
                    strategy,
                    prediction.modelVersion(),
                    threshold,
                    quote.getSource(),
                    p1,
                    p2,
                    p2,
                    tunedSnapshot.player2Probability(),
                    tunedSnapshot.player2ConfidenceLow(),
                    tunedSnapshot.player2ConfidenceHigh(),
                    implied2,
                    quote.getAmericanOddsPlayer2(),
                    now
            );
        }

        LocalDateTime cutoff = now.minusDays(Math.max(1, retentionDays));
        valueOpportunityRepository.deleteByCreatedAtBefore(cutoff);
        oddsQuoteRepository.deleteByScrapedAtBefore(cutoff);

        return new OddsRefreshResultDto(
                quoteSource,
                fetched.size(),
                resolved,
                opportunities,
                strategy,
                effectiveModelVersion,
                now
        );
    }

    @Transactional(readOnly = true)
    public List<ValueOpportunityDto> listValueOpportunities(String strategyRaw, int limit) {
        int take = Math.max(1, Math.min(limit, 200));
        String strategy = normalizeStrategy(strategyRaw);
        List<ValueOpportunity> rows;
        if (!StringUtils.hasText(strategyRaw) || "ALL".equalsIgnoreCase(strategyRaw.trim())) {
            rows = valueOpportunityRepository.findAllByOrderByCreatedAtDescEdgeDesc(PageRequest.of(0, take));
        } else {
            rows = valueOpportunityRepository.findByStrategyOrderByCreatedAtDescEdgeDesc(strategy, PageRequest.of(0, take));
        }
        List<ValueOpportunityDto> out = new ArrayList<>(rows.size());
        for (ValueOpportunity row : rows) {
            out.add(new ValueOpportunityDto(
                    row.getId(),
                    row.getSource(),
                    row.getStrategy(),
                    row.getModelVersion(),
                    row.getPlayer1Id(),
                    row.getPlayer2Id(),
                    row.getPlayerSideId(),
                    row.getPlayerSideName(),
                    row.getModelProbability(),
                    row.getConfidenceLow(),
                    row.getConfidenceHigh(),
                    row.getImpliedProbability(),
                    row.getEdge(),
                    row.getThreshold(),
                    row.getAmericanOdds(),
                    row.getCreatedAt()
            ));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<LiveOddsRecommendationDto> liveOddsRecommendations(String strategyRaw,
                                                                   String modelSelectorRaw,
                                                                   int limit,
                                                                   boolean includeUnresolved) {
        int take = Math.max(1, Math.min(limit, 250));
        String strategy = normalizeStrategy(strategyRaw);
        String modelSelector = StringUtils.hasText(modelSelectorRaw)
                ? modelSelectorRaw.trim()
                : defaultModelFamily;
        double threshold = strategyThreshold(strategy);

        List<MatchOdds> fetched = hardRockOddsScraper.fetch();
        List<LiveOddsRecommendationDto> out = new ArrayList<>();
        LocalDateTime snapshotTime = LocalDateTime.now();

        for (MatchOdds odds : fetched) {
            try {
                if (odds.getOddsA() <= 1.0 || odds.getOddsB() <= 1.0) {
                    continue;
                }

                double implied1Raw = 1.0 / odds.getOddsA();
                double implied2Raw = 1.0 / odds.getOddsB();
                double total = implied1Raw + implied2Raw;
                if (total <= 0.0) {
                    continue;
                }
                double implied1 = implied1Raw / total;
                double implied2 = implied2Raw / total;

                Optional<Player> p1Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerA());
                Optional<Player> p2Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerB());
                boolean resolved = p1Opt.isPresent() && p2Opt.isPresent() && !p1Opt.get().getId().equals(p2Opt.get().getId());

                if (!resolved && !includeUnresolved) {
                    continue;
                }

                String source = StringUtils.hasText(odds.getSource()) ? odds.getSource() : "HARD_ROCK";
                String eventName = StringUtils.hasText(odds.getEventName())
                        ? odds.getEventName()
                        : odds.getPlayerA() + " vs " + odds.getPlayerB();
                String competitionName = StringUtils.hasText(odds.getCompetitionName())
                        ? odds.getCompetitionName()
                        : "Table Tennis";
                String unresolvedMatchupKey = buildMatchupKey(
                        null,
                        odds.getPlayerA(),
                        null,
                        odds.getPlayerB(),
                        odds.getStartTimeIso()
                );

                if (!resolved) {
                    out.add(new LiveOddsRecommendationDto(
                            source,
                            strategy,
                            modelSelector,
                            eventName,
                            competitionName,
                            odds.isLive(),
                            odds.getStartTimeIso(),
                            odds.getLiveScore(),
                            StringUtils.hasText(odds.getMatchPhase()) ? odds.getMatchPhase() : (odds.isLive() ? "LIVE" : "UPCOMING"),
                            null,
                            odds.getPlayerA(),
                            null,
                            odds.getPlayerB(),
                            odds.getOddsA(),
                            odds.getOddsB(),
                            decimalToAmerican(odds.getOddsA()),
                            decimalToAmerican(odds.getOddsB()),
                            implied1,
                            implied2,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            "N/A",
                            "Players could not be resolved to internal identities.",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            unresolvedMatchupKey,
                            null,
                            odds.getSourceType(),
                            odds.getSourceConfidence(),
                            odds.getExternalEventId(),
                            odds.isDisplayed(),
                            odds.isResulted(),
                            odds.isMatchCompleted(),
                            odds.getSourceFeedCode(),
                            odds.getSourceFeedEventId(),
                            odds.getScoreDetail()
                    ));
                    continue;
                }

                Player p1 = p1Opt.get();
                Player p2 = p2Opt.get();
                String matchupKey = buildMatchupKey(
                        p1.getId(),
                        p1.getName(),
                        p2.getId(),
                        p2.getName(),
                        odds.getStartTimeIso()
                );
                PredictionModelService.PredictionSnapshot prediction = predictionModelService.predict(
                        p1.getId(),
                        p2.getId(),
                        LocalDate.now(),
                        modelSelector
                );
                MatchupFeatureVectorDto featureVector = prediction.featureVector();

                LiveAdjustedProbability adjusted = applyLiveContext(
                        prediction.player1Probability(),
                        prediction.player2Probability(),
                        implied1,
                        implied2,
                        prediction.player1ConfidenceLow(),
                        prediction.player1ConfidenceHigh(),
                        odds
                );
                PredictionModelService.AdaptiveRegimeTuning p1RegimeTuning = predictionModelService.currentAdaptiveRegimeTuning(
                        odds.isLive(),
                        odds.getMatchPhase(),
                        implied1
                );
                PredictionModelService.AdaptiveRegimeTuning p2RegimeTuning = predictionModelService.currentAdaptiveRegimeTuning(
                        odds.isLive(),
                        odds.getMatchPhase(),
                        implied2
                );
                adjusted = applyRegimeTuning(adjusted, p1RegimeTuning, p2RegimeTuning);

                double edge1 = adjusted.player1Probability() - implied1;
                double edge2 = adjusted.player2Probability() - implied2;
                boolean p1ConfidenceOk = adjusted.player1ConfidenceLow() > implied1;
                boolean p2ConfidenceOk = adjusted.player2ConfidenceLow() > implied2;
                boolean pickPlayer1 = edge1 >= edge2;

                String suggestedSide = pickPlayer1 ? p1.getName() : p2.getName();
                double suggestedEdge = pickPlayer1 ? edge1 : edge2;
                double confidenceLow = pickPlayer1 ? adjusted.player1ConfidenceLow() : adjusted.player2ConfidenceLow();
                double confidenceHigh = pickPlayer1 ? adjusted.player1ConfidenceHigh() : adjusted.player2ConfidenceHigh();
                boolean confidenceOk = pickPlayer1 ? p1ConfidenceOk : p2ConfidenceOk;
                int suggestedAmericanOdds = pickPlayer1 ? decimalToAmerican(odds.getOddsA()) : decimalToAmerican(odds.getOddsB());
                boolean longshotRisk = suggestedAmericanOdds > Math.abs(maxRecommendedAmericanOdds);
                boolean recommended = suggestedEdge >= threshold && confidenceOk && !longshotRisk;
                String topTrigger = null;
                Double topTriggerContribution = null;
                if (prediction.featureContributions() != null && !prediction.featureContributions().isEmpty()) {
                    MatchupAnalysisDto.FeatureContributionDto top = prediction.featureContributions().get(0);
                    topTrigger = top.feature();
                    topTriggerContribution = top.contribution();
                }
                Double overallReliability = extractOverallReliability(featureVector);
                Double ratingAgreement = extractRatingAgreement(featureVector);
                Double topTriggerReliability = extractTopTriggerReliability(featureVector, topTrigger);
                Double suggestedSideBaselineStability = extractSuggestedSideBaselineStability(featureVector, pickPlayer1);

                String grade = gradeFor(suggestedEdge, confidenceLow, confidenceHigh, recommended);
                String rationale = buildRationale(
                        suggestedSide,
                        suggestedEdge,
                        threshold,
                        confidenceLow,
                        confidenceHigh,
                        recommended,
                        longshotRisk,
                        overallReliability,
                        ratingAgreement,
                        topTrigger,
                        topTriggerReliability,
                        suggestedSideBaselineStability,
                        pickPlayer1 ? p1RegimeTuning : p2RegimeTuning
                );

                out.add(new LiveOddsRecommendationDto(
                        source,
                        strategy,
                        prediction.modelVersion(),
                        eventName,
                        competitionName,
                        odds.isLive(),
                        odds.getStartTimeIso(),
                        odds.getLiveScore(),
                        StringUtils.hasText(odds.getMatchPhase()) ? odds.getMatchPhase() : (odds.isLive() ? "LIVE" : "UPCOMING"),
                        p1.getId(),
                        p1.getName(),
                        p2.getId(),
                        p2.getName(),
                        odds.getOddsA(),
                        odds.getOddsB(),
                        decimalToAmerican(odds.getOddsA()),
                        decimalToAmerican(odds.getOddsB()),
                        implied1,
                        implied2,
                        adjusted.player1Probability(),
                        adjusted.player2Probability(),
                        edge1,
                        edge2,
                        probabilityToAmerican(adjusted.player1Probability()),
                        probabilityToAmerican(adjusted.player2Probability()),
                        suggestedSide,
                        suggestedEdge,
                        probabilityToAmerican(pickPlayer1 ? adjusted.player1Probability() : adjusted.player2Probability()),
                        confidenceLow,
                        confidenceHigh,
                        recommended,
                        grade,
                        rationale,
                        topTrigger,
                        topTriggerContribution,
                        overallReliability,
                        ratingAgreement,
                        topTriggerReliability,
                        suggestedSideBaselineStability,
                        matchupKey,
                        buildSuggestedDedupeKey(matchupKey, suggestedSide),
                        odds.getSourceType(),
                        odds.getSourceConfidence(),
                        odds.getExternalEventId(),
                        odds.isDisplayed(),
                        odds.isResulted(),
                        odds.isMatchCompleted(),
                        odds.getSourceFeedCode(),
                        odds.getSourceFeedEventId(),
                        odds.getScoreDetail()
                ));
            } catch (Exception ex) {
                log.warn(
                        "[live-board] skipping matchup after row-level failure: event='{}' players='{}' vs '{}' source='{}'",
                        odds.getEventName(),
                        odds.getPlayerA(),
                        odds.getPlayerB(),
                        odds.getSource(),
                        ex
                );
            }
        }

        out.sort(Comparator
                .comparing(LiveOddsRecommendationDto::recommended).reversed()
                .thenComparing(LiveOddsRecommendationDto::live).reversed()
                .thenComparing((a, b) -> Double.compare(
                        Math.abs(valueOrZero(b.suggestedEdge())),
                        Math.abs(valueOrZero(a.suggestedEdge()))
                )));

        if (out.size() > take) {
            return out.subList(0, take);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<LiveScoreSnapshotDto> liveScoreSnapshots(int limit, boolean includeUnresolved) {
        int take = Math.max(1, Math.min(limit, 1600));
        List<MatchOdds> fetched = hardRockOddsScraper.fetchScoreboard();
        return toLiveScoreSnapshots(fetched, take, includeUnresolved);
    }

    @Transactional(readOnly = true)
    public List<LiveScoreSnapshotDto> liveScoreSnapshotsForEventIds(Collection<String> externalEventIds,
                                                                    int limit,
                                                                    boolean includeUnresolved) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (externalEventIds != null) {
            for (String raw : externalEventIds) {
                if (!StringUtils.hasText(raw)) {
                    continue;
                }
                String cleaned = raw.trim().replaceAll("[^A-Za-z0-9:_-]", "");
                if (StringUtils.hasText(cleaned)) {
                    normalized.add(cleaned);
                }
            }
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        int take = Math.max(1, Math.min(limit, 1600));
        List<MatchOdds> fetched = hardRockOddsScraper.fetchScoreboardByEventIds(normalized);
        return toLiveScoreSnapshots(fetched, take, includeUnresolved);
    }

    private List<LiveScoreSnapshotDto> toLiveScoreSnapshots(List<MatchOdds> fetched,
                                                            int take,
                                                            boolean includeUnresolved) {
        if (fetched == null || fetched.isEmpty()) {
            return List.of();
        }

        List<LiveScoreSnapshotDto> out = new ArrayList<>();
        for (MatchOdds odds : fetched) {
            if (odds == null || !StringUtils.hasText(odds.getPlayerA()) || !StringUtils.hasText(odds.getPlayerB())) {
                continue;
            }

            Optional<Player> p1Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerA());
            Optional<Player> p2Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerB());
            Long p1Id = p1Opt.map(Player::getId).orElse(null);
            Long p2Id = p2Opt.map(Player::getId).orElse(null);
            if (p1Id != null && p2Id != null && p1Id.equals(p2Id)) {
                continue;
            }

            boolean resolved = p1Id != null && p2Id != null;
            if (!resolved && !includeUnresolved) {
                continue;
            }

            String p1Name = resolved
                    ? p1Opt.map(Player::getName).orElse(odds.getPlayerA())
                    : odds.getPlayerA();
            String p2Name = resolved
                    ? p2Opt.map(Player::getName).orElse(odds.getPlayerB())
                    : odds.getPlayerB();

            String source = StringUtils.hasText(odds.getSource()) ? odds.getSource() : "HARD_ROCK_SCORE";
            String eventName = StringUtils.hasText(odds.getEventName())
                    ? odds.getEventName()
                    : odds.getPlayerA() + " vs " + odds.getPlayerB();
            String competitionName = StringUtils.hasText(odds.getCompetitionName())
                    ? odds.getCompetitionName()
                    : "Table Tennis";
            String matchupKey = buildMatchupKey(
                    p1Id,
                    p1Name,
                    p2Id,
                    p2Name,
                    odds.getStartTimeIso()
            );
            String phase = StringUtils.hasText(odds.getMatchPhase())
                    ? odds.getMatchPhase()
                    : (odds.isLive() ? "LIVE" : "UPCOMING");

            out.add(new LiveScoreSnapshotDto(
                    source,
                    StringUtils.hasText(odds.getSourceType()) ? odds.getSourceType() : "UNKNOWN",
                    odds.getSourceConfidence(),
                    Math.max(0L, (System.currentTimeMillis() - odds.getTimestamp()) / 1000L),
                    eventName,
                    competitionName,
                    odds.isLive(),
                    odds.getStartTimeIso(),
                    odds.getLiveScore(),
                    phase,
                    odds.getExternalEventId(),
                    odds.isDisplayed(),
                    odds.isResulted(),
                    odds.isMatchCompleted(),
                    odds.getSourceFeedCode(),
                    odds.getSourceFeedEventId(),
                    odds.getScoreDetail(),
                    p1Id,
                    p1Name,
                    p2Id,
                    p2Name,
                    matchupKey
            ));
        }

        out.sort(Comparator
                .comparing(LiveScoreSnapshotDto::live).reversed()
                .thenComparing((a, b) -> Boolean.compare(
                        StringUtils.hasText(b.liveScore()),
                        StringUtils.hasText(a.liveScore())
                ))
                .thenComparing(LiveScoreSnapshotDto::sourceConfidence, Comparator.reverseOrder())
                .thenComparing(LiveScoreSnapshotDto::sourceAgeSeconds)
                .thenComparing((a, b) -> safeSortToken(b.startTimeIso()).compareTo(safeSortToken(a.startTimeIso()))));

        if (out.size() > take) {
            return out.subList(0, take);
        }
        return out;
    }

    private OddsQuote persistQuote(MatchOdds odds, String source, LocalDateTime scrapedAt) {
        OddsQuote quote = new OddsQuote();
        quote.setSource(StringUtils.hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : "UNKNOWN");
        quote.setPlayer1Display(odds.getPlayerA());
        quote.setPlayer2Display(odds.getPlayerB());
        quote.setPlayer1Normalized(NameUtils.normalizeForLookup(odds.getPlayerA()));
        quote.setPlayer2Normalized(NameUtils.normalizeForLookup(odds.getPlayerB()));
        quote.setEventName(odds.getEventName());
        quote.setCompetitionName(odds.getCompetitionName());
        quote.setLiveAtQuote(odds.isLive());
        quote.setStartTimeIso(odds.getStartTimeIso());
        quote.setLiveScore(odds.getLiveScore());
        quote.setMatchPhase(odds.getMatchPhase());
        quote.setQuoteTimestampMs(odds.getTimestamp());
        quote.setDecimalOddsPlayer1(odds.getOddsA());
        quote.setDecimalOddsPlayer2(odds.getOddsB());
        quote.setAmericanOddsPlayer1(decimalToAmerican(odds.getOddsA()));
        quote.setAmericanOddsPlayer2(decimalToAmerican(odds.getOddsB()));
        quote.setScrapedAt(scrapedAt == null ? LocalDateTime.now() : scrapedAt);
        return oddsQuoteRepository.save(quote);
    }

    private int maybePersistOpportunity(String strategy,
                                        String modelVersion,
                                        double threshold,
                                        String source,
                                        Player p1,
                                        Player p2,
                                        Player side,
                                        double probability,
                                        double confidenceLow,
                                        double confidenceHigh,
                                        double impliedProbability,
                                        int americanOdds,
                                        LocalDateTime createdAt) {
        double edge = probability - impliedProbability;
        boolean strongEnough = edge >= threshold && confidenceLow > impliedProbability;
        if (!strongEnough) {
            return 0;
        }

        ValueOpportunity opp = new ValueOpportunity();
        opp.setSource(source);
        opp.setStrategy(strategy);
        opp.setModelVersion(StringUtils.hasText(modelVersion) ? modelVersion : "unknown");
        opp.setPlayer1Id(p1.getId());
        opp.setPlayer2Id(p2.getId());
        opp.setPlayerSideId(side.getId());
        opp.setPlayerSideName(side.getName());
        opp.setModelProbability(probability);
        opp.setConfidenceLow(confidenceLow);
        opp.setConfidenceHigh(confidenceHigh);
        opp.setImpliedProbability(impliedProbability);
        opp.setEdge(edge);
        opp.setThreshold(threshold);
        opp.setAmericanOdds(americanOdds);
        opp.setCreatedAt(createdAt);
        valueOpportunityRepository.save(opp);
        return 1;
    }

    private String normalizeStrategy(String strategyRaw) {
        if (!StringUtils.hasText(strategyRaw)) {
            return STRATEGY_CONSERVATIVE;
        }
        String s = strategyRaw.trim().toUpperCase(Locale.ROOT);
        if (STRATEGY_AGGRESSIVE.equals(s)) {
            return STRATEGY_AGGRESSIVE;
        }
        return STRATEGY_CONSERVATIVE;
    }

    private double strategyThreshold(String strategy) {
        if (STRATEGY_AGGRESSIVE.equalsIgnoreCase(strategy)) {
            return clamp(aggressiveThreshold, 0.005, 0.2);
        }
        return clamp(conservativeThreshold, 0.01, 0.3);
    }

    private int decimalToAmerican(double decimalOdds) {
        if (decimalOdds <= 1.0) return 0;
        if (decimalOdds >= 2.0) {
            return (int) Math.round((decimalOdds - 1.0) * 100.0);
        }
        return (int) Math.round(-100.0 / (decimalOdds - 1.0));
    }

    private String gradeFor(double edge,
                            double confidenceLow,
                            double confidenceHigh,
                            boolean recommended) {
        double ciWidth = Math.max(0.0, confidenceHigh - confidenceLow);
        double score = edge * 100.0;
        score += recommended ? 3.0 : 0.0;
        score += Math.max(0.0, (0.25 - ciWidth) * 10.0);

        if (score >= 12.0) return "A";
        if (score >= 8.0) return "B";
        if (score >= 5.0) return "C";
        if (score >= 2.0) return "D";
        return "F";
    }

    private String buildRationale(String side,
                                  double edge,
                                  double threshold,
                                  double confidenceLow,
                                  double confidenceHigh,
                                  boolean recommended,
                                  boolean longshotRisk,
                                  Double overallReliability,
                                  Double ratingAgreement,
                                  String topTrigger,
                                  Double topTriggerReliability,
                                  Double suggestedSideBaselineStability,
                                  PredictionModelService.AdaptiveRegimeTuning regimeTuning) {
        String verdict = recommended ? "Recommended" : "Watchlist";
        String risk = longshotRisk ? " Longshot guardrail triggered." : "";
        String reliability = buildReliabilityNote(
                overallReliability,
                ratingAgreement,
                topTrigger,
                topTriggerReliability,
                suggestedSideBaselineStability
        );
        String regimeNote = buildRegimeNote(regimeTuning);
        return String.format(
                "%s: %s edge %.2f%% vs threshold %.2f%%, confidence range %.1f%%-%.1f%%.%s%s%s",
                verdict,
                side,
                edge * 100.0,
                threshold * 100.0,
                confidenceLow * 100.0,
                confidenceHigh * 100.0,
                risk,
                reliability,
                regimeNote
        );
    }

    private String buildRegimeNote(PredictionModelService.AdaptiveRegimeTuning regimeTuning) {
        if (regimeTuning == null || regimeTuning.reliability() < 0.05) {
            return "";
        }
        return String.format(
                " Regime tuning: %s reliability %.0f%%, scale %.2f, CI %+,.2f%%.",
                regimeTuning.label(),
                regimeTuning.reliability() * 100.0,
                regimeTuning.confidenceScale(),
                regimeTuning.ciBoost() * 100.0
        );
    }

    private String buildReliabilityNote(Double overallReliability,
                                        Double ratingAgreement,
                                        String topTrigger,
                                        Double topTriggerReliability,
                                        Double suggestedSideBaselineStability) {
        List<String> parts = new ArrayList<>();
        if (overallReliability != null) {
            parts.add(String.format("overall %s (%.0f%%)", reliabilityBand(overallReliability), overallReliability * 100.0));
        }
        if (StringUtils.hasText(topTrigger) && topTriggerReliability != null) {
            parts.add(String.format("%s signal %s (%.0f%%)", topTrigger, reliabilityBand(topTriggerReliability), topTriggerReliability * 100.0));
        }
        if (suggestedSideBaselineStability != null) {
            parts.add(String.format("baseline %s (%.0f%%)", reliabilityBand(suggestedSideBaselineStability), suggestedSideBaselineStability * 100.0));
        }
        if (ratingAgreement != null) {
            parts.add(String.format("model agreement %s (%.0f%%)", reliabilityBand(ratingAgreement), ratingAgreement * 100.0));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return " Reliability: " + String.join(", ", parts) + ".";
    }

    private Double extractOverallReliability(MatchupFeatureVectorDto featureVector) {
        if (featureVector == null || featureVector.reliabilitySummary() == null) {
            return null;
        }
        return clamp(featureVector.reliabilitySummary().overallReliability(), 0.0, 1.0);
    }

    private Double extractRatingAgreement(MatchupFeatureVectorDto featureVector) {
        if (featureVector == null || featureVector.reliabilitySummary() == null) {
            return null;
        }
        return clamp(featureVector.reliabilitySummary().ratingAgreement(), 0.0, 1.0);
    }

    private Double extractSuggestedSideBaselineStability(MatchupFeatureVectorDto featureVector, boolean pickPlayer1) {
        if (featureVector == null) {
            return null;
        }
        MatchupFeatureVectorDto.PlayerFeatureDto player = pickPlayer1 ? featureVector.player1() : featureVector.player2();
        if (player == null) {
            return null;
        }
        return clamp(player.ratingStability(), 0.0, 1.0);
    }

    private Double extractTopTriggerReliability(MatchupFeatureVectorDto featureVector, String topTrigger) {
        if (featureVector == null
                || featureVector.reliabilitySummary() == null
                || !StringUtils.hasText(topTrigger)) {
            return null;
        }
        MatchupFeatureVectorDto.PlayerFeatureDto p1 = featureVector.player1();
        MatchupFeatureVectorDto.PlayerFeatureDto p2 = featureVector.player2();
        return switch (topTrigger.trim().toUpperCase(Locale.ROOT)) {
            case "HEAD-TO-HEAD (DECAYED)" -> clamp(featureVector.headToHeadReliability(), 0.0, 1.0);
            case "RECENT FORM DELTA", "P1 RECENT FORM", "P2 RECENT FORM" -> {
                double left = p1 == null ? 0.0 : clamp(p1.recentFormReliability(), 0.0, 1.0);
                double right = p2 == null ? 0.0 : clamp(p2.recentFormReliability(), 0.0, 1.0);
                yield clamp((left + right) / 2.0, 0.0, 1.0);
            }
            case "OPPONENT-ADJUSTED FORM DELTA" -> {
                double left = p1 == null ? 0.0 : clamp(p1.opponentAdjustedReliability(), 0.0, 1.0);
                double right = p2 == null ? 0.0 : clamp(p2.opponentAdjustedReliability(), 0.0, 1.0);
                yield clamp((left + right) / 2.0, 0.0, 1.0);
            }
            case "SCHEDULE STRENGTH DELTA" -> {
                double left = p1 == null ? 0.0 : clamp(p1.scheduleStrengthReliability(), 0.0, 1.0);
                double right = p2 == null ? 0.0 : clamp(p2.scheduleStrengthReliability(), 0.0, 1.0);
                yield clamp((left + right) / 2.0, 0.0, 1.0);
            }
            case "ELO PROBABILITY DELTA",
                 "GLICKO PROBABILITY DELTA",
                 "GLICKO RATING DELTA",
                 "GLICKO RD ADVANTAGE",
                 "VOLATILITY ADVANTAGE",
                 "FORM × H2H INTERACTION" -> clamp(featureVector.reliabilitySummary().ratingAgreement(), 0.0, 1.0);
            default -> clamp(featureVector.reliabilitySummary().overallReliability(), 0.0, 1.0);
        };
    }

    private String reliabilityBand(double value) {
        if (value >= 0.78) return "strong";
        if (value >= 0.6) return "solid";
        if (value >= 0.42) return "mixed";
        return "thin";
    }

    private LiveAdjustedProbability applyLiveContext(double baseP1,
                                                     double baseP2,
                                                     double impliedP1,
                                                     double impliedP2,
                                                     double p1Low,
                                                     double p1High,
                                                     MatchOdds odds) {
        double p1 = clamp(baseP1, 0.01, 0.99);
        double p2 = clamp(baseP2, 0.01, 0.99);
        double low = clamp(p1Low, 0.01, 0.99);
        double high = clamp(p1High, 0.01, 0.99);

        if (odds != null && odds.isLive()) {
            String phase = StringUtils.hasText(odds.getMatchPhase())
                    ? odds.getMatchPhase().trim().toUpperCase(Locale.ROOT)
                    : "LIVE";
            ScoreContext scoreContext = parseScoreContext(odds.getLiveScore());

            double marketBlend = switch (phase) {
                case "LIVE_EARLY" -> 0.18;
                case "LIVE_MID" -> 0.28;
                case "LIVE_LATE" -> 0.40;
                default -> 0.26;
            };
            p1 = ((1.0 - marketBlend) * p1) + (marketBlend * impliedP1);

            if (scoreContext != null) {
                if (scoreContext.setsP1 != null && scoreContext.setsP2 != null) {
                    int setDelta = scoreContext.setsP1 - scoreContext.setsP2;
                    double setBiasPerSet = switch (phase) {
                        case "LIVE_EARLY" -> 0.05;
                        case "LIVE_MID" -> 0.08;
                        case "LIVE_LATE" -> 0.12;
                        default -> 0.07;
                    };
                    p1 = clamp(p1 + clamp(setDelta * setBiasPerSet, -0.30, 0.30), 0.01, 0.99);

                    int setsTop = Math.max(scoreContext.setsP1, scoreContext.setsP2);
                    int setsMargin = Math.abs(setDelta);
                    if (setsTop >= 3 && setsMargin >= 1) {
                        p1 = setDelta > 0 ? 0.965 : 0.035;
                    }
                }

                if (scoreContext.pointsP1 != null && scoreContext.pointsP2 != null) {
                    int pointDelta = scoreContext.pointsP1 - scoreContext.pointsP2;
                    int topPoints = Math.max(scoreContext.pointsP1, scoreContext.pointsP2);
                    double pointWeight = switch (phase) {
                        case "LIVE_EARLY" -> 0.012;
                        case "LIVE_MID" -> 0.018;
                        case "LIVE_LATE" -> 0.024;
                        default -> 0.016;
                    };
                    if (topPoints >= 10) {
                        pointWeight *= 1.5;
                    } else if (topPoints >= 8) {
                        pointWeight *= 1.2;
                    }
                    p1 = clamp(p1 + clamp(pointDelta * pointWeight, -0.14, 0.14), 0.01, 0.99);
                    if (topPoints >= 10 && Math.abs(pointDelta) >= 1) {
                        double gamePointBias = clamp(Math.abs(pointDelta) * 0.012, 0.012, 0.05);
                        p1 = clamp(p1 + (pointDelta > 0 ? gamePointBias : -gamePointBias), 0.01, 0.99);
                    }
                }
            } else {
                p1 = 0.5 + ((p1 - 0.5) * 0.88);
            }

            double spread = (high - low) * 0.5;
            if (scoreContext != null && scoreContext.setsP1 != null && scoreContext.setsP2 != null) {
                int setDeltaAbs = Math.abs(scoreContext.setsP1 - scoreContext.setsP2);
                spread *= Math.max(0.45, 1.0 - (setDeltaAbs * 0.22));
            } else {
                spread *= 1.10;
            }
            if ("LIVE_LATE".equals(phase)) {
                spread *= 0.78;
            } else if ("LIVE_EARLY".equals(phase)) {
                spread *= 1.06;
            }
            spread = clamp(spread, 0.02, 0.40);
            low = clamp(p1 - spread, 0.01, 0.99);
            high = clamp(p1 + spread, 0.01, 0.99);
        }

        p1 = clamp(p1, 0.01, 0.99);
        p2 = clamp(1.0 - p1, 0.01, 0.99);
        low = clamp(low, 0.01, 0.99);
        high = clamp(high, 0.01, 0.99);
        if (high < low) {
            double tmp = low;
            low = high;
            high = tmp;
        }
        double p2Low = clamp(1.0 - high, 0.01, 0.99);
        double p2High = clamp(1.0 - low, 0.01, 0.99);
        return new LiveAdjustedProbability(p1, p2, low, high, p2Low, p2High);
    }

    private LiveAdjustedProbability applyRegimeTuning(LiveAdjustedProbability base,
                                                      PredictionModelService.AdaptiveRegimeTuning p1Tuning,
                                                      PredictionModelService.AdaptiveRegimeTuning p2Tuning) {
        if (base == null) {
            return null;
        }
        PredictionModelService.AdaptiveRegimeTuning left = p1Tuning == null
                ? PredictionModelService.AdaptiveRegimeTuning.neutral("All Settled")
                : p1Tuning;
        PredictionModelService.AdaptiveRegimeTuning right = p2Tuning == null
                ? PredictionModelService.AdaptiveRegimeTuning.neutral("All Settled")
                : p2Tuning;

        double tunedP1 = 0.5 + ((base.player1Probability() - 0.5) * left.confidenceScale());
        double tunedP2 = 0.5 + ((base.player2Probability() - 0.5) * right.confidenceScale());
        tunedP1 = clamp(tunedP1, 0.01, 0.99);
        tunedP2 = clamp(tunedP2, 0.01, 0.99);
        double total = tunedP1 + tunedP2;
        if (total > 0.0) {
            tunedP1 = clamp(tunedP1 / total, 0.01, 0.99);
            tunedP2 = clamp(1.0 - tunedP1, 0.01, 0.99);
        } else {
            tunedP1 = base.player1Probability();
            tunedP2 = base.player2Probability();
        }

        double avgCiBoost = clamp((left.ciBoost() + right.ciBoost()) / 2.0, 0.0, 0.12);
        double spread = ((base.player1ConfidenceHigh() - base.player1ConfidenceLow()) / 2.0) + avgCiBoost;
        spread = clamp(spread, 0.03, 0.45);
        double low = clamp(tunedP1 - spread, 0.01, 0.99);
        double high = clamp(tunedP1 + spread, 0.01, 0.99);
        double p2Low = clamp(1.0 - high, 0.01, 0.99);
        double p2High = clamp(1.0 - low, 0.01, 0.99);
        return new LiveAdjustedProbability(tunedP1, tunedP2, low, high, p2Low, p2High);
    }

    private int probabilityToAmerican(double probability) {
        double p = clamp(probability, 0.01, 0.99);
        if (p >= 0.5) {
            return (int) Math.round(-(100.0 * p) / Math.max(0.001, (1.0 - p)));
        }
        return (int) Math.round((100.0 * (1.0 - p)) / Math.max(0.001, p));
    }

    private ScoreContext parseScoreContext(String score) {
        if (!StringUtils.hasText(score)) {
            return null;
        }
        Matcher matcher = SCORE_PAIR_PATTERN.matcher(score);
        List<int[]> pairs = new ArrayList<>();
        while (matcher.find()) {
            try {
                pairs.add(new int[]{
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))
                });
            } catch (Exception ignore) {
                // keep scanning
            }
        }
        if (pairs.isEmpty()) {
            return null;
        }

        Integer setsP1 = null;
        Integer setsP2 = null;
        Integer pointsP1 = null;
        Integer pointsP2 = null;

        int[] first = pairs.get(0);
        if (isLikelySetScore(first[0], first[1])) {
            setsP1 = first[0];
            setsP2 = first[1];
        } else if (isLikelyPointScore(first[0], first[1])) {
            pointsP1 = first[0];
            pointsP2 = first[1];
        }

        for (int i = 1; i < pairs.size(); i++) {
            int[] pair = pairs.get(i);
            if (pointsP1 == null && isLikelyPointScore(pair[0], pair[1])) {
                pointsP1 = pair[0];
                pointsP2 = pair[1];
                continue;
            }
            if (setsP1 == null && isLikelySetScore(pair[0], pair[1])) {
                setsP1 = pair[0];
                setsP2 = pair[1];
            }
        }

        if (setsP1 == null && pointsP1 == null) {
            int[] last = pairs.get(pairs.size() - 1);
            if (isLikelySetScore(last[0], last[1])) {
                setsP1 = last[0];
                setsP2 = last[1];
            } else {
                pointsP1 = last[0];
                pointsP2 = last[1];
            }
        }
        return new ScoreContext(setsP1, setsP2, pointsP1, pointsP2);
    }

    private boolean isLikelySetScore(int left, int right) {
        return Math.max(left, right) <= 7;
    }

    private boolean isLikelyPointScore(int left, int right) {
        return Math.max(left, right) >= 8;
    }

    private String buildMatchupKey(Long player1Id,
                                   String player1Name,
                                   Long player2Id,
                                   String player2Name,
                                   String startTimeIso) {
        String p1 = player1Id == null ? normalizeToken(player1Name) : "id-" + player1Id;
        String p2 = player2Id == null ? normalizeToken(player2Name) : "id-" + player2Id;
        if (p1.compareTo(p2) > 0) {
            String tmp = p1;
            p1 = p2;
            p2 = tmp;
        }
        return p1 + "|" + p2 + "|" + startBucket(startTimeIso);
    }

    private String buildSuggestedDedupeKey(String matchupKey, String suggestedSide) {
        if (!StringUtils.hasText(matchupKey) || !StringUtils.hasText(suggestedSide)) {
            return null;
        }
        return matchupKey + "|" + normalizeToken(suggestedSide);
    }

    private String startBucket(String startTimeIso) {
        if (!StringUtils.hasText(startTimeIso)) {
            return LocalDate.now().toString();
        }
        String v = startTimeIso.trim();
        if (v.length() >= 16 && v.charAt(4) == '-' && v.charAt(7) == '-' && (v.charAt(10) == 'T' || v.charAt(10) == 't')) {
            return normalizeToken(v.substring(0, 16));
        }
        if (v.length() >= 10 && v.charAt(4) == '-' && v.charAt(7) == '-') {
            return v.substring(0, 10);
        }
        return normalizeToken(v);
    }

    private String normalizeToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "na";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private String safeSortToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim();
    }

    private static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    private record LiveAdjustedProbability(double player1Probability,
                                           double player2Probability,
                                           double player1ConfidenceLow,
                                           double player1ConfidenceHigh,
                                           double player2ConfidenceLow,
                                           double player2ConfidenceHigh) {
    }

    private record ScoreContext(Integer setsP1,
                                Integer setsP2,
                                Integer pointsP1,
                                Integer pointsP2) {
    }
}
