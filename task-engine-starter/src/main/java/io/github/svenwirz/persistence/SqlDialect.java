package io.github.svenwirz.persistence;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.jdbc.support.JdbcUtils;

/**
 * Kleine DB-Dialekt-Abstraktion. Die Referenzimplementierung zielt auf PostgreSQL
 * (JSONB, {@code FOR UPDATE SKIP LOCKED}, {@code NOTIFY}); für Tests auf H2 muss der
 * Payload als TEXT geschrieben werden, daher die Fallunterscheidung beim Cast.
 */
public final class SqlDialect {

    private final boolean postgres;

    public SqlDialect(DataSource dataSource) {
        this.postgres = detectPostgres(dataSource);
    }

    private static boolean detectPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("postgre");
        } catch (SQLException e) {
            // Im Zweifel kein PG-spezifisches SQL erzeugen.
            return false;
        }
    }

    public boolean isPostgres() {
        return postgres;
    }

    /** Platzhalter für den Payload-Bind: auf PG nach {@code jsonb} casten. */
    public String payloadPlaceholder() {
        return postgres ? "?::jsonb" : "?";
    }

    /** {@code FOR UPDATE SKIP LOCKED} nur auf PG; sonst einfaches {@code FOR UPDATE}. */
    public String skipLocked() {
        return postgres ? " FOR UPDATE SKIP LOCKED" : " FOR UPDATE";
    }

    static String quietProduct(Connection c) {
        return JdbcUtils.commonDatabaseName(safeProduct(c));
    }

    private static String safeProduct(Connection c) {
        try {
            return c.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            return "";
        }
    }
}
