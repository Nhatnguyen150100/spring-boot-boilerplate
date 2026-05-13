package com.spring.app.modules.auth.repositories;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByToken(String token);

  List<RefreshToken> findAllByUserAndIsRevokedFalse(User user);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.user = :user AND t.revoked = false")
  void revokeAllByUser(@Param("user") User user);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM RefreshToken t WHERE t.expiryDate < :now OR t.revoked = true")
  void deleteExpiredOrRevokedTokens(@Param("now") Instant now);
}
