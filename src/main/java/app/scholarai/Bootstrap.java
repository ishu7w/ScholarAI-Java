package app.scholarai;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polymorphism: Spring calls the CommandLineRunner interface; this class supplies run(). An explicitly configured administrator is created at startup.
 */
@Component
public class Bootstrap implements CommandLineRunner {
  private final Store store;
  private final PasswordEncoder encoder;
  private final String email, password;

  public Bootstrap(
      Store store,
      PasswordEncoder encoder,
      @Value("${app.admin-email}") String email,
      @Value("${app.admin-password}") String password) {
    this.store = store;
    this.encoder = encoder;
    this.email = email.trim().toLowerCase(Locale.ROOT);
    this.password = password;
  }

  @Override
  @Transactional
  public void run(String... args) throws Exception {
    if (!email.isBlank()) {
      Accounts.require(
          email.length() <= 254 && email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),
          "ADMIN_EMAIL must be a valid email address.");
    }
    if (!email.isBlank() && store.one("SELECT id FROM users WHERE email=?", email) == null) {
      Accounts.password(password);
      String id = UUID.randomUUID().toString();
      store
          .jdbc()
          .update(
              "INSERT INTO users(id,email,name,password,role,approved) VALUES"
                  + " (?,?,?,?,'admin',TRUE)",
              id,
              email,
              "Administrator",
              encoder.encode(password));
      store.audit(id, "admin.bootstrapped", id);
    }
  }

  static String read(String name) throws java.io.IOException {
    return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
  }
}
