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
    // Relative: "Try it out" calls whichever host served the docs (any
    // environment, behind any proxy), and the controller mappings already
    // carry the /api/v1 prefix, so the server URL must not repeat it.
    @Server(description = "Current host", url = "/"),
}, security = {
    @SecurityRequirement(name = "bearerAuth")
})
@SecurityScheme(name = "bearerAuth", description = "JWT auth description", scheme = "bearer", type = SecuritySchemeType.HTTP, bearerFormat = "JWT", in = SecuritySchemeIn.HEADER)
public class SwaggerConfig {

}
