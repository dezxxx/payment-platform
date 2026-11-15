package com.dezxxx.currency.repository;

import com.dezxxx.currency.entity.RateProviderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RateProviderRepository extends JpaRepository<RateProviderEntity, String> {

    @Query("select p from RateProviderEntity p where p.active = true order by p.priority")
    List<RateProviderEntity> findAllActiveByPriority();

    @Query("select p from RateProviderEntity p order by p.priority")
    List<RateProviderEntity> findAllByPriority();
}
