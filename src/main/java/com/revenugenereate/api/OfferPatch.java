package com.revenugenereate.api;

import com.revenugenereate.config.ValidHttpUrl;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;

public record OfferPatch(
        String name,
        @ValidHttpUrl String destinationUrl,
        List<@Pattern(regexp = "[A-Z]{2}") String> supportedCountries,
        List<String> approvedClaims,
        LocalDate termsReviewDate,
        String disclosure,
        Boolean enabled) {
}
