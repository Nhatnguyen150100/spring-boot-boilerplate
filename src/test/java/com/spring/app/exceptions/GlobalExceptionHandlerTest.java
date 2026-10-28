package com.spring.app.exceptions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.spring.app.modules.auth.exceptions.InvalidTokenException;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Standalone MockMvc: only the test controller below and the real
 * {@link GlobalExceptionHandler}; no Spring context, no security filters.
 */
class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  // ---------------------------------------------------------------- exceptions thrown by application code

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(delimiter = '|', textBlock = """
      not-found                | 404 | User not found
      bad-request              | 400 | Bad input
      conflict                 | 409 | Already exists
      rate-limit               | 429 | Too many requests from this client
      user-not-active          | 403 | Account is not active
      internal-auth-not-active | 403 | Account is not active
      internal-auth            | 500 | Internal server error
      invalid-token            | 401 | Invalid or expired refresh token
      bad-credentials          | 401 | Invalid email or password
      username-not-found       | 401 | Invalid email or password
      disabled                 | 401 | Authentication failed
      access-denied            | 403 | You do not have permission to access this resource
      jwt-expired              | 401 | Invalid or expired token
      jwt-malformed            | 401 | Invalid or expired token
      data-integrity           | 409 | The request conflicts with existing data
      tx-validation            | 400 | name is required
      tx-other                 | 500 | Internal server error
      constraint               | 400 | name is required
      max-upload               | 413 | Maximum upload size exceeded
      generic                  | 500 | Internal server error
      """)
  void mapsThrownExceptionToStatusAndBaseResponse(String kind, int status, String message) throws Exception {
    mockMvc.perform(get("/test/throw/{kind}", kind))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.statusCode").value(status))
        .andExpect(jsonPath("$.message").value(message))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @ParameterizedTest
  @ValueSource(strings = { "internal-auth", "data-integrity", "tx-other", "generic", "user-not-active" })
  void neverEchoesInternalDetails(String kind) throws Exception {
    mockMvc.perform(get("/test/throw/{kind}", kind))
        .andExpect(content().string(not(containsString("secret"))))
        .andExpect(content().string(not(containsString("SQL"))))
        .andExpect(content().string(not(containsString("a@b.c"))));
  }

  @Test
  void noResourceFoundIs404() throws Exception {
    mockMvc.perform(get("/test/throw/no-resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.statusCode").value(404))
        .andExpect(jsonPath("$.message").exists());
  }

  // ---------------------------------------------------------------- exceptions raised by Spring MVC itself

  @Test
  void malformedJsonIs400() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value("Failed to read request"));
  }

  @Test
  void invalidBodyListsFieldErrors() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value("name: name is required"));
  }

  @Test
  void unsupportedMediaTypeIs415() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("name"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.statusCode").value(415));
  }

  @Test
  void wrongMethodIs405WithAllowHeader() throws Exception {
    mockMvc.perform(delete("/test/ok"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", containsString("GET")))
        .andExpect(jsonPath("$.statusCode").value(405));
  }

  @Test
  void pathVariableTypeMismatchIs400() throws Exception {
    mockMvc.perform(get("/test/items/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400));
  }

  @Test
  void parameterConstraintViolationIs400() throws Exception {
    mockMvc.perform(get("/test/min").param("value", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value("value: value must be at least 5"));
  }

  @Test
  void invalidReturnValueIsAGeneric500() throws Exception {
    // Return-value validation fails on the server's side: HandlerMethodValidationException with 500.
    mockMvc.perform(get("/test/invalid-return"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.statusCode").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("secret"))));
  }

  @Test
  void missingMultipartPartIs400() throws Exception {
    mockMvc.perform(multipart("/test/upload"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value(containsString("file")));
  }

  // ---------------------------------------------------------------- fixtures

  public static class Payload {
    @NotBlank(message = "name is required")
    public String name;

    public Payload() {
    }

    Payload(String name) {
      this.name = name;
    }
  }

  @RestController
  @RequestMapping("/test")
  static class ThrowingController {

    @GetMapping("/ok")
    public String ok() {
      return "ok";
    }

    @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String body(@Valid @RequestBody Payload payload) {
      return payload.name;
    }

    @GetMapping("/items/{id}")
    public String item(@PathVariable("id") Long id) {
      return "item " + id;
    }

    @GetMapping("/min")
    public String min(@RequestParam("value") @Min(value = 5, message = "value must be at least 5") int value) {
      return "ok";
    }

    @GetMapping("/invalid-return")
    public @NotBlank(message = "secret return-value detail") String invalidReturn() {
      return "";
    }

    @PostMapping("/upload")
    public String upload(@RequestPart("file") MultipartFile file) {
      return file.getOriginalFilename();
    }

    @GetMapping("/throw/{kind}")
    public String throwIt(@PathVariable("kind") String kind) throws Exception {
      throw exceptionFor(kind);
    }
  }

  static Exception exceptionFor(String kind) {
    return switch (kind) {
      case "not-found" -> new ResourceNotFoundException("User not found");
      case "bad-request" -> new BadRequestException("Bad input");
      case "conflict" -> new ConflictException("Already exists");
      case "rate-limit" -> new RateLimitExceededException("Too many requests from this client");
      case "user-not-active" -> new UserNotActiveException("User with email a@b.c is not active");
      case "internal-auth-not-active" ->
        new InternalAuthenticationServiceException("wrapped", new UserNotActiveException("inactive"));
      case "internal-auth" -> new InternalAuthenticationServiceException("secret jdbc:mysql://db:3306 refused",
          new IllegalStateException("secret cause"));
      case "invalid-token" -> new InvalidTokenException("Invalid or expired refresh token");
      case "bad-credentials" -> new BadCredentialsException("Bad credentials");
      case "username-not-found" -> new UsernameNotFoundException("User not found with email a@b.c");
      case "disabled" -> new DisabledException("User is disabled");
      case "access-denied" -> new AccessDeniedException("nope");
      case "jwt-expired" -> new ExpiredJwtException(null, null, "JWT expired at 2026-01-01T00:00:00Z");
      case "jwt-malformed" -> new MalformedJwtException("Malformed JWT");
      case "data-integrity" -> new DataIntegrityViolationException(
          "could not execute statement; SQL [insert into users (email) values (?)]; constraint [uk_users_email] secret");
      case "tx-validation" -> new TransactionSystemException("Could not commit JPA transaction", constraintViolation());
      case "tx-other" -> new TransactionSystemException("Could not commit JPA transaction: secret");
      case "constraint" -> constraintViolation();
      case "max-upload" -> new MaxUploadSizeExceededException(10L);
      case "no-resource" -> new NoResourceFoundException(HttpMethod.GET, "missing.txt");
      default -> new IllegalStateException("secret internal detail");
    };
  }

  static ConstraintViolationException constraintViolation() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Set<ConstraintViolation<Payload>> violations = factory.getValidator().validate(new Payload(""));
      return new ConstraintViolationException(violations);
    }
  }
}
