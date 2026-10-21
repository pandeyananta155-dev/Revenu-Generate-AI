package com.revenugenereate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import com.revenugenereate.watchlist.WatchlistModels.RefreshHistory;
import com.revenugenereate.watchlist.WatchlistModels.RefreshItem;
import com.revenugenereate.watchlist.WatchlistRepository;

@SpringBootTest(properties = "app.data-directory=target/test-data-${random.uuid}")
@AutoConfigureMockMvc
class ApplicationApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WatchlistRepository watchlistRepository;

    @Test
    void healthReturnsSafeApplicationInformation() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("Trend-to-Revenue AI Factory"))
                .andExpect(jsonPath("$.version").value("0.1.0"));
    }

    @Test
    void openApiSpecificationIsAvailable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Trend-to-Revenue API"))
                .andExpect(jsonPath("$.paths['/api/v1/health']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers'].post.requestBody.content['application/json'].example").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{code}'].patch.requestBody.content['application/json'].example").exists())
                .andExpect(jsonPath("$.paths['/api/v1/offers'].post.requestBody.content['application/json'].example").exists())
                .andExpect(jsonPath("$.paths['/api/v1/offers/{code}'].patch.requestBody.content['application/json'].example").exists())
                .andExpect(jsonPath("$.paths['/api/v1/discovery/providers'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/discovery/scans'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/discovery/google-trends/import'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/watchlists'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/watchlists'].post.requestBody.content['application/json'].example").exists())
                .andExpect(jsonPath("$.paths['/api/v1/watchlists/{id}/refresh'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/research-notes'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/research-notes/{id}'].patch").exists())
                .andExpect(jsonPath("$.components.schemas.Provider").exists())
                .andExpect(jsonPath("$.components.schemas.OfferCreateRequest").exists())
                .andExpect(jsonPath("$.components.schemas.CreateSource").exists())
                .andExpect(jsonPath("$.components.schemas.CreateNote").exists());
    }

    @Test
    void redditIsReportedBlockedAndNoRequestIsMade() throws Exception {
        String providers = mockMvc.perform(get("/api/v1/discovery/providers"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(providers).contains("\"code\":\"REDDIT_RESEARCH\"", "\"status\":\"BLOCKED\"");
        mockMvc.perform(post("/api/v1/discovery/scans")
                        .contentType("application/json")
                        .content("""
                                {"marketCode":"US","nicheCode":"AI_DEV_TOOLS","windowDays":7,"providers":["REDDIT_RESEARCH"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providers[0].status").value("BLOCKED"))
                .andExpect(jsonPath("$.resultCount").value(0));
    }

    @Test
    void importsCsvAndExposesProviderNativeInterestAsText() throws Exception {
        MockMultipartFile csv = new MockMultipartFile(
                "file", "trends.csv", "text/csv", "Query,Interest,Date\nAI coding assistant,100,2026-10-01\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String response = mockMvc.perform(multipart("/api/v1/discovery/google-trends/import")
                        .file(csv)
                        .param("countryCode", "US")
                        .param("nicheCode", "AI_DEV_TOOLS"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.attribution").value(org.hamcrest.Matchers.containsString("Google Trends")))
                .andReturn().getResponse().getContentAsString();
        String scanId = objectMapper.readTree(response).get("scanId").asString();
        mockMvc.perform(get("/api/v1/discovery/scans/{scanId}/results", scanId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].providerCode").value("GOOGLE_TRENDS_CSV"))
                .andExpect(jsonPath("$.items[0].googleTrendsNativeInterest").value("100"))
                .andExpect(jsonPath("$.items[0].countrySemantics").value(
                        org.hamcrest.Matchers.containsString("User-provided export market")));
    }

    @Test
    void unsupportedMethodReturnsProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(jsonPath("$.detail").value(
                        "The HTTP method is not supported for this resource"));
    }

    @Test
    void catalogReadsAndAllowedUpdatesWork() throws Exception {
        mockMvc.perform(get("/api/v1/markets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].code").value("US"));
        mockMvc.perform(get("/api/v1/niches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/v1/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'GOOGLE_TRENDS')]").exists())
                .andExpect(jsonPath("$[?(@.secretEnvironmentVariables[0] == 'secret-value')]").doesNotExist());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/providers/GOOGLE_TRENDS")
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/offers/FREE_WORKFLOW_CHECKLIST")
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void providerPolicyCannotBeBypassedThroughWriteApi() throws Exception {
        mockMvc.perform(post("/api/v1/providers")
                        .contentType("application/json")
                        .content("""
                                {
                                  "code":"REDDIT_TEST",
                                  "name":"Reddit test",
                                  "sourceType":"REDDIT",
                                  "endpointUrls":[],
                                  "enabled":true,
                                  "permissions":{"display":false,"scoring":false,"contentGrounding":false,"mediaDownload":false},
                                  "termsUrl":"https://example.com/terms",
                                  "termsReviewDate":"2026-10-21",
                                  "retentionDays":0,
                                  "requiredApprovals":[],
                                  "secretEnvironmentVariables":[]
                                }
                                """))
                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.type").value(
                                        "https://trend-to-revenue.local/problems/provider-policy-rejected"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/providers/YOUTUBE_WATCHLIST")
                        .contentType("application/json")
                        .content("""
                                {"permissions":{"display":true,"scoring":true,"contentGrounding":false,"mediaDownload":false}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "YouTube-derived analytics require an accepted approval reference"));
    }

    @Test
    void offerCreateRejectsSecretBearingUrlWithoutEchoingSecret() throws Exception {
        String secretValue = "never-return-this-value";
        String response = mockMvc.perform(post("/api/v1/offers")
                        .contentType("application/json")
                        .content("""
                                {"code":"INVALID_LINK","name":"Invalid link","destinationUrl":"https://example.com/?api_key=%s","supportedCountries":["US"],"approvedClaims":["A verified claim"],"termsReviewDate":"2026-10-21","disclosure":"Disclosure","enabled":false}
                                """.formatted(secretValue)))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).doesNotContain(secretValue);
    }

    @Test
    void createsAndReadsEditableProviderAndOfferRecords() throws Exception {
        mockMvc.perform(post("/api/v1/providers")
                        .contentType("application/json")
                        .content("""
                                {
                                  "code":"TEST_FEED",
                                  "name":"Test feed",
                                  "sourceType":"RSS_ATOM",
                                  "endpointUrls":["https://example.com/feed.xml"],
                                  "enabled":false,
                                  "permissions":{"display":true,"scoring":false,"contentGrounding":false,"mediaDownload":false},
                                  "termsUrl":"https://example.com/terms",
                                  "termsReviewDate":"2026-10-21",
                                  "retentionDays":14,
                                  "requiredApprovals":[],
                                  "secretEnvironmentVariables":["TEST_FEED_TOKEN"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TEST_FEED"))
                .andExpect(jsonPath("$.secretEnvironmentVariables[0]").value("TEST_FEED_TOKEN"))
                .andExpect(jsonPath("$.secretValue").doesNotExist());

        mockMvc.perform(post("/api/v1/offers")
                        .contentType("application/json")
                        .content("""
                                {
                                  "code":"TEST_OFFER",
                                  "name":"Test offer",
                                  "destinationUrl":"https://example.com/offer",
                                  "supportedCountries":["US","NP"],
                                  "approvedClaims":["A verified test claim"],
                                  "termsReviewDate":"2026-10-21",
                                  "disclosure":"Test disclosure",
                                  "enabled":false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TEST_OFFER"));
        mockMvc.perform(get("/api/v1/offers/TEST_OFFER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationUrl").value("https://example.com/offer"));
    }

    @Test
    void managesYouTubeWatchlistAndKeepsNotesWhenSourceIsArchived() throws Exception {
        String sourceResponse = mockMvc.perform(post("/api/v1/watchlists")
                        .contentType("application/json")
                        .content("""
                                {"sourceType":"YOUTUBE_VIDEO","sourceUrl":"https://youtu.be/dQw4w9WgXcQ?feature=shared",
                                 "displayName":"Demo video","countryCode":"US","nicheCode":"TECH_GENERAL",
                                 "keywords":["release","demo"],"priority":4,"enabled":false,"refreshIntervalHours":12}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceUrl").value("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .andExpect(jsonPath("$.sourceStatus").value("DISABLED"))
                .andReturn().getResponse().getContentAsString();
        String sourceId = objectMapper.readTree(sourceResponse).get("id").asString();

        mockMvc.perform(post("/api/v1/watchlists")
                        .contentType("application/json")
                        .content("""
                                {"sourceType":"YOUTUBE_VIDEO","sourceUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                                 "displayName":"Duplicate","countryCode":"US","nicheCode":"TECH_GENERAL"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/watchlists/{id}/refresh", sourceId))
                .andExpect(status().isConflict());

        String noteResponse = mockMvc.perform(post("/api/v1/research-notes")
                        .contentType("application/json")
                        .content("""
                                {"sourceId":"%s","title":"Verify claim","noteText":"Check the release notes first.",
                                 "relatedSourceUrl":"https://example.com/release","countryCode":"US",
                                 "nicheCode":"TECH_GENERAL","tags":["verification"]}
                                """.formatted(sourceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceId").value(sourceId))
                .andReturn().getResponse().getContentAsString();
        String noteId = objectMapper.readTree(noteResponse).get("id").asString();

        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchSource =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/watchlists/{id}", sourceId)
                        .contentType("application/json").content("{\"displayName\":\"Updated demo\"}");
        mockMvc.perform(patchSource).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated demo"));

        java.time.Instant refreshedAt = java.time.Instant.now();
        watchlistRepository.saveHistory(new RefreshHistory("ad4c51c9-31cb-491d-98ef-bc49c5e89e13",
                sourceId, refreshedAt, "SUCCEEDED", 1, "Mocked refresh", java.util.List.of("videos.list"),
                java.util.List.of(new RefreshItem("dQw4w9WgXcQ", "Example refreshed video",
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ", refreshedAt,
                        "UC1234567890123456789012", "Demo channel", java.util.Map.of("viewCount", 7L))),
                refreshedAt.plus(java.time.Duration.ofDays(30))));
        mockMvc.perform(get("/api/v1/watchlists/{id}/history", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].records[0].title").value("Example refreshed video"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/watchlists/{id}/enabled", sourceId)
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/watchlists/{id}", sourceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("ARCHIVED"));
        mockMvc.perform(get("/api/v1/research-notes/{id}", noteId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceId").value(sourceId));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/research-notes/{id}", noteId)
                        .contentType("application/json").content("{\"title\":\"Updated verification note\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Updated verification note"));
        mockMvc.perform(get("/api/v1/watchlists/{id}/history", sourceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].outcome").value("SUCCEEDED"));
        mockMvc.perform(get("/api/v1/watchlists").param("includeArchived", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(sourceId)).exists());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/research-notes/{id}", noteId))
                .andExpect(status().isNoContent());
    }

    @Test
    void watchlistRejectsUnsupportedYouTubeHostsAndContext() throws Exception {
        mockMvc.perform(post("/api/v1/watchlists").contentType("application/json").content("""
                {"sourceType":"YOUTUBE_VIDEO","sourceUrl":"https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ","displayName":"Bad"}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/watchlists").contentType("application/json").content("""
                {"sourceType":"YOUTUBE_CHANNEL","sourceUrl":"https://youtube.com/@validhandle","displayName":"Bad context","countryCode":"ZZ"}
                """))
                .andExpect(status().isBadRequest());
    }
}
