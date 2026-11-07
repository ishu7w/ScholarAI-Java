package app.scholarai;

import static app.scholarai.Accounts.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Composition: the controller delegates work to small services rather than inheriting their
 * behavior. Spring injects the collaborators through the constructor, making dependencies explicit
 * and testable.
 */
@Controller
public class WebController {
  private final Store store;
  private final Accounts accounts;
  private final Scholarships programs;
  private final Matching matching;
  private final Documents documents;
  private final DocumentChecks documentChecks;
  private final Copilot copilot;
  private final Administration administration;
  private final FinancialAid financialAid;
  private final ScholarshipSync scholarshipSync;
  private final String cronSecret;

  public WebController(
      Store store,
      Accounts accounts,
      Scholarships programs,
      Matching matching,
      Documents documents,
      DocumentChecks documentChecks,
      Copilot copilot,
      Administration administration,
      FinancialAid financialAid,
      ScholarshipSync scholarshipSync,
      @org.springframework.beans.factory.annotation.Value("${app.cron-secret:}")
          String cronSecret) {
    this.store = store;
    this.accounts = accounts;
    this.programs = programs;
    this.matching = matching;
    this.documents = documents;
    this.documentChecks = documentChecks;
    this.copilot = copilot;
    this.administration = administration;
    this.financialAid = financialAid;
    this.scholarshipSync = scholarshipSync;
    this.cronSecret = cronSecret;
  }

  @ModelAttribute
  public void shared(Model model, Principal principal) {
    model.addAttribute("title", "ScholarAI");
    model.addAttribute(
        "sourceStatuses",
        store
            .rows(
                "SELECT id AS source_id,name,source_url,status,message,record_count AS"
                    + " count,last_attempt_at AS last_fetched_at FROM scholarship_sources ORDER BY"
                    + " name")
            .stream()
            .map(
                row -> {
                  row.put("sourceId", row.get("source_id"));
                  row.put("sourceUrl", row.get("source_url"));
                  row.put("lastFetchedAt", row.get("last_fetched_at"));
                  return row;
                })
            .toList());
    if (principal != null) {
      var user = accounts.byEmail(principal.getName());
      model.addAttribute("user", user);
      if (user != null)
        model.addAttribute(
            "notifications",
            store.rows(
                "SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 20",
                user.get("id")));
    }
  }

  private Map<String, Object> user(Principal principal) {
    if (principal == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first.");
    return accounts.byEmail(principal.getName());
  }

  private String uid(Principal p) {
    return user(p).get("id").toString();
  }

  private String page(Model model, String title, String view) {
    model.addAttribute("title", title);
    return view;
  }

  @GetMapping("/health")
  @ResponseBody
  Map<String, String> health() {
    store.jdbc().queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "UP");
  }

