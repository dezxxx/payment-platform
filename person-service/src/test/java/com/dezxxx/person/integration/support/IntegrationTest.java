package com.dezxxx.person.integration.support;

import com.dezxxx.person.api.model.CreateIndividualRequestDto;
import com.dezxxx.person.api.model.CreateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.CreateIndividualRequestUserDto;
import com.dezxxx.person.api.model.IndividualResponseDto;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.export.enabled=false")
@AutoConfigureRestTestClient
public abstract class IntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:18.2";

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
    }

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected JdbcTemplate jdbc;

    protected static String freshEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.org";
    }

    protected static CreateIndividualRequestDto createRequest(String email) {
        return new CreateIndividualRequestDto()
                .passportNumber("123456789")
                .phoneNumber("+380441234567")
                .user(new CreateIndividualRequestUserDto()
                        .email(email)
                        .firstName("Ivan")
                        .lastName("Petrov")
                        .address(new CreateIndividualRequestUserAddressDto()
                                .countryAlpha3("UKR")
                                .countryAlpha2("UA")
                                .city("Kyiv")
                                .state("Kyiv")
                                .zipCode("01001")
                                .addressLine("1 Example St.")));
    }

    protected IndividualResponseDto createIndividual(String email) {
        return client.post().uri("/api/v1/individuals")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createRequest(email))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(IndividualResponseDto.class)
                .returnResult()
                .getResponseBody();
    }

    protected int rowsOf(String table, String column, Object value) {
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }
}
