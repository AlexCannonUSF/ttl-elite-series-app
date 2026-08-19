package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.SettlementDiffLog;
import com.ttl.tabletennis.dto.OpsSettlementDiffRowDto;
import com.ttl.tabletennis.dto.OpsSettlementDiffSummaryDto;
import com.ttl.tabletennis.dto.OpsSettlementDiffsDto;
import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

@Service
public class OpsSettlementDiffService {

    static final String FOCUS_ALL = "ALL";
    static final String FOCUS_CONTRADICTION = "CONTRADICTION";
    static final String FOCUS_AMBIGUITY = "AMBIGUITY";
    static final String FOCUS_DISAGREEMENT = "DISAGREEMENT";

    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 100;
    private static final Set<String> AMBIGUITY_REASONS = Set.of(
            "MANUAL_REVIEW_AWAITING",
            "VOIDED_AMBIGUOUS_UNRESOLVED",
            SettlementDiffLogService.SHADOW_SKIPPED_NO_EVIDENCE
    );

    private final SettlementDiffLogRepository settlementDiffLogRepository;

    public OpsSettlementDiffService(SettlementDiffLogRepository settlementDiffLogRepository) {
        this.settlementDiffLogRepository = settlementDiffLogRepository;
    }

    @Transactional(readOnly = true)
    public OpsSettlementDiffsDto snapshot(String focus, Integer page, Integer size) {
        Instant generatedAt = Instant.now();
        long totalRows = settlementDiffLogRepository.count();
        long agreeRows = settlementDiffLogRepository.countByDiffKind(SettlementDiffLog.DIFF_KIND_AGREE);
        long contradictionRows = settlementDiffLogRepository.countByDiffKind(SettlementDiffLog.DIFF_KIND_CONTRADICTION);
        long outcomeDiffRows = settlementDiffLogRepository.countByDiffKind(SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF);
        long disagreementRows = Math.max(0L, totalRows - agreeRows);
        String normalizedFocus = normalizeFocus(focus);
        int normalizedPage = page == null || page < 0 ? 0 : page;
        int normalizedSize = normalizeSize(size);

        OpsSettlementDiffSummaryDto summary = new OpsSettlementDiffSummaryDto(
                totalRows,
                agreeRows,
                disagreementRows,
                contradictionRows,
                outcomeDiffRows
        );

        Page<SettlementDiffLog> rowPage = findPage(normalizedFocus, normalizedPage, normalizedSize);

        var rows = rowPage.getContent().stream()
                .map(this::toRowDto)
                .toList();
        return new OpsSettlementDiffsDto(
                generatedAt,
                normalizedFocus,
                normalizedPage,
                normalizedSize,
                rowPage.getTotalElements(),
                rowPage.getTotalPages(),
                rowPage.hasPrevious(),
                rowPage.hasNext(),
                summary,
                rows
        );
    }

    private Page<SettlementDiffLog> findPage(String focus, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return switch (focus) {
            case FOCUS_CONTRADICTION ->
                    settlementDiffLogRepository.findByDiffKindOrderByDecidedAtDescIdDesc(SettlementDiffLog.DIFF_KIND_CONTRADICTION, pageable);
            case FOCUS_AMBIGUITY ->
                    settlementDiffLogRepository.findByNewReasonInOrderByDecidedAtDescIdDesc(AMBIGUITY_REASONS, pageable);
            case FOCUS_DISAGREEMENT ->
                    settlementDiffLogRepository.findByDiffKindNotOrderByDecidedAtDescIdDesc(SettlementDiffLog.DIFF_KIND_AGREE, pageable);
            default ->
                    settlementDiffLogRepository.findAllByOrderByDecidedAtDescIdDesc(pageable);
        };
    }

    private String normalizeFocus(String focus) {
        if (focus == null || focus.isBlank()) {
            return FOCUS_ALL;
        }
        String candidate = focus.trim().toUpperCase();
        return switch (candidate) {
            case FOCUS_ALL, FOCUS_CONTRADICTION, FOCUS_AMBIGUITY, FOCUS_DISAGREEMENT -> candidate;
            default -> FOCUS_ALL;
        };
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private OpsSettlementDiffRowDto toRowDto(SettlementDiffLog row) {
        return new OpsSettlementDiffRowDto(
                row.getBetId(),
                row.getDiffKind(),
                row.getOldReason(),
                row.getNewReason(),
                row.getOldWinner(),
                row.getNewWinner(),
                row.getDecidedAt() == null ? null : row.getDecidedAt().atZone(ZoneId.systemDefault()).toInstant(),
                row.getCorrelationId()
        );
    }
}
