package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Enforces the invariant that the paper-trading ledger has at most one ACTIVE
 * run. Older builds could race during startup before the first insert became
 * visible to other scheduler threads; any empty duplicates are closed here
 * with an auditable frozen receipt before the database guard is installed.
 */
public class V20260817004__single_active_run_guard extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        DatabaseMetaData metadata = context.getConnection().getMetaData();
        if (!tableExists(metadata, "paper_trade_session")) return;

        List<ActiveRun> activeRuns = loadActiveRuns(context);
        Long survivorId = chooseSurvivor(activeRuns);
        for (ActiveRun run : activeRuns) {
            if (survivorId != null && survivorId.equals(run.id())) continue;
            closeDuplicate(context, run.id());
        }

        try (Statement statement = context.getConnection().createStatement()) {
            if (!columnExists(metadata, "paper_trade_session", "active_guard")) {
                statement.execute("""
                        ALTER TABLE paper_trade_session
                        ADD COLUMN active_guard INTEGER GENERATED ALWAYS AS
                            (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END)
                        """);
            }
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uk_paper_trade_single_active
                    ON paper_trade_session(active_guard)
                    """);
        }
    }

    private static List<ActiveRun> loadActiveRuns(Context context) throws Exception {
        boolean callsAvailable = tableExists(context.getConnection().getMetaData(), "paper_trade_model_call");
        String sql = callsAvailable
                ? """
                  SELECT s.id, s.total_bets, COUNT(c.id) AS model_calls
                  FROM paper_trade_session s
                  LEFT JOIN paper_trade_model_call c ON c.session_id = s.id
                  WHERE s.status = 'ACTIVE'
                  GROUP BY s.id, s.total_bets
                  ORDER BY s.id DESC
                  """
                : "SELECT id, total_bets, 0 AS model_calls FROM paper_trade_session WHERE status = 'ACTIVE' ORDER BY id DESC";
        List<ActiveRun> runs = new ArrayList<>();
        try (Statement statement = context.getConnection().createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                runs.add(new ActiveRun(rows.getLong("id"), rows.getLong("total_bets"), rows.getLong("model_calls")));
            }
        }
        return runs;
    }

    private static Long chooseSurvivor(List<ActiveRun> runs) {
        if (runs.size() <= 1) return runs.isEmpty() ? null : runs.get(0).id();
        return runs.stream()
                .filter(run -> run.totalBets() > 0 || run.modelCalls() > 0)
                .max(Comparator.comparingLong(ActiveRun::evidenceCount).thenComparingLong(ActiveRun::id))
                .map(ActiveRun::id)
                .orElse(null);
    }

    private static void closeDuplicate(Context context, long sessionId) throws Exception {
        LocalDateTime closedAt = LocalDateTime.now();
        String frozen = "summaryVersion=1|sessionId=" + sessionId
                + "|status=CLOSED|migration=single-active-run-guard|reason=CONCURRENT_STARTUP_DUPLICATE";
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(frozen.getBytes(StandardCharsets.UTF_8)));
        try (PreparedStatement statement = context.getConnection().prepareStatement("""
                UPDATE paper_trade_session
                SET status = 'CLOSED', closed_at = ?, updated_at = ?,
                    frozen_run_summary = COALESCE(frozen_run_summary, ?),
                    frozen_run_summary_checksum = COALESCE(frozen_run_summary_checksum, ?)
                WHERE id = ? AND status = 'ACTIVE'
                """)) {
            statement.setObject(1, closedAt);
            statement.setObject(2, closedAt);
            statement.setString(3, frozen);
            statement.setString(4, checksum);
            statement.setLong(5, sessionId);
            statement.executeUpdate();
        }
    }

    private static boolean tableExists(DatabaseMetaData metadata, String tableName) throws Exception {
        for (String candidate : candidates(tableName)) {
            try (ResultSet rows = metadata.getTables(null, null, candidate, new String[]{"TABLE"})) {
                if (rows.next()) return true;
            }
        }
        return false;
    }

    private static boolean columnExists(DatabaseMetaData metadata, String tableName, String columnName) throws Exception {
        for (String table : candidates(tableName)) {
            for (String column : candidates(columnName)) {
                try (ResultSet rows = metadata.getColumns(null, null, table, column)) {
                    if (rows.next()) return true;
                }
            }
        }
        return false;
    }

    private static String[] candidates(String value) {
        return new String[]{value, value.toUpperCase(Locale.ROOT), value.toLowerCase(Locale.ROOT)};
    }

    private record ActiveRun(long id, long totalBets, long modelCalls) {
        long evidenceCount() { return totalBets + modelCalls; }
    }
}
