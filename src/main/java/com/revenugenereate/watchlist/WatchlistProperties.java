package com.revenugenereate.watchlist;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Limits stored YouTube refresh data and bounds work for one channel refresh. */
@Validated
@ConfigurationProperties(prefix = "watchlists")
public record WatchlistProperties(
        @Min(1) @Max(50) int maxVideosPerRefresh,
        @Min(1) @Max(30) int historyRetentionDays,
        List<@Valid SourceDefinition> sources) {

    /** One optional YAML seed entry, also manageable later through the REST API. */
    public record SourceDefinition(
            @NotBlank @Pattern(regexp = "YOUTUBE_CHANNEL|YOUTUBE_VIDEO") String sourceType,
            @NotBlank @Size(max = 500) String sourceUrl,
            @NotBlank @Size(max = 160) String displayName,
            @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String nicheCode,
            @Size(max = 20) List<@NotBlank @Size(max = 80) String> keywords,
            @Min(1) @Max(5) Integer priority,
            Boolean enabled,
            @Min(1) @Max(720) Integer refreshIntervalHours) {
    }
}
