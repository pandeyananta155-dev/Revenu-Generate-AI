package com.revenugenereate.api;

import com.revenugenereate.api.WatchlistRequests.CreateNote;
import com.revenugenereate.api.WatchlistRequests.CreateSource;
import com.revenugenereate.api.WatchlistRequests.EnabledRequest;
import com.revenugenereate.api.WatchlistRequests.PatchNote;
import com.revenugenereate.api.WatchlistRequests.PatchSource;
import com.revenugenereate.watchlist.WatchlistModels.Page;
import com.revenugenereate.watchlist.WatchlistModels.ProviderReadiness;
import com.revenugenereate.watchlist.WatchlistModels.RefreshHistory;
import com.revenugenereate.watchlist.WatchlistModels.ResearchNote;
import com.revenugenereate.watchlist.WatchlistModels.Source;
import com.revenugenereate.watchlist.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST API for YouTube watchlist sources and personal, source-attributed research notes. */
@Validated
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Watchlists and research notes", description = "Manual YouTube source refreshes and local research notes.")
public class WatchlistController {
    private final WatchlistService service;

    public WatchlistController(WatchlistService service) { this.service = service; }

    /** Lists configured watchlist sources with pagination. */
    @GetMapping("/watchlists")
    @Operation(summary = "List watchlist sources", description = "Archived sources are excluded unless includeArchived=true.")
    public Page<Source> listSources(@RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return service.listSources(includeArchived, page, size);
    }

    /** Creates a normalized channel or video watch entry without making a provider request. */
    @PostMapping("/watchlists")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a YouTube watchlist source", description = "Accepts supported YouTube channel or video URLs only.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(examples = @ExampleObject(value = "{\"sourceType\":\"YOUTUBE_CHANNEL\",\"sourceUrl\":\"https://www.youtube.com/@GoogleDevelopers\",\"displayName\":\"Google Developers\",\"countryCode\":\"US\",\"nicheCode\":\"TECH_GENERAL\",\"keywords\":[\"developer tools\"],\"priority\":3,\"enabled\":true,\"refreshIntervalHours\":24}"))))
    @ApiResponse(responseCode = "201", description = "Source created")
    public Source createSource(@Valid @RequestBody CreateSource request) { return service.createSource(request); }

    /** Reads a source, including archived entries. */
    @GetMapping("/watchlists/{id}")
    @Operation(summary = "Get a watchlist source")
    public Source getSource(@PathVariable String id) { return service.getSource(id); }

    /** Updates configuration fields without performing a refresh. */
    @PatchMapping("/watchlists/{id}")
    @Operation(summary = "Update a watchlist source", description = "Null fields retain existing values. A source URL must still identify a valid YouTube channel or video.")
    public Source updateSource(@PathVariable String id, @Valid @RequestBody PatchSource patch) {
        return service.updateSource(id, patch);
    }

    /** Enables or disables a source while preserving its history. */
    @PatchMapping("/watchlists/{id}/enabled")
    @Operation(summary = "Enable or disable a watchlist source")
    public Source setEnabled(@PathVariable String id, @Valid @RequestBody EnabledRequest request) {
        return service.setEnabled(id, request.enabled());
    }

    /** Archives a source instead of deleting it, preserving its notes and history. */
    @DeleteMapping("/watchlists/{id}")
    @Operation(summary = "Archive a watchlist source", description = "Soft archives the source; linked notes and refresh history are retained.")
    public Source archiveSource(@PathVariable String id) { return service.archiveSource(id); }

    /** Reports readiness without exposing API key values. */
    @GetMapping("/watchlists/provider-status")
    @Operation(summary = "Get YouTube watchlist provider status")
    public ProviderReadiness providerStatus() { return service.providerReadiness(); }

    /** Starts one synchronous, bounded refresh of an enabled source. */
    @PostMapping("/watchlists/{id}/refresh")
    @Operation(summary = "Refresh a watchlist source", description = "Performs one manual request sequence against the official YouTube Data API. A provider failure is recorded in the response and history.")
    @ApiResponse(responseCode = "200", description = "Refresh outcome recorded")
    public RefreshHistory refresh(@PathVariable String id) { return service.refresh(id); }

    /** Lists retained refresh history for a watched source. */
    @GetMapping("/watchlists/{id}/history")
    @Operation(summary = "List source refresh history", description = "Only the latest 100 attempts, retained for at most 30 days, are available.")
    public Page<RefreshHistory> history(@PathVariable String id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return service.history(id, page, size);
    }

    /** Lists notes, optionally scoped to a watched source. */
    @GetMapping("/research-notes")
    @Operation(summary = "List research notes", description = "Notes are personal research and are not treated as verified facts.")
    public Page<ResearchNote> listNotes(
            @RequestParam(required = false) String sourceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return service.listNotes(sourceId, page, size);
    }

    /** Creates a personal note with its optional source and reference URL. */
    @PostMapping("/research-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a research note", description = "User-authored notes remain distinct from provider evidence.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(examples = @ExampleObject(value = "{\"sourceId\":null,\"title\":\"Follow up\",\"noteText\":\"Check the release notes before relying on this claim.\",\"relatedSourceUrl\":\"https://example.com/release\",\"countryCode\":\"US\",\"nicheCode\":\"TECH_GENERAL\",\"tags\":[\"verification\"]}"))))
    public ResearchNote createNote(@Valid @RequestBody CreateNote request) { return service.createNote(request); }

    /** Retrieves a note by ID. */
    @GetMapping("/research-notes/{id}")
    @Operation(summary = "Get a research note")
    public ResearchNote getNote(@PathVariable String id) { return service.getNote(id); }

    /** Updates a personal note. */
    @PatchMapping("/research-notes/{id}")
    @Operation(summary = "Update a research note")
    public ResearchNote updateNote(@PathVariable String id, @Valid @RequestBody PatchNote request) {
        return service.updateNote(id, request);
    }

    /** Deletes a note without affecting its source or refresh history. */
    @DeleteMapping("/research-notes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a research note")
    public void deleteNote(@PathVariable String id) { service.deleteNote(id); }
}
