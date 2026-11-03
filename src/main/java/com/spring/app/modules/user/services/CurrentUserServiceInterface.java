package com.spring.app.modules.user.services;

import com.spring.app.modules.auth.entities.User;
import org.springframework.security.core.Authentication;

public interface CurrentUserServiceInterface {
  User getCurrentUser();

  Authentication getAuthentication();
}
