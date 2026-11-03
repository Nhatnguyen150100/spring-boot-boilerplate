package com.spring.app.modules.user.services.impl;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.user.services.CurrentUserServiceInterface;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService implements CurrentUserServiceInterface {

  @Override
  public User getCurrentUser() {
    Authentication authentication = this.getAuthentication();
    if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
      throw new AuthenticationCredentialsNotFoundException("Authentication required");
    }
      
    Object principal = authentication.getPrincipal();
    
    if(!(principal instanceof User user)){
      throw new IllegalStateException("Current user object is not an instance of User");
    }
    
    return user;
  }

  @Override
  public Authentication getAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
