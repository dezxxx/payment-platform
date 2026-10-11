package com.dezxxx.person.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserDto;
import com.dezxxx.person.entity.IndividualEntity;
import com.dezxxx.person.integration.support.IntegrationTest;
import com.dezxxx.person.repository.IndividualRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.history.Revision;
import org.springframework.data.history.RevisionMetadata.RevisionType;
import org.springframework.http.MediaType;

@DisplayName("Envers audit")
class AuditIT extends IntegrationTest {

    @Autowired
    private IndividualRepository individualRepository;

    @Test
    @DisplayName("given an individual created, patched and removed by compensation, when reading its history, then three revisions: insert, update, delete")
    void recordsRevisions() {
        // given
        IndividualResponseDto created = createIndividual(freshEmail("audit"));
        patch(created, new UpdateIndividualRequestDto().phoneNumber("+380441234568"));
        client.delete().uri("/api/v1/individuals/compensate-registration/{id}", created.getId()).exchange().expectStatus().isNoContent();

        // when
        List<Revision<Long, IndividualEntity>> revisions = individualRepository.findRevisions(created.getId()).getContent();

        // then
        assertThat(revisions).extracting(revision -> revision.getMetadata().getRevisionType())
                .containsExactly(RevisionType.INSERT, RevisionType.UPDATE, RevisionType.DELETE);
        assertThat(revisions.get(1).getEntity().getPhoneNumber()).isEqualTo("+380441234568");
        assertThat(revisions.get(2).getEntity().getPassportNumber()).isEqualTo(created.getPassportNumber());
    }

    @Test
    @DisplayName("given one PATCH of the individual, its user and the address, when reading the history tables, then one revision with the right fields flagged")
    void flagsModifiedFields() {
        // given
        IndividualResponseDto created = createIndividual(freshEmail("audit-flags"));

        // when
        patch(created, new UpdateIndividualRequestDto()
                .phoneNumber("+380441234568")
                .user(new UpdateIndividualRequestUserDto()
                        .lastName("Petrov-Senior")
                        .address(new UpdateIndividualRequestUserAddressDto().city("Lviv"))));

        // then
        Map<String, Object> individual = jdbc.queryForMap(
                "select revision, phone_number_mod, passport_number_mod from person_history.individuals_history"
                        + " where id = ? and revision_type = 1", created.getId());
        Map<String, Object> user = jdbc.queryForMap(
                "select revision, last_name_mod, first_name_mod from person_history.users_history"
                        + " where id = ? and revision_type = 1", created.getUser().getId());
        Map<String, Object> address = jdbc.queryForMap(
                "select revision, city_mod, state_mod from person_history.addresses_history"
                        + " where id = ? and revision_type = 1", created.getUser().getAddress().getId());
        assertThat(individual).containsEntry("phone_number_mod", true).containsEntry("passport_number_mod", false);
        assertThat(user).containsEntry("last_name_mod", true).containsEntry("first_name_mod", false);
        assertThat(address).containsEntry("city_mod", true).containsEntry("state_mod", false);
        assertThat(user.get("revision")).isEqualTo(individual.get("revision"));
        assertThat(address.get("revision")).isEqualTo(individual.get("revision"));
    }

    private void patch(IndividualResponseDto individual, UpdateIndividualRequestDto request) {
        client.patch().uri("/api/v1/individuals/{id}", individual.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isOk();
    }
}
