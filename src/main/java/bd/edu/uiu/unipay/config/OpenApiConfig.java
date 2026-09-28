package bd.edu.uiu.unipay.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 metadata for Swagger UI ({@code /swagger-ui.html}).
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI unipayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("UniPay API")
                        .version("1.0.0")
                        .description("""
                                Enclosed campus money management & real-time digital wallet for the \
                                UIU ecosystem. Advanced OOP (CSE 3118 / CSE 2118) lab project, \
                                Department of CSE, United International University. \
                                Group 2 — Section J."""))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme()
                                .name(SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
