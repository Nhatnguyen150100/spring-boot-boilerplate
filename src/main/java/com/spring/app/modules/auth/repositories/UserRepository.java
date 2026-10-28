package com.spring.app.modules.auth.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.spring.app.modules.auth.entities.User;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
  /** Any row, DELETED included - the same scope as the uk_users_email constraint. */
  boolean existsByEmail(String email);

  Optional<User> findByEmail(String email);
}
