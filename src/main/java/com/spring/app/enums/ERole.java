package com.spring.app.enums;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import static com.spring.app.enums.EPermission.*;

@RequiredArgsConstructor
public enum ERole {

  USER(Collections.emptySet()),
  ADMIN(
      Set.of(
          ADMIN_READ,
          ADMIN_UPDATE,
          ADMIN_DELETE,
          ADMIN_CREATE,
          MANAGER_READ,
          MANAGER_UPDATE,
          MANAGER_DELETE,
          MANAGER_CREATE)),
  MANAGER(
      Set.of(
          MANAGER_READ,
          MANAGER_UPDATE,
          MANAGER_DELETE,
          MANAGER_CREATE));

  @Getter
  private final Set<EPermission> listPermission;

  public List<SimpleGrantedAuthority> getListAuthorities() {
    var authorities = this.getListPermission().stream()
        .map(permission -> new SimpleGrantedAuthority(permission.getPermission()))
        .collect(Collectors.toList());
    authorities.add(new SimpleGrantedAuthority("ROLE_" + this.name()));
    return authorities;
  }
}
