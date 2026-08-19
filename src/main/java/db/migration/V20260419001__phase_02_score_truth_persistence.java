package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260419001__phase_02_score_truth_persistence extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        ensureSettlementEvidenceTable(connection, metaData);
        ensureContradictionTable(connection, metaData);
        ensureSettlementAuditTable(connection, metaData);
    }

    private void ensureSettlementEvidenceTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "settlement_evidence")) {
            execute(connection, """
                    CREATE TABLE settlement_evidence (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        bet_id BIGINT NOT NULL,
                        tracked_event_id VARCHAR(128) NULL,
                        bundle_as_of TIMESTAMP NOT NULL,
                        coverage_state VARCHAR(16) NOT NULL,
                        ambiguity_score DOUBLE NOT NULL,
                        confidence DOUBLE NOT NULL,
                        payload_json LONGTEXT NOT NULL,
                        correlation_id VARCHAR(64) NULL,
                        CONSTRAINT uq_settlement_evidence_bet_asof UNIQUE (bet_id, bundle_as_of)
                    )
                    """);
        }

        ensureIndex(connection, metaData, "settlement_evidence", "idx_settlement_evidence_bet_asof", false, "bet_id, bundle_as_of");
        ensureIndex(connection, metaData, "settlement_evidence", "idx_settlement_evidence_event_asof", false, "tracked_event_id, bundle_as_of");
    }

    private void ensureContradictionTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "contradiction")) {
            execute(connection, """
                    CREATE TABLE contradiction (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        evidence_id BIGINT NULL,
                        bet_id BIGINT NOT NULL,
                        observed_at TIMESTAMP NOT NULL,
                        kind VARCHAR(32) NOT NULL,
                        severity DOUBLE NOT NULL,
                        resolved BOOLEAN NOT NULL DEFAULT FALSE,
                        resolution_note TEXT NULL,
                        payload_json LONGTEXT NOT NULL,
                        correlation_id VARCHAR(64) NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "contradiction", "idx_contradiction_bet_observed", false, "bet_id, observed_at");
        ensureIndex(connection, metaData, "contradiction", "idx_contradiction_evidence", false, "evidence_id");
        ensureIndex(connection, metaData, "contradiction", "idx_contradiction_resolved", false, "resolved, observed_at");
    }

    private void ensureSettlementAuditTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "settlement_audit")) {
            execute(connection, """
                    CREATE TABLE settlement_audit (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        bet_id BIGINT NOT NULL,
                        tracked_event_id VARCHAR(128) NULL,
                        decision VARCHAR(24) NOT NULL,
                        reason VARCHAR(64) NOT NULL,
                        confidence DOUBLE NULL,
                        evidence_id BIGINT NULL,
                        decided_at TIMESTAMP NOT NULL,
                        payload_json LONGTEXT NOT NULL,
                        correlation_id VARCHAR(64) NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "settlement_audit", "idx_settlement_audit_bet_decided", false, "bet_id, decided_at");
        ensureIndex(connection, metaData, "settlement_audit", "idx_settlement_audit_event_decided", false, "tracked_event_id, decided_at");
        ensureIndex(connection, metaData, "settlement_audit", "idx_settlement_audit_decision", false, "decision, decided_at");
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws SQLException {
        try (ResultSet rs = metaData.getTables(null, null, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                if (matchesName(rs.getString("TABLE_NAME"), tableName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void ensureIndex(Connection connection,
                             DatabaseMetaData metaData,
                             String tableName,
                             String indexName,
                             boolean unique,
                             String columns) throws SQLException {
        if (indexExists(metaData, tableName, indexName)) {
            return;
        }
        String uniqueness = unique ? "UNIQUE " : "";
        execute(connection, "CREATE " + uniqueness + "INDEX " + indexName + " ON " + tableName + " (" + columns + ")");
    }

    private boolean indexExists(DatabaseMetaData metaData, String tableName, String indexName) throws SQLException {
        String lookupTable = tableName == null ? null : tableName.toUpperCase(Locale.ROOT);
        try (ResultSet rs = metaData.getIndexInfo(null, null, lookupTable, false, false)) {
            while (rs.next()) {
                if (matchesName(rs.getString("TABLE_NAME"), tableName)
                        && matchesName(rs.getString("INDEX_NAME"), indexName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean matchesName(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return actual.trim().toLowerCase(Locale.ROOT).equals(expected.trim().toLowerCase(Locale.ROOT));
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
