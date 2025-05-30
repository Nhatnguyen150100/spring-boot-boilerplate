package com.spring.app.configs;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;

@OpenAPIDefinition(info = @Info(contact = @Contact(name = "Nhatnguyen150100", email = "nhatnguyen150100@gmail.com"), description = "OpenApi documentation for Spring App", title = "OpenApi specification - Spring App", version = "1.0", license = @License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0"), termsOfService = "http://swagger.io/terms/"), servers = {
    @Server(description = "Local ENV", url = "http://localhost:8080/api/v1"),
}, security = {
    @SecurityRequirement(name = "bearerAuth")
})
@SecurityScheme(name = "bearerAuth", description = "JWT auth description", scheme = "bearer", type = SecuritySchemeType.HTTP, bearerFormat = "JWT", in = SecuritySchemeIn.HEADER)
public class SwaggerConfig {

}
