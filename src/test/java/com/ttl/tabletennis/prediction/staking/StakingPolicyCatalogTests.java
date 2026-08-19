package com.ttl.tabletennis.prediction.staking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.SettlementPolicyAuditRecord;
import com.ttl.tabletennis.repository.SettlementPolicyAuditRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StakingPolicyCatalogTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-18T20:00:00Z"), ZoneId.of("UTC"));

    @TempDir
    Path tempDir;

    @Test
    void loadsYamlOverridesDefaultsAndAuditsStartup() throws Exception {
        Path policyPath = tempDir.resolve("policy.yaml");
        Files.writeString(policyPath, policyYaml(0.30, 1.8, 2.5, 1.2, 6.0, 0.05, 40, -0.06, 0.40));

        SettlementPolicyAuditRecordRepository repo = auditRepo();
        StakingPolicyCatalog catalog = new StakingPolicyCatalog(
                policyPath.toString(), repo, new SimpleMeterRegistry(), new ObjectMapper(), CLOCK);

        StakingPolicyConfig config = catalog.currentConfig();
        assertEquals(0.30, config.fractionalKelly(), 1e-9);
        assertEquals(1.8, config.kellyCapUnits(), 1e-9);
        assertEquals(2.5, config.perEventCapUnits(), 1e-9);
        assertEquals(1.2, config.perPlayerDailyCapUnits(), 1e-9);
        assertEquals(6.0, config.maxOpenExposureUnits(), 1e-9);
        assertEquals(0.05, config.minimumEdge(), 1e-9);
        assertEquals(40, config.drawdownLookbackBets());
        assertEquals(-0.06, config.drawdownTriggerRoi(), 1e-9);
        assertEquals(0.40, config.drawdownFactor(), 1e-9);
        assertTrue(catalog.snapshot().fileBacked());

        ArgumentCaptor<SettlementPolicyAuditRecord> captor = ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(repo, atLeastOnce()).save(captor.capture());
        SettlementPolicyAuditRecord record = captor.getValue();
        assertEquals(StakingPolicyCatalog.POLICY_NAME, record.getPolicyName());
        assertEquals("startup", record.getTriggeredBy());
        assertEquals(StakingPolicyCatalog.STATUS_LOADED, record.getStatus());
    }

    @Test
    void missingPolicyFallsBackToDefaultsAndAudits() {
        Path policyPath = tempDir.resolve("missing-policy.yaml");
        SettlementPolicyAuditRecordRepository repo = auditRepo();
        StakingPolicyCatalog catalog = new StakingPolicyCatalog(
                policyPath.toString(), repo, new SimpleMeterRegistry(), new ObjectMapper(), CLOCK);

        StakingPolicyConfig config = catalog.currentConfig();
        StakingPolicyConfig defaults = StakingPolicyConfig.defaults();
        assertEquals(defaults.fractionalKelly(), config.fractionalKelly(), 1e-9);
        assertFalse(catalog.snapshot().fileBacked());

        ArgumentCaptor<SettlementPolicyAuditRecord> captor = ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(repo, atLeastOnce()).save(captor.capture());
        assertEquals(StakingPolicyCatalog.STATUS_DEFAULTED_MISSING, captor.getValue().getStatus());
    }

    @Test
    void reloadIfChangedDoesNotAuditWhenFileUnchanged() throws Exception {
        Path policyPath = tempDir.resolve("policy.yaml");
        Files.writeString(policyPath, policyYaml(0.25, 1.5, 2.0, 1.5, 5.0, 0.025, 50, -0.08, 0.50));
        SettlementPolicyAuditRecordRepository repo = auditRepo();
        StakingPolicyCatalog catalog = new StakingPolicyCatalog(
                policyPath.toString(), repo, new SimpleMeterRegistry(), new ObjectMapper(), CLOCK);
        clearInvocations(repo);

        assertFalse(catalog.reloadIfChanged("scheduled"));
        verify(repo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void reloadIfChangedAuditsAfterFileChange() throws Exception {
        Path policyPath = tempDir.resolve("policy.yaml");
        Files.writeString(policyPath, policyYaml(0.25, 1.5, 2.0, 1.5, 5.0, 0.025, 50, -0.08, 0.50));
        SettlementPolicyAuditRecordRepository repo = auditRepo();
        StakingPolicyCatalog catalog = new StakingPolicyCatalog(
                policyPath.toString(), repo, new SimpleMeterRegistry(), new ObjectMapper(), CLOCK);
        String firstChecksum = catalog.snapshot().checksum();
        clearInvocations(repo);

        Files.writeString(policyPath, policyYaml(0.15, 1.2, 1.5, 0.9, 4.0, 0.04, 30, -0.05, 0.30));

        assertTrue(catalog.reloadIfChanged("scheduled"));
        assertNotEquals(firstChecksum, catalog.snapshot().checksum());
        assertEquals(0.15, catalog.currentConfig().fractionalKelly(), 1e-9);

        ArgumentCaptor<SettlementPolicyAuditRecord> captor = ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(repo, atLeast(1)).save(captor.capture());
        SettlementPolicyAuditRecord row = captor.getValue();
        assertEquals(StakingPolicyCatalog.STATUS_RELOADED, row.getStatus());
        assertEquals("scheduled", row.getTriggeredBy());
    }

    @Test
    void reloadNowAlwaysAuditsEvenWithoutChange() throws Exception {
        Path policyPath = tempDir.resolve("policy.yaml");
        Files.writeString(policyPath, policyYaml(0.25, 1.5, 2.0, 1.5, 5.0, 0.025, 50, -0.08, 0.50));
        SettlementPolicyAuditRecordRepository repo = auditRepo();
        StakingPolicyCatalog catalog = new StakingPolicyCatalog(
                policyPath.toString(), repo, new SimpleMeterRegistry(), new ObjectMapper(), CLOCK);
        clearInvocations(repo);

        StakingPolicyCatalog.Snapshot snapshot = catalog.reloadNow("ops");
        assertEquals(StakingPolicyConfig.defaults().fractionalKelly(), snapshot.config().fractionalKelly(), 1e-9);
        verify(repo, atLeast(1)).save(any());
    }

    @Test
    void killSwitchEventsAreAudited() {
        SettlementPolicyAuditRecordRepository repo = auditRepo();
        StakingPolicyCatalog catalog = new StakingPolicyCatalog(
                tempDir.resolve("missing.yaml").toString(), repo, new SimpleMeterRegistry(), new ObjectMapper(), CLOCK);
        clearInvocations(repo);

        catalog.recordKillSwitchEvent(true, "ops", "incident");
        catalog.recordKillSwitchEvent(false, "ops", "resolved");

        ArgumentCaptor<SettlementPolicyAuditRecord> captor = ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(repo, atLeast(2)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r -> StakingPolicyCatalog.STATUS_KILL_SWITCH_ON.equals(r.getStatus())));
        assertTrue(captor.getAllValues().stream().anyMatch(r -> StakingPolicyCatalog.STATUS_KILL_SWITCH_OFF.equals(r.getStatus())));
    }

    private SettlementPolicyAuditRecordRepository auditRepo() {
        SettlementPolicyAuditRecordRepository repo = mock(SettlementPolicyAuditRecordRepository.class);
        when(repo.save(any(SettlementPolicyAuditRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    private static String policyYaml(double fractionalKelly,
                                     double kellyCap,
                                     double perMatchCap,
                                     double perPlayerCap,
                                     double maxOpen,
                                     double edge,
                                     int lookback,
                                     double trigger,
                                     double factor) {
        return """
                schema_version: 1
                prediction:
                  minimumEdge:
                    default: %s
                  staking:
                    fractionalKelly: %s
                    kellyCapUnits: %s
                    perMatchCapUnits: %s
                    perPlayerDailyCapUnits: %s
                    maxOpenExposureUnits: %s
                    minStakeUnits: 0.1
                    sessionDrawdownStop:
                      lookbackBets: %d
                      triggerRoi: %s
                      factor: %s
                """.formatted(edge, fractionalKelly, kellyCap, perMatchCap, perPlayerCap, maxOpen, lookback, trigger, factor);
    }
}
