package com.spring.app.modules.auth.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
  Optional<RefreshToken> findByToken(String token);

  List<RefreshToken> findAllByUserAndIsRevokedFalse(User user);
}