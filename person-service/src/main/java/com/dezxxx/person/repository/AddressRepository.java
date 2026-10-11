package com.dezxxx.person.repository;

import com.dezxxx.person.entity.AddressEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<AddressEntity, UUID> {
}
