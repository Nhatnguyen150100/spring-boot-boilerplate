package com.spring.app.modules.auth.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
  boolean existsByEmailAndStatus(String email, EUserStatus status);

  Optional<User> findByEmailAndStatus(String email, EUserStatus status);

  Optional<User> findByEmail(String email);

  @NonNull
  Optional<User> findByIdAndStatus(UUID id, EUserStatus status);

}
