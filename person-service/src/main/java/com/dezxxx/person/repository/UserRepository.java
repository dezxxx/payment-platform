package com.dezxxx.person.repository;

import com.dezxxx.person.entity.UserEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID>, RevisionRepository<UserEntity, UUID, Long> {

    // lower(), not the derived ...IgnoreCase (that one is upper()): only lower() can use uk_users_email_lower
    @Query("select count(u) > 0 from UserEntity u where lower(u.email) = lower(:email)")
    boolean existsByEmail(String email);
}
