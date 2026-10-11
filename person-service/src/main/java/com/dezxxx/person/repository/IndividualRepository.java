package com.dezxxx.person.repository;

import com.dezxxx.person.entity.IndividualEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;

public interface IndividualRepository extends JpaRepository<IndividualEntity, UUID>, RevisionRepository<IndividualEntity, UUID, Long> {

    @Query("select i from IndividualEntity i where i.id = :id and i.status <> com.dezxxx.person.entity.IndividualStatus.ARCHIVED")
    Optional<IndividualEntity> findActiveById(UUID id);

    // lower(), not the derived ...IgnoreCase (that one is upper()): only lower() can use uk_users_email_lower
    @Query("select i from IndividualEntity i where lower(i.user.email) = lower(:email)"
            + " and i.status <> com.dezxxx.person.entity.IndividualStatus.ARCHIVED")
    Optional<IndividualEntity> findActiveByEmail(String email);
}
