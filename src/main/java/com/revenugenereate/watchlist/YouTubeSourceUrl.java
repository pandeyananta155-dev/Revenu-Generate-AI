package com.revenugenereate.watchlist;

import com.revenugenereate.config.HttpUrlValidator;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validates supported YouTube URLs and converts them to stable, canonical source identifiers. */
public final class YouTubeSourceUrl {
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern HANDLE = Pattern.compile("@[A-Za-z0-9._-]{3,30}");
    private static final List<String> YOUTUBE_HOSTS = List.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be");

    private YouTubeSourceUrl() {}

    /** Parses a user-provided URL, rejecting other hosts and ambiguous URL forms. */
    public static ParsedSource parse(String sourceType, String value) {
        if (sourceType == null || value == null) throw new IllegalArgumentException("sourceType and sourceUrl are required");
        final URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sourceUrl is malformed", exception);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !YOUTUBE_HOSTS.contains(host)
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                || !HttpUrlValidator.isSafeHttpUrl(uri.toString())) {
            throw new IllegalArgumentException("sourceUrl must be a supported HTTPS YouTube URL");
        }
        if ("YOUTUBE_CHANNEL".equals(sourceType)) return parseChannel(host, uri);
        if ("YOUTUBE_VIDEO".equals(sourceType)) return parseVideo(host, uri);
        throw new IllegalArgumentException("sourceType must be YOUTUBE_CHANNEL or YOUTUBE_VIDEO");
    }

    private static ParsedSource parseChannel(String host, URI uri) {
        if (host.contains("youtu.be")) throw invalidType("channel");
        String path = uri.getPath();
        String id = null;
        String canonical;
        if (path != null && path.startsWith("/channel/")) {
            id = path.substring("/channel/".length());
            if (!CHANNEL_ID.matcher(id).matches()) throw invalidId("channel");
            canonical = "https://www.youtube.com/channel/" + id;
        } else if (path != null && path.matches("/@[A-Za-z0-9._-]{3,30}")) {
            id = path.substring(1);
            canonical = "https://www.youtube.com/" + id;
        } else {
            throw invalidType("channel");
        }
        return new ParsedSource("YOUTUBE_CHANNEL", canonical, id);
    }

    private static ParsedSource parseVideo(String host, URI uri) {
        String id = null;
        if (host.contains("youtu.be")) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.matches("/[A-Za-z0-9_-]{11}/?")) id = path.substring(1).replace("/", "");
        } else {
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.equals("/watch")) {
                if (uri.getRawQuery() != null) {
                    for (String parameter : uri.getRawQuery().split("&")) {
                        String[] pair = parameter.split("=", 2);
                        if ("v".equals(pair[0])) {
                            if (id != null) throw new IllegalArgumentException("Video URL has multiple v parameters");
                            id = pair.length == 2 ? pair[1] : "";
                        }
                    }
                }
            } else if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) {
                id = path.substring(path.indexOf('/', 1) + 1);
            }
        }
        if (id == null || !VIDEO_ID.matcher(id).matches()) throw invalidId("video");
        return new ParsedSource("YOUTUBE_VIDEO", "https://www.youtube.com/watch?v=" + id, id);
    }

    private static IllegalArgumentException invalidType(String source) {
        return new IllegalArgumentException("sourceUrl is not a supported YouTube " + source + " URL");
    }

    private static IllegalArgumentException invalidId(String source) {
        return new IllegalArgumentException("YouTube " + source + " identifier is invalid");
    }

    /** Canonical source details used by persistence and the official API adapter. */
    public record ParsedSource(String sourceType, String sourceUrl, String externalSourceId) {}
}
