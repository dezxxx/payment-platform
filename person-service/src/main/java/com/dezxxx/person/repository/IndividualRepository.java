package com.dezxxx.person.repository;

import com.dezxxx.person.entity.IndividualEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

public interface IndividualRepository extends JpaRepository<IndividualEntity, UUID>, RevisionRepository<IndividualEntity, UUID, Long> {
}
