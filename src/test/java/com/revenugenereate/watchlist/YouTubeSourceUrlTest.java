package com.revenugenereate.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class YouTubeSourceUrlTest {
    @Test
    void normalizesChannelIdAndHandleUrls() {
        var channel = YouTubeSourceUrl.parse("YOUTUBE_CHANNEL",
                "https://www.youtube.com/channel/UC1234567890123456789012?view=featured");
        var handle = YouTubeSourceUrl.parse("YOUTUBE_CHANNEL", "https://youtube.com/@Example.Channel");

        assertThat(channel.sourceUrl()).isEqualTo("https://www.youtube.com/channel/UC1234567890123456789012");
        assertThat(channel.externalSourceId()).isEqualTo("UC1234567890123456789012");
        assertThat(handle.sourceUrl()).isEqualTo("https://www.youtube.com/@Example.Channel");
        assertThat(handle.externalSourceId()).isEqualTo("@Example.Channel");
    }

    @Test
    void normalizesCommonVideoUrls() {
        assertThat(YouTubeSourceUrl.parse("YOUTUBE_VIDEO",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42").sourceUrl())
                .isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(YouTubeSourceUrl.parse("YOUTUBE_VIDEO", "https://youtu.be/dQw4w9WgXcQ").externalSourceId())
                .isEqualTo("dQw4w9WgXcQ");
        assertThat(YouTubeSourceUrl.parse("YOUTUBE_VIDEO",
                "https://www.youtube.com/shorts/dQw4w9WgXcQ").externalSourceId()).isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void rejectsUnsupportedHostsAndMalformedSourceTypes() {
        assertThatThrownBy(() -> YouTubeSourceUrl.parse("YOUTUBE_VIDEO", "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YouTubeSourceUrl.parse("YOUTUBE_CHANNEL", "https://youtu.be/dQw4w9WgXcQ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YouTubeSourceUrl.parse("YOUTUBE_VIDEO", "http://youtube.com/watch?v=dQw4w9WgXcQ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YouTubeSourceUrl.parse("YOUTUBE_VIDEO", "https://youtube.com/watch?v=bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
