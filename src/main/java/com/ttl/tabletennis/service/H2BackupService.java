package com.ttl.tabletennis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * #128 — Scheduled H2 online backup.
 *
 * <p>The application stores everything in a single H2 file-mode database
 * ({@code data/ttl.mv.db}, ~1.2GB at the time of this fix). Until now there
 * was no backup mechanism — a corrupt MVStore would lose every settled
 * bet, every CLV snapshot, every adaptive-learning sample, every
 * settlement-audit row in one event.
 *
 * <p>H2 supports {@code BACKUP TO 'file.zip'} as an online statement (no
 * write lock, just snapshots the pages). This service runs that statement
 * nightly, writes a timestamped zip into
 * {@code ${ttl.backup.directory:./data/backups}}, and prunes anything
 * beyond the configured retention count
 * ({@code ${ttl.backup.retainCount:7}}). Default cron 02:00 daily — runs
 * before the 03:15 odds-snapshot prune and the 03:30 generic retention
 * sweep, so the backup captures the largest stable state.
 *
 * <p>Failure to back up is logged at WARN but never throws — backup
 * failures should not bring down the application, and the next attempt
 * will retry the following day. Operators should alert on the
 * {@code ttl.backup.last_success_age_seconds} micrometer gauge (added in
 * a future pass) or simply on the presence of recent files in the backup
 * directory.
 */
@Service
public class H2BackupService {

    private static final Logger log = LoggerFactory.getLogger(H2BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final DataSource dataSource;

    @Value("${ttl.backup.enabled:true}")
    private boolean enabled;

    @Value("${ttl.backup.directory:./data/backups}")
    private String backupDirectoryRaw;

    @Value("${ttl.backup.filenamePrefix:ttl-backup-}")
    private String filenamePrefix;

    @Value("${ttl.backup.retainCount:7}")
    private int retainCount;

    public H2BackupService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Scheduled(cron = "${ttl.backup.cron:0 0 2 * * *}")
    public void scheduledBackup() {
        if (!enabled) {
            return;
        }
        runBackup(LocalDateTime.now());
    }

    /**
     * Run a single backup at the given timestamp. Returns the path of the
     * backup file (or null on failure). Public for ops endpoint use.
     */
    public Path runBackup(LocalDateTime now) {
        LocalDateTime safeNow = now == null ? LocalDateTime.now() : now;
        Path dir;
        try {
            dir = Path.of(backupDirectoryRaw).toAbsolutePath().normalize();
            Files.createDirectories(dir);
        } catch (IOException ex) {
            log.warn("[backup] failed to create backup directory {}: {}", backupDirectoryRaw, ex.toString());
            return null;
        }
        Path target = dir.resolve(filenamePrefix + safeNow.format(STAMP) + ".zip");
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // H2's BACKUP TO is a no-lock online operation that snapshots
            // the MVStore into a zip. Works on file-mode H2; in-memory or
            // non-H2 datasources will throw.
            String escaped = target.toString().replace("'", "''");
            stmt.execute("BACKUP TO '" + escaped + "'");
            log.info("[backup] wrote {} ({} bytes)", target, Files.size(target));
            pruneOldBackups(dir);
            return target;
        } catch (SQLException ex) {
            log.warn("[backup] BACKUP TO {} failed: {} (database may not be H2 file-mode)",
                    target, ex.getMessage());
            // Best-effort cleanup of a half-written file.
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            return null;
        } catch (IOException ex) {
            log.warn("[backup] could not stat backup file {}: {}", target, ex.getMessage());
            return target;
        }
    }

    /**
     * Keep only the newest {@code retainCount} backup files. Anything older
     * gets deleted. Run after each successful backup so the directory
     * doesn't fill up.
     */
    private void pruneOldBackups(Path dir) {
        int keep = Math.max(1, retainCount);
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(filenamePrefix))
                    .filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .forEach(backups::add);
        } catch (IOException ex) {
            log.warn("[backup] could not list backup directory {}: {}", dir, ex.getMessage());
            return;
        }
        if (backups.size() <= keep) {
            return;
        }
        // Sort newest-first by filename (filenames are ISO-ish so lexicographic == chronological).
        backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (Path stale : backups.subList(keep, backups.size())) {
            try {
                Files.deleteIfExists(stale);
                log.info("[backup] pruned old backup {}", stale.getFileName());
            } catch (IOException ex) {
                log.warn("[backup] could not delete old backup {}: {}", stale, ex.getMessage());
            }
        }
    }
}
