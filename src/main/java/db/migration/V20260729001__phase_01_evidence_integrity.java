package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 1 evidence-integrity persistence.
 *
 * <p>Legacy fingerprints remain null because their original semantic input
 * cannot be reconstructed safely. Legacy manual-review rows are migrated
 * into the explicit review lifecycle so an upgrade never hides unresolved
 * operator work.
 */
public class V20260729001__phase_01_evidence_integrity extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        addColumnIfMissing(connection, metaData, "settlement_evidence",
                "evidence_fingerprint", "VARCHAR(64) NULL");
        addColumnIfMissing(connection, metaData, "settlement_audit",
                "decision_fingerprint", "VARCHAR(64) NULL");
        addColumnIfMissing(connection, metaData, "settlement_audit",
                "review_status", "VARCHAR(24) NULL");
        addColumnIfMissing(connection, metaData, "settlement_audit",
                "review_decision_id", "BIGINT NULL");
        addColumnIfMissing(connection, metaData, "settlement_diff_log",
                "diff_fingerprint", "VARCHAR(64) NULL");

        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "settlement_confidence", "DOUBLE NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "settlement_evidence_id", "BIGINT NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "settlement_evidence_fingerprint", "VARCHAR(64) NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "settlement_evidence_source_count", "INTEGER NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "settlement_coverage_state", "VARCHAR(16) NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "settlement_ambiguity_score", "DOUBLE NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "settlement_observed_at", "TIMESTAMP NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "closing_decimal_odds", "DOUBLE NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "closing_observed_at", "TIMESTAMP NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "closing_source", "VARCHAR(16) NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_bet",
                "closing_market_state", "VARCHAR(24) NULL");

        addColumnIfMissing(connection, metaData, "paper_trade_learning_sample",
                "closing_source", "VARCHAR(16) NULL");
        addColumnIfMissing(connection, metaData, "paper_trade_learning_sample",
                "closing_market_state", "VARCHAR(24) NULL");

        backfillReviewLifecycle(connection, metaData);

        ensureIndex(connection, metaData, "settlement_evidence",
                "uq_settlement_evidence_fingerprint", true, "evidence_fingerprint");
        ensureIndex(connection, metaData, "settlement_audit",
                "uq_settlement_audit_fingerprint", true, "decision_fingerprint");
        ensureIndex(connection, metaData, "settlement_audit",
                "idx_settlement_audit_review", false, "decision, review_status, decided_at");
        ensureIndex(connection, metaData, "settlement_diff_log",
                "uq_settlement_diff_fingerprint", true, "diff_fingerprint");
        ensureIndex(connection, metaData, "paper_trade_bet",
                "idx_paper_bet_settlement_evidence", false, "settlement_evidence_id");
    }

    private void addColumnIfMissing(Connection connection,
                                    DatabaseMetaData metaData,
                                    String tableName,
                                    String columnName,
                                    String definition) throws SQLException {
        if (!tableExists(metaData, tableName) || columnExists(metaData, tableName, columnName)) {
            return;
        }
        execute(connection, "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws SQLException {
        try (ResultSet rs = metaData.getTables(null, null, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                if (matchesName(rs.getString("TABLE_NAME"), tableName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnExists(DatabaseMetaData metaData,
                                 String tableName,
                                 String columnName) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, null, null)) {
            while (rs.next()) {
                if (matchesName(rs.getString("TABLE_NAME"), tableName)
                        && matchesName(rs.getString("COLUMN_NAME"), columnName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureIndex(Connection connection,
                             DatabaseMetaData metaData,
                             String tableName,
                             String indexName,
                             boolean unique,
                             String columns) throws SQLException {
        if (!tableExists(metaData, tableName) || indexExists(metaData, tableName, indexName)) {
            return;
        }
        execute(connection, "CREATE " + (unique ? "UNIQUE " : "")
                + "INDEX " + indexName + " ON " + tableName + " (" + columns + ")");
    }

    private boolean indexExists(DatabaseMetaData metaData,
                                String tableName,
                                String indexName) throws SQLException {
        String lookupTable = tableName == null ? null : tableName.toUpperCase(Locale.ROOT);
        try (ResultSet rs = metaData.getIndexInfo(null, null, lookupTable, false, false)) {
            while (rs.next()) {
                if (matchesName(rs.getString("TABLE_NAME"), tableName)
                        && matchesName(rs.getString("INDEX_NAME"), indexName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesName(String actual, String expected) {
        return actual != null
                && expected != null
                && actual.trim().toLowerCase(Locale.ROOT)
                .equals(expected.trim().toLowerCase(Locale.ROOT));
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void backfillReviewLifecycle(Connection connection,
                                         DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "settlement_audit")
                || !columnExists(metaData, "settlement_audit", "review_status")
                || !columnExists(metaData, "settlement_audit", "review_decision_id")) {
            return;
        }
        execute(connection, """
                UPDATE settlement_audit
                SET review_status = 'OPEN'
                WHERE decision = 'MANUAL_REVIEW'
                  AND review_status IS NULL
                """);

        List<LegacyReviewAction> legacyActions = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet actions = query.executeQuery("""
                     SELECT id, decision, payload_json
                     FROM settlement_audit
                     WHERE decision IN (
                         'MANUAL_REVIEW_ACCEPTED',
                         'MANUAL_REVIEW_REJECTED',
                         'MANUAL_REVIEW_COMMENT'
                     )
                     """)) {
            while (actions.next()) {
                Long reviewDecisionId = extractReviewDecisionId(actions.getString("payload_json"));
                if (reviewDecisionId == null) {
                    continue;
                }
                legacyActions.add(new LegacyReviewAction(
                        actions.getLong("id"),
                        actions.getString("decision"),
                        reviewDecisionId
                ));
            }
        }
        for (LegacyReviewAction action : legacyActions) {
            updateReviewActionLink(connection, action.actionId(), action.reviewDecisionId());
            if ("MANUAL_REVIEW_ACCEPTED".equals(action.decision())) {
                updateReviewStatus(connection, action.reviewDecisionId(), "ACCEPTED");
            } else if ("MANUAL_REVIEW_REJECTED".equals(action.decision())) {
                updateReviewStatus(connection, action.reviewDecisionId(), "REJECTED");
            }
        }
        backfillSupersededAndResolvedReviews(connection);
    }

    private Long extractReviewDecisionId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"reviewDecisionId\"\\s*:\\s*(\\d+)")
                .matcher(payloadJson);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void updateReviewActionLink(Connection connection,
                                        long actionId,
                                        long reviewDecisionId) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                UPDATE settlement_audit
                SET review_decision_id = ?
                WHERE id = ?
                  AND review_decision_id IS NULL
                """)) {
            statement.setLong(1, reviewDecisionId);
            statement.setLong(2, actionId);
            statement.executeUpdate();
        }
    }

    private void updateReviewStatus(Connection connection,
                                    long reviewDecisionId,
                                    String reviewStatus) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                UPDATE settlement_audit
                SET review_status = ?
                WHERE id = ?
                  AND decision = 'MANUAL_REVIEW'
                """)) {
            statement.setString(1, reviewStatus);
            statement.setLong(2, reviewDecisionId);
            statement.executeUpdate();
        }
    }

    private void backfillSupersededAndResolvedReviews(Connection connection) throws SQLException {
        List<LegacyOpenReview> openReviews = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet reviews = query.executeQuery("""
                     SELECT id, bet_id, decided_at
                     FROM settlement_audit
                     WHERE decision = 'MANUAL_REVIEW'
                       AND review_status = 'OPEN'
                     """)) {
            while (reviews.next()) {
                openReviews.add(new LegacyOpenReview(
                        reviews.getLong("id"),
                        reviews.getLong("bet_id"),
                        reviews.getTimestamp("decided_at")
                ));
            }
        }

        for (LegacyOpenReview review : openReviews) {
            String newerDecision = findLatestNewerLifecycleDecision(connection, review);
            if ("SETTLE".equals(newerDecision) || "VOID".equals(newerDecision)) {
                updateReviewStatus(connection, review.reviewId(), "RESOLVED");
            } else if ("MANUAL_REVIEW".equals(newerDecision)) {
                updateReviewStatus(connection, review.reviewId(), "SUPERSEDED");
            }
        }
    }

    private String findLatestNewerLifecycleDecision(Connection connection,
                                                    LegacyOpenReview review) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                SELECT decision
                FROM settlement_audit
                WHERE bet_id = ?
                  AND decision IN ('MANUAL_REVIEW', 'SETTLE', 'VOID')
                  AND (
                    decided_at > ?
                    OR (decided_at = ? AND id > ?)
                  )
                ORDER BY decided_at DESC, id DESC
                """)) {
            statement.setLong(1, review.betId());
            statement.setTimestamp(2, review.decidedAt());
            statement.setTimestamp(3, review.decidedAt());
            statement.setLong(4, review.reviewId());
            try (ResultSet newer = statement.executeQuery()) {
                return newer.next() ? newer.getString("decision") : null;
            }
        }
    }

    private record LegacyReviewAction(long actionId, String decision, long reviewDecisionId) {
    }

    private record LegacyOpenReview(long reviewId,
                                    long betId,
                                    java.sql.Timestamp decidedAt) {
    }
}
