package com.revenugenereate.api;

import com.revenugenereate.config.ValidHttpUrl;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Validated request bodies for watchlist and personal research-note operations. */
public final class WatchlistRequests {
    private WatchlistRequests() {}

    /** Request to add a supported YouTube channel or video as a watched source. */
    public record CreateSource(
            @NotBlank @Pattern(regexp = "YOUTUBE_CHANNEL|YOUTUBE_VIDEO") String sourceType,
            @NotBlank @Size(max = 500) String sourceUrl,
            @NotBlank @Size(max = 160) String displayName,
            @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String nicheCode,
            @Size(max = 20) List<@NotBlank @Size(max = 80) String> keywords,
            @Min(1) @Max(5) Integer priority,
            Boolean enabled,
            @Min(1) @Max(720) Integer refreshIntervalHours) {}

    /** Partial source configuration update; null fields keep their current values. */
    public record PatchSource(
            @Pattern(regexp = "YOUTUBE_CHANNEL|YOUTUBE_VIDEO") String sourceType,
            @Size(max = 500) String sourceUrl,
            @Size(max = 160) String displayName,
            @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String nicheCode,
            @Size(max = 20) List<@NotBlank @Size(max = 80) String> keywords,
            @Min(1) @Max(5) Integer priority,
            Boolean enabled,
            @Min(1) @Max(720) Integer refreshIntervalHours) {}

    /** Enable/disable flag request. */
    public record EnabledRequest(@NotNull Boolean enabled) {}

    /** Request to add a short, attributed personal note. */
    public record CreateNote(
            String sourceId,
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 10000) String noteText,
            @ValidHttpUrl String relatedSourceUrl,
            @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String nicheCode,
            @Size(max = 30) List<@NotBlank @Size(max = 40) String> tags) {}

    /** Partial note update; null fields preserve existing values. */
    public record PatchNote(
            String sourceId,
            @Size(max = 160) String title,
            @Size(max = 10000) String noteText,
            @ValidHttpUrl String relatedSourceUrl,
            @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String nicheCode,
            @Size(max = 30) List<@NotBlank @Size(max = 40) String> tags) {}
}
