package com.revenugenereate.api;

import com.revenugenereate.config.ValidHttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;

public record OfferCreateRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String code,
        @NotBlank String name,
        @NotBlank @ValidHttpUrl String destinationUrl,
        @NotEmpty List<@Pattern(regexp = "[A-Z]{2}") String> supportedCountries,
        @NotEmpty List<@NotBlank String> approvedClaims,
        @jakarta.validation.constraints.NotNull LocalDate termsReviewDate,
        @NotBlank String disclosure,
        boolean enabled) {
}
