package app.scholarai;

import static org.springframework.http.HttpStatus.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
/**
 * Encapsulation: account validation and password hashing rules live here. Controllers request
 * account operations without implementing authentication rules.
 */
public class Accounts {
  private final Store store;
  private final PasswordEncoder passwords;

  public Accounts(Store store, PasswordEncoder passwords) {
    this.store = store;
    this.passwords = passwords;
  }

  public Map<String, Object> byEmail(String email) {
    return store.one("SELECT * FROM users WHERE email=?", email.trim().toLowerCase(Locale.ROOT));
  }

  public Map<String, Object> user(String id) {
    return store.one("SELECT * FROM users WHERE id=?", id);
  }

  public static void require(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(BAD_REQUEST, message);
  }

  public static String text(Map<String, String> input, String key, int max) {
    String value = input.getOrDefault(key, "").trim();
    require(value.length() <= max, "Value too long: " + key);
    return value;
  }

  public static void password(String value) {
    require(
        value.codePointCount(0, value.length()) >= 12
            && value.getBytes(StandardCharsets.UTF_8).length <= 72,
        "Password must contain at least 12 characters and at most 72 UTF-8 bytes.");
  }

  @Transactional
  public String register(String name, String email, String password, String role) {
    name = name.trim();
    email = email.trim().toLowerCase(Locale.ROOT);
    require(
        name.length() >= 2 && name.length() <= 120, "Enter a name between 2 and 120 characters.");
    require(
        email.length() <= 254 && email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),
        "Enter a valid email address.");
    password(password);
    require(Set.of("student", "institution").contains(role), "Choose a valid role.");
    String id = UUID.randomUUID().toString();
    try {
      store
          .jdbc()
          .update(
              "INSERT INTO users(id,email,name,password,role,approved) VALUES (?,?,?,?,?,?)",
              id,
              email,
              name,
              passwords.encode(password),
              role,
              role.equals("student"));
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(
          CONFLICT, "Unable to register this email. Try signing in instead.");
    }
    if (role.equals("student"))
      store.jdbc().update("INSERT INTO student_profiles(user_id,payload) VALUES (?,?)", id, "{}");
    store.audit(id, "account.registered", id);
    return id;
  }

  // Database-backed throttles expire and work across app restarts. Synchronization covers the
  // single-instance deployment.
  public synchronized boolean allow(String key, int limit, Duration duration) {
    String bucket = hash(key);
    Instant now = Instant.now();
    store.jdbc().update("DELETE FROM rate_limits WHERE expires_at < ?", Timestamp.from(now));
    var current = store.one("SELECT * FROM rate_limits WHERE bucket=?", bucket);
    if (current == null) {
      try {
        store
            .jdbc()
            .update(
                "INSERT INTO rate_limits(bucket,attempts,expires_at) VALUES (?,1,?)",
                bucket,
                Timestamp.from(now.plus(duration)));
        return true;
      } catch (DuplicateKeyException ignored) {
        current = store.one("SELECT * FROM rate_limits WHERE bucket=?", bucket);
      }
    }
    return store
            .jdbc()
            .update(
                "UPDATE rate_limits SET attempts=attempts+1 WHERE bucket=? AND attempts < ?",
                bucket,
                limit)
        == 1;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
