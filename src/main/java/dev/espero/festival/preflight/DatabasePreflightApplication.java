package dev.espero.festival.preflight;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Standalone JDBC program. Deliberately has no Spring, Flyway or application service dependencies. */
public final class DatabasePreflightApplication {

    private DatabasePreflightApplication() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), DriverManager::getConnection, System.out));
    }

    static int run(String[] args, Map<String, String> environment,
                   ConnectionFactory connections, PrintStream output) {
        if (args.length == 1 && "--help".equals(args[0])) {
            output.println("READ_ONLY_DATABASE_PREFLIGHT: standalone PostgreSQL metadata inspection.");
            output.println("Required environment: PREFLIGHT_DATASOURCE_URL, PREFLIGHT_DATASOURCE_USERNAME,");
            output.println("PREFLIGHT_DATASOURCE_PASSWORD, PREFLIGHT_SCHEMA. Optional: PREFLIGHT_FESTIVAL_ID.");
            output.println("No .env, Spring profiles, Flyway, web server or application services are loaded.");
            return 0;
        }
        try {
            if (args.length != 0) {
                throw new IllegalArgumentException("Arguments are not accepted; use environment variables or --help.");
            }
            Settings settings = Settings.from(environment);
            MigrationInventory inventory = MigrationInventory.load();
            try (Connection connection = connections.open(settings.url(), settings.connectionProperties())) {
                DatabasePreflight.Report report = new DatabasePreflight(inventory).inspect(
                    connection, settings.schema(), settings.festivalId());
                report.print(output);
                return report.requiresReview() ? 2 : 0;
            }
        } catch (IllegalArgumentException exception) {
            stop(output, "CONFIGURATION_INVALID");
            return 2;
        } catch (SQLException exception) {
            // Exception text may contain a URL, credentials, SQL or data returned by the server.
            stop(output, "DATABASE_INSPECTION_FAILED");
            String state = exception.getSQLState();
            if (state != null && state.matches("[A-Z0-9]{5}")) {
                output.println("sqlState=" + state);
            }
            return 2;
        } catch (Exception exception) {
            stop(output, "PREFLIGHT_UNAVAILABLE");
            return 2;
        }
    }

    private static void stop(PrintStream output, String reason) {
        output.println("mode=READ_ONLY_DATABASE_PREFLIGHT");
        output.println("status=STOP_AND_REVIEW");
        output.println("mutationAuthorized=false");
        output.println("finding=" + reason);
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(String url, Properties properties) throws SQLException;
    }

    record Settings(String url, String username, String password, String schema, UUID festivalId) {
        static Settings from(Map<String, String> environment) {
            String url = required(environment, "PREFLIGHT_DATASOURCE_URL");
            String username = required(environment, "PREFLIGHT_DATASOURCE_USERNAME");
            String password = required(environment, "PREFLIGHT_DATASOURCE_PASSWORD");
            String schema = required(environment, "PREFLIGHT_SCHEMA");
            if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]{0,62}") || schema.startsWith("pg_")
                    || "information_schema".equals(schema)) {
                throw new IllegalArgumentException("A non-system schema identifier is required.");
            }
            if (!url.startsWith("jdbc:postgresql://") || url.contains("#") || url.contains("@")
                    || url.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A PostgreSQL JDBC URL without credentials is required.");
            }
            int queryStart = url.indexOf('?');
            if (queryStart >= 0) {
                for (String parameter : url.substring(queryStart + 1).split("&", -1)) {
                    String name = parameter.split("=", 2)[0];
                    // URL options must not override read-only, credentials, timeouts or driver factories.
                    if (!name.equals("sslmode") && !name.equals("sslrootcert")) {
                        throw new IllegalArgumentException("Only sslmode and sslrootcert URL options are accepted.");
                    }
                }
            }
            String festival = environment.get("PREFLIGHT_FESTIVAL_ID");
            return new Settings(url, username, password, schema,
                festival == null || festival.isBlank() ? null : UUID.fromString(festival));
        }

        Properties connectionProperties() {
            Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password);
            properties.setProperty("ApplicationName", "espero-read-only-preflight");
            properties.setProperty("readOnly", "true");
            properties.setProperty("readOnlyMode", "always");
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("socketTimeout", "15");
            properties.setProperty("options", "-c default_transaction_read_only=on -c statement_timeout=5000"
                + " -c lock_timeout=1000 -c idle_in_transaction_session_timeout=30000");
            return properties;
        }

        @Override
        public String toString() {
            return "DatabasePreflightSettings[redacted]";
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing preflight environment.");
            }
            return value;
        }
    }
}
