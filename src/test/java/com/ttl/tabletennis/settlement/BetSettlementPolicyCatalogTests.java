package com.ttl.tabletennis.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.SettlementPolicyAuditRecord;
import com.ttl.tabletennis.repository.SettlementPolicyAuditRecordRepository;
import com.ttl.tabletennis.scrape.SourceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BetSettlementPolicyCatalogTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-19T20:00:00Z"), ZoneId.of("UTC"));

    @TempDir
    Path tempDir;

    @Test
    void loadsYamlPolicyAndAuditsStartup() throws Exception {
        Path policyPath = tempDir.resolve("bet_settlement_policy.yaml");
        Files.writeString(policyPath, policyYaml(0.82, 0.42, 3, 7, 90, false, 300, List.of("HR_TGT", "SOFASCORE")));
        SettlementPolicyAuditRecordRepository auditRepository = auditRepository();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        BetSettlementPolicyCatalog catalog = new BetSettlementPolicyCatalog(
                policyPath.toString(),
                auditRepository,
                meterRegistry,
                new ObjectMapper(),
                CLOCK
        );

        SettlementPolicy policy = catalog.currentPolicy();
        assertEquals(0.82, policy.settlement().minConfidenceToAutoSettle(), 1.0e-9);
        assertEquals(0.42, policy.settlement().contradictionBlockSeverity(), 1.0e-9);
        assertEquals(3, policy.settlement().requireSources());
        assertEquals(7, policy.staleLiveRecovery().enterAfterMinutesDark());
        assertEquals(90, policy.staleLiveRecovery().officialWindowMinutes());
        assertEquals(List.of(SourceId.HR_TGT, SourceId.SOFASCORE), policy.staleLiveRecovery().escalationOrder());
        assertFalse(policy.heuristic().allowed());
        assertEquals(300, policy.heuristic().afterDarkMinutes());

        var captor = org.mockito.ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(auditRepository).save(captor.capture());
        SettlementPolicyAuditRecord record = captor.getValue();
        assertEquals(BetSettlementPolicyCatalog.POLICY_NAME, record.getPolicyName());
        assertEquals(BetSettlementPolicyCatalog.STATUS_LOADED, record.getStatus());
        assertEquals("startup", record.getTriggeredBy());
        assertTrue(record.getPayloadJson().contains("minConfidenceToAutoSettle"));
        assertEquals(1.0, meterRegistry.get("ttl.score_truth.policy.reloads")
                .tag("policy", BetSettlementPolicyCatalog.POLICY_NAME)
                .tag("status", BetSettlementPolicyCatalog.STATUS_LOADED)
                .counter()
                .count());
    }

    @Test
    void hotReloadAppliesChangedYamlAndAuditsDiff() throws Exception {
        Path policyPath = tempDir.resolve("policy.yaml");
        Files.writeString(policyPath, policyYaml(0.85, 0.5, 2, 10, 180, true, 240, List.of("HR_TGT", "SOFASCORE")));
        SettlementPolicyAuditRecordRepository auditRepository = auditRepository();
        BetSettlementPolicyCatalog catalog = new BetSettlementPolicyCatalog(
                policyPath.toString(),
                auditRepository,
                new SimpleMeterRegistry(),
                new ObjectMapper(),
                CLOCK
        );
        String beforeChecksum = catalog.snapshot().checksum();
        clearInvocations(auditRepository);

        Files.writeString(policyPath, policyYaml(0.91, 0.5, 4, 10, 210, true, 240, List.of("BETSAPI", "AISCORE")));

        assertTrue(catalog.reloadIfChanged("operator:alice"));
        assertNotEquals(beforeChecksum, catalog.snapshot().checksum());
        assertEquals(0.91, catalog.currentPolicy().settlement().minConfidenceToAutoSettle(), 1.0e-9);
        assertEquals(4, catalog.currentPolicy().settlement().requireSources());
        assertEquals(210, catalog.currentPolicy().staleLiveRecovery().officialWindowMinutes());
        assertEquals(List.of(SourceId.BETSAPI, SourceId.AISCORE), catalog.currentPolicy().staleLiveRecovery().escalationOrder());

        var captor = org.mockito.ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(auditRepository).save(captor.capture());
        SettlementPolicyAuditRecord record = captor.getValue();
        assertEquals(BetSettlementPolicyCatalog.STATUS_RELOADED, record.getStatus());
        assertEquals("operator:alice", record.getTriggeredBy());
        assertTrue(record.getPayloadJson().contains("settlement.minConfidenceToAutoSettle"));
        assertTrue(record.getPayloadJson().contains("staleLiveRecovery.officialWindowMinutes"));
    }

    @Test
    void invalidReloadKeepsLastGoodPolicyAndAuditsFailure() throws Exception {
        Path policyPath = tempDir.resolve("policy.yaml");
        Files.writeString(policyPath, policyYaml(0.84, 0.5, 2, 10, 180, true, 240, List.of("HR_TGT")));
        SettlementPolicyAuditRecordRepository auditRepository = auditRepository();
        BetSettlementPolicyCatalog catalog = new BetSettlementPolicyCatalog(
                policyPath.toString(),
                auditRepository,
                new SimpleMeterRegistry(),
                new ObjectMapper(),
                CLOCK
        );
        String beforeChecksum = catalog.snapshot().checksum();
        clearInvocations(auditRepository);

        Files.writeString(policyPath, policyYaml(1.40, 0.5, 2, 10, 180, true, 240, List.of("HR_TGT")));

        assertTrue(catalog.reloadIfChanged("scheduled"));
        assertEquals(beforeChecksum, catalog.snapshot().checksum());
        assertEquals(0.84, catalog.currentPolicy().settlement().minConfidenceToAutoSettle(), 1.0e-9);

        var captor = org.mockito.ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(auditRepository).save(captor.capture());
        SettlementPolicyAuditRecord record = captor.getValue();
        assertEquals(BetSettlementPolicyCatalog.STATUS_RELOAD_FAILED, record.getStatus());
        assertTrue(record.getErrorMessage().contains("minConfidenceToAutoSettle"));
    }

    @Test
    void missingPolicyFileDefaultsAndDoesNotReloadUntilFileAppears() throws Exception {
        Path policyPath = tempDir.resolve("missing.yaml");
        SettlementPolicyAuditRecordRepository auditRepository = auditRepository();
        BetSettlementPolicyCatalog catalog = new BetSettlementPolicyCatalog(
                policyPath.toString(),
                auditRepository,
                new SimpleMeterRegistry(),
                new ObjectMapper(),
                CLOCK
        );

        assertFalse(catalog.snapshot().fileBacked());
        assertEquals(SettlementPolicy.defaults(), catalog.currentPolicy());
        assertFalse(catalog.reloadIfChanged("scheduled"));

        Files.writeString(policyPath, policyYaml(0.88, 0.5, 2, 10, 180, true, 240, List.of("HR_TGT")));

        assertTrue(catalog.reloadIfChanged("scheduled"));
        assertTrue(catalog.snapshot().fileBacked());
        assertEquals(0.88, catalog.currentPolicy().settlement().minConfidenceToAutoSettle(), 1.0e-9);
    }

    private SettlementPolicyAuditRecordRepository auditRepository() {
        SettlementPolicyAuditRecordRepository repository = mock(SettlementPolicyAuditRecordRepository.class);
        when(repository.save(org.mockito.ArgumentMatchers.any(SettlementPolicyAuditRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return repository;
    }

    private String policyYaml(double minConfidence,
                              double contradictionSeverity,
                              int requireSources,
                              int enterAfterMinutesDark,
                              int officialWindowMinutes,
                              boolean heuristicAllowed,
                              int heuristicAfterDarkMinutes,
                              List<String> escalationOrder) {
        String sources = escalationOrder.stream()
                .map(source -> "      - " + source)
                .reduce("", (left, right) -> left + right + "\n");
        return """
                schema_version: 1
                settlement_policy:
                  ambiguity:
                    max_allowed_without_tiebreaker: 0.70
                  settlement:
                    min_confidence_to_auto_settle: %.2f
                    contradiction_block_severity: %.2f
                    require_sources: %d
                  stale_live_recovery:
                    enter_after_minutes_dark: %d
                    official_window_minutes: %d
                    escalation_order:
                %s  heuristic:
                    allowed: %s
                    after_dark_minutes: %d
                """.formatted(
                minConfidence,
                contradictionSeverity,
                requireSources,
                enterAfterMinutesDark,
                officialWindowMinutes,
                sources,
                heuristicAllowed,
                heuristicAfterDarkMinutes
        );
    }
}
