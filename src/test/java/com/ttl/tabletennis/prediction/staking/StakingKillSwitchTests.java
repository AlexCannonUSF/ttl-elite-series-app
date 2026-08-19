package com.ttl.tabletennis.prediction.staking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.SettlementPolicyAuditRecord;
import com.ttl.tabletennis.repository.SettlementPolicyAuditRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StakingKillSwitchTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-18T20:00:00Z"), ZoneId.of("UTC"));

    @TempDir
    Path tempDir;

    @Test
    void inactiveByDefault() {
        StakingKillSwitch killSwitch = new StakingKillSwitch(newCatalog(), new SimpleMeterRegistry(), CLOCK);
        assertFalse(killSwitch.isActive());
        assertEquals("", killSwitch.status().triggeredBy());
        assertTrue(killSwitch.ifActive().isEmpty());
    }

    @Test
    void activateFlipsAndAudits() {
        SettlementPolicyAuditRecordRepository repo = repo();
        StakingPolicyCatalog catalog = catalogWith(repo);
        clearInvocations(repo);
        StakingKillSwitch killSwitch = new StakingKillSwitch(catalog, new SimpleMeterRegistry(), CLOCK);

        StakingKillSwitch.Status status = killSwitch.activate("ops", "incident");

        assertTrue(killSwitch.isActive());
        assertEquals("ops", status.triggeredBy());
        assertEquals("incident", status.reason());

        ArgumentCaptor<SettlementPolicyAuditRecord> captor = ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(repo, atLeast(1)).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(r -> StakingPolicyCatalog.STATUS_KILL_SWITCH_ON.equals(r.getStatus())));
    }

    @Test
    void doubleActivateDoesNotDoubleAudit() {
        SettlementPolicyAuditRecordRepository repo = repo();
        StakingPolicyCatalog catalog = catalogWith(repo);
        StakingKillSwitch killSwitch = new StakingKillSwitch(catalog, new SimpleMeterRegistry(), CLOCK);

        killSwitch.activate("ops", "incident");
        clearInvocations(repo);
        killSwitch.activate("ops", "still incident");

        verify(repo, never()).save(any());
    }

    @Test
    void deactivateClearsAndAudits() {
        SettlementPolicyAuditRecordRepository repo = repo();
        StakingPolicyCatalog catalog = catalogWith(repo);
        StakingKillSwitch killSwitch = new StakingKillSwitch(catalog, new SimpleMeterRegistry(), CLOCK);
        killSwitch.activate("ops", "incident");
        clearInvocations(repo);

        StakingKillSwitch.Status status = killSwitch.deactivate("ops", "resolved");

        assertFalse(killSwitch.isActive());
        assertEquals("resolved", status.reason());
        ArgumentCaptor<SettlementPolicyAuditRecord> captor = ArgumentCaptor.forClass(SettlementPolicyAuditRecord.class);
        verify(repo, atLeast(1)).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(r -> StakingPolicyCatalog.STATUS_KILL_SWITCH_OFF.equals(r.getStatus())));
    }

    @Test
    void stakingPolicyShortCircuitsWhenKillSwitchActive() {
        SettlementPolicyAuditRecordRepository repo = repo();
        StakingPolicyCatalog catalog = catalogWith(repo);
        StakingKillSwitch killSwitch = new StakingKillSwitch(catalog, new SimpleMeterRegistry(), CLOCK);
        killSwitch.activate("ops", "drill");

        StakingPolicy policy = new StakingPolicy(catalog, killSwitch);
        StakingRequest request = new StakingRequest(
                "evt-1", 10L, 20L, 10L,
                0.7, 1.9, 0.06,
                10.0,
                null,
                List.of(),
                List.of()
        );

        StakingDecision decision = policy.decide(request);
        assertEquals(StakingDecision.Outcome.NO_BET, decision.outcome());
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_KILL_SWITCH_ACTIVE));
    }

    private SettlementPolicyAuditRecordRepository repo() {
        SettlementPolicyAuditRecordRepository repo = mock(SettlementPolicyAuditRecordRepository.class);
        when(repo.save(any(SettlementPolicyAuditRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    private StakingPolicyCatalog newCatalog() {
        return new StakingPolicyCatalog(
                tempDir.resolve("missing.yaml").toString(), repo(), new SimpleMeterRegistry(), new ObjectMapper(), CLOCK);
    }

    private StakingPolicyCatalog catalogWith(SettlementPolicyAuditRecordRepository repo) {
        return new StakingPolicyCatalog(
                tempDir.resolve("missing-" + System.nanoTime() + ".yaml").toString(),
                repo,
                new SimpleMeterRegistry(),
                new ObjectMapper(),
                CLOCK);
    }
}
