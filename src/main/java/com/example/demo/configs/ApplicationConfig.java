package com.example.demo.configs;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.demo.modules.auth.repositories.UserRepository;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class ApplicationConfig {

  private final UserRepository userRepository;

  private final ApplicationAuditAware applicationAuditAware;

  /**
   * A UserDetailsService that fetches users from the database by email.
   * 
   * @return A UserDetailsService that can be used in a DaoAuthenticationProvider.
   */
  @Bean
  UserDetailsService userDetailsService() {
    return username -> this.userRepository.findByEmail(username)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
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
   * PasswordEncoder to authenticate users. This is a DaoAuthenticationProvider
   * that fetches users from the database by email and uses BCrypt to hash
   * passwords.
   * 
   * @return An AuthenticationProvider that can be used to authenticate users.
   */
  @Bean
  AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
    authProvider.setUserDetailsService(userDetailsService());
    authProvider.setPasswordEncoder(passwordEncoder());
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
  AuditorAware<UUID> auditorProvider() {
    return this.applicationAuditAware;
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
  AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
  }
}
