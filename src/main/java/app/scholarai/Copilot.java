package app.scholarai;

import java.util.*;
import org.springframework.stereotype.Service;

/** Composition: uses the same matching and persistence collaborators as the dashboards. */
@Service
public class Copilot {
  private final Store store;
  private final Matching matching;
  private final Scholarships scholarships;

  public Copilot(Store store, Matching matching, Scholarships scholarships) {
    this.store = store;
    this.matching = matching;
    this.scholarships = scholarships;
  }

  public String answer(String userId, String question) {
    var user = store.one("SELECT role,approved FROM users WHERE id=?", userId);
    if (user == null) return "Sign in to ask about your own workspace.";
    if ("institution".equals(user.get("role"))) {
      long programs =
          store
              .jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM scholarships WHERE owner_id=?", Long.class, userId);
      long applications =
          store
              .jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM applications a JOIN scholarships s ON a.scholarship_id=s.id"
                      + " WHERE s.owner_id=?",
                  Long.class,
                  userId);
      return "Your institution has "
          + programs
          + " scholarship program(s) and "
          + applications
          + " application(s). "
          + (Boolean.TRUE.equals(user.get("approved"))
              ? "Manage your programs and review applicants in the institution workspace. Rankings"
                    + " use the score frozen at submission; record an evidence-based reason for"
                    + " every decision."
              : "Your institution is awaiting administrator approval. Publishing and application"
                    + " decisions remain locked until approval.");
    }
    if ("admin".equals(user.get("role"))) {
      long users = store.jdbc().queryForObject("SELECT COUNT(*) FROM users", Long.class);
      long applications =
          store.jdbc().queryForObject("SELECT COUNT(*) FROM applications", Long.class);
      return "The platform has "
          + users
          + " registered account(s) and "
          + applications
          + " application(s). Use platform administration to approve institutions, manage user"
          + " access, record evidence-backed profile assessments and inspect the audit trail.";
    }
    var profile = store.profile(userId);
    String query = question.toLowerCase(Locale.ROOT);
    if (query.contains("deadline")) {
      return "Check each scholarship detail page for the deadline. Expired or closed programs"
          + " cannot accept applications. Official funding opportunities must be checked"
          + " with the provider.";
    }
    if (query.contains("document")) {
      return "Upload PDF, PNG, or JPEG files up to 5 MB in your document vault. Readable PDFs are"
          + " checked for consistency with your profile. Images and scans remain pending. A"
          + " text match does not prove authenticity.";
    }
    if (query.contains("score") || query.contains("improve")) {
      return "Your current weighted score is "
          + matching.score(profile)
          + "/100. "
          + String.join(" ", matching.roadmap(profile));
    }
    if (query.contains("application") || query.contains("status")) {
      var applications = scholarships.applications("a.student_id=?", userId);
      return applications.isEmpty()
          ? "You have not submitted any applications. Complete your profile and inspect eligibility"
              + " before applying."
          : String.join(
              " ",
              applications.stream()
                  .limit(5)
                  .map(
                      a ->
                          asMap(a.get("scholarship")).get("name")
                              + ": "
                              + a.get("status")
                              + ". "
                              + a.get("reason"))
                  .toList());
    }
    if (query.contains("match") || query.contains("scholarship") || query.contains("eligible")) {
      var matches = matching.rank(profile, scholarships.catalogue(""));
      return String.join(
          " ",
          matches.stream()
              .limit(3)
              .map(
                  m ->
                      asMap(m.get("scholarship")).get("name")
                          + ": "
                          + m.get("matchScore")
                          + "% match; "
                          + (Boolean.TRUE.equals(m.get("eligible"))
                              ? "eligibility criteria met"
                              : "criteria missing: " + m.get("missingCriteria"))
                          + ". Check its deadline before applying.")
              .toList());
    }
    return "Complete your profile, browse the scholarship explorer, and inspect eligibility"
        + " explanations before applying. Your submitted score is frozen for institution"
        + " review. Use Financial Aid to estimate your funding gap.";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }
}
