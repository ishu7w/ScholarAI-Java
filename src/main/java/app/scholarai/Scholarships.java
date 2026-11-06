package app.scholarai;

import static app.scholarai.Accounts.*;

import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
/**
 * Composition: this service combines a Store and a Matching engine through constructor injection.
 * It owns workflow rules; HTTP controllers only collect input and choose the next page.
 */
public class Scholarships {
  private final Store store;
  private final Matching matching;

  public Scholarships(Store store, Matching matching) {
    this.store = store;
    this.matching = matching;
  }

  public List<Map<String, Object>> catalogue(String query) {
    String q = query.toLowerCase(Locale.ROOT);
    return store.rows("SELECT * FROM scholarships WHERE status='active'").stream()
        .map(store::scholarship)
        .filter(
            s ->
                (s.get("name")
                        + " "
                        + s.get("provider")
                        + " "
                        + s.get("category")
                        + " "
                        + s.get("tags"))
                    .toLowerCase(Locale.ROOT)
                    .contains(q))
        .toList();
  }

  public Map<String, Object> find(String id) {
    var s = store.scholarship(id);
    if (s == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scholarship not found.");
    return s;
  }

  public void institution(Map<String, Object> user) {
    if (!"institution".equals(user.get("role")) || !Boolean.TRUE.equals(user.get("approved")))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "An administrator must approve your institution first.");
  }

