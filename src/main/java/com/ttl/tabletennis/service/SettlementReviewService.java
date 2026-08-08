package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.SettlementContradictionRecord;
import com.ttl.tabletennis.dto.SettlementReviewItemDto;
import com.ttl.tabletennis.dto.SettlementReviewPageDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.SettlementContradictionRecordRepository;
import com.ttl.tabletennis.service.papertrade.ScorePair;
import com.ttl.tabletennis.service.papertrade.ScoreWinnerResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds a stable, human-readable forensic view over every completed paper
 * settlement.  It intentionally covers both the Score Truth path and legacy
 * archive recovery so an operator never has to reverse-engineer a reason code.
 */
@Service
public class SettlementReviewService {

    static final String FLAG_ARCHIVE_NOT_RECENT = "ARCHIVE_MATCH_NOT_IN_RECENT_COMPLETED";
    static final String FLAG_ARCHIVE_SCORE_CONFLICT = "ARCHIVE_WINNER_CONFLICTS_LATE_SCORE_DIRECTION";
    static final String FLAG_MULTIPLE_SAME_DAY = "MULTIPLE_SAME_DAY_CANDIDATES";

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final Collection<String> COMPLETED_STATUSES = List.of(
            PaperTradeBet.STATUS_WON,
            PaperTradeBet.STATUS_LOST,
            PaperTradeBet.STATUS_PUSHED,
            PaperTradeBet.STATUS_VOIDED
    );

    private final PaperTradeBetRepository betRepository;
    private final MatchRepository matchRepository;
    private final SettlementContradictionRecordRepository contradictionRepository;
    private final ScoreWinnerResolver scoreWinnerResolver;

    public SettlementReviewService(PaperTradeBetRepository betRepository,
                                   MatchRepository matchRepository,
                                   SettlementContradictionRecordRepository contradictionRepository,
                                   ScoreWinnerResolver scoreWinnerResolver) {
        this.betRepository = betRepository;
        this.matchRepository = matchRepository;
        this.contradictionRepository = contradictionRepository;
        this.scoreWinnerResolver = scoreWinnerResolver;
    }

    @Transactional(readOnly = true)
    public SettlementReviewPageDto review(Integer page, Integer size, boolean suspiciousOnly) {
        int normalizedPage = page == null || page < 0 ? 0 : page;
        int normalizedSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage, normalizedSize);

        Page<SettlementReviewItemDto> result;
        if (suspiciousOnly) {
            List<SettlementReviewItemDto> suspicious = betRepository
                    .findAllByStatusInOrderBySettledAtDescIdDesc(COMPLETED_STATUSES, Pageable.unpaged())
                    .getContent().stream()
                    .map(this::toItem)
                    .filter(SettlementReviewItemDto::suspicious)
                    .toList();
            int from = Math.min(normalizedPage * normalizedSize, suspicious.size());
            int to = Math.min(from + normalizedSize, suspicious.size());
            result = new PageImpl<>(suspicious.subList(from, to), pageable, suspicious.size());
        } else {
            result = betRepository
                    .findAllByStatusInOrderBySettledAtDescIdDesc(COMPLETED_STATUSES, pageable)
                    .map(this::toItem);
        }

        long suspiciousCount = result.getContent().stream().filter(SettlementReviewItemDto::suspicious).count();
        long highTrustCount = result.getContent().stream().filter(item -> "HIGH".equals(item.trustBand())).count();
        long lowTrustCount = result.getContent().stream().filter(item -> "LOW".equals(item.trustBand())).count();
        return new SettlementReviewPageDto(
                Instant.now(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasPrevious(),
                result.hasNext(),
                suspiciousCount,
                highTrustCount,
                lowTrustCount,
                result.getContent()
        );
    }

