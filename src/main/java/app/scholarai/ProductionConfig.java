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
        List.of(
            "DATABASE_URL",
            "DATABASE_USER",
            "DATABASE_PASSWORD",
            "APP_BASE_URL",
            "MAIL_FROM",
            "SMTP_HOST",
            "DOCUMENT_STORAGE")) {
      String value = env.getProperty(key);
      if (value == null || value.isBlank())
        throw new IllegalStateException("Production requires " + key);
    }
    if (!env.getRequiredProperty("DATABASE_URL").startsWith("jdbc:postgresql:"))
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
    if (!java.nio.file.Path.of(env.getRequiredProperty("DOCUMENT_STORAGE")).isAbsolute())
      throw new IllegalStateException("DOCUMENT_STORAGE must be an absolute durable storage path.");
    if (Boolean.parseBoolean(env.getProperty("SMTP_AUTH", "true"))) {
      for (String key : List.of("SMTP_USERNAME", "SMTP_PASSWORD"))
        if (env.getProperty(key, "").isBlank())
          throw new IllegalStateException("Authenticated SMTP requires " + key);
    }
  }
}
