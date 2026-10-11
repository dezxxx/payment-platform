package com.dezxxx.person.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserDto;
import com.dezxxx.person.integration.support.IntegrationTest;
import com.dezxxx.person.mapper.AddressMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@DisplayName("Transactional rollback of the aggregate")
class TransactionRollbackIT extends IntegrationTest {

    @MockitoSpyBean
    private AddressMapper addressMapper;

    @Test
    @DisplayName("given the address fails after the phone and the last name were changed, when patching, then 500 and the whole change is rolled back")
    void rollsBackPartialChange() {
        // given
        IndividualResponseDto created = createIndividual(freshEmail("rollback"));
        Long versionBefore = jdbc.queryForObject("select version from person.individuals where id = ?", Long.class, created.getId());
        doThrow(new IllegalStateException("address write failed"))
                .when(addressMapper).updateAddress(any(), any(), any());
        UpdateIndividualRequestDto patch = new UpdateIndividualRequestDto()
                .phoneNumber("+380441234568")
                .user(new UpdateIndividualRequestUserDto()
                        .lastName("Petrov-Senior")
                        .address(new UpdateIndividualRequestUserAddressDto().city("Lviv")));

        // when
        client.patch().uri("/api/v1/individuals/{id}", created.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(patch)
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.error").isEqualTo("INTERNAL_ERROR");

        // then
        assertThat(jdbc.queryForObject("select phone_number from person.individuals where id = ?", String.class, created.getId()))
                .isEqualTo("+380441234567");
        assertThat(jdbc.queryForObject("select last_name from person.users where id = ?", String.class, created.getUser().getId()))
                .isEqualTo("Petrov");
        assertThat(jdbc.queryForObject("select city from person.addresses where id = ?", String.class, created.getUser().getAddress().getId()))
                .isEqualTo("Kyiv");
        assertThat(jdbc.queryForObject("select version from person.individuals where id = ?", Long.class, created.getId()))
                .isEqualTo(versionBefore);
    }
}