  @GetMapping("/api/internal/scholarships/refresh")
  @ResponseBody
  ResponseEntity<Map<String, Object>> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    if (cronSecret.isBlank())
      return ResponseEntity.status(503)
          .body(Map.of("success", false, "error", "Refresh authentication is not configured."));
    if (!java.security.MessageDigest.isEqual(
        ("Bearer " + cronSecret).getBytes(java.nio.charset.StandardCharsets.UTF_8),
        authorization.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      return ResponseEntity.status(401).body(Map.of("success", false, "error", "Unauthorized"));
    var result = scholarshipSync.refresh();
    return ResponseEntity.status(Boolean.TRUE.equals(result.get("success")) ? 200 : 502)
        .body(result);
  }

  @PostMapping("/dashboard/admin/scholarships/refresh")
  String manualRefresh(RedirectAttributes redirect) {
    var result = scholarshipSync.refresh();
    redirect.addFlashAttribute("message", result.get("status"));
    return "redirect:/dashboard/admin";
  }

  @GetMapping("/")
  String home(Model model) {
    model.addAttribute("count", programs.catalogue("").size());
    model.addAttribute(
        "platformStats",
        Map.of(
            "activeScholarships", programs.catalogue("").size(),
            "approvedInstitutions",
                store
                    .jdbc()
                    .queryForObject(
                        "SELECT COUNT(*) FROM users WHERE role='institution' AND approved=TRUE AND"
                            + " disabled=FALSE",
                        Long.class),
            "registeredStudents",
                store
                    .jdbc()
                    .queryForObject(
                        "SELECT COUNT(*) FROM users WHERE role='student' AND disabled=FALSE",
                        Long.class),
            "decisionsRecorded",
                store
                    .jdbc()
                    .queryForObject(
                        "SELECT COUNT(*) FROM applications WHERE status IN ('approved','rejected')",
                        Long.class)));
    return page(model, "Every decision, explained.", "home");
  }

  @GetMapping({"/login", "/register"})
  String auth(HttpServletRequest request, Model model) {
    String mode = request.getRequestURI().substring(request.getContextPath().length() + 1);
    model.addAttribute("mode", mode);
    return page(model, mode.replace('-', ' '), "auth");
  }

  @PostMapping("/register")
  String register(@RequestParam Map<String, String> form, HttpServletRequest request) {
    require(
        accounts.allow("register:" + request.getRemoteAddr(), 10, Duration.ofHours(1)),
        "Too many registrations. Try again later.");
    accounts.register(
        text(form, "name", 120),
        text(form, "email", 254),
        form.getOrDefault("password", ""),
        text(form, "role", 20));
    return "redirect:/login?registered";
  }

  @GetMapping("/dashboard")
  String dashboard(Principal principal) {
    return "redirect:/dashboard/" + user(principal).get("role");
  }

  @GetMapping("/dashboard/student")
  String student(Model model, Principal principal) {
    var p = store.profile(uid(principal));
    model.addAttribute("profile", p);
    model.addAttribute("score", matching.score(p));
    model.addAttribute("components", matching.components(p));
    model.addAttribute("roadmap", matching.roadmap(p));
    model.addAttribute(
        "matches", matching.rank(p, programs.catalogue("")).stream().limit(4).toList());
    model.addAttribute("applications", programs.applications("a.student_id=?", uid(principal)));
    return page(model, "Your opportunity starts here.", "student");
  }

  @GetMapping("/dashboard/student/profile")
  String profile(Model model, Principal p) {
    model.addAttribute("profile", store.profile(uid(p)));
    return page(model, "Your academic profile", "profile");
  }

  @PostMapping("/dashboard/student/profile")
  String profileSave(
      @RequestParam Map<String, String> form, Principal p, RedirectAttributes redirect) {
    programs.saveProfile(uid(p), form);
    redirect.addFlashAttribute(
        "message", "Profile saved. Matches now use your updated information.");
    return "redirect:/dashboard/student";
  }

  @GetMapping({"/catalogue", "/scholarships"})
  String catalogue(@RequestParam(defaultValue = "") String q, Model model, Principal principal) {
    var list = programs.catalogue(q);
    model.addAttribute("q", q);
    model.addAttribute("scholarships", list);
    if (principal != null && "student".equals(user(principal).get("role")))
      model.addAttribute("matches", matching.rank(store.profile(uid(principal)), list));
    return page(model, "Scholarship explorer", "catalogue");
  }

  @GetMapping("/scholarships/{id}")
  String detail(@PathVariable String id, Model model, Principal principal) {
    var s = programs.find(id);
    model.addAttribute("scholarship", s);
    if (principal != null && "student".equals(user(principal).get("role")))
      model.addAttribute("match", matching.match(store.profile(uid(principal)), s));
    return page(model, s.get("name").toString(), "detail");
  }

  @GetMapping("/scholarships/{id}/apply")
  ResponseEntity<Void> officialApply(@PathVariable String id) {
    var scholarship = programs.find(id);
    Object url = scholarship.get("applicationUrl");
    require(url != null, "The provider has not published an application link yet.");
    return ResponseEntity.status(302)
        .location(java.net.URI.create(Scholarships.officialUrl(url.toString())))
        .build();
  }

  @PostMapping("/scholarships/{id}/apply")
  String apply(@PathVariable String id, Principal p, RedirectAttributes redirect) {
    programs.apply(uid(p), id);
    redirect.addFlashAttribute(
        "message", "Application submitted. Your score snapshot is frozen for review.");
    return "redirect:/dashboard/student";
  }

  @PostMapping("/scholarships/{id}/save")
  String save(@PathVariable String id, Principal p) {
    programs.save(uid(p), id);
    return "redirect:/dashboard/student/saved";
  }

  @GetMapping("/dashboard/student/saved")
  String saved(Model model, Principal p) {
    model.addAttribute("q", "");
    model.addAttribute(
        "scholarships",
        store
            .rows(
                "SELECT s.* FROM scholarships s JOIN saved_scholarships saved ON"
                    + " saved.scholarship_id=s.id WHERE saved.student_id=?",
                uid(p))
            .stream()
            .map(store::scholarship)
            .toList());
    return page(model, "Saved scholarships", "catalogue");
  }

  @GetMapping("/dashboard/institution")
  String institution(Model model, Principal p) {
    var u = user(p);
    model.addAttribute(
        "programs",
        store.rows("SELECT * FROM scholarships WHERE owner_id=?", u.get("id")).stream()
            .map(store::scholarship)
            .toList());
    var applications = programs.applications("s.owner_id=?", u.get("id"));
    applications.sort(
        Comparator.comparingDouble(
                (Map<String, Object> a) -> Matching.number(cast(a.get("ai")), "score"))
            .reversed());
    model.addAttribute("applications", applications);
    return page(model, "Institution workspace", "institution");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> cast(Object map) {
    return (Map<String, Object>) map;
  }

  @GetMapping("/dashboard/institution/scholarships")
  String institutionPrograms() {
    return "redirect:/dashboard/institution";
  }

  @GetMapping({
    "/dashboard/institution/scholarships/new",
    "/dashboard/institution/scholarships/{id}"
  })
  String programForm(@PathVariable(required = false) String id, Model model, Principal p) {
    programs.institution(user(p));
    var s = id == null ? new LinkedHashMap<String, Object>() : programs.find(id);
    if (id != null) programs.owner(user(p), s);
    model.addAttribute("scholarship", s);
    model.addAttribute("criteria", s.getOrDefault("criteria", Map.of()));
    return page(model, id == null ? "Publish a scholarship" : "Edit scholarship", "program-form");
  }

  @PostMapping({
    "/dashboard/institution/scholarships/new",
    "/dashboard/institution/scholarships/{id}"
  })
  String programSave(
      @PathVariable(required = false) String id,
      @RequestParam Map<String, String> form,
      Principal p) {
    programs.publish(user(p), id, form);
    return "redirect:/dashboard/institution";
  }

  @PostMapping("/applications/{id}/decision")
  String decision(
      @PathVariable String id,
      @RequestParam String status,
      @RequestParam String reason,
      Principal p) {
    programs.decide(user(p), id, status, reason);
    return "redirect:/dashboard/institution";
  }

  @GetMapping({"/dashboard/admin", "/dashboard/admin/users", "/dashboard/admin/audit"})
  String admin(Model model) {
    model.addAttribute(
        "users",
        store.rows(
            "SELECT id,name,email,role,approved,disabled FROM users ORDER BY created_at DESC"));
    model.addAttribute(
        "audit",
        store.rows(
            "SELECT a.*,u.name FROM audit_log a LEFT JOIN users u ON a.actor_id=u.id ORDER BY"
                + " a.created_at DESC LIMIT 200"));
    model.addAttribute(
        "applicationCount",
        store.jdbc().queryForObject("SELECT COUNT(*) FROM applications", Long.class));
    model.addAttribute("components", matching.components(Map.of()));
    return page(model, "Platform administration", "admin");
  }

  @PostMapping("/dashboard/admin/users/{id}")
  String adminUpdate(@PathVariable String id, @RequestParam String action, Principal principal) {
    administration.updateUser(user(principal), id, action);
    return "redirect:/dashboard/admin/users";
  }

  @PostMapping("/dashboard/admin/users/{id}/assessment")
  String assess(
      @PathVariable String id, @RequestParam Map<String, String> form, Principal principal) {
    administration.assess(user(principal), id, form);
    return "redirect:/dashboard/admin/users";
  }

  @GetMapping("/dashboard/student/documents")
  String documents(Model model, Principal p) {
    var rows =
        store.rows("SELECT * FROM documents WHERE owner_id=? ORDER BY created_at DESC", uid(p));
    for (var d : rows) d.put("review", store.object(d.get("checks").toString()));
    model.addAttribute("documents", rows);
    return page(model, "Document vault", "documents");
  }

  @PostMapping("/dashboard/student/documents")
  String upload(@RequestParam String kind, @RequestParam MultipartFile file, Principal p)
      throws IOException {
    documents.upload(uid(p), kind, file);
    return "redirect:/dashboard/student/documents";
  }

  @GetMapping("/documents/{id}")
  ResponseEntity<byte[]> download(@PathVariable String id, Principal p) {
    var row = documents.accessible(user(p), id);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(row.get("name").toString(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(MediaType.parseMediaType(row.get("media_type").toString()))
        .body(documents.content(id));
  }

  @PostMapping("/documents/{id}/delete")
  String remove(@PathVariable String id, Principal p) throws IOException {
    documents.delete(uid(p), id);
    return "redirect:/dashboard/student/documents";
  }

  @PostMapping("/notifications/{id}/read")
  String notification(@PathVariable String id, Principal p) {
    store
        .jdbc()
        .update("UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?", id, uid(p));
    return "redirect:/dashboard";
  }

  @GetMapping("/resume-analyzer")
  String resume(Model model) {
    return page(model, "Resume intelligence", "resume");
  }

  @PostMapping("/resume-analyzer")
  String analyze(@RequestParam String text, Model model, Principal p) {
    require(
        text.length() >= 30 && text.length() <= 50000, "Paste between 30 and 50,000 characters.");
    model.addAttribute("text", text);
    model.addAttribute("analysis", matching.analyzeResume(text));
    return page(model, "Resume intelligence", "resume");
  }

  @PostMapping("/resume-analyzer/upload")
  String analyzeFile(@RequestParam MultipartFile file, Model model, Principal p)
      throws IOException {
    require(file.getSize() > 0 && file.getSize() <= 4 * 1024 * 1024, "Upload a PDF up to 4 MB.");
    String text = documentChecks.extract(file.getBytes());
    require(
        text.length() >= 30,
        "No readable resume text found. Paste your resume text or upload a text-based PDF.");
    model.addAttribute("analysis", matching.analyzeResume(text));
    model.addAttribute("text", text);
    return page(model, "Resume intelligence", "resume");
  }

  @PostMapping("/chat")
  String chat(@RequestParam String question, Model model, Principal p) {
    require(
        question.length() >= 2 && question.length() <= 1000,
        "Ask a question between 2 and 1000 characters.");
    String answer = copilot.answer(uid(p), question);
    model.addAttribute("answer", answer);
    model.addAttribute("question", question);
    return page(model, "ScholarAI copilot", "chat");
  }

  @PostMapping("/api/chat")
  @ResponseBody
  ResponseEntity<Map<String, Object>> apiChat(
      @RequestBody Map<String, Object> body, Principal principal) {
    Object raw = body.get("message");
    if (!(raw instanceof String question)
        || question.trim().length() < 2
        || question.length() > 1000)
      return ResponseEntity.badRequest()
          .body(Map.of("success", false, "error", "Ask a question between 2 and 1000 characters."));
    if (!accounts.allow("chat:" + uid(principal), 30, Duration.ofMinutes(1)))
      return ResponseEntity.status(429)
          .body(Map.of("success", false, "error", "Too many questions. Try again in a minute."));
    return ResponseEntity.ok(
        Map.of("success", true, "reply", copilot.answer(uid(principal), question)));
  }

  @GetMapping("/financial-aid")
  String aid(Model model, Principal p) throws IOException {
    var plan = financialAid.load(uid(p));
    aidModel(model, plan);
    return page(model, "Financial aid planner", "aid");
  }

  void aidModel(Model model, Map<String, Object> plan) throws IOException {
    model.addAttribute("plan", plan);
    model.addAttribute("gap", financialAid.gap(plan));
    model.addAttribute(
        "funding",
        programs.catalogue("").stream()
            .filter(s -> "official".equals(s.get("sourceType")))
            .toList());
    model.addAttribute("checklist", store.list(Bootstrap.read("checklist.json")));
  }

  @PostMapping("/financial-aid")
  String aidSave(
      @RequestParam Map<String, String> form,
      @RequestParam(required = false) List<String> checked,
      Principal principal)
      throws IOException {
    financialAid.save(uid(principal), form, checked);
    return "redirect:/financial-aid";
  }
}
