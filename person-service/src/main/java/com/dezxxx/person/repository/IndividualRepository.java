package com.dezxxx.person.repository;

import com.dezxxx.person.entity.IndividualEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndividualRepository extends JpaRepository<IndividualEntity, UUID> {
}
