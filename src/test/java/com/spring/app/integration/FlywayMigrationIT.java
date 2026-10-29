package com.spring.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

/** Flyway builds the real MySQL schema and Hibernate validates the entities against it. */
class FlywayMigrationIT extends IntegrationTestBase {

  @Autowired
  private Flyway flyway;

  @Autowired
  private EntityManagerFactory entityManagerFactory;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void everyMigrationOnTheClasspathIsApplied() throws IOException {
    int migrationFiles = new PathMatchingResourcePatternResolver()
        .getResources("classpath:db/migrations/V*.sql").length;

    MigrationInfoService info = flyway.info();

    assertThat(migrationFiles).isPositive();
    assertThat(info.pending()).isEmpty();
    assertThat(info.applied())
        .hasSize(migrationFiles)
        .allSatisfy(migration -> assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
  }

  @Test
  void hibernateValidatedTheFlywaySchema() {
    // The context only starts when validation passes; this pins that it was
    // `validate` that ran, not `update`/`create` papering over a bad migration.
    assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");

    Integer tables = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_name IN ('users', 'refresh_tokens')",
        Integer.class);
    assertThat(tables).isEqualTo(2);
  }
}