    private SettlementReviewItemDto toItem(PaperTradeBet bet) {
        boolean archiveSettlement = isArchiveSettlement(bet);
        Optional<Match> candidate = bet.getResultMatchId() == null
                ? Optional.empty()
                : matchRepository.findById(bet.getResultMatchId());
        LocalDate targetDate = targetDate(bet);
        LocalDate selectedDate = candidate.map(Match::getDate).orElse(archiveSettlement ? targetDate : null);

        double playerSetConfidence = candidate.map(match -> playerSetConfidence(bet, match))
                .orElse(archiveSettlement ? 0.75 : 0.0);
        Boolean feedIdentityMatch = archiveSettlement
                ? candidate.map(match -> feedIdentityMatches(bet, match)).orElse(false)
                : null;

        List<Match> recentCandidates = recentCandidates(bet);
        boolean inRecentCompleted = candidate
                .map(match -> recentCandidates.stream().anyMatch(recent -> Objects.equals(recent.getId(), match.getId())))
                .orElse(false);
        int sameDayCandidates = sameDayCandidateCount(bet, selectedDate);
        Optional<Long> lateScoreDirection = determineLateScoreDirection(bet);
        boolean scoreConflict = archiveSettlement
                && lateScoreDirection.isPresent()
                && bet.getWinnerPlayerId() != null
                && !Objects.equals(lateScoreDirection.get(), bet.getWinnerPlayerId());

        LinkedHashSet<String> suspicionFlags = new LinkedHashSet<>();
        if (archiveSettlement && !inRecentCompleted) {
            suspicionFlags.add(FLAG_ARCHIVE_NOT_RECENT);
        }
        if (scoreConflict) {
            suspicionFlags.add(FLAG_ARCHIVE_SCORE_CONFLICT);
        }
        if (archiveSettlement && sameDayCandidates > 1) {
            suspicionFlags.add(FLAG_MULTIPLE_SAME_DAY);
        }

        LinkedHashSet<String> contradictionFlags = new LinkedHashSet<>(suspicionFlags);
        if (bet.isScoreEvidenceContradictory()) {
            contradictionFlags.add("SCORE_EVIDENCE_CONTRADICTORY");
        }
        if (bet.getSettlementAmbiguityScore() != null && bet.getSettlementAmbiguityScore() >= 0.30) {
            contradictionFlags.add("HIGH_SETTLEMENT_AMBIGUITY");
        }
        if (StringUtils.hasText(bet.getSettlementCoverageState())
                && !"FULL".equalsIgnoreCase(bet.getSettlementCoverageState())) {
            contradictionFlags.add("INCOMPLETE_EVIDENCE_COVERAGE");
        }
        if (bet.getWinnerPlayerId() != null
                && !Objects.equals(bet.getWinnerPlayerId(), bet.getPlayer1Id())
                && !Objects.equals(bet.getWinnerPlayerId(), bet.getPlayer2Id())) {
            contradictionFlags.add("WINNER_OUTSIDE_LOCKED_PLAYER_SET");
        }
        addPersistedContradictions(bet, contradictionFlags);

        Double archiveConfidence = archiveSettlement
                ? archiveConfidence(bet, playerSetConfidence, feedIdentityMatch, inRecentCompleted,
                selectedDate, targetDate, sameDayCandidates, scoreConflict)
                : null;
        double effectiveConfidence = effectiveConfidence(bet, archiveConfidence);
        String trustBand = trustBand(bet, effectiveConfidence, suspicionFlags, contradictionFlags, scoreConflict);
        String winnerName = playerName(bet, bet.getWinnerPlayerId());
        String directionName = playerName(bet, lateScoreDirection.orElse(null));
        String explanation = explanation(
                bet,
                archiveSettlement,
                candidate.orElse(null),
                selectedDate,
                playerSetConfidence,
                feedIdentityMatch,
                inRecentCompleted,
                sameDayCandidates,
                scoreConflict,
                trustBand
        );

        return new SettlementReviewItemDto(
                bet.getId(),
                bet.getSessionId(),
                bet.getStatus(),
                bet.getEventName(),
                bet.getCompetitionName(),
                bet.getPlayer1Name(),
                bet.getPlayer2Name(),
                bet.getSideName(),
                bet.getWinnerPlayerId(),
                winnerName,
                bet.getSettlementSource(),
                bet.getSettlementReason(),
                bet.getSettledAt(),
                bet.getResultMatchId(),
                selectedDate,
                archiveSettlement ? round4(playerSetConfidence) : null,
                feedIdentityMatch,
                archiveConfidence,
                inRecentCompleted,
                recentCandidates.size(),
                sameDayCandidates,
                bet.getLastObservedScore(),
                bet.getLastObservedPhase(),
                lateScoreDirection.orElse(null),
                directionName,
                bet.getScoreEvidenceQuality(),
                bet.getScoreEvidenceFinality(),
                bet.getScoreEvidenceConfidence(),
                bet.getScoreEvidenceObservationCount(),
                bet.getScoreEvidenceSourceCount(),
                bet.getScoreEvidenceAgreeingSources(),
                bet.getScoreEvidenceCompletionSignals(),
                bet.getSettlementEvidenceId(),
                bet.getSettlementCoverageState(),
                bet.getSettlementAmbiguityScore(),
                bet.getSettlementConfidence(),
                trustBand,
                !suspicionFlags.isEmpty(),
                List.copyOf(suspicionFlags),
                List.copyOf(contradictionFlags),
                explanation
        );
    }

