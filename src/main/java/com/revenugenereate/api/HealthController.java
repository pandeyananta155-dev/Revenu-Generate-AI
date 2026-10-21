package com.revenugenereate.api;

import com.revenugenereate.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Service health")
public class HealthController {

    private final AppProperties appProperties;

    public HealthController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping("/health")
    @Operation(summary = "Get service health")
    public HealthResponse health() {
        return new HealthResponse("UP", appProperties.name(), appProperties.version());
    }

    public record HealthResponse(String status, String application, String version) {
    }
}
