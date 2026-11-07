package app.scholarai;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

/**
 * Composition: Spring's shared JDBC session repository keeps login valid across container
 * instances.
 */
@Configuration
@Profile("prod")
@EnableJdbcHttpSession(tableName = "spring_session", maxInactiveIntervalInSeconds = 1800)
public class SharedSessionConfig {}
