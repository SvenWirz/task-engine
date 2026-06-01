package io.github.svenwirz;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Stellt für die PostgreSQL-Integrationstests eine Datenquelle bereit.
 *
 * <p><b>Default (CI / normale Dev-Maschine):</b> ein per Testcontainers gestartetes
 * PostgreSQL. <b>Optional:</b> ein bereits laufendes PostgreSQL über die System-Property
 * {@code it.postgres.baseurl} (z. B. {@code jdbc:postgresql://localhost:5599/postgres}).
 * Im externen Modus wird pro Testklasse eine eigene Datenbank angelegt, damit die
 * Kontexte sich nicht gegenseitig die Tasks aus einer geteilten DB greifen.
 *
 * <p>Der externe Modus existiert, weil manche Docker-Desktop-Versionen vom Java-Docker-
 * Client nicht angesprochen werden können; die Testcontainers-Variante bleibt der
 * Standard und der eigentliche Lieferumfang.
 */
final class PostgresSupport {

    private static final String BASE_URL = System.getProperty("it.postgres.baseurl");
    private static final String USER = System.getProperty("it.postgres.user", "postgres");
    private static final String PASS = System.getProperty("it.postgres.pass", "postgres");

    private PostgresSupport() {
    }

    /**
     * Ob die PG-Integrationstests laufen können: entweder ist eine externe DB konfiguriert
     * oder ein nutzbares Docker vorhanden. Sonst werden die Tests übersprungen (statt zu
     * scheitern), z. B. auf Maschinen ohne lauffähiges Testcontainers-Docker.
     */
    static boolean available() {
        if (BASE_URL != null) {
            return true;
        }
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("resource") // Container muss laufen bleiben; Ryuk räumt am JVM-Ende auf.
    static void register(DynamicPropertyRegistry registry, String database) {
        if (BASE_URL != null) {
            String url = createDatabase(database);
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> USER);
            registry.add("spring.datasource.password", () -> PASS);
        } else {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:18-alpine").asCompatibleSubstituteFor("postgres"));
            container.start(); // Ryuk räumt den Container beim JVM-Ende auf.
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }

    private static String createDatabase(String database) {
        try (Connection con = DriverManager.getConnection(BASE_URL, USER, PASS);
             Statement st = con.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("Konnte Test-Datenbank '" + database + "' nicht anlegen", e);
        }
        int lastSlash = BASE_URL.lastIndexOf('/');
        return BASE_URL.substring(0, lastSlash + 1) + database;
    }
}
