package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.service.PaperTradingShadowService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionLifecycleServiceTests {

    @Test
    void concurrentStartupCreatesExactlyOneActiveSession() throws Exception {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        AtomicReference<PaperTradeSession> active = new AtomicReference<>();
        when(repo.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE))
                .thenAnswer(invocation -> Optional.ofNullable(active.get()));
        when(repo.save(any(PaperTradeSession.class))).thenAnswer(invocation -> {
            PaperTradeSession session = invocation.getArgument(0);
            active.set(session);
            return session;
        });

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);
        service.overrideDefaultStartingBankrollForTest(500.0);
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PaperTradeSession>> futures = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(ignored -> pool.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        return service.getOrCreateActiveSession();
                    }))
                    .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            PaperTradeSession canonical = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<PaperTradeSession> future : futures) {
                assertSame(canonical, future.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        verify(repo, times(1)).save(any(PaperTradeSession.class));
        verify(shadow, times(1)).mirrorSession(any(PaperTradeSession.class));
    }

    @Test
    void getOrCreateActiveSession_returnsExistingActive() {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        PaperTradeSession existing = new PaperTradeSession();
        existing.setStatus(PaperTradeSession.STATUS_ACTIVE);
        when(repo.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE))
                .thenReturn(Optional.of(existing));

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);

        PaperTradeSession result = service.getOrCreateActiveSession();

        assertSame(existing, result, "found-active path returns the repo's instance untouched");
        verify(repo, never()).save(any());
        verify(shadow, never()).mirrorSession(any());
    }

    @Test
    void getOrCreateActiveSession_createsNewWhenNoneActive() {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        when(repo.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE))
                .thenReturn(Optional.empty());
        when(repo.save(any(PaperTradeSession.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);
        service.overrideDefaultStartingBankrollForTest(500.0);

        PaperTradeSession created = service.getOrCreateActiveSession();

        assertNotNull(created);
        assertEquals(PaperTradeSession.STATUS_ACTIVE, created.getStatus());
        assertEquals(500.0, created.getStartingBankroll(), 1e-9, "default bankroll honoured");
        verify(shadow).mirrorSession(created);
    }

    @Test
    void createSession_clampsBankrollToFloor() {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        when(repo.save(any(PaperTradeSession.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);

        PaperTradeSession created = service.createSession(50.0, "Custom Label");

        // 50.0 below the 100.0 floor → clamped to 100.0
        assertEquals(100.0, created.getStartingBankroll(), 1e-9);
        assertEquals(100.0, created.getCurrentBankroll(), 1e-9);
        assertEquals(100.0, created.getPeakBankroll(), 1e-9);
        assertEquals("Custom Label", created.getLabel());
    }

    @Test
    void createSession_clampsBankrollToCeiling() {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        when(repo.save(any(PaperTradeSession.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);

        PaperTradeSession created = service.createSession(5_000_000.0, null);

        // 5M above the 1M ceiling → clamped
        assertEquals(1_000_000.0, created.getStartingBankroll(), 1e-9);
        assertTrue(created.getLabel().startsWith("Paper Session "), "null label gets default prefix");
    }

    @Test
    void createSession_initialisesAdaptiveDefaults() {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        when(repo.save(any(PaperTradeSession.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);

        PaperTradeSession created = service.createSession(500.0, "Sample");

        assertEquals(0, created.getAdaptiveSampleSize());
        assertEquals(0.0, created.getAdaptiveEdgeShift(), 1e-9);
        assertEquals(0.0, created.getAdaptiveSelectionScoreShift(), 1e-9);
        assertEquals(1.0, created.getAdaptiveStakeMultiplier(), 1e-9, "stake multiplier defaults to 1.0");
        assertEquals(0.0, created.getAdaptiveCalibrationError(), 1e-9);
        assertEquals(0.0, created.getAdaptiveRoiSignal(), 1e-9);
    }

    @Test
    void saveSession_persistsAndMirrors() {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        PaperTradeSession session = new PaperTradeSession();
        PaperTradeSession persisted = new PaperTradeSession();
        when(repo.save(session)).thenReturn(persisted);

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);
        PaperTradeSession result = service.saveSession(session);

        assertSame(persisted, result, "returns the repo's persisted instance, not the input");
        verify(shadow).mirrorSession(persisted);
    }

    @Test
    void saveSessions_persistsAndMirrorsBulk() {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        List<PaperTradeSession> input = List.of(new PaperTradeSession(), new PaperTradeSession());
        List<PaperTradeSession> saved = List.of(new PaperTradeSession(), new PaperTradeSession());
        when(repo.saveAll(input)).thenReturn(saved);

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);
        List<PaperTradeSession> result = service.saveSessions(input);

        assertEquals(saved, result);
        ArgumentCaptor<List<PaperTradeSession>> captor = ArgumentCaptor.forClass(List.class);
        verify(shadow).mirrorSessions(captor.capture());
        assertSame(saved, captor.getValue(), "shadow receives the persisted list, not the input");
    }

    @Test
    void applicationShutdownClosesTheActiveRun() {
        PaperTradeSessionRepository repo = mock(PaperTradeSessionRepository.class);
        PaperTradingShadowService shadow = mock(PaperTradingShadowService.class);
        PaperTradeSession active = new PaperTradeSession();
        active.setStatus(PaperTradeSession.STATUS_ACTIVE);
        when(repo.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE))
                .thenReturn(Optional.of(active));
        when(repo.save(active)).thenReturn(active);

        SessionLifecycleService service = new SessionLifecycleService(repo, shadow);
        service.closeActiveSessionOnShutdown();

        assertEquals(PaperTradeSession.STATUS_CLOSED, active.getStatus());
        assertNotNull(active.getClosedAt());
        assertNotNull(active.getFrozenRunSummary());
        assertNotNull(active.getFrozenRunSummaryChecksum());
        assertEquals(64, active.getFrozenRunSummaryChecksum().length());
        verify(repo).save(active);
        verify(shadow).mirrorSession(active);
    }
}
