package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.CompletedMatchLogDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;

/**
 * Second extract from {@code PaperTradingService} as part of the §4
 * decomposition (see
 * {@code docs/ttlelite-series-3.0/runbooks/paper-trading-service-decomposition.md}).
 *
 * <p>Owns the read-only "recent completed matches" query. Picked second
 * because its three private helpers ({@code scoreLabel}, {@code winnerName},
 * {@code loserName}) are used by no other call site, so the move is
 * self-contained.
 */
@Service
public class CompletedMatchLogQueryService {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 30;
    private static final int MIN_LIMIT = 10;
    private static final int MAX_LIMIT = 400;

    private final MatchRepository matchRepository;
    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradeBetRepository betRepository;

    public CompletedMatchLogQueryService(MatchRepository matchRepository,
                                         PaperTradeSessionRepository sessionRepository,
                                         PaperTradeBetRepository betRepository) {
        this.matchRepository = matchRepository;
        this.sessionRepository = sessionRepository;
        this.betRepository = betRepository;
    }

    @Transactional(readOnly = true)
    public List<CompletedMatchLogDto> recentCompletedMatchesLog(int days, int limit) {
        int withinDays = clamp(days, MIN_DAYS, MAX_DAYS);
        int take = clamp(limit, MIN_LIMIT, MAX_LIMIT);
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(withinDays);

        List<Match> completed = new ArrayList<>(matchRepository.findCompletedMatchesBetween(fromDate, toDate));
        completed.sort(Comparator
                .comparing(Match::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Match::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        if (completed.size() > take) {
            completed = completed.subList(0, take);
        }

        Long activeSessionId = sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE)
                .map(PaperTradeSession::getId)
                .orElse(null);

        List<CompletedMatchLogDto> out = new ArrayList<>();
        for (Match match : completed) {
            Optional<PaperTradeBet> activePick = Optional.empty();
            if (activeSessionId != null && match.getId() != null) {
                activePick = betRepository.findFirstBySessionIdAndResultMatchIdOrderByIdAsc(activeSessionId, match.getId());
            }
            Optional<PaperTradeBet> historicalPick = match.getId() == null
                    ? Optional.empty()
                    : betRepository.findFirstByResultMatchIdOrderBySettledAtDesc(match.getId());
            Optional<PaperTradeBet> pick = activePick.or(() -> historicalPick);

            String p1 = match.getPlayer1() == null ? "Player 1" : match.getPlayer1().getName();
            String p2 = match.getPlayer2() == null ? "Player 2" : match.getPlayer2().getName();
            String winner = winnerName(match, p1, p2);
            String loser = loserName(match, p1, p2, winner);
            String score = scoreLabel(match);

            String matchDateIso = match.getDate() == null ? null : match.getDate().toString();
            String startTimeIso = pick.map(PaperTradeBet::getStartTimeIso)
                    .filter(StringUtils::hasText)
                    .or(() -> historicalPick.map(PaperTradeBet::getStartTimeIso).filter(StringUtils::hasText))
                    .orElse(null);

            out.add(new CompletedMatchLogDto(
                    match.getId(),
                    p1 + " vs " + p2,
                    matchDateIso,
                    startTimeIso,
                    p1,
                    p2,
                    winner,
                    loser,
                    score,
                    activePick.isPresent() || historicalPick.isPresent(),
                    pick.map(PaperTradeBet::getStatus).orElse(null)
            ));
        }
        return out;
    }

    private static String scoreLabel(Match match) {
        if (match == null) {
            return "N/A";
        }
        if (StringUtils.hasText(match.getResult())) {
            return match.getResult().trim();
        }
        if (match.getPlayer1SetsWon() != null && match.getPlayer2SetsWon() != null) {
            return match.getPlayer1SetsWon() + ":" + match.getPlayer2SetsWon();
        }
        return "N/A";
    }

    private static String winnerName(Match match, String p1, String p2) {
        if (match == null || match.getWinnerPlayerId() == null) {
            return "N/A";
        }
        if (match.getPlayer1() != null && match.getPlayer1().getId() != null
                && match.getWinnerPlayerId().equals(match.getPlayer1().getId())) {
            return p1;
        }
        if (match.getPlayer2() != null && match.getPlayer2().getId() != null
                && match.getWinnerPlayerId().equals(match.getPlayer2().getId())) {
            return p2;
        }
        return "N/A";
    }

    private static String loserName(Match match, String p1, String p2, String winner) {
        if (match == null || !StringUtils.hasText(winner) || "N/A".equalsIgnoreCase(winner)) {
            return "N/A";
        }
        if (winner.equals(p1)) {
            return p2;
        }
        if (winner.equals(p2)) {
            return p1;
        }
        return "N/A";
    }

    // clamp now provided by PaperTradingHelpers (import-static above).
}
