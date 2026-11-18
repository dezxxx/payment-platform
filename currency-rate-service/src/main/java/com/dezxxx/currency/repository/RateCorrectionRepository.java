package com.dezxxx.currency.repository;

import com.dezxxx.currency.entity.RateCorrectionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RateCorrectionRepository extends JpaRepository<RateCorrectionEntity, Long> {

    @Query("select c from RateCorrectionEntity c where c.active = true")
    List<RateCorrectionEntity> findAllActive();
}
