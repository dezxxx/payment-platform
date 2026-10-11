package com.dezxxx.person.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.api.model.UpdateUserRequestAddressDto;
import com.dezxxx.person.api.model.UpdateUserRequestDto;
import com.dezxxx.person.api.model.UserResponseDto;
import com.dezxxx.person.entity.UserEntity;
import com.dezxxx.person.integration.support.IntegrationTest;
import com.dezxxx.person.repository.UserRepository;
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
    private UserRepository userRepository;

    @Test
    @DisplayName("given a user created, patched and deleted, when reading its history, then three revisions: insert, update, delete")
    void recordsRevisions() {
        // given
        UserResponseDto created = createUser(freshEmail("audit"));
        patch(created, new UpdateUserRequestDto().lastName("Petrov-Senior"));
        client.delete().uri("/api/v1/users/{id}", created.getId()).exchange().expectStatus().isNoContent();

        // when
        List<Revision<Long, UserEntity>> revisions = userRepository.findRevisions(created.getId()).getContent();

        // then
        assertThat(revisions).extracting(revision -> revision.getMetadata().getRevisionType())
                .containsExactly(RevisionType.INSERT, RevisionType.UPDATE, RevisionType.DELETE);
        assertThat(revisions.get(1).getEntity().getLastName()).isEqualTo("Petrov-Senior");
        assertThat(revisions.get(2).getEntity().getEmail()).isEqualTo(created.getEmail());
    }

    @Test
    @DisplayName("given one PATCH of the user and its address, when reading the history tables, then one revision with the right fields flagged")
    void flagsModifiedFields() {
        // given
        UserResponseDto created = createUser(freshEmail("audit-flags"));

        // when
        patch(created, new UpdateUserRequestDto()
                .lastName("Petrov-Senior")
                .address(new UpdateUserRequestAddressDto().city("Lviv")));

        // then
        Map<String, Object> user = jdbc.queryForMap(
                "select revision, last_name_mod, first_name_mod from person_history.users_history"
                        + " where id = ? and revision_type = 1", created.getId());
        Map<String, Object> address = jdbc.queryForMap(
                "select revision, city_mod, state_mod from person_history.addresses_history"
                        + " where id = ? and revision_type = 1", created.getAddress().getId());
        assertThat(user).containsEntry("last_name_mod", true).containsEntry("first_name_mod", false);
        assertThat(address).containsEntry("city_mod", true).containsEntry("state_mod", false);
        assertThat(address.get("revision")).isEqualTo(user.get("revision"));
    }

    private void patch(UserResponseDto user, UpdateUserRequestDto request) {
        client.patch().uri("/api/v1/users/{id}", user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isOk();
    }
}
