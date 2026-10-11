package com.dezxxx.individuals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DisplayName("person-service migrations")
class PersonSchemaMigrationIT {

    private static final String POSTGRES_IMAGE = "postgres:18.2";

    private static final String MIGRATIONS_DIR_PROPERTY = "person.migrations.dir";

    private static final String SCHEMA = "person";

    private static final String HISTORY_SCHEMA = "person_history";

    private static final int ISO_3166_1_ENTRIES = 249;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    private static Path migrations;

    @BeforeEach
    void emptyTheDatabase() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS " + HISTORY_SCHEMA + " CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    @BeforeAll
    static void locateTheScripts() {
        String configured = System.getProperty(MIGRATIONS_DIR_PROPERTY);
        assertThat(configured)
                .as("-D%s is set by the integrationTest task", MIGRATIONS_DIR_PROPERTY)
                .isNotBlank();

        migrations = Path.of(configured);
        assertThat(Files.isDirectory(migrations)).as("%s exists", migrations).isTrue();
    }

    @Test
    @DisplayName("IT-DB-001: given an empty database, when Flyway runs, then every migration applies and the schema is usable")
    void appliesEveryMigration() throws SQLException {
        // given
        Flyway flyway = flywayOverTheScripts();

        // when
        MigrateResult result = flyway.migrate();

        // then
        assertThat(result.success).isTrue();
        assertThat(result.migrations).isNotEmpty();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("005");

        try (Connection connection = connect()) {

            assertThat(tableExists(connection, "users")).isTrue();
            assertThat(tableExists(connection, "individuals")).isTrue();
            assertThat(tableExists(connection, "addresses")).isTrue();
            assertThat(tableExists(connection, "countries")).isTrue();

            assertThat(rowCount(connection, "countries")).isEqualTo(ISO_3166_1_ENTRIES);
        }
    }

    @Test
    @DisplayName("IT-DB-001: given the migrations already applied, when Flyway runs again, then it is a no-op")
    void isRepeatable() {
        // given
        Flyway flyway = flywayOverTheScripts();
        flyway.migrate();

        // when
        MigrateResult second = flyway.migrate();

        // then
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Flyway flywayOverTheScripts() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrations)
                .load();
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, SCHEMA, table, null)) {
            return tables.next();
        }
    }

    private static int rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + SCHEMA + "." + table)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
