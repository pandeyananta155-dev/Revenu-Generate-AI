package com.revenugenereate.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Trend-to-Revenue API",
                version = "v1",
                description = "Local REST foundation for the Trend-to-Revenue AI Factory."))
public class OpenApiConfiguration {
}
