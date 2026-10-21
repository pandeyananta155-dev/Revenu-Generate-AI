package com.revenugenereate.persistence;

import com.revenugenereate.catalog.OfferRecord;
import com.revenugenereate.catalog.SourceProfile;
import com.revenugenereate.config.CatalogProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CatalogRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public CatalogRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void seed(CatalogProperties catalogs) {
        catalogs.providers().forEach(provider -> insertProviderIfMissing(SourceProfile.from(provider)));
        catalogs.offers().forEach(offer -> insertOfferIfMissing(OfferRecord.from(offer)));
    }

    public List<SourceProfile> findAllProviders() {
        return jdbcClient.sql("SELECT * FROM source_profiles ORDER BY code")
                .query(this::mapProvider)
                .list();
    }

    public Optional<SourceProfile> findProvider(String code) {
        return jdbcClient.sql("SELECT * FROM source_profiles WHERE code = :code")
                .param("code", code)
                .query(this::mapProvider)
                .optional();
    }

    public void insertProvider(SourceProfile provider) {
        jdbcClient.sql("""
                        INSERT INTO source_profiles(
                            code, name, source_type, endpoint_urls_json, enabled, permissions_json,
                            terms_url, terms_review_date, retention_days, required_approvals_json,
                            secret_environment_variables_json, reddit_permission_reference,
                            youtube_approval_reference)
                        VALUES (
                            :code, :name, :sourceType, :endpointUrls, :enabled, :permissions,
                            :termsUrl, :reviewDate, :retentionDays, :approvals,
                            :secretNames, :redditReference, :youtubeReference)
                        """)
                .param("code", provider.code())
                .param("name", provider.name())
                .param("sourceType", provider.sourceType())
                .param("endpointUrls", writeJson(provider.endpointUrls()))
                .param("enabled", provider.enabled() ? 1 : 0)
                .param("permissions", writeJson(provider.permissions()))
                .param("termsUrl", provider.termsUrl())
                .param("reviewDate", provider.termsReviewDate().toString())
                .param("retentionDays", provider.retentionDays())
                .param("approvals", writeJson(provider.requiredApprovals()))
                .param("secretNames", writeJson(provider.secretEnvironmentVariables()))
                .param("redditReference", provider.redditCommercialPermissionReference())
                .param("youtubeReference", provider.youtubeDerivedMetricsApprovalReference())
                .update();
    }

    public void updateProvider(SourceProfile provider) {
        jdbcClient.sql("""
                        UPDATE source_profiles SET
                            endpoint_urls_json = :endpointUrls,
                            enabled = :enabled,
                            permissions_json = :permissions,
                            terms_url = :termsUrl,
                            terms_review_date = :reviewDate,
                            retention_days = :retentionDays,
                            required_approvals_json = :approvals,
                            secret_environment_variables_json = :secretNames,
                            reddit_permission_reference = :redditReference,
                            youtube_approval_reference = :youtubeReference,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE code = :code
                        """)
                .param("code", provider.code())
                .param("endpointUrls", writeJson(provider.endpointUrls()))
                .param("enabled", provider.enabled() ? 1 : 0)
                .param("permissions", writeJson(provider.permissions()))
                .param("termsUrl", provider.termsUrl())
                .param("reviewDate", provider.termsReviewDate().toString())
                .param("retentionDays", provider.retentionDays())
                .param("approvals", writeJson(provider.requiredApprovals()))
                .param("secretNames", writeJson(provider.secretEnvironmentVariables()))
                .param("redditReference", provider.redditCommercialPermissionReference())
                .param("youtubeReference", provider.youtubeDerivedMetricsApprovalReference())
                .update();
    }

    public List<OfferRecord> findAllOffers() {
        return jdbcClient.sql("SELECT * FROM offers ORDER BY code")
                .query(this::mapOffer)
                .list();
    }

    public Optional<OfferRecord> findOffer(String code) {
        return jdbcClient.sql("SELECT * FROM offers WHERE code = :code")
                .param("code", code)
                .query(this::mapOffer)
                .optional();
    }

    public void insertOffer(OfferRecord offer) {
        jdbcClient.sql("""
                        INSERT INTO offers(
                            code, name, destination_url, supported_countries_json,
                            approved_claims_json, terms_review_date, disclosure, enabled)
                        VALUES (:code, :name, :url, :countries, :claims, :reviewDate, :disclosure, :enabled)
                        """)
                .param("code", offer.code())
                .param("name", offer.name())
                .param("url", offer.destinationUrl())
                .param("countries", writeJson(offer.supportedCountries()))
                .param("claims", writeJson(offer.approvedClaims()))
                .param("reviewDate", offer.termsReviewDate().toString())
                .param("disclosure", offer.disclosure())
                .param("enabled", offer.enabled() ? 1 : 0)
                .update();
    }

    public void updateOffer(OfferRecord offer) {
        jdbcClient.sql("""
                        UPDATE offers SET
                            name = :name,
                            destination_url = :url,
                            supported_countries_json = :countries,
                            approved_claims_json = :claims,
                            terms_review_date = :reviewDate,
                            disclosure = :disclosure,
                            enabled = :enabled,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE code = :code
                        """)
                .param("code", offer.code())
                .param("name", offer.name())
                .param("url", offer.destinationUrl())
                .param("countries", writeJson(offer.supportedCountries()))
                .param("claims", writeJson(offer.approvedClaims()))
                .param("reviewDate", offer.termsReviewDate().toString())
                .param("disclosure", offer.disclosure())
                .param("enabled", offer.enabled() ? 1 : 0)
                .update();
    }

    private void insertProviderIfMissing(SourceProfile provider) {
        jdbcClient.sql("""
                        INSERT INTO source_profiles(
                            code, name, source_type, endpoint_urls_json, enabled, permissions_json,
                            terms_url, terms_review_date, retention_days, required_approvals_json,
                            secret_environment_variables_json, reddit_permission_reference,
                            youtube_approval_reference)
                        VALUES (
                            :code, :name, :sourceType, :endpointUrls, :enabled, :permissions,
                            :termsUrl, :reviewDate, :retentionDays, :approvals,
                            :secretNames, :redditReference, :youtubeReference)
                        ON CONFLICT(code) DO NOTHING
                        """)
                .param("code", provider.code())
                .param("name", provider.name())
                .param("sourceType", provider.sourceType())
                .param("endpointUrls", writeJson(provider.endpointUrls()))
                .param("enabled", provider.enabled() ? 1 : 0)
                .param("permissions", writeJson(provider.permissions()))
                .param("termsUrl", provider.termsUrl())
                .param("reviewDate", provider.termsReviewDate().toString())
                .param("retentionDays", provider.retentionDays())
                .param("approvals", writeJson(provider.requiredApprovals()))
                .param("secretNames", writeJson(provider.secretEnvironmentVariables()))
                .param("redditReference", provider.redditCommercialPermissionReference())
                .param("youtubeReference", provider.youtubeDerivedMetricsApprovalReference())
                .update();
    }

    private void insertOfferIfMissing(OfferRecord offer) {
        jdbcClient.sql("""
                        INSERT INTO offers(
                            code, name, destination_url, supported_countries_json,
                            approved_claims_json, terms_review_date, disclosure, enabled)
                        VALUES (:code, :name, :url, :countries, :claims, :reviewDate, :disclosure, :enabled)
                        ON CONFLICT(code) DO NOTHING
                        """)
                .param("code", offer.code())
                .param("name", offer.name())
                .param("url", offer.destinationUrl())
                .param("countries", writeJson(offer.supportedCountries()))
                .param("claims", writeJson(offer.approvedClaims()))
                .param("reviewDate", offer.termsReviewDate().toString())
                .param("disclosure", offer.disclosure())
                .param("enabled", offer.enabled() ? 1 : 0)
                .update();
    }

    private SourceProfile mapProvider(ResultSet rs, int rowNumber) throws SQLException {
        return new SourceProfile(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("source_type"),
                readJson(rs.getString("endpoint_urls_json"), new TypeReference<>() {}),
                rs.getInt("enabled") == 1,
                readJson(rs.getString("permissions_json"), CatalogProperties.Permissions.class),
                rs.getString("terms_url"),
                LocalDate.parse(rs.getString("terms_review_date")),
                (Integer) rs.getObject("retention_days"),
                readJson(rs.getString("required_approvals_json"), new TypeReference<>() {}),
                readJson(rs.getString("secret_environment_variables_json"), new TypeReference<>() {}),
                rs.getString("reddit_permission_reference"),
                rs.getString("youtube_approval_reference"),
                parseTimestamp(rs.getString("created_at")),
                parseTimestamp(rs.getString("updated_at")));
    }

    private OfferRecord mapOffer(ResultSet rs, int rowNumber) throws SQLException {
        return new OfferRecord(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("destination_url"),
                readJson(rs.getString("supported_countries_json"), new TypeReference<>() {}),
                readJson(rs.getString("approved_claims_json"), new TypeReference<>() {}),
                LocalDate.parse(rs.getString("terms_review_date")),
                rs.getString("disclosure"),
                rs.getInt("enabled") == 1,
                parseTimestamp(rs.getString("created_at")),
                parseTimestamp(rs.getString("updated_at")));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize local configuration data", exception);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read persisted local configuration data", exception);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read persisted local configuration data", exception);
        }
    }

    private LocalDateTime parseTimestamp(String timestamp) {
        return LocalDateTime.parse(timestamp.replace(' ', 'T'));
    }
}
