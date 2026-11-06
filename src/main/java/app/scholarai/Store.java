package app.scholarai;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Abstraction: common persistence and JSON mapping live behind named helper methods. The mapper is
 * private; services deliberately use jdbc() for explicit SQL instead of an ORM or entity hierarchy.
 * JDBC placeholders bind values separately from SQL, preventing SQL injection.
 */
@Repository
public class Store {
  private final JdbcTemplate db;

  public JdbcTemplate jdbc() {
    return db;
  }

  private final JsonMapper json = JsonMapper.builder().build();

  public Store(JdbcTemplate db) {
    this.db = db;
  }

  public String json(Object value) {
    return json.writeValueAsString(value);
  }

  public Map<String, Object> object(String value) {
    return json.readValue(value, new TypeReference<>() {});
  }

  public List<Map<String, Object>> list(String value) {
    return json.readValue(value, new TypeReference<>() {});
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void audit(String actor, String action, String entity) {
    db.update(
        "INSERT INTO audit_log(id,actor_id,action,entity_id) VALUES (?,?,?,?)",
        UUID.randomUUID().toString(),
        actor,
        action,
        entity);
  }

  public void notify(String user, String message) {
    db.update(
        "INSERT INTO notifications(id,user_id,message) VALUES (?,?,?)",
        UUID.randomUUID().toString(),
        user,
        message);
  }

  public Map<String, Object> profile(String user) {
    var row = one("SELECT payload FROM student_profiles WHERE user_id=?", user);
    return row == null ? new LinkedHashMap<>() : object(row.get("payload").toString());
  }

  public Map<String, Object> scholarship(Map<String, Object> row) {
    var result = object(row.get("payload").toString());
    result.put("status", row.get("status"));
    result.put("ownerId", row.get("owner_id"));
    return result;
  }

  public Map<String, Object> scholarship(String id) {
    var row = one("SELECT * FROM scholarships WHERE id=?", id);
    return row == null ? null : scholarship(row);
  }
}
