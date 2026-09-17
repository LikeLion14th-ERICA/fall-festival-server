package dev.espero.festival.cleanup;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Chooses the isolated pool when configured and the application pool otherwise. */
@Component
@Profile("db")
public class CleanupDataSourceProvider {

    private final DataSource applicationDataSource;
    private final CleanupProperties properties;
    private final DataSource dedicatedDataSource;

    public CleanupDataSourceProvider(
        @Qualifier("dataSource") DataSource applicationDataSource,
        CleanupProperties properties
    ) {
        this.applicationDataSource = applicationDataSource;
        this.properties = properties;
        this.dedicatedDataSource = createDedicatedDataSource(properties);
    }

    public DataSource dataSource() {
        return dedicatedDataSource == null ? applicationDataSource : dedicatedDataSource;
    }

    public boolean hasDedicatedDataSourceAndRole() {
        if (!properties.hasDedicatedDataSourceAndRole() || dedicatedDataSource == null) {
            return false;
        }
        try (Connection connection = dedicatedDataSource.getConnection()) {
            String currentUser;
            try (var statement = connection.prepareStatement("SELECT current_user");
                 var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                currentUser = resultSet.getString(1);
            }
            return properties.getDatasource().getRole().strip().equals(currentUser);
        } catch (SQLException exception) {
            return false;
        }
    }

    @PreDestroy
    void closeDedicatedDataSource() {
        if (dedicatedDataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Could not close the cleanup datasource", exception);
            }
        }
    }

    private DataSource createDedicatedDataSource(CleanupProperties properties) {
        if (!properties.hasDedicatedDataSourceAndRole()) {
            return null;
        }
        CleanupProperties.CleanupDataSourceProperties datasource = properties.getDatasource();
        return DataSourceBuilder.create()
            .url(datasource.getUrl())
            .username(datasource.getUsername())
            .password(datasource.getPassword())
            .build();
    }
}
