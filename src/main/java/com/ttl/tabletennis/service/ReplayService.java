package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.ReplayDefinition;
import com.ttl.tabletennis.domain.ReplayEventLog;
import com.ttl.tabletennis.dto.ModelCallTrackingDto;
import com.ttl.tabletennis.dto.ReplayDefinitionRequest;
import com.ttl.tabletennis.dto.ReplayDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.ReplayDefinitionRepository;
import com.ttl.tabletennis.repository.ReplayEventLogRepository;
import com.ttl.tabletennis.service.papertrade.ModelCallLedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic playback over already-frozen model calls. The initial engine
 * deliberately refuses to reconstruct features from today's database: that
 * would turn a historical replay into a future-data leak.
 */
@Service
public class ReplayService {

    private final ReplayDefinitionRepository definitionRepository;
    private final ReplayEventLogRepository eventRepository;
    private final PaperTradeSessionRepository sessionRepository;
    private final ModelCallLedgerService ledgerService;

    public ReplayService(ReplayDefinitionRepository definitionRepository,
                         ReplayEventLogRepository eventRepository,
                         PaperTradeSessionRepository sessionRepository,
                         ModelCallLedgerService ledgerService) {
        this.definitionRepository = definitionRepository;
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true)
    public List<ReplayDto> all() {
        return definitionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(definition -> dto(definition, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReplayDto get(long id) {
        return dto(require(id), true);
    }

    @Transactional
    public ReplayDto create(ReplayDefinitionRequest request) {
        List<Long> runIds = normalizeIds(request.sourceRunIds());
        validateWindow(request.windowStart(), request.windowEnd());
        for (Long runId : runIds) {
            PaperTradeSession session = sessionRepository.findById(runId)
                    .orElseThrow(() -> new ResourceNotFoundException("Run " + runId + " was not found"));
            if (!PaperTradeSession.STATUS_CLOSED.equalsIgnoreCase(session.getStatus())) {
                throw new IllegalArgumentException("Replay sources must be closed, immutable runs; run " + runId + " is " + session.getStatus());
            }
        }

        ReplayDefinition definition = new ReplayDefinition();
        definition.setLabel(request.label().trim());
        definition.setReplayMode(normalizeMode(request.replayMode()));
        definition.setSourceRunIds(joinLongs(runIds));
        definition.setWindowStart(request.windowStart());
        definition.setWindowEnd(request.windowEnd());
        definition.setCaptureRule(text(request.captureRule(), "FROZEN_ORIGINAL_CALL"));
        definition.setModelLaneKeys(joinStrings(request.modelLaneKeys(), "CHAMPION"));
        definition.setPortfolioKeys(joinStrings(request.portfolioKeys(), "ALL_CALLS"));
        definition.setExecutionBook(text(request.executionBook(), "HR_MKT"));
        definition.setInitialBankroll(request.initialBankroll() == null ? 1000.0 : Math.max(1.0, request.initialBankroll()));
        definition.setMaxQuoteAgeSeconds(request.maxQuoteAgeSeconds() == null ? 45 : Math.max(1, request.maxQuoteAgeSeconds()));
        definition.setDeterministicSeed(request.deterministicSeed() == null ? 31_415_926L : request.deterministicSeed());
        definition.setStatus("DRAFT");
        definition.setLeakageAuditStatus("PENDING");
        definition.setReproducible(false);
        definition.setDefinitionChecksum(checksum(canonical(definition, null)));
        return definitionRepository.findByDefinitionChecksum(definition.getDefinitionChecksum())
                .map(existing -> dto(existing, true))
                .orElseGet(() -> dto(definitionRepository.save(definition), true));
    }

    @Transactional
    public ReplayDto start(long id) {
        ReplayDefinition definition = require(id);
        if ("COMPLETED".equals(definition.getStatus())) {
            return dto(definition, true);
        }
        if (eventRepository.countByReplayId(id) > 0) {
            throw new IllegalStateException("A partial replay receipt already exists; completed replays are never overwritten");
        }
        definition.setStatus("RUNNING");
        definition.setStartedAt(LocalDateTime.now());
        definitionRepository.save(definition);

        List<ModelCallTrackingDto> calls = parseLongs(definition.getSourceRunIds()).stream()
                .flatMap(runId -> ledgerService.monitorAllForResearch(runId).calls().stream())
                .filter(call -> within(call.capturedAt(), definition.getWindowStart(), definition.getWindowEnd()))
                .sorted(Comparator.comparing(ReplayService::capturedAt)
                        .thenComparing(ModelCallTrackingDto::callId))
                .toList();

        List<ReplayEventLog> events = new ArrayList<>();
        int sequence = 1;
        int resolved = 0;
        int pricedResolved = 0;
        int correct = 0;
        double pnl = 0.0;
        for (ModelCallTrackingDto call : calls) {
            ReplayEventLog event = new ReplayEventLog();
            event.setReplayId(definition.getId());
            event.setSequenceNumber(sequence++);
            event.setSourceRunId(call.sessionId());
            event.setSourceCallId(call.callId());
            event.setEventTime(capturedAt(call));
            event.setEventType("MODEL_CALL");
            event.setEventName(call.eventName());
            event.setCaptureType(call.captureType());
            event.setPredictedWinnerName(call.predictedWinnerName());
            event.setModelProbability(call.modelProbability());
            event.setHardRockAmericanOdds(call.hardRockAmericanOdds());
            event.setDecisionStatus(call.decisionStatus());
            event.setPipelineStage(call.pipelineStage());
            event.setEffectiveOutcome(call.effectiveOutcome());
            event.setOutcomeSource(call.effectiveOutcomeSource());
            Double profit = flatProfit(call.effectiveOutcome(), call.hardRockAmericanOdds());
            event.setFlatStakeProfit(profit);
            if ("CORRECT".equals(call.effectiveOutcome()) || "INCORRECT".equals(call.effectiveOutcome())) {
                resolved++;
                if ("CORRECT".equals(call.effectiveOutcome())) correct++;
                if (profit != null) {
                    pricedResolved++;
                    pnl += profit;
                }
            }
            events.add(event);
        }
        eventRepository.saveAll(events);
        definition.setEventCount(events.size());
        definition.setResolvedCount(resolved);
        definition.setPricedResolvedCount(pricedResolved);
        definition.setCorrectCount(correct);
        definition.setFlatStakePnl(round4(pnl));
        definition.setLeakageAuditStatus("FROZEN_RECEIPTS_ONLY");
        definition.setReproducible(true);
        definition.setStatus("COMPLETED");
        definition.setCompletedAt(LocalDateTime.now());
        definitionRepository.save(definition);
        return dto(definition, true);
    }

    @Transactional
    public ReplayDto branch(long id) {
        ReplayDefinition parent = require(id);
        ReplayDefinition branch = new ReplayDefinition();
        branch.setParentReplayId(parent.getId());
        branch.setLabel(parent.getLabel() + " · Branch");
        branch.setReplayMode(parent.getReplayMode());
        branch.setSourceRunIds(parent.getSourceRunIds());
        branch.setWindowStart(parent.getWindowStart());
        branch.setWindowEnd(parent.getWindowEnd());
        branch.setCaptureRule(parent.getCaptureRule());
        branch.setModelLaneKeys(parent.getModelLaneKeys());
        branch.setPortfolioKeys(parent.getPortfolioKeys());
        branch.setExecutionBook(parent.getExecutionBook());
        branch.setInitialBankroll(parent.getInitialBankroll());
        branch.setMaxQuoteAgeSeconds(parent.getMaxQuoteAgeSeconds());
        branch.setDeterministicSeed(parent.getDeterministicSeed());
        branch.setStatus("DRAFT");
        branch.setLeakageAuditStatus("PENDING");
        branch.setDefinitionChecksum(checksum(canonical(branch, LocalDateTime.now().toString())));
        return dto(definitionRepository.save(branch), true);
    }

    private ReplayDto dto(ReplayDefinition definition, boolean includeEvents) {
        List<ReplayDto.Event> events = includeEvents
                ? eventRepository.findByReplayIdOrderBySequenceNumberAsc(definition.getId()).stream()
                .map(event -> new ReplayDto.Event(event.getSequenceNumber(), event.getSourceRunId(), event.getSourceCallId(),
                        event.getEventTime(), event.getEventType(), event.getEventName(), event.getCaptureType(),
                        event.getPredictedWinnerName(), event.getModelProbability(), event.getHardRockAmericanOdds(),
                        event.getDecisionStatus(), event.getPipelineStage(), event.getEffectiveOutcome(),
                        event.getOutcomeSource(), event.getFlatStakeProfit()))
                .toList()
                : List.of();
        double accuracy = definition.getResolvedCount() == 0 ? 0.0
                : round2(definition.getCorrectCount() * 100.0 / definition.getResolvedCount());
        double roi = definition.getPricedResolvedCount() == 0 ? 0.0
                : round2(definition.getFlatStakePnl() * 100.0 / definition.getPricedResolvedCount());
        List<String> notes = List.of(
                "Playback uses the original frozen model-call receipt and original captured Hard Rock price.",
                "Results are joined only after event-time ordering; unresolved calls remain awaiting and never become losses.",
                "This replay does not rebuild historical features from the current player or rating tables.");
        return new ReplayDto(definition.getId(), definition.getParentReplayId(), definition.getLabel(),
                definition.getStatus(), definition.getReplayMode(), parseLongs(definition.getSourceRunIds()),
                definition.getWindowStart(), definition.getWindowEnd(), definition.getCaptureRule(),
                split(definition.getModelLaneKeys()), split(definition.getPortfolioKeys()), definition.getExecutionBook(),
                definition.getInitialBankroll(), definition.getMaxQuoteAgeSeconds(), definition.getDeterministicSeed(),
                definition.getDefinitionChecksum(), definition.getLeakageAuditStatus(), definition.isReproducible(),
                definition.getEventCount(), definition.getResolvedCount(), definition.getPricedResolvedCount(),
                definition.getCorrectCount(), accuracy,
                definition.getFlatStakePnl(), roi, definition.getCreatedAt(), definition.getStartedAt(),
                definition.getCompletedAt(), events, notes);
    }

    private ReplayDefinition require(long id) {
        return definitionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Replay " + id + " was not found"));
    }

    private static List<Long> normalizeIds(List<Long> values) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (values != null) values.stream().filter(id -> id != null && id > 0).forEach(ids::add);
        if (ids.isEmpty()) throw new IllegalArgumentException("Choose at least one source run");
        if (ids.size() > 8) throw new IllegalArgumentException("Replay at most eight source runs together");
        return List.copyOf(ids);
    }

    private static void validateWindow(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && !start.isBefore(end)) {
            throw new IllegalArgumentException("Replay window start must be before its end");
        }
    }

    private static String normalizeMode(String value) {
        String mode = text(value, "HISTORICAL_AS_KNOWN").toUpperCase(Locale.ROOT);
        if ("MODERN_MODEL_RETROSPECTIVE".equals(mode)) {
            throw new IllegalArgumentException("Modern-model retrospective replay requires a candidate with a registered training cutoff; use Historical as known until that artifact is selected.");
        }
        if (!"HISTORICAL_AS_KNOWN".equals(mode)) throw new IllegalArgumentException("Unknown replay mode " + value);
        return mode;
    }

    private static boolean within(String value, LocalDateTime start, LocalDateTime end) {
        LocalDateTime time = LocalDateTime.parse(value);
        return (start == null || !time.isBefore(start)) && (end == null || time.isBefore(end));
    }

    private static LocalDateTime capturedAt(ModelCallTrackingDto call) {
        return LocalDateTime.parse(call.capturedAt());
    }

    private static Double flatProfit(String outcome, Integer americanOdds) {
        if (americanOdds == null) return null;
        if ("INCORRECT".equals(outcome)) return -1.0;
        if (!"CORRECT".equals(outcome)) return null;
        return americanOdds > 0 ? americanOdds / 100.0 : 100.0 / Math.abs(americanOdds);
    }

    private static String canonical(ReplayDefinition d, String salt) {
        return String.join("|", d.getLabel(), d.getReplayMode(), d.getSourceRunIds(),
                String.valueOf(d.getWindowStart()), String.valueOf(d.getWindowEnd()), d.getCaptureRule(),
                d.getModelLaneKeys(), d.getPortfolioKeys(), d.getExecutionBook(),
                String.valueOf(d.getInitialBankroll()), String.valueOf(d.getMaxQuoteAgeSeconds()),
                String.valueOf(d.getDeterministicSeed()), String.valueOf(d.getParentReplayId()), String.valueOf(salt));
    }

    private static String checksum(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to checksum replay definition", exception);
        }
    }

    private static String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String joinLongs(List<Long> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static String joinStrings(List<String> values, String fallback) {
        if (values == null || values.isEmpty()) return fallback;
        return values.stream().filter(StringUtils::hasText).map(String::trim).distinct()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Long> parseLongs(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasText)
                .map(Long::valueOf).toList();
    }

    private static List<String> split(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }

    private static double round2(double value) { return Math.round(value * 100.0) / 100.0; }
    private static double round4(double value) { return Math.round(value * 10_000.0) / 10_000.0; }
}