    private List<Match> recentCandidates(PaperTradeBet bet) {
        if (bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return List.of();
        }
        LocalDate asOf = bet.getSettledAt() == null ? LocalDate.now() : bet.getSettledAt().toLocalDate();
        return matchRepository.findCompletedRecentMatchesByPlayersUpToDate(
                bet.getPlayer1Id(), bet.getPlayer2Id(), asOf, PageRequest.of(0, 10));
    }

    private int sameDayCandidateCount(PaperTradeBet bet, LocalDate date) {
        if (date == null || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return 0;
        }
        return matchRepository.findCompletedMatchesByPlayersOnDate(
                bet.getPlayer1Id(), bet.getPlayer2Id(), date).size();
    }

    private void addPersistedContradictions(PaperTradeBet bet, LinkedHashSet<String> flags) {
        if (bet.getSettlementEvidenceId() == null) {
            return;
        }
        contradictionRepository.findByEvidenceIdOrderByObservedAtDesc(
                        bet.getSettlementEvidenceId(), PageRequest.of(0, 25))
                .stream()
                .map(SettlementContradictionRecord::getKind)
                .filter(StringUtils::hasText)
                .map(value -> "EVIDENCE_" + value.trim().toUpperCase(Locale.ROOT))
                .forEach(flags::add);
    }

    private Optional<Long> determineLateScoreDirection(PaperTradeBet bet) {
        if (bet.getPlayer1Id() == null || bet.getPlayer2Id() == null || !StringUtils.hasText(bet.getLastObservedScore())) {
            return Optional.empty();
        }
        Optional<Long> resolved = scoreWinnerResolver.determineWinnerFromScore(
                bet.getLastObservedScore(), bet.getPlayer1Id(), bet.getPlayer2Id(), bet.getLastObservedPhase(), true);
        if (resolved.isPresent()) {
            return resolved;
        }
        resolved = scoreWinnerResolver.determineWinnerFromConfidenceState(
                bet.getLastObservedScore(), bet.getPlayer1Id(), bet.getPlayer2Id(), bet.getLastObservedPhase());
        if (resolved.isPresent()) {
            return resolved;
        }
        List<ScorePair> pairs = ScorePair.parseAll(bet.getLastObservedScore());
        int setIndex = ScoreWinnerResolver.findPrimarySetScorePairIndex(pairs, 3);
        if (setIndex < 0) {
            return Optional.empty();
        }
        ScorePair sets = pairs.get(setIndex);
        int top = Math.max(sets.left(), sets.right());
        int margin = Math.abs(sets.left() - sets.right());
        if (top < 2 || margin < 1) {
            return Optional.empty();
        }
        return Optional.of(sets.left() > sets.right() ? bet.getPlayer1Id() : bet.getPlayer2Id());
    }

