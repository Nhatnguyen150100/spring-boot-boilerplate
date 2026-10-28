package com.spring.app.modules.auth.repositories;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByToken(String token);

  /**
   * Bulk JPQL needs a transaction; AuthServiceImpl no longer opens one around
   * a whole request, so the method brings its own (or joins the caller's).
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE RefreshToken t SET t.isRevoked = true WHERE t.user = :user AND t.isRevoked = false")
  void revokeAllByUser(@Param("user") User user);

  /**
   * Revokes one token only if it is still active, in a single conditional
   * UPDATE. Returns 1 for the caller that revoked it and 0 for everyone else,
   * so two concurrent refreshes with the same token cannot both succeed.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE RefreshToken t SET t.isRevoked = true WHERE t.token = :token AND t.isRevoked = false")
  int revokeIfActive(@Param("token") String token);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM RefreshToken t WHERE t.expiryDate < :now OR t.isRevoked = true")
  void deleteExpiredOrRevokedTokens(@Param("now") Instant now);
}
