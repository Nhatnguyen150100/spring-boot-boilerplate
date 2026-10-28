package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.spring.app.filter.JwtAuthenticatorFilter;
import com.spring.app.filter.RateLimitFilter;

/**
 * Full context on the test profile (H2, no Redis needed). MockMvc runs without
 * servlet filters, so no request here touches Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class WebInfrastructureIntegrationTest {

  @Autowired
  private ApplicationContext context;

  @Test
  @SuppressWarnings("rawtypes")
  void appFiltersRunOnlyInsideTheSecurityChain() {
    Map<String, FilterRegistrationBean> registrations = context.getBeansOfType(FilterRegistrationBean.class);

    List<Object> disabledFilters = registrations.values().stream()
        .filter(registration -> !registration.isEnabled())
        .map(registration -> (Object) registration.getFilter())
        .toList();

    assertThat(disabledFilters).contains(
        context.getBean(RateLimitFilter.class),
        context.getBean(JwtAuthenticatorFilter.class));
  }
}
