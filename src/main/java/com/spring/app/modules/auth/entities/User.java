package com.spring.app.modules.auth.entities;

import com.spring.app.common.entities.BaseEntity;
import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.*;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "password")
public class User extends BaseEntity implements UserDetails {

  @Column(nullable = false, unique = true)
  private String email;

  @Column(nullable = false)
  @JsonIgnore
  private String password;

  private String fullName;

  private String phone;

  private String address;

  private String dateOfBirth;

  @Column(length = 512)
  private String description;

  private String avatarUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private ERole role = ERole.USER;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private EUserStatus status = EUserStatus.SUSPENDED;

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return this.role.getListAuthorities();
  }

  @Override
  public String getUsername() {
    return this.email;
  }

  @Override
  public String getPassword() {
    return this.password;
  }
}
