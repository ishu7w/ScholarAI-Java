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
public class SharedSessionConfig {
  @org.springframework.context.annotation.Bean
  public org.springframework.session.web.http.CookieSerializer cookieSerializer() {
    var cookie = new org.springframework.session.web.http.DefaultCookieSerializer();
    // Production is HTTPS at Vercel's edge even when the upstream container connection is HTTP.
    cookie.setUseSecureCookie(true);
    cookie.setUseHttpOnlyCookie(true);
    cookie.setSameSite("Lax");
    return cookie;
  }
}
