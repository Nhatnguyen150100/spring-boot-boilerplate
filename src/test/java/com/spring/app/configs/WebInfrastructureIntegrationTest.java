package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private FilterChainProxy filterChainProxy;

  @Autowired
  private HealthContributorRegistry healthContributorRegistry;

  @Autowired
  private HealthEndpointProperties healthEndpointProperties;

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

  @Test
  void rateLimitRunsBeforeTheOAuth2LoginFilters() {
    List<Class<?>> chain = filterChainProxy.getFilterChains().stream()
        .flatMap(securityChain -> securityChain.getFilters().stream())
        .<Class<?>>map(Object::getClass)
        .toList();

    // Both OAuth2 filters can complete the response themselves (redirect to Google,
    // callback), so anything placed after them never sees /api/v1/auth/oauth2/**.
    assertThat(chain).contains(JwtAuthenticatorFilter.class);
    assertThat(chain.indexOf(RateLimitFilter.class))
        .isNotNegative()
        .isLessThan(chain.indexOf(OAuth2AuthorizationRequestRedirectFilter.class))
        .isLessThan(chain.indexOf(OAuth2LoginAuthenticationFilter.class));
  }

  @Test
  void openApiServerIsRelative() throws Exception {
    mockMvc.perform(get("/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.servers.length()").value(1))
        .andExpect(jsonPath("$.servers[0].url").value("/"));
  }

  @Test
  void healthUsesBootIndicatorsOnly() {
    assertThat(healthContributorRegistry.getContributor("db")).isNotNull();
    assertThat(healthContributorRegistry.getContributor("redis")).isNotNull();
    assertThat(healthContributorRegistry.getContributor("database")).isNull();
  }

  @Test
  void healthDetailsAreForAdminsOnly() {
    assertThat(healthEndpointProperties.getShowDetails()).isEqualTo(Show.WHEN_AUTHORIZED);
    assertThat(healthEndpointProperties.getRoles()).containsExactly("ADMIN");
  }
}
