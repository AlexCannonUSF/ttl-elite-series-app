package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.ScrapeRun;
import com.ttl.tabletennis.repository.ScrapeRunRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScrapeRunOrphanCleanupTests {

    @Test
    void noopWhenNoOrphans() {
        ScrapeRunRepository repo = mock(ScrapeRunRepository.class);
        when(repo.findByStatus("RUNNING")).thenReturn(List.of());
        ScrapeRunOrphanCleanup cleanup = new ScrapeRunOrphanCleanup(repo);

        ScrapeRunOrphanCleanup.CleanupResult result = cleanup.finalizeOrphans();

        assertEquals(0, result.finalized());
        verify(repo, never()).save(any());
    }

    @Test
    void marksRunningRowsAsFailedAndStampsReason() {
        ScrapeRun orphan = run("RUNNING", null);
        ScrapeRunRepository repo = mock(ScrapeRunRepository.class);
        when(repo.findByStatus("RUNNING")).thenReturn(List.of(orphan));
        ScrapeRunOrphanCleanup cleanup = new ScrapeRunOrphanCleanup(repo);

        ScrapeRunOrphanCleanup.CleanupResult result = cleanup.finalizeOrphans();

        assertEquals(1, result.finalized());
        ArgumentCaptor<ScrapeRun> captor = ArgumentCaptor.forClass(ScrapeRun.class);
        verify(repo).save(captor.capture());
        ScrapeRun saved = captor.getValue();
        assertAll(
                () -> assertEquals("FAILED", saved.getStatus()),
                () -> assertNotNull(saved.getFinishedAt()),
                () -> assertTrue(saved.getErrorMessage() != null
                        && saved.getErrorMessage().contains("JVM_RESTART"))
        );
    }

    @Test
    void preservesPriorErrorMessageWhenAppending() {
        ScrapeRun orphan = run("RUNNING", "fetch retry budget exhausted");
        ScrapeRunRepository repo = mock(ScrapeRunRepository.class);
        when(repo.findByStatus("RUNNING")).thenReturn(List.of(orphan));
        ScrapeRunOrphanCleanup cleanup = new ScrapeRunOrphanCleanup(repo);

        cleanup.finalizeOrphans();

        ArgumentCaptor<ScrapeRun> captor = ArgumentCaptor.forClass(ScrapeRun.class);
        verify(repo).save(captor.capture());
        String msg = captor.getValue().getErrorMessage();
        assertTrue(msg.startsWith("fetch retry budget exhausted"), msg);
        assertTrue(msg.contains("JVM_RESTART"), msg);
    }

    @Test
    void idempotentAcrossMultipleCalls() {
        ScrapeRunRepository repo = mock(ScrapeRunRepository.class);
        // First call returns one orphan; second call returns empty (the row
        // is now FAILED so it doesn't match findByStatus("RUNNING")).
        when(repo.findByStatus("RUNNING"))
                .thenReturn(List.of(run("RUNNING", null)))
                .thenReturn(List.of());
        ScrapeRunOrphanCleanup cleanup = new ScrapeRunOrphanCleanup(repo);

        assertEquals(1, cleanup.finalizeOrphans().finalized());
        assertEquals(0, cleanup.finalizeOrphans().finalized());
    }

    private static ScrapeRun run(String status, String errorMessage) {
        ScrapeRun row = new ScrapeRun();
        row.setRunNumber(42);
        row.setMode("PAGE_RANGE");
        row.setStatus(status);
        row.setStartedAt(LocalDateTime.now().minusHours(2));
        row.setErrorMessage(errorMessage);
        return row;
    }

    // Mockito.any() forwarder so the static-import collision in the
    // verify(repo, never()).save(any()) line stays terse without pulling in
    // ArgumentMatchers as a static import elsewhere.
    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
