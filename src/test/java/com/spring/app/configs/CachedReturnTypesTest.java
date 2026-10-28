package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;

import com.spring.app.modules.user.services.impl.UserService;
import com.spring.app.shared.services.AuthCacheService;

import jakarta.persistence.Entity;

/**
 * Guard rail: values put into Redis must be immutable DTOs. A cached JPA
 * entity comes back detached and stale, and a cached ResponseEntity cannot be
 * deserialized at all.
 */
class CachedReturnTypesTest {

  @Test
  void cachedMethodsNeverReturnEntitiesOrResponseEntities() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
      @Override
      protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
        return true; // include interfaces, abstract and nested classes too
      }
    };
    scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);

    Set<String> scanned = new HashSet<>();
    List<String> offenders = new ArrayList<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("com.spring.app")) {
      Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), getClass().getClassLoader());
      scanned.add(type.getName());
      for (Method method : type.getDeclaredMethods()) {
        boolean cached = AnnotatedElementUtils.hasAnnotation(method, Cacheable.class)
            || AnnotatedElementUtils.hasAnnotation(method, CachePut.class);
        Class<?> returned = method.getReturnType();
        if (cached && (returned.isAnnotationPresent(Entity.class)
            || ResponseEntity.class.isAssignableFrom(returned))) {
          offenders.add(type.getSimpleName() + "#" + method.getName() + " -> " + returned.getSimpleName());
        }
      }
    }

    // Sanity check that the scan really covered the caching classes.
    assertThat(scanned).contains(UserService.class.getName(), AuthCacheService.class.getName());
    assertThat(offenders).isEmpty();
  }
}