  public void owner(Map<String, Object> user, Map<String, Object> s) {
    institution(user);
    if (!user.get("id").equals(s.get("ownerId")))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "This scholarship belongs to another institution.");
  }

  public static double decimal(Map<String, String> form, String key, double min, double max) {
    try {
      double n = Double.parseDouble(form.getOrDefault(key, "0"));
      require(Double.isFinite(n) && n >= min && n <= max, "Invalid value for " + key);
      return n;
    } catch (NumberFormatException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a number for " + key);
    }
  }

  @Transactional
  public void saveProfile(String id, Map<String, String> form) {
    var lockedProfile =
        store.one("SELECT payload FROM student_profiles WHERE user_id=? FOR UPDATE", id);
    require(lockedProfile != null, "Student profile not found.");
    var existing = store.object(lockedProfile.get("payload").toString());
    Map<String, Object> p = new LinkedHashMap<>();
    for (String key : List.of("field", "degree", "location", "gender"))
      p.put(key, text(form, key, 120));
    require(Set.of("female", "male", "other").contains(p.get("gender")), "Select a valid gender.");
    for (String key :
        List.of(
            "cgpa",
            "attendance",
            "familyIncome",
            "year",
            "achievements",
            "researchPapers",
            "hackathons",
            "sportsLevel",
            "certifications",
            "leadershipRoles",
            "volunteerHours",
            "projects",
            "previousScholarships")) {
      double max =
          switch (key) {
            case "cgpa" -> 10;
            case "attendance" -> 100;
            case "sportsLevel" -> 3;
            case "year" -> 10;
            case "familyIncome" -> 1000000000;
            default -> 10000;
          };
      double value = decimal(form, key, key.equals("year") ? 1 : 0, max);
      if (!key.equals("cgpa") && !key.equals("familyIncome"))
        require(value == Math.floor(value), "Enter a whole number for " + key);
      p.put(key, value);
    }
    p.put(
        "skills",
        Arrays.stream(text(form, "skills", 1000).split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .distinct()
            .limit(40)
            .toList());
    for (String key : List.of("minority", "disability", "firstGeneration"))
      p.put(key, form.containsKey(key));
    // Reviewer-assessed values cannot be increased by self-reporting on the student form.
    for (String key : List.of("sopQuality", "recommendationStrength", "behaviourScore"))
      p.put(key, existing.getOrDefault(key, 0));
    p.put(
        "profileCompletion",
        !p.get("field").toString().isBlank()
                && !p.get("degree").toString().isBlank()
                && !p.get("location").toString().isBlank()
            ? 100
            : 50);
    store.jdbc().update("UPDATE student_profiles SET payload=? WHERE user_id=?", store.json(p), id);
    store.audit(id, "profile.updated", id);
  }

  @Transactional
  public void apply(String student, String id) {
    // Lock the program while checking its status/deadline and creating a submission.
    var row = store.one("SELECT * FROM scholarships WHERE id=? FOR UPDATE", id);
    require(row != null, "Scholarship not found.");
    var s = store.scholarship(row);
    require("active".equals(s.get("status")), "This scholarship is closed.");
    require(
        !LocalDate.parse(s.get("deadline").toString())
            .isBefore(LocalDate.now(java.time.ZoneOffset.UTC)),
        "The application deadline has passed.");
    var p = store.profile(student);
    require(
        Matching.number(p, "profileCompletion") == 100, "Complete your profile before applying.");
    var match = matching.match(p, s);
    require(
        Boolean.TRUE.equals(match.get("eligible")),
        "Your profile does not meet the eligibility criteria.");
    var snapshot =
        Map.of(
            "match",
            match,
            "score",
            matching.score(p),
            "components",
            matching.components(p),
            "profile",
            p,
            "fraudSignals",
            matching.fraud(p));
    try {
      String application = UUID.randomUUID().toString();
      store
          .jdbc()
          .update(
              "INSERT INTO applications(id,student_id,scholarship_id,snapshot) VALUES (?,?,?,?)",
              application,
              student,
              id,
              store.json(snapshot));
      for (var document :
          store.rows("SELECT id FROM documents WHERE owner_id=? FOR UPDATE", student))
        store
            .jdbc()
            .update(
                "INSERT INTO application_documents(application_id,document_id) VALUES (?,?)",
                application,
                document.get("id"));
      s.put("applicants", Matching.number(s, "applicants") + 1);
      s.remove("status");
      s.remove("ownerId");
      store.jdbc().update("UPDATE scholarships SET payload=? WHERE id=?", store.json(s), id);
      store.audit(student, "application.submitted", application);
      store.notify(student, "Application submitted: " + s.get("name"));
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "You already applied to this scholarship.");
    }
  }

  @Transactional
  public void save(String user, String scholarship) {
    find(scholarship);
    int deleted =
        store
            .jdbc()
            .update(
                "DELETE FROM saved_scholarships WHERE student_id=? AND scholarship_id=?",
                user,
                scholarship);
    if (deleted == 0)
      try {
        store
            .jdbc()
            .update(
                "INSERT INTO saved_scholarships(student_id,scholarship_id) VALUES (?,?)",
                user,
                scholarship);
      } catch (DuplicateKeyException ignored) {
      }
  }

  @Transactional
  public String publish(Map<String, Object> user, String id, Map<String, String> form) {
    institution(user);
    Map<String, Object> existing = null;
    if (id != null) {
      // Share the submission lock so editing cannot overwrite a concurrent applicant increment.
      var row = store.one("SELECT * FROM scholarships WHERE id=? FOR UPDATE", id);
      if (row == null)
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scholarship not found.");
      existing = store.scholarship(row);
      owner(user, existing);
    } else id = "sch-" + UUID.randomUUID();
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("id", id);
    for (String key :
        List.of("name", "provider", "category", "description", "currency", "deadline"))
      s.put(key, text(form, key, key.equals("description") ? 5000 : 200));
    require(
        !s.get("name").toString().isBlank()
            && !s.get("provider").toString().isBlank()
            && !s.get("description").toString().isBlank(),
        "Name, provider, and description are required.");
    require(
        Set.of(
                "Government",
                "Private",
                "NGO",
                "University",
                "International",
                "Corporate",
                "Research Grant",
                "Need-based",
                "Merit",
                "Women",
                "Minority",
                "Sports")
            .contains(s.get("category")),
        "Choose a valid category.");
    require(
        Set.of("USD", "INR", "GBP", "EUR", "CAD", "AUD").contains(s.get("currency")),
        "Select a supported currency.");
    try {
      LocalDate.parse(s.get("deadline").toString());
    } catch (java.time.format.DateTimeParseException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid deadline.");
    }
    s.put("amount", decimal(form, "amount", 1, 100000000));
    double seats = decimal(form, "seats", 1, 1000000);
    require(seats == Math.floor(seats), "Enter a whole number of available seats.");
    s.put("seats", seats);
    s.put("applicants", existing == null ? 0 : existing.get("applicants"));
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("minCgpa", decimal(form, "minCgpa", 0, 10));
    c.put("minAttendance", decimal(form, "minAttendance", 0, 100));
    c.put(
        "maxIncome",
        form.getOrDefault("maxIncome", "").isBlank()
            ? null
            : decimal(form, "maxIncome", 0, 1000000000));
    for (String key :
        List.of(
            "requiresResearch",
            "requiresLeadership",
            "womenOnly",
            "minorityOnly",
            "sportsRequired",
            "disabilityPreferred")) c.put(key, form.containsKey(key));
    for (String key : List.of("locations", "fields"))
      c.put(
          key,
          Arrays.stream(text(form, key, 1000).split(","))
              .map(String::trim)
              .filter(v -> !v.isBlank())
              .toList());
    s.put("criteria", c);
    s.put(
        "tags",
        Arrays.stream(text(form, "tags", 1000).split(","))
            .map(String::trim)
            .filter(v -> !v.isBlank())
            .toList());
    String status = form.getOrDefault("status", "active");
    require(Set.of("active", "closed", "draft").contains(status), "Invalid status.");
    if (existing == null)
      store
          .jdbc()
          .update(
              "INSERT INTO scholarships(id,owner_id,status,payload) VALUES (?,?,?,?)",
              id,
              user.get("id"),
              status,
              store.json(s));
    else
      store
          .jdbc()
          .update(
              "UPDATE scholarships SET payload=?,status=? WHERE id=?", store.json(s), status, id);
    store.audit(user.get("id").toString(), "scholarship.saved", id);
    return id;
  }

  public List<Map<String, Object>> applications(String clause, Object user) {
    var rows =
        store.rows(
            "SELECT a.*,u.name AS student_name,s.payload AS scholarship_payload FROM applications a"
                + " JOIN users u ON a.student_id=u.id JOIN scholarships s ON a.scholarship_id=s.id"
                + " WHERE "
                + clause
                + " ORDER BY a.submitted_at DESC",
            user);
    for (var row : rows) {
      row.put("scholarship", store.object(row.get("scholarship_payload").toString()));
      row.put("ai", store.object(row.get("snapshot").toString()));
      row.put(
          "documents",
          store.rows(
              "SELECT d.id,d.name,d.kind,d.status FROM documents d JOIN application_documents ad ON"
                  + " ad.document_id=d.id WHERE ad.application_id=?",
              row.get("id")));
    }
    return rows;
  }

  @Transactional
  public void decide(Map<String, Object> user, String id, String status, String reason) {
    institution(user);
    require(Set.of("under_review", "approved", "rejected").contains(status), "Invalid decision.");
    require(
        reason.trim().length() >= 5 && reason.length() <= 2000,
        "Enter a decision reason between 5 and 2000 characters.");
    var application =
        store.one(
            "SELECT a.* FROM applications a JOIN scholarships s ON a.scholarship_id=s.id WHERE"
                + " a.id=? AND s.owner_id=? FOR UPDATE",
            id,
            user.get("id"));
    if (application == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found.");
    require(
        !Set.of("approved", "rejected").contains(application.get("status")),
        "A final decision has already been recorded.");
    store
        .jdbc()
        .update(
            "UPDATE applications SET status=?,reason=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
            status,
            reason,
            id);
    store.notify(application.get("student_id").toString(), "Application " + status + ": " + reason);
    store.audit(user.get("id").toString(), "application." + status, id);
  }
}
