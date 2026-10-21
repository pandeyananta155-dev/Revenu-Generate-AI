package com.revenugenereate.discovery;

import com.revenugenereate.api.ApiRequestException;
import com.revenugenereate.discovery.DiscoveryModels.TrendsImportResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Imports bounded Google Trends exports supplied by the user without scraping or evaluating formulas. */
@Service
public class GoogleTrendsCsvService {
    private static final int MAX_ROWS = 1000;
    private static final String ATTRIBUTION =
            "Source: Google Trends (user-provided CSV export); relative interest is provider-native and not a demand score.";

    private final DiscoveryProperties properties;
    private final DiscoveryService discoveryService;
    private final DiscoveryRepository repository;

    public GoogleTrendsCsvService(
            DiscoveryProperties properties, DiscoveryService discoveryService, DiscoveryRepository repository) {
        this.properties = properties;
        this.discoveryService = discoveryService;
        this.repository = repository;
    }

    /** Parses required query and interest headers, records provenance, and creates a results scan. */
    @Transactional
    public TrendsImportResponse importCsv(
            MultipartFile file, String countryCode, String nicheCode, List<com.revenugenereate.config.CatalogProperties.Market> markets) {
        if (file == null || file.isEmpty()) throw invalid("CSV file is required");
        if (file.getSize() > properties.maxCsvBytes()) throw invalid("CSV file exceeds the configured size limit");
        String filename = safeFilename(file.getOriginalFilename());
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) throw invalid("Only .csv files are accepted");
        com.revenugenereate.config.CatalogProperties.Market market = markets.stream()
                .filter(value -> value.countryCode().equals(countryCode)).findFirst()
                .orElseThrow(() -> invalid("Country must match a configured market"));
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException exception) {
            throw invalid("Uploaded CSV could not be read");
        }
        if (bytes.length > properties.maxCsvBytes()) throw invalid("CSV file exceeds the configured size limit");
        String input = new String(bytes, StandardCharsets.UTF_8);
        if (input.startsWith("\uFEFF")) input = input.substring(1);
        List<DiscoveryProvider.ProviderItem> items = new ArrayList<>();
        String firstDate = null;
        String lastDate = null;
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .get()
                .parse(new java.io.StringReader(input))) {
            Map<String, String> headers = normalizedHeaders(parser.getHeaderMap());
            String queryHeader = firstHeader(headers, "query", "search term", "term", "keyword");
            String interestHeader = firstHeader(headers, "interest", "search interest", "value", "relative interest");
            String timeHeader = firstHeader(headers, "date", "time", "period", "time period");
            if (queryHeader == null || interestHeader == null) {
                throw invalid("CSV must contain a query/search term and interest/value column");
            }
            int rowNumber = 0;
            for (CSVRecord row : parser) {
                if (++rowNumber > MAX_ROWS) throw invalid("CSV exceeds the maximum of " + MAX_ROWS + " data rows");
                String query = boundedCell(row.get(queryHeader), 500, "query");
                String interest = boundedCell(row.get(interestHeader), 32, "interest");
                if (query.isBlank()) throw invalid("CSV query values must not be empty");
                if (interest.matches("<\\s*1")) {
                    // Google Trends represents very small relative-interest values as "<1".
                } else if (interest.matches("[0-9]{1,3}(?:\\.[0-9]{1,3})?")) {
                    if (Double.parseDouble(interest) > 100) {
                        throw invalid("CSV interest must not exceed 100");
                    }
                } else {
                    throw invalid("CSV interest must be a numeric Google Trends value or <1");
                }
                String dateValue = timeHeader == null ? null : boundedCell(row.get(timeHeader), 100, "time period");
                if (dateValue != null && !dateValue.isBlank()) {
                    if (firstDate == null) firstDate = dateValue;
                    lastDate = dateValue;
                }
                items.add(new DiscoveryProvider.ProviderItem(
                        null, query, null,
                        "https://trends.google.com/trends/explore?geo=" + countryCode
                                + "&q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8),
                        "Google Trends", null, null, null, interest, null,
                        "User-provided Google Trends CSV export",
                        List.of("Relative search interest is normalized by Google Trends and is not absolute search volume.",
                                "CSV values are stored as text; no formulas are evaluated.")));
            }
        } catch (ApiRequestException exception) {
            throw exception;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw invalid("CSV is malformed or contains invalid data");
        }
        if (items.isEmpty()) throw invalid("CSV contains no data rows");
        String timeWindow = firstDate == null ? null
                : firstDate.equals(lastDate) ? firstDate : firstDate + " through " + lastDate;
        if (timeWindow != null && timeWindow.length() > 200) timeWindow = timeWindow.substring(0, 200);
        Instant importedAt = Instant.now();
        DiscoveryModels.Scan scan = discoveryService.saveImportedResults(
                market.code(), countryCode, nicheCode, timeWindow, items);
        String importId = UUID.randomUUID().toString();
        TrendsImportResponse result = new TrendsImportResponse(importId, scan.id(), filename, countryCode,
                nicheCode, timeWindow, items.size(), "Google Trends CSV", importedAt, ATTRIBUTION);
        repository.saveImport(result);
        return result;
    }

    private static Map<String, String> normalizedHeaders(Map<String, Integer> original) {
        Map<String, String> headers = new HashMap<>();
        original.keySet().forEach(header -> {
            String normalized = header.trim().toLowerCase(Locale.ROOT);
            headers.put(normalized, header);
        });
        return headers;
    }

    private static String firstHeader(Map<String, String> headers, String... candidates) {
        for (String candidate : candidates) {
            if (headers.containsKey(candidate)) return headers.get(candidate);
        }
        return null;
    }

    private static String boundedCell(String value, int max, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > max || trimmed.indexOf('\0') >= 0) {
            throw invalid("CSV " + field + " cell is invalid or too long");
        }
        return trimmed;
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "google-trends.csv";
        String safe = filename.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1);
        if (safe.isBlank() || safe.length() > 255 || safe.chars().anyMatch(Character::isISOControl)) {
            throw invalid("CSV filename is invalid");
        }
        return safe;
    }

    private static ApiRequestException invalid(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "invalid-google-trends-csv", message);
    }
}
