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
 * #121 — Add {@code error_class} column to {@code scrape_error}.
 *
 * <p>Previously every scrape error went into a free-text {@code message}
 * column with no classification. The Session 65 watcher couldn't
 * distinguish "the GZIP fix #105 is regressing" from "tt-series.com is
 * just slow tonight" — both produced +N alerts in the same counter.
 *
 * <p>Adds a nullable enum-style string column populated at write time by
 * {@code TtSeriesScraper} via a regex classifier (gzip / timeout /
 * network / parse / other). Existing rows stay null.
 */
public class V20260525001__scrape_error_error_class extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();
        // Guard against running on a partial schema (FlywayMigrationTests
        // applies migrations against a synthetic empty DB where scrape_error
        // hasn't been created yet — production always has it).
        if (!tableExists(metaData, "scrape_error")) {
            return;
        }
        if (!columnExists(metaData, "scrape_error", "error_class")) {
            execute(connection, "ALTER TABLE scrape_error ADD COLUMN error_class VARCHAR(32) NULL");
        }
        if (!indexExists(connection, "idx_scrape_error_class")) {
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_scrape_error_class ON scrape_error(error_class)");
        }
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws SQLException {
        for (String name : new String[]{tableName, tableName.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = metaData.getTables(null, null, name, null)) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
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
                String actual = rs.getString("COLUMN_NAME");
                if (actual != null
                        && actual.trim().toLowerCase(Locale.ROOT).equals(columnName.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean indexExists(Connection connection, String indexName) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT 1 FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = '"
                             + indexName.toUpperCase(Locale.ROOT) + "'")) {
            return rs.next();
        } catch (SQLException ex) {
            return false;
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
