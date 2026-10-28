package com.spring.app.shared.services;

import java.util.Locale;
import java.util.UUID;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import com.spring.app.configs.CacheConfig;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * User / refresh-token lookups for the auth flows.
 *
 * <p>
 * JPA entities are deliberately NOT cached. A {@code User} or
 * {@code RefreshToken} read back from Redis is detached, comes back without
 * its {@code @JsonIgnore}d password, and goes stale the moment a bulk JPQL
 * update (e.g. {@code revokeAllByUser}) runs; saving such a copy resurrects old
 * state. Every read here therefore goes to the database. The only cache this
 * class touches is {@link CacheConfig#USER_PROFILE} (an immutable
 * {@code UserResponseDto} per user id), which it evicts when a user changes.
 * </p>
 *
 * <p>
 * The method names are kept so existing callers compile unchanged.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class AuthCacheService {

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final CacheManager cacheManager;

  /**
   * Canonical form of an e-mail address for lookups and any e-mail-derived
   * key: trimmed and lower-cased with {@link Locale#ROOT}. MySQL compares
   * {@code users.email} case-insensitively, while Redis keys and H2 do not.
   */
  public static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  /** Always a fresh, managed entity from the database. */
  public User getUserByEmail(String email) {
    return userRepository.findByEmail(normalizeEmail(email))
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
  }

  /**
   * Call after changing a user. Nothing entity-shaped is cached; this evicts
   * the user's cached profile DTO so the next read reflects the change.
   */
  public User updateCachedUser(User user) {
    if (user != null && user.getId() != null) {
      evictUserProfile(user.getId());
    }
    return user;
  }

  /** Evicts the cached profile DTO of the user with this e-mail, if any. */
  public void evictCachedUser(String email) {
    userRepository.findByEmail(normalizeEmail(email))
        .map(User::getId)
        .ifPresent(this::evictUserProfile);
  }

  /** Always read from the database, so a revoked token is seen as revoked. */
  public RefreshToken getRefreshToken(String token) {
    return refreshTokenRepository.findByToken(token)
        .orElseThrow(() -> new BadRequestException("Invalid refresh token"));
  }

  /**
   * No-op: refresh tokens are no longer cached. Kept so existing callers
   * compile; never log the token value.
   */
  public void evictCachedToken(String token) {
    // Intentionally empty.
  }

  private void evictUserProfile(UUID userId) {
    Cache cache = cacheManager.getCache(CacheConfig.USER_PROFILE);
    if (cache != null) {
      cache.evict(userId);
    }
  }
}
