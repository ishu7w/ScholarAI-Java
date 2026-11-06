package app.scholarai;

import static app.scholarai.Accounts.*;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Encapsulation: user-management and reviewer-assessment rules stay outside HTTP handlers. */
@Service
public class Administration {
  private final Store store;
  private final Accounts accounts;

  public Administration(Store store, Accounts accounts) {
    this.store = store;
    this.accounts = accounts;
  }

  private void administrator(Map<String, Object> actor) {
    var current = accounts.user(actor.get("id").toString());
    if (current == null
        || !"admin".equals(current.get("role"))
        || Boolean.TRUE.equals(current.get("disabled")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required.");
  }

  @Transactional
  public void updateUser(Map<String, Object> actor, String id, String action) {
    // Serialize administrator changes before rechecking the acting account. Concurrent admins
    // cannot disable/demote each other using identities read before the other change committed.
    store.rows("SELECT id FROM users WHERE role='admin' ORDER BY id FOR UPDATE");
    administrator(actor);
    require(!actor.get("id").equals(id), "You cannot modify your own administrator account here.");
    require(store.one("SELECT id FROM users WHERE id=? FOR UPDATE", id) != null, "User not found.");
    switch (action) {
      case "approve" -> store.jdbc().update("UPDATE users SET approved=TRUE WHERE id=?", id);
      case "disable" -> store.jdbc().update("UPDATE users SET disabled=TRUE WHERE id=?", id);
      case "enable" -> store.jdbc().update("UPDATE users SET disabled=FALSE WHERE id=?", id);
      case "role:student", "role:institution", "role:admin" -> {
        String role = action.substring(5);
        store
            .jdbc()
            .update(
                "UPDATE users SET role=?,approved=? WHERE id=?",
                role,
                !role.equals("institution"),
                id);
        if (role.equals("student")
            && store.one("SELECT user_id FROM student_profiles WHERE user_id=?", id) == null)
          store
              .jdbc()
              .update("INSERT INTO student_profiles(user_id,payload) VALUES (?,?)", id, "{}");
      }
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user action.");
    }
    store.audit(actor.get("id").toString(), "user." + action, id);
  }

  @Transactional
  public void assess(Map<String, Object> actor, String id, Map<String, String> form) {
    administrator(actor);
    var target = accounts.user(id);
    require(target != null && "student".equals(target.get("role")), "Choose a student account.");
    // The student profile editor takes this same lock before merging its JSON fields.
    require(
        store.one("SELECT user_id FROM student_profiles WHERE user_id=? FOR UPDATE", id) != null,
        "Student profile not found.");
    var profile = store.profile(id);
    String reason = text(form, "reason", 2000);
    require(reason.length() >= 10, "Record the evidence supporting this assessment.");
    for (String key : List.of("sopQuality", "recommendationStrength", "behaviourScore")) {
      double value = Scholarships.decimal(form, key, 0, 100);
      require(value == Math.floor(value), "Assessment scores must be whole numbers.");
      profile.put(key, value);
    }
    String assessment = UUID.randomUUID().toString();
    store
        .jdbc()
        .update("UPDATE student_profiles SET payload=? WHERE user_id=?", store.json(profile), id);
    store
        .jdbc()
        .update(
            "INSERT INTO"
                + " profile_assessments(id,student_id,reviewer_id,sop_quality,recommendation_strength,behaviour_score,reason)"
                + " VALUES (?,?,?,?,?,?,?)",
            assessment,
            id,
            actor.get("id"),
            ((Number) profile.get("sopQuality")).intValue(),
            ((Number) profile.get("recommendationStrength")).intValue(),
            ((Number) profile.get("behaviourScore")).intValue(),
            reason);
    store.audit(actor.get("id").toString(), "profile.assessed", assessment);
    store.notify(
        id,
        "Your supporting statement, recommendations and conduct have been assessed. Future"
            + " applications use these scores; submitted snapshots remain unchanged.");
  }
}
