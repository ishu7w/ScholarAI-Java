package app.scholarai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DATABASE_URL:jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
      "spring.datasource.username=${TEST_DATABASE_USER:sa}",
      "spring.datasource.password=${TEST_DATABASE_PASSWORD:}",
      "app.storage=target/test-documents"
    })
@AutoConfigureMockMvc
@Transactional
class ScholarAiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired Accounts accounts;
  @Autowired Store store;
  @Autowired Scholarships scholarships;
  @Autowired Matching matching;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  org.springframework.mail.javamail.JavaMailSender mail;

  String student, institution, otherInstitution, email;
  final String password = "A-test-password-123";

  @BeforeEach
  void identities() {
    email = "student-" + UUID.randomUUID() + "@example.test";
    student = accounts.register("Test Student", email, password, "student");
    institution =
        accounts.register(
            "Test Institution",
            "inst-" + UUID.randomUUID() + "@example.test",
            password,
            "institution");
    otherInstitution =
        accounts.register(
            "Other Institution",
            "other-" + UUID.randomUUID() + "@example.test",
            password,
            "institution");
  }

  SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor studentSession() {
    return user(email).roles("STUDENT");
  }

  SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor institutionSession() {
    return user(accounts.user(institution).get("email").toString()).roles("INSTITUTION");
  }

  Map<String, String> profile() {
    Map<String, String> p = new HashMap<>();
    p.put("field", "Computer Science");
    p.put("degree", "Bachelor");
    p.put("location", "India");
    p.put("gender", "female");
    p.put("cgpa", "9");
    p.put("attendance", "95");
    p.put("familyIncome", "12000");
    p.put("year", "2");
    p.put("skills", "Java, SQL");
    return p;
  }

  String program() {
    store.jdbc().update("UPDATE users SET approved=TRUE WHERE id=?", institution);
    Map<String, String> s = new HashMap<>();
    s.put("name", "Live test scholarship");
    s.put("provider", "Test Institution");
    s.put("category", "Merit");
    s.put("description", "A real test program.");
    s.put("currency", "USD");
    s.put("deadline", "2099-12-31");
    s.put("amount", "1000");
    s.put("seats", "10");
    s.put("minCgpa", "8");
    s.put("minAttendance", "75");
    return scholarships.publish(accounts.user(institution), null, s);
  }

  @Test
  void publicPagesRenderAndProtectedRoutesRequireLogin() throws Exception {
    for (String path :
        List.of(
            "/",
            "/login",
            "/register",
            "/forgot-password",
            "/reset-password?token=test",
            "/scholarships",
            "/catalogue",
            "/scholarships/sch-merit-excellence",
            "/styles.css",
            "/app.js")) mvc.perform(get(path)).andExpect(status().isOk());
    mvc.perform(get("/dashboard/student")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/dashboard/admin").with(studentSession())).andExpect(status().isForbidden());
  }

  @Test
  void studentPagesRenderWithoutSeededProfile() throws Exception {
    for (String path :
        List.of(
            "/dashboard/student",
            "/dashboard/student/profile",
            "/dashboard/student/documents",
            "/dashboard/student/saved",
            "/financial-aid",
            "/resume-analyzer"))
      mvc.perform(get(path).with(studentSession())).andExpect(status().isOk());
  }

  @Test
  void loginChecksPasswordAndProducesUsableSession() throws Exception {
    mvc.perform(formLogin().user("email", email).password("incorrect"))
        .andExpect(redirectedUrl("/login?error"));
    var result =
        mvc.perform(formLogin().user("email", email).password(password))
            .andExpect(redirectedUrl("/dashboard"))
            .andReturn();
    var session = (MockHttpSession) result.getRequest().getSession(false);
    mvc.perform(get("/dashboard/student").session(session)).andExpect(status().isOk());
    store.jdbc().update("UPDATE users SET disabled=TRUE WHERE id=?", student);
    mvc.perform(get("/dashboard/student").session(session)).andExpect(redirectedUrl("/login"));
  }

  @Test
  void csrfAndRoleEscalationAreBlocked() throws Exception {
    mvc.perform(post("/dashboard/student/profile").with(studentSession()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/register")
                .with(csrf())
                .param("name", "Attacker")
                .param("email", "attacker@example.test")
                .param("password", password)
                .param("role", "admin"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/dashboard/institution/scholarships/new").with(institutionSession()))
        .andExpect(status().isForbidden());
  }

  @Test
  void profileApplicationSnapshotAndInstitutionDecisionPersist() throws Exception {
    scholarships.saveProfile(student, profile());
    String id = program();
    mvc.perform(get("/dashboard/institution/scholarships/new").with(institutionSession()))
        .andExpect(status().isOk());
    mvc.perform(get("/dashboard/institution/scholarships/" + id).with(institutionSession()))
        .andExpect(status().isOk());
    scholarships.apply(student, id);
    var application = store.one("SELECT * FROM applications WHERE student_id=?", student);
    String original = application.get("snapshot").toString();
    var changed = profile();
    changed.put("cgpa", "10");
    scholarships.saveProfile(student, changed);
    assertThat(
            store
                .one("SELECT snapshot FROM applications WHERE id=?", application.get("id"))
                .get("snapshot"))
        .isEqualTo(original);
    store.jdbc().update("UPDATE users SET approved=TRUE WHERE id=?", otherInstitution);
    assertThatThrownBy(
            () ->
                scholarships.decide(
                    accounts.user(otherInstitution),
                    application.get("id").toString(),
                    "approved",
                    "Meets criteria"))
        .isInstanceOf(ResponseStatusException.class);
    scholarships.decide(
        accounts.user(institution),
        application.get("id").toString(),
        "approved",
        "Meets all criteria");
    assertThat(
            store
                .one("SELECT status FROM applications WHERE id=?", application.get("id"))
                .get("status"))
        .isEqualTo("approved");
    mvc.perform(get("/dashboard/institution").with(institutionSession()))
        .andExpect(status().isOk());
    assertThat(store.rows("SELECT * FROM notifications WHERE user_id=?", student)).hasSize(2);
    assertThatThrownBy(() -> scholarships.apply(student, id))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void closedExpiredIneligibleAndIncompleteSubmissionsAreBlocked() {
    String id = program();
    assertThatThrownBy(() -> scholarships.apply(student, id))
        .isInstanceOf(ResponseStatusException.class);
    var p = profile();
    p.put("cgpa", "5");
    scholarships.saveProfile(student, p);
    assertThatThrownBy(() -> scholarships.apply(student, id))
        .isInstanceOf(ResponseStatusException.class);
    scholarships.saveProfile(student, profile());
    store.jdbc().update("UPDATE scholarships SET status='closed' WHERE id=?", id);
    assertThatThrownBy(() -> scholarships.apply(student, id))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> scholarships.apply(student, "sch-merit-excellence"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void financialAidAndResumeRenderAndPersist() throws Exception {
    mvc.perform(
            post("/financial-aid")
                .with(studentSession())
                .with(csrf())
                .param("currency", "USD")
                .param("cost", "20000")
                .param("confirmed", "5000")
                .param("contribution", "3000")
                .param("checked", "aid-income"))
        .andExpect(status().is3xxRedirection());
    mvc.perform(get("/financial-aid").with(studentSession()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("12,000.00")));
    mvc.perform(
            post("/resume-analyzer")
                .with(studentSession())
                .with(csrf())
                .param(
                    "text",
                    "Bachelor at Test University. Built Java projects for 500 students. Award"
                        + " winner."))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ATS HEURISTIC")));
    mvc.perform(
            post("/chat")
                .with(studentSession())
                .with(csrf())
                .param("question", "How can I improve my score?"))
        .andExpect(status().isOk());
  }

  @Test
  void documentFormatAndOwnershipAreEnforced() throws Exception {
    var invalid =
        new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a PDF".getBytes());
    mvc.perform(
            multipart("/dashboard/student/documents")
                .file(invalid)
                .param("kind", "resume")
                .with(studentSession())
                .with(csrf()))
        .andExpect(status().isBadRequest());
    var pdf =
        new MockMultipartFile(
            "file", "resume.pdf", "application/pdf", "%PDF-1.7 test data".getBytes());
    mvc.perform(
            multipart("/dashboard/student/documents")
                .file(pdf)
                .param("kind", "resume")
                .with(studentSession())
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    var doc = store.one("SELECT * FROM documents WHERE owner_id=?", student);
    mvc.perform(get("/documents/" + doc.get("id")).with(studentSession()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/documents/" + doc.get("id")).with(institutionSession()))
        .andExpect(status().isNotFound());
    mvc.perform(post("/documents/" + doc.get("id") + "/delete").with(studentSession()).with(csrf()))
        .andExpect(status().is3xxRedirection());
  }

  @Test
  void resetTokensAreOneUseAndRejectExpiredTokens() {
    String token = "test-token";
    store
        .jdbc()
        .update(
            "INSERT INTO password_resets(token_hash,user_id,expires_at) VALUES (?,?,?)",
            Accounts.hash(token),
            student,
            java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(100)));
    accounts.reset(token, "A-new-password-123");
    assertThatThrownBy(() -> accounts.reset(token, "Another-password-123"))
        .isInstanceOf(ResponseStatusException.class);
    store
        .jdbc()
        .update(
            "INSERT INTO password_resets(token_hash,user_id,expires_at) VALUES (?,?,?)",
            Accounts.hash("expired"),
            student,
            java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
    assertThatThrownBy(() -> accounts.reset("expired", "Another-password-123"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void adminPagesRenderAndStudentsCannotUseControls() throws Exception {
    String id = UUID.randomUUID().toString();
    store
        .jdbc()
        .update(
            "INSERT INTO users(id,name,email,password,role,approved) VALUES (?,?,?,?,'admin',TRUE)",
            id,
            "Test Admin",
            "admin@example.test",
            "unused");
    for (String path :
        List.of("/dashboard/admin", "/dashboard/admin/users", "/dashboard/admin/audit"))
      mvc.perform(get(path).with(user("admin@example.test").roles("ADMIN")))
          .andExpect(status().isOk());
    mvc.perform(
            post("/dashboard/admin/users/" + student)
                .with(studentSession())
                .with(csrf())
                .param("action", "approve"))
        .andExpect(status().isForbidden());
  }

  @Test
  void recoveryEmailDeliversOneUseLinkAndRevokesOldSession() throws Exception {
    var login = mvc.perform(formLogin().user("email", email).password(password)).andReturn();
    var session = (MockHttpSession) login.getRequest().getSession(false);
    mvc.perform(post("/forgot-password").with(csrf()).param("email", email))
        .andExpect(redirectedUrl("/forgot-password?sent"));
    var captor =
        org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
    org.mockito.Mockito.verify(mail).send(captor.capture());
    var message = captor.getValue();
    assertThat(message.getTo()).containsExactly(email);
    String token = message.getText().split("token=")[1].split("\\s")[0];
    mvc.perform(
            post("/reset-password")
                .with(csrf())
                .param("token", token)
                .param("password", "A-reset-password-123"))
        .andExpect(redirectedUrl("/login?reset"));
    mvc.perform(get("/dashboard/student").session(session)).andExpect(redirectedUrl("/login"));
    mvc.perform(
            post("/reset-password")
                .with(csrf())
                .param("token", token)
                .param("password", "A-reset-password-123"))
        .andExpect(status().isBadRequest());
    mvc.perform(formLogin().user("email", email).password("A-reset-password-123"))
        .andExpect(redirectedUrl("/dashboard"));
  }

  @Test
  void loginThrottleAndCopilotRequireValidSessionAndCsrf() throws Exception {
    for (int i = 0; i < 8; i++)
      mvc.perform(formLogin().user("email", email).password("wrong"))
          .andExpect(status().is3xxRedirection());
    mvc.perform(formLogin().user("email", email).password("wrong"))
        .andExpect(status().isTooManyRequests());
    mvc.perform(
            post("/api/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"my score\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/chat")
                .with(studentSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"my score\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/chat")
                .with(studentSession())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"my score\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.reply").isString());
  }

  @Test
  void registrationNormalizesEmailAndRejectsDuplicateAndOversizedPassword() throws Exception {
    String registered = "new-" + UUID.randomUUID() + "@example.test";
    mvc.perform(
            post("/register")
                .with(csrf())
                .param("name", "New Student")
                .param("email", registered.toUpperCase(Locale.ROOT))
                .param("password", password)
                .param("role", "student"))
        .andExpect(redirectedUrl("/login?registered"));
    assertThat(accounts.byEmail(registered).get("role")).isEqualTo("student");
    assertThatThrownBy(
            () ->
                accounts.register(
                    "Invalid Password", "bad@example.test", "é".repeat(50), "student"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> accounts.register("Duplicate", registered, password, "student"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void sharedDocumentsAndAssessedSignalsRespectAuthorization() throws Exception {
    scholarships.saveProfile(student, profile());
    String program = program();
    var image =
        new MockMultipartFile(
            "file",
            "evidence.png",
            "image/png",
            new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    mvc.perform(
            multipart("/dashboard/student/documents")
                .file(image)
                .param("kind", "marksheet")
                .with(studentSession())
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    var document = store.one("SELECT * FROM documents WHERE owner_id=?", student);
    mvc.perform(get("/documents/" + document.get("id")).with(institutionSession()))
        .andExpect(status().isNotFound());
    scholarships.apply(student, program);
    mvc.perform(get("/documents/" + document.get("id")).with(institutionSession()))
        .andExpect(status().isOk());
    String frozen =
        store
            .one("SELECT snapshot FROM applications WHERE student_id=?", student)
            .get("snapshot")
            .toString();
    String admin = UUID.randomUUID().toString();
    store
        .jdbc()
        .update(
            "INSERT INTO users(id,name,email,password,role,approved) VALUES (?,?,?,?,'admin',TRUE)",
            admin,
            "Reviewer",
            "reviewer@example.test",
            "unused");
    mvc.perform(
            post("/dashboard/admin/users/" + student + "/assessment")
                .with(studentSession())
                .with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/dashboard/admin/users/" + student + "/assessment")
                .with(user("reviewer@example.test").roles("ADMIN"))
                .with(csrf())
                .param("sopQuality", "80")
                .param("recommendationStrength", "90")
                .param("behaviourScore", "95")
                .param("reason", "Verified against institutional records."))
        .andExpect(status().is3xxRedirection());
    assertThat(Matching.number(store.profile(student), "sopQuality")).isEqualTo(80);
    assertThat(
            store
                .one("SELECT snapshot FROM applications WHERE student_id=?", student)
                .get("snapshot"))
        .isEqualTo(frozen);
    var update = new org.springframework.util.LinkedMultiValueMap<String, String>();
    profile().forEach(update::add);
    update.add("sopQuality", "100");
    mvc.perform(
            post("/dashboard/student/profile").with(studentSession()).with(csrf()).params(update))
        .andExpect(status().is3xxRedirection());
    assertThat(Matching.number(store.profile(student), "sopQuality")).isEqualTo(80);
  }
}
