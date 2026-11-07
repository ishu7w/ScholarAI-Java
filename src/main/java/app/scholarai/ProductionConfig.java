package app.scholarai;

import java.net.URI;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Fail closed if a production deployment still points at development infrastructure. */
@Configuration
@Profile("prod")
public class ProductionConfig {
  public ProductionConfig(Environment env) {
    for (String key :
        List.of("DATABASE_USER", "DATABASE_PASSWORD", "APP_BASE_URL", "CRON_SECRET")) {
      if (env.getProperty(key, "").isBlank())
        throw new IllegalStateException("Production requires " + key);
    }
    if (env.getRequiredProperty("CRON_SECRET").length() < 32)
      throw new IllegalStateException("CRON_SECRET must contain at least 32 characters.");
    String database = env.getProperty("JDBC_DATABASE_URL", env.getProperty("DATABASE_URL", ""));
    if (!database.startsWith("jdbc:postgresql:"))
      throw new IllegalStateException("Production requires PostgreSQL.");
    URI base = URI.create(env.getRequiredProperty("APP_BASE_URL"));
    if (!"https".equals(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getRawQuery() != null
        || base.getRawFragment() != null
        || (base.getRawPath() != null
            && !base.getRawPath().isEmpty()
            && !base.getRawPath().equals("/")))
      throw new IllegalStateException(
          "APP_BASE_URL must be an HTTPS origin without a path, query or fragment.");
  }
}
