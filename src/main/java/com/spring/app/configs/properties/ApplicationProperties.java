package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@ConfigurationProperties(prefix = "application")
@Component
@Data
public class ApplicationProperties {
  private String frontendUrl;
  private String trustedProxies;

  /**
   * Optional comma-separated whitelist of email domains allowed to sign in via
   * OAuth2 (e.g. "solashi.com,example.com"). When blank, any OAuth2 email is
   * accepted (current behaviour). Set this to restrict OAuth2 to your org.
   */
  private String oauth2AllowedEmailDomains;
}
