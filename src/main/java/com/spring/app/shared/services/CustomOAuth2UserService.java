package com.spring.app.shared.services;

import java.util.Map;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  private final UserRepository userRepository;

  private static final String ATTR_EMAIL = "email";
  private static final String ATTR_NAME = "name";
  private static final String ATTR_PICTURE = "picture";

  @Override
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    log.info(">>> [CustomOAuth2UserService] Loading user info from provider...");
    try {
      OAuth2User oauth2User = super.loadUser(userRequest);
      if (oauth2User == null) {
        throw new IllegalStateException("Failed to load OAuth2 user");
      }

      String email = oauth2User.getAttribute(ATTR_EMAIL);
      if (email == null || email.isBlank()) {
        throw new IllegalStateException("OAuth2 provider did not return an email");
      }

      User user = findOrCreateUser(oauth2User, email);

      return new DefaultOAuth2User(
          user.getAuthorities(),
          buildUserAttributes(user),
          ATTR_EMAIL);

    } catch (Exception e) {
      throw new RuntimeException("Error loading OAuth2 user", e);
    }
  }

  private User findOrCreateUser(OAuth2User oauth2User, String email) {
    return userRepository.findByEmail(email)
        .orElseGet(() -> createNewUser(oauth2User, email));
  }

  private User createNewUser(OAuth2User oauth2User, String email) {
    String name = oauth2User.getAttribute(ATTR_NAME);
    String avatar = oauth2User.getAttribute(ATTR_PICTURE);

    log.info("Creating new user: email={}, name={}, avatar={}", email, name, avatar);

    User newUser = User.builder()
        .email(email)
        .password(RandomStringUtils.secureStrong().next(10))
        .status(EUserStatus.ACTIVE)
        .avatarUrl(avatar)
        .fullName(name)
        .build();

    return userRepository.save(newUser);
  }

  private Map<String, Object> buildUserAttributes(User user) {
    return Map.of(
        "id", user.getId(),
        "role", user.getRole(),
        ATTR_EMAIL, user.getEmail(),
        "avatarUrl", user.getAvatarUrl());
  }
}
