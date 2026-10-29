package com.spring.app.shared.services;

import java.util.UUID;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import com.spring.app.configs.CacheConfig;
import com.spring.app.modules.auth.entities.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Evicts a user's cached profile after the user changes.
 *
 * <p>
 * JPA entities are deliberately NOT cached. A {@code User} read back from
 * Redis is detached, comes back without its {@code @JsonIgnore}d password, and
 * goes stale the moment a bulk JPQL update runs; saving such a copy resurrects
 * old state. User reads therefore always go to the database. The only cache this
 * class touches is {@link CacheConfig#USER_PROFILE} (an immutable
 * {@code UserResponseDto} per user id).
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthCacheService {

  private final CacheManager cacheManager;

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

  private void evictUserProfile(UUID userId) {
    try {
      Cache cache = cacheManager.getCache(CacheConfig.USER_PROFILE);
      if (cache != null) {
        cache.evict(userId);
      }
    } catch (RuntimeException e) {
      // The DB change is already committed; a stale profile expires with its TTL.
      log.warn("Could not evict cached profile for user {}: {}", userId, e.toString());
    }
  }
}
