package com.spring.app.shared.services;

import com.spring.app.configs.CacheConfig;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthCacheService {

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;

  @Cacheable(value = CacheConfig.USERS_CACHE, key = "#email", unless = "#result == null")
  public User getUserByEmail(String email) {
    return userRepository.findByEmail(email)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
  }

  @CachePut(value = CacheConfig.USERS_CACHE, key = "#user.email")
  public User updateCachedUser(User user) {
    return user;
  }

  @CacheEvict(value = CacheConfig.USERS_CACHE, key = "#email")
  public void evictCachedUser(String email) {
    log.debug("Evicting cached user: {}", email);
  }

  @Cacheable(value = CacheConfig.TOKENS_CACHE, key = "#token", unless = "#result == null")
  public RefreshToken getRefreshToken(String token) {
    return refreshTokenRepository.findByToken(token)
        .orElseThrow(() -> new BadRequestException("Invalid refresh token"));
  }

  @CacheEvict(value = CacheConfig.TOKENS_CACHE, key = "#token")
  public void evictCachedToken(String token) {
    log.debug("Evicting cached token: {}", token);
  }
}