    private double playerSetConfidence(PaperTradeBet bet, Match match) {
        if (match.getPlayer1() == null || match.getPlayer2() == null
                || match.getPlayer1().getId() == null || match.getPlayer2().getId() == null
                || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return 0.5;
        }
        boolean exact = (Objects.equals(match.getPlayer1().getId(), bet.getPlayer1Id())
                && Objects.equals(match.getPlayer2().getId(), bet.getPlayer2Id()))
                || (Objects.equals(match.getPlayer1().getId(), bet.getPlayer2Id())
                && Objects.equals(match.getPlayer2().getId(), bet.getPlayer1Id()));
        return exact ? 1.0 : 0.0;
    }

    private boolean feedIdentityMatches(PaperTradeBet bet, Match match) {
        String feedId = firstText(
                bet.getLockedSourceFeedEventId(),
                bet.getLastSourceFeedEventId(),
                bet.getLockedExternalEventId(),
                bet.getExternalEventId()
        );
        return StringUtils.hasText(feedId)
                && (feedId.equalsIgnoreCase(nullToEmpty(match.getSourceFeedEventId()))
                || feedId.equalsIgnoreCase(nullToEmpty(match.getExternalId())));
    }

    private Double archiveConfidence(PaperTradeBet bet,
                                     double playerSetConfidence,
                                     Boolean feedIdentityMatch,
                                     boolean inRecentCompleted,
                                     LocalDate selectedDate,
                                     LocalDate targetDate,
                                     int sameDayCandidates,
                                     boolean scoreConflict) {
        double confidence = finiteOrDefault(bet.getSettlementConfidence(), 0.78);
        confidence += (playerSetConfidence - 0.75) * 0.20;
        confidence += inRecentCompleted ? 0.05 : -0.20;
        String feedId = firstText(
                bet.getLockedSourceFeedEventId(), bet.getLastSourceFeedEventId(),
                bet.getLockedExternalEventId(), bet.getExternalEventId());
        if (Boolean.TRUE.equals(feedIdentityMatch)) {
            confidence += 0.10;
        } else if (StringUtils.hasText(feedId)) {
            confidence -= 0.12;
        }
        if (selectedDate != null && targetDate != null && Objects.equals(selectedDate, targetDate)) {
            confidence += 0.05;
        }
        if (sameDayCandidates > 1) {
            confidence -= 0.20;
        }
        if (scoreConflict) {
            confidence -= 0.35;
        }
        return round4(clamp01(confidence));
    }

    private double effectiveConfidence(PaperTradeBet bet, Double archiveConfidence) {
        double base = finiteOrDefault(bet.getSettlementConfidence(), 0.35);
        if (archiveConfidence != null) {
            base = Math.min(base, archiveConfidence);
        }
        if (bet.getScoreEvidenceConfidence() != null && Double.isFinite(bet.getScoreEvidenceConfidence())) {
            base = Math.min(base, clamp01(bet.getScoreEvidenceConfidence()));
        }
        return base;
    }

