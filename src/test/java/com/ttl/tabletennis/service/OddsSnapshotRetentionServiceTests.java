package com.ttl.tabletennis.service;

import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OddsSnapshotRetentionServiceTests {

    @Test
    void pruneExpiredSnapshotsUsesConfiguredRetentionWindow() {
        OddsSnapshotRepository repository = mock(OddsSnapshotRepository.class);
        OddsSnapshotRetentionService service = new OddsSnapshotRetentionService(repository);
        ReflectionTestUtils.setField(service, "retentionDays", 30);

        LocalDateTime now = LocalDateTime.of(2026, 4, 19, 13, 0);
        service.pruneExpiredSnapshots(now);

        verify(repository).deleteByObservedAtBefore(LocalDateTime.of(2026, 3, 20, 13, 0));
    }
}
