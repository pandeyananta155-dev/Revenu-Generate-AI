package com.revenugenereate.persistence;

import com.revenugenereate.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class DatabaseConfiguration {

    @Bean
    DataSource dataSource(AppProperties properties) {
        try {
            Path dataDirectory = Path.of(properties.dataDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(dataDirectory);
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.sqlite.JDBC");
            dataSource.setUrl("jdbc:sqlite:" + dataDirectory.resolve("trend-to-revenue.db"));
            return dataSource;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot prepare app.data-directory '" + properties.dataDirectory()
                            + "'. Choose a writable local folder using APP_DATA_DIRECTORY.",
                    exception);
        }
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(new JdbcTemplate(dataSource));
    }
}
