package com.spring.app.configs;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.AntPathMatcher;

import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
@EnableJpaAuditing(auditorAwareRef = "applicationAuditAware")
public class ApplicationConfig {

  private final UserRepository userRepository;

  private final ApplicationAuditAware applicationAuditAware;

  /**
   * A UserDetailsService that fetches users from the database by email.
   *
   * <p>
   * It does NOT check the account status: DaoAuthenticationProvider loads the
   * user before it compares the password, so rejecting PENDING/INACTIVE here
   * told an anonymous caller that the account exists. The status is checked
   * after the password in {@link #authenticationProvider()}, and on every
   * bearer request in {@code JwtFunctionUtil}. DELETED accounts are treated as
   * not found.
   * </p>
   *
   * @return A UserDetailsService that can be used in a DaoAuthenticationProvider.
   */
  @Bean
  UserDetailsService userDetailsService() {
    return email -> userRepository.findByEmail(email)
        .filter(user -> user.getStatus() != EUserStatus.DELETED)
        .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
  }

  /**
   * Provides an AntPathMatcher bean that is used for matching URL patterns
   * with Ant-style path patterns in Spring applications.
   * 
   * @return An AntPathMatcher instance for URL pattern matching.
   */

  @Bean
  AntPathMatcher antPathMatcher() {
    return new AntPathMatcher();
  }

  /**
   * Provides a PasswordEncoder bean that uses BCrypt hashing algorithm.
   * 
   * @return A PasswordEncoder instance with BCryptPasswordEncoder.
   */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Provides an AuthenticationProvider that uses the UserDetailsService and
   * PasswordEncoder to authenticate users.
   *
   * <p>
   * {@code hideUserNotFoundExceptions} keeps its default (true): an unknown or
   * DELETED e-mail fails with the same BadCredentialsException as a wrong
   * password. The account status is checked only after the password matched.
   * </p>
   *
   * @return An AuthenticationProvider that can be used to authenticate users.
   */
  @Bean
  AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService());
    authProvider.setPasswordEncoder(passwordEncoder());
    authProvider.setPostAuthenticationChecks(user -> {
      if (user instanceof User appUser && appUser.getStatus() != EUserStatus.ACTIVE) {
        throw new UserNotActiveException("Account is not active");
      }
    });
    return authProvider;
  }

  /**
   * Provides an AuditorAware bean that can be used by Spring Data JPA
   * to populate the created_by and updated_by fields of entities.
   * 
   * @return An AuditorAware that can be used to populate the created_by and
   *         updated_by fields of entities.
   */
  @Bean
  AuditorAware<String> auditorProvider() {
    return applicationAuditAware;
  }

  /**
   * Provides an AuthenticationManager that can be used to authenticate users.
   * This is done by using the AuthenticationConfiguration to get the
   * authentication manager.
   * 
   * @param config The AuthenticationConfiguration to use.
   * @return An AuthenticationManager that can be used to authenticate users.
   * @throws Exception If the AuthenticationManager cannot be created.
   */
  @Bean
  AuthenticationManager authenticationManager(@NonNull AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
  }
}
