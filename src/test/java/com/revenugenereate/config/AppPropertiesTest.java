package com.revenugenereate.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.data-directory=target/test-data")
class AppPropertiesTest {

    @Autowired
    private AppProperties properties;

    @Test
    void bindsDefaultMarketsAndNichesFromValidatedConfiguration() {
        assertThat(properties.catalogs().markets())
                .extracting(CatalogProperties.Market::code)
                .containsExactly("US", "NP", "IN", "GB");
        assertThat(properties.catalogs().niches())
                .extracting(CatalogProperties.Niche::code)
                .containsExactly("AI_DEV_TOOLS", "TECH_GENERAL", "NEPAL_DIGITAL");
    }
}
