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

// IT-DB-001: person-service's Flyway migrations on a real PostgreSQL (same image
// as docker-compose). Gradle passes the scripts' location - see integrationTest
// in build.gradle.kts
@Testcontainers
@DisplayName("person-service migrations")
class PersonSchemaMigrationIT {

    // keep equal to POSTGRES_IMAGE in .env by hand
    private static final String POSTGRES_IMAGE = "postgres:18.2";

    private static final String MIGRATIONS_DIR_PROPERTY = "person.migrations.dir";

    // created by V001; all domain tables live here
    private static final String SCHEMA = "person";

    // rows seeded by V002: the full ISO 3166-1 list
    private static final int ISO_3166_1_ENTRIES = 249;

    // Testcontainers 2.x moved this class to org.testcontainers.postgresql and
    // dropped the self-type parameter the 1.x class carried.
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    private static Path migrations;

    // one container for both tests - start each from an empty database
    @BeforeEach
    void emptyTheDatabase() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
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
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("002");

        // and the schema is not merely recorded as migrated - it is there
        try (Connection connection = connect()) {

            assertThat(tableExists(connection, "users")).isTrue();
            assertThat(tableExists(connection, "individuals")).isTrue();
            assertThat(tableExists(connection, "addresses")).isTrue();
            assertThat(tableExists(connection, "countries")).isTrue();

            // V002 seeds every ISO 3166-1 entry; an empty table would mean the
            // script ran and inserted nothing, which Flyway reports as success
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

        // then - a redeploy of an unchanged service must not touch the schema
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

    // the table name is our constant, so concatenation is safe here
    private static int rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + SCHEMA + "." + table)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