    private String trustBand(PaperTradeBet bet,
                             double effectiveConfidence,
                             LinkedHashSet<String> suspicionFlags,
                             LinkedHashSet<String> contradictionFlags,
                             boolean scoreConflict) {
        if (scoreConflict || bet.isScoreEvidenceContradictory()
                || contradictionFlags.contains("WINNER_OUTSIDE_LOCKED_PLAYER_SET")
                || effectiveConfidence < 0.70) {
            return "LOW";
        }
        if (!suspicionFlags.isEmpty()
                || !contradictionFlags.isEmpty()
                || effectiveConfidence < 0.90
                || (bet.getSettlementAmbiguityScore() != null && bet.getSettlementAmbiguityScore() >= 0.20)) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private String explanation(PaperTradeBet bet,
                               boolean archive,
                               Match candidate,
                               LocalDate selectedDate,
                               double playerSetConfidence,
                               Boolean feedIdentityMatch,
                               boolean inRecent,
                               int sameDayCandidates,
                               boolean scoreConflict,
                               String trustBand) {
        if (!archive) {
            String quality = StringUtils.hasText(bet.getScoreEvidenceQuality())
                    ? bet.getScoreEvidenceQuality()
                    : "ungraded";
            int observations = bet.getScoreEvidenceObservationCount() == null ? 0 : bet.getScoreEvidenceObservationCount();
            int sources = bet.getScoreEvidenceSourceCount() == null ? 0 : bet.getScoreEvidenceSourceCount();
            return "%s-trust %s settlement from %s score evidence (%d observations across %d sources)."
                    .formatted(title(trustBand), safeReason(bet), quality, observations, sources);
        }
        String candidateLabel = candidate == null ? "an external archive row" : "archive match #" + candidate.getId();
        String feedLabel = Boolean.TRUE.equals(feedIdentityMatch) ? "feed identity matched" : "feed identity did not match";
        String recencyLabel = inRecent ? "present in the recent completed ledger" : "not present in the recent completed ledger";
        String conflictLabel = scoreConflict ? " The archived winner conflicts with the late score direction." : "";
        return "%s-trust %s settlement selected %s dated %s; player-set confidence %.0f%%, %s, %s, and %d completed candidate(s) existed that day.%s"
                .formatted(title(trustBand), safeReason(bet), candidateLabel,
                        selectedDate == null ? "unknown" : selectedDate,
                        playerSetConfidence * 100.0, feedLabel, recencyLabel, sameDayCandidates, conflictLabel);
    }

    private boolean isArchiveSettlement(PaperTradeBet bet) {
        String source = upper(bet.getSettlementSource());
        String reason = upper(bet.getSettlementReason());
        return bet.getResultMatchId() != null
                || source.contains("OFFICIAL")
                || source.contains("DATABASE")
                || reason.contains("OFFICIAL")
                || reason.contains("DATABASE")
                || reason.contains("LEDGER");
    }

    private LocalDate targetDate(PaperTradeBet bet) {
        String value = StringUtils.hasText(bet.getLockedStartTimeIso())
                ? bet.getLockedStartTimeIso()
                : bet.getStartTimeIso();
        if (StringUtils.hasText(value)) {
            try {
                return OffsetDateTime.parse(value).toLocalDate();
            } catch (RuntimeException ignored) {
                try {
                    return LocalDateTime.parse(value).toLocalDate();
                } catch (RuntimeException ignoredAgain) {
                    if (value.length() >= 10) {
                        try {
                            return LocalDate.parse(value.substring(0, 10));
                        } catch (RuntimeException ignoredDate) {
                            // fall through to persisted timestamps
                        }
                    }
                }
            }
        }
        if (bet.getPlacedAt() != null) {
            return bet.getPlacedAt().toLocalDate();
        }
        return bet.getSettledAt() == null ? null : bet.getSettledAt().toLocalDate();
    }

    private String playerName(PaperTradeBet bet, Long playerId) {
        if (playerId == null) {
            return null;
        }
        if (Objects.equals(playerId, bet.getPlayer1Id())) {
            return bet.getPlayer1Name();
        }
        if (Objects.equals(playerId, bet.getPlayer2Id())) {
            return bet.getPlayer2Name();
        }
        return "Player #" + playerId;
    }

    private String safeReason(PaperTradeBet bet) {
        return StringUtils.hasText(bet.getSettlementReason()) ? bet.getSettlementReason() : "unclassified";
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String title(String value) {
        if (!StringUtils.hasText(value)) {
            return "Unknown";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private double finiteOrDefault(Double value, double fallback) {
        return value != null && Double.isFinite(value) ? clamp01(value) : fallback;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
