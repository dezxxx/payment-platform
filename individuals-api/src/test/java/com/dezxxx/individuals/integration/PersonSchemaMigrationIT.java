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

/**
 * IT-DB-001: person-service's migrations, applied to a real PostgreSQL.
 *
 * <p>The scripts belong to person-service, which ships no code in module 1 and
 * therefore has nothing to run them. They still have to be proven: a migration
 * that only ever ran on the developer's machine is a deployment that fails on
 * the first real environment. Flyway is driven here directly, against the same
 * PostgreSQL image docker-compose uses.
 *
 * <p>Gradle hands over where the scripts live - see {@code integrationTest} in
 * {@code individuals-api/build.gradle.kts}. A test that guessed a relative path
 * to a sibling module would break the moment anything moved.
 */
@Testcontainers
@DisplayName("person-service migrations")
class PersonSchemaMigrationIT {

    /** Kept in step with {@code POSTGRES_IMAGE} in .env by hand - see CONTEXT §10. */
    private static final String POSTGRES_IMAGE = "postgres:18.2";

    private static final String MIGRATIONS_DIR_PROPERTY = "person.migrations.dir";

    /** V001 creates this schema; every table of the domain lives inside it. */
    private static final String SCHEMA = "person";

    /** Rows V002 seeds - the full ISO 3166-1 list, countries and territories. */
    private static final int ISO_3166_1_ENTRIES = 249;

    // Testcontainers 2.x moved this class to org.testcontainers.postgresql and
    // dropped the self-type parameter the 1.x class carried.
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    private static Path migrations;

    /**
     * One container serves both tests, so each has to start from nothing.
     * Without this the second test to run would find the schema already there,
     * migrate nothing, and pass while proving nothing.
     */
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

    /**
     * The table name is a constant of this class, never anything a caller
     * supplies, so string concatenation here cannot become an injection.
     */
    private static int rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + SCHEMA + "." + table)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
