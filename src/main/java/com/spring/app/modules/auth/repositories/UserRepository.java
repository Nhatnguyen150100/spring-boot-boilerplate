package com.spring.app.modules.auth.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.spring.app.modules.auth.entities.User;

public interface UserRepository extends JpaRepository<User, UUID> {
  boolean existsByEmail(String email);

  Optional<User> findByEmail(String email);
}
