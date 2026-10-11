package com.dezxxx.person.repository;

import com.dezxxx.person.entity.CountryEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryRepository extends JpaRepository<CountryEntity, Integer> {

    Optional<CountryEntity> findByAlpha3(String alpha3);

    Optional<CountryEntity> findByAlpha2(String alpha2);
}
