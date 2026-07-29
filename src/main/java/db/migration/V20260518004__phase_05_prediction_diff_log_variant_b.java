package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Phase 05 item 5 — Variant B sanity-check columns on prediction_diff_log.
 *
 * <p>The Python {@code /v1/blend} response now carries an optional
 * {@code sanity} block when the Variant B (with-market) blender is
 * loaded. We persist those values so §9.3 agreement
 * (mean |Δp_top| ≤ 0.04) is queryable across runs.
 */
public class V20260518004__phase_05_prediction_diff_log_variant_b extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        if (!columnExists(metaData, "prediction_diff_log", "v3_variant_b_model_version")) {
            execute(connection, "ALTER TABLE prediction_diff_log ADD COLUMN v3_variant_b_model_version VARCHAR(48) NULL");
        }
        if (!columnExists(metaData, "prediction_diff_log", "v3_variant_b_p1_probability")) {
            execute(connection, "ALTER TABLE prediction_diff_log ADD COLUMN v3_variant_b_p1_probability DECIMAL(7,6) NULL");
        }
        if (!columnExists(metaData, "prediction_diff_log", "variant_ab_abs_diff")) {
            execute(connection, "ALTER TABLE prediction_diff_log ADD COLUMN variant_ab_abs_diff DECIMAL(7,6) NULL");
        }
    }

    private boolean columnExists(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        if (lookupColumn(metaData, tableName.toUpperCase(Locale.ROOT), columnName)) {
            return true;
        }
        return lookupColumn(metaData, tableName, columnName);
    }

    private boolean lookupColumn(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                if (matchesName(rs.getString("COLUMN_NAME"), columnName)) {
                    return true;
                }
            }
        }
        return false;
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
