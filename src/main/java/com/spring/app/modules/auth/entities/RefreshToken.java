package com.spring.app.modules.auth.entities;

import java.time.Instant;

import com.spring.app.common.entities.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table(name = "refresh_tokens")
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken extends BaseEntity {

  @Column()
  private String token;

  @Column()
  private boolean isRevoked;

  @Column()
  private Instant expiryDate;

  @ManyToOne
  @JoinColumn(name = "user_id")
  private User user;
}
