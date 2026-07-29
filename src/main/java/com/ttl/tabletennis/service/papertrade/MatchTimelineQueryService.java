package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.TrackedMatchObservationDto;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;

/**
 * First service extracted from {@code PaperTradingService} as part of the
 * §4 decomposition (see
 * {@code docs/ttlelite-series-3.0/runbooks/paper-trading-service-decomposition.md}).
 *
 * <p>Owns the read-only timeline lookup for a single tracked event. Picked
 * as the first extract because it has no transactional state coupling, no
 * shared private helpers beyond a tiny DTO conversion, and the smallest
 * blast radius for proving the decomposition pattern.
 */
@Service
public class MatchTimelineQueryService {

    private final TrackedMatchObservationRepository observationRepository;

    public MatchTimelineQueryService(TrackedMatchObservationRepository observationRepository) {
        this.observationRepository = observationRepository;
    }

    @Transactional(readOnly = true)
    public List<TrackedMatchObservationDto> getMatchTimeline(String eventKey) {
        if (!StringUtils.hasText(eventKey)) {
            return List.of();
        }
        return observationRepository.findByEventKeyOrderByObservedAtAsc(eventKey.trim())
                .stream()
                .map(MatchTimelineQueryService::toObservationDto)
                .toList();
    }

    private static TrackedMatchObservationDto toObservationDto(TrackedMatchObservation observation) {
        return new TrackedMatchObservationDto(
                observation.getId(),
                observation.getSessionId(),
                observation.getBetId(),
                observation.getEventKey(),
                observation.getDedupeKey(),
                observation.getExternalEventId(),
                observation.getSource(),
                observation.getSourceKind(),
                round4(observation.getSourceConfidence()),
                observation.isDisplayed(),
                observation.isResulted(),
                observation.isMatchCompleted(),
                observation.getSourceFeedCode(),
                observation.getSourceFeedEventId(),
                observation.isLive(),
                observation.isTrackedAfterClose(),
                observation.getEventName(),
                observation.getCompetitionName(),
                observation.getStartTimeIso(),
                observation.getPlayer1Id(),
                observation.getPlayer1Name(),
                observation.getPlayer2Id(),
                observation.getPlayer2Name(),
                observation.getLiveScore(),
                observation.getMatchPhase(),
                observation.getScoreDetail(),
                observation.getObservedAt()
        );
    }

    // round4 now provided by PaperTradingHelpers (import-static above).
}
