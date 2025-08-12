package com.spring.app.shared.services;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  /**
   * Load user information from the OAuth2 provider.
   *
   * @param userRequest Contains the user request details from the OAuth2
   *                    provider.
   * @return The OAuth2 user containing the user information.
   * @throws OAuth2AuthenticationException If there is a problem loading the user.
   */
  @Override
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    try {
      OAuth2User oauth2User = super.loadUser(userRequest);
      if (oauth2User == null) {
        throw new RuntimeException("Failed to load OAuth2 user");
      }

      String email = oauth2User.getAttribute("email");
      String name = oauth2User.getAttribute("name");
      String googleId = oauth2User.getAttribute("sub");

      System.out.println("User Info: email=" + email + ", name=" + name + ", googleId=" + googleId);

      return oauth2User;
    } catch (Exception e) {
      throw new RuntimeException("Error loading OAuth2 user: " + e.getMessage(), e);
    }
  }

}
