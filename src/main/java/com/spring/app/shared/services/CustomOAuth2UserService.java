package com.spring.app.shared.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.utils.EmailNormalizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps a provider sign-in to a local {@link User}.
 *
 * <p>
 * Google is registered with the {@code openid} scope, so Spring Security
 * routes its logins through the OIDC user service, not {@link #loadUser}.
 * {@link #loadOidcUser} is that entry point (wired in SecurityConfig);
 * {@link #loadUser} covers plain OAuth2 providers. Both go through
 * {@link #resolveUser}, and both principals carry the app user's id in the
 * {@code id} attribute (read by ApplicationAuditAware).
 * </p>
 *
 * <p>
 * Every failure is an {@link OAuth2AuthenticationException}, so it reaches
 * OAuth2AuthenticationFailureHandler (redirect with an error code) instead of
 * escaping as a 500.
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  static final String ATTR_EMAIL = "email";
  static final String ATTR_EMAIL_VERIFIED = "email_verified";
  static final String ATTR_NAME = "name";
  static final String ATTR_PICTURE = "picture";

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final ApplicationProperties applicationProperties;
  private final OidcUserService oidcDelegate = new OidcUserService();

  @Override
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    OAuth2User oauth2User = super.loadUser(userRequest);
    return toPrincipal(resolveUser(oauth2User.getAttributes()));
  }

  public OidcUser loadOidcUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
    OidcUser oidcUser = oidcDelegate.loadUser(userRequest);
    return toOidcPrincipal(resolveUser(oidcUser.getAttributes()), oidcUser);
  }

  User resolveUser(Map<String, Object> attributes) {
    try {
      String email = EmailNormalizer.normalize(asString(attributes.get(ATTR_EMAIL)));
      if (email == null || email.isEmpty()) {
        throw failure("missing_email", "OAuth2 provider did not return an email");
      }
      // Without this, whoever controls an IdP account that merely CLAIMS the
      // address could sign in as (or pre-create) the local account owning it.
      if (!isEmailVerified(attributes.get(ATTR_EMAIL_VERIFIED))) {
        throw failure("email_not_verified", "OAuth2 provider has not verified this email");
      }
      if (!isEmailDomainAllowed(email)) {
        throw failure("email_domain_not_allowed", "OAuth2 email domain is not allowed");
      }

      User user = userRepository.findByEmail(email).orElseGet(() -> createNewUser(attributes, email));
      // PENDING must finish OTP activation; INACTIVE and DELETED must not sign in.
      if (user.getStatus() != EUserStatus.ACTIVE) {
        throw failure("account_not_active", "Account is not active");
      }
      return user;
    } catch (OAuth2AuthenticationException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("OAuth2 user resolution failed", e);
      throw new OAuth2AuthenticationException(new OAuth2Error("server_error"), "OAuth2 login failed", e);
    }
  }

  OAuth2User toPrincipal(User user) {
    return new DefaultOAuth2User(user.getAuthorities(), principalAttributes(user), ATTR_EMAIL);
  }

  /**
   * Keeps the provider's ID token and adds the app attributes ({@code id},
   * {@code role}, ...) through the user-info claims. DefaultOidcUser lets
   * ID-token claims win on a clash, so {@code email} keeps the provider's
   * casing; consumers normalise it.
   */
  OidcUser toOidcPrincipal(User user, OidcUser oidcUser) {
    Map<String, Object> claims = new HashMap<>();
    if (oidcUser.getUserInfo() != null) {
      claims.putAll(oidcUser.getUserInfo().getClaims());
    }
    claims.putAll(principalAttributes(user));
    return new DefaultOidcUser(user.getAuthorities(), oidcUser.getIdToken(), new OidcUserInfo(claims), ATTR_EMAIL);
  }

  private static Map<String, Object> principalAttributes(User user) {
    // HashMap: the immutable map factories throw on a null value (e.g. no avatar).
    Map<String, Object> attributes = new HashMap<>();
    attributes.put(ATTR_EMAIL, user.getEmail());
    attributes.put("role", user.getRole().name());
    if (user.getId() != null) {
      attributes.put("id", user.getId().toString());
    }
    if (user.getAvatarUrl() != null) {
      attributes.put("avatarUrl", user.getAvatarUrl());
    }
    return attributes;
  }

  private User createNewUser(Map<String, Object> attributes, String email) {
    User newUser = User.builder()
        .email(email)
        // Random 32-byte ASCII password (BCrypt accepts at most 72 bytes): the
        // account signs in through the provider, or after a password reset.
        .password(passwordEncoder.encode(RandomStringUtils.secureStrong().nextAlphanumeric(32)))
        .status(EUserStatus.ACTIVE)
        .avatarUrl(asString(attributes.get(ATTR_PICTURE)))
        .fullName(asString(attributes.get(ATTR_NAME)))
        .build();
    User saved = userRepository.save(newUser);
    log.info("Created user {} from an OAuth2 login", saved.getId());
    return saved;
  }

  private static boolean isEmailVerified(Object claim) {
    return Boolean.TRUE.equals(claim) || (claim instanceof String value && Boolean.parseBoolean(value));
  }

  private boolean isEmailDomainAllowed(String email) {
    List<String> allowed = applicationProperties.getOauth2AllowedEmailDomains();
    if (allowed == null || allowed.isEmpty()) {
      return true; // No whitelist configured -> accept any domain.
    }
    int at = email.lastIndexOf('@');
    if (at < 0) {
      return false;
    }
    String domain = email.substring(at + 1);
    return allowed.stream()
        .map(d -> d.trim().toLowerCase())
        .filter(d -> !d.isEmpty())
        .anyMatch(domain::equals);
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }

  private static OAuth2AuthenticationException failure(String errorCode, String description) {
    return new OAuth2AuthenticationException(new OAuth2Error(errorCode, description, null), description);
  }
}
