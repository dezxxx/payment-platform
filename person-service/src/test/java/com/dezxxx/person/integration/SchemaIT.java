package com.dezxxx.person.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dezxxx.person.api.model.UserResponseDto;
import com.dezxxx.person.integration.support.IntegrationTest;
import com.dezxxx.person.repository.CountryRepository;
import com.dezxxx.person.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("Flyway schema and repositories")
class SchemaIT extends IntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Test
    @DisplayName("given the service started, when reading Flyway history, then V001 to V005 are applied and successful")
    void appliesEveryMigration() {
        // when
        List<String> versions = jdbc.queryForList(
                "select version from person.flyway_schema_history where success and version is not null order by installed_rank",
                String.class);

        // then
        assertThat(versions).containsExactly("001", "002", "003", "004", "005");
    }

    @Test
    @DisplayName("given the seed migration, when counting countries, then the full ISO 3166-1 list is there and found by both codes")
    void seedsCountries() {
        // when
        Integer countries = jdbc.queryForObject("select count(*) from person.countries", Integer.class);

        // then
        assertThat(countries).isEqualTo(249);
        assertThat(countryRepository.findByAlpha3("UKR")).get().extracting("alpha2").isEqualTo("UA");
        assertThat(countryRepository.findByAlpha2("UA")).get().extracting("alpha3").isEqualTo("UKR");
    }

    @Test
    @DisplayName("given an email stored, when inserting it again in another case straight into the table, then the lower(email) index refuses")
    void emailIndexIgnoresCase() {
        // given
        String email = freshEmail("index");
        createUser(email);

        // when / then
        assertThatThrownBy(() -> jdbc.update(
                "insert into person.users (id, email, created, updated, first_name, last_name)"
                        + " values (gen_random_uuid(), ?, now(), now(), 'A', 'B')", email.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_users_email_lower");
    }

    @Test
    @DisplayName("given a stored user, when searching by email in another case, then the repository finds it")
    void repositoryIgnoresCase() {
        // given
        String email = freshEmail("repository");
        UserResponseDto created = createUser(email);

        // when / then
        assertThat(userRepository.findByEmail(email.toUpperCase())).get().extracting("id").isEqualTo(created.getId());
        assertThat(userRepository.existsByEmail(email.toUpperCase())).isTrue();
        assertThat(userRepository.existsByEmail(freshEmail("nobody"))).isFalse();
    }
}
