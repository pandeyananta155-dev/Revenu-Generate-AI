package com.revenugenereate.config;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public class HttpUrlValidator implements ConstraintValidator<ValidHttpUrl, String> {

    private static final Set<String> SENSITIVE_QUERY_KEYS = Set.of(
            "key", "apikey", "token", "accesstoken", "refreshtoken", "clientsecret",
            "secret", "password", "passwd", "auth", "authorization", "signature", "credential");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return isSafeHttpUrl(value);
    }

    public static boolean isSafeHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            if (!(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null)) {
                return false;
            }
            if (uri.getRawQuery() == null) {
                return true;
            }
            for (String parameter : uri.getRawQuery().split("&")) {
                String rawKey = parameter.split("=", 2)[0];
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z]", "");
                if (SENSITIVE_QUERY_KEYS.contains(key)) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
