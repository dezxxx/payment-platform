package com.dezxxx.currency.repository;

import com.dezxxx.currency.entity.CurrencyEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CurrencyRepository extends JpaRepository<CurrencyEntity, Long> {

    @Query("select c from CurrencyEntity c where c.active = true")
    List<CurrencyEntity> findAllActive();

    @Query("select c from CurrencyEntity c order by c.code")
    List<CurrencyEntity> findAllByCode();
}
