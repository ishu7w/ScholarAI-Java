package app.scholarai;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DATABASE_URL:jdbc:h2:mem:security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
      "spring.datasource.username=${TEST_DATABASE_USER:sa}",
      "spring.datasource.password=${TEST_DATABASE_PASSWORD:}",
      "app.scholarships.sync-enabled=false"
    })
class SecurityRegressionTest {
  @Autowired Store store;
  @Autowired Documents documents;
  @Autowired DocumentChecks checks;
  @Autowired Scholarships scholarships;
  @Autowired PlatformTransactionManager transactionManager;

  private String user(String role) {
    String id = UUID.randomUUID().toString();
    store
        .jdbc()
        .update(
            "INSERT INTO users(id,email,name,password,role,approved) VALUES (?,?,?,?,?,TRUE)",
            id,
            id + "@example.test",
            "Test Student",
            "unused",
            role);
    if (role.equals("student"))
      store.jdbc().update("INSERT INTO student_profiles(user_id,payload) VALUES (?,?)", id, "{}");
    return id;
  }

  private byte[] imageBytes() {
    return new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
  }

  private MockMultipartFile image() {
    return new MockMultipartFile("file", "test.png", "image/png", imageBytes());
  }

  @Test
  void rolledBackUploadDoesNotLeavePrivateBytes() {
    String student = user("student");
    var tx = new TransactionTemplate(transactionManager);
    String id =
        tx.execute(
            status -> {
              try {
                documents.upload(student, "marksheet", image());
              } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
              }
              var row = store.one("SELECT id FROM documents WHERE owner_id=?", student);
              String created = row.get("id").toString();
              assertThat(documents.content(created)).isEqualTo(imageBytes());
              assertThat(
                      store.one(
                          "SELECT document_id FROM document_contents WHERE document_id=?", created))
                  .isNotNull();
              status.setRollbackOnly();
              return created;
            });
    assertThat(store.one("SELECT id FROM documents WHERE id=?", id)).isNull();
    assertThat(store.one("SELECT document_id FROM document_contents WHERE document_id=?", id))
        .isNull();
    assertThatThrownBy(() -> documents.content(id)).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rolledBackDeletionPreservesOriginalBytesAndCommittedDeletionRemovesThem() {
    String student = user("student");
    var tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> {
          try {
            documents.upload(student, "marksheet", image());
          } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
          }
        });
    String id =
        store.one("SELECT id FROM documents WHERE owner_id=?", student).get("id").toString();
    tx.executeWithoutResult(
        status -> {
          documents.delete(student, id);
          status.setRollbackOnly();
        });
    assertThat(store.one("SELECT id FROM documents WHERE id=?", id)).isNotNull();
    assertThat(documents.content(id)).isEqualTo(imageBytes());
    tx.executeWithoutResult(
        status -> {
          documents.delete(student, id);
        });
    assertThat(store.one("SELECT id FROM documents WHERE id=?", id)).isNull();
    assertThat(store.one("SELECT document_id FROM document_contents WHERE document_id=?", id))
        .isNull();
    assertThatThrownBy(() -> documents.content(id)).isInstanceOf(ResponseStatusException.class);
  }

  private byte[] pdf(String text, int pages) throws Exception {
    try (var pdf = new PDDocument();
        var bytes = new ByteArrayOutputStream()) {
      for (int i = 0; i < pages; i++) {
        var page = new PDPage();
        pdf.addPage(page);
        try (var content = new PDPageContentStream(pdf, page)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          content.newLineAtOffset(40, 700);
          content.showText(text);
          content.endText();
        }
      }
      pdf.save(bytes);
      return bytes.toByteArray();
    }
  }

  @Test
  void identitySubstringsAndMonthlyIncomeNeverPassAsMatchingAnnualEvidence() throws Exception {
    var identity =
        checks.check("id", "application/pdf", pdf("Johnson Smith", 1), Map.of(), "John Smith");
    assertThat(identity.status()).isEqualTo("flagged");
    var income =
        checks.check(
            "income_cert",
            "application/pdf",
            pdf("Monthly salary USD 10000", 1),
            Map.of("familyIncome", 10000),
            "Test Student");
    assertThat(income.status()).isEqualTo("flagged");
    assertThat(income.flags()).anyMatch(flag -> flag.contains("annual"));
  }

  @Test
  void oversizedPdfTextStopsExtractionInsteadOfReturningTruncatedVerifiedEvidence()
      throws Exception {
    var result =
        checks.check(
            "resume", "application/pdf", pdf("A".repeat(80000), 3), Map.of(), "Test Student");
    assertThat(result.status()).isEqualTo("pending");
    assertThat(result.extracted().get("textExtracted")).isEqualTo(false);
  }

  private MockEnvironment production() {
    return new MockEnvironment()
        .withProperty("DATABASE_URL", "jdbc:postgresql://database/scholarai")
        .withProperty("DATABASE_USER", "scholarai")
        .withProperty("DATABASE_PASSWORD", "test-only-password")
        .withProperty("APP_BASE_URL", "https://scholarai.example.com")
        .withProperty("CRON_SECRET", "test-only-cron-secret-with-at-least-32-characters");
  }

  @Test
  void productionRejectsUnsafeOriginsAndMissingCronSecret() {
    assertThatCode(() -> new ProductionConfig(production())).doesNotThrowAnyException();
    assertThatCode(
            () ->
                new ProductionConfig(
                    production()
                        .withProperty("DATABASE_URL", "")
                        .withProperty("JDBC_DATABASE_URL", "jdbc:postgresql://database/scholarai")))
        .doesNotThrowAnyException();
    for (String url :
        List.of(
            "https://scholarai.example.com/path",
            "https://scholarai.example.com?query=1",
            "https://scholarai.example.com#fragment",
            "http://scholarai.example.com"))
      assertThatThrownBy(() -> new ProductionConfig(production().withProperty("APP_BASE_URL", url)))
          .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ProductionConfig(production().withProperty("CRON_SECRET", "")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void programEditsPreserveApplicantCountAndRequireWholeSeats() {
    String institution = user("institution");
    var actor = store.one("SELECT * FROM users WHERE id=?", institution);
    var form = new HashMap<String, String>();
    form.put("name", "Test Award");
    form.put("provider", "Test Institution");
    form.put("category", "Merit");
    form.put("description", "A test award.");
    form.put("applicationUrl", "https://provider.example.test/apply");
    form.put("currency", "USD");
    form.put("deadline", "2099-12-31");
    form.put("amount", "1000");
    form.put("seats", "1.5");
    assertThatThrownBy(() -> scholarships.publish(actor, null, form))
        .isInstanceOf(ResponseStatusException.class);
    form.put("seats", "2");
    String id = scholarships.publish(actor, null, form);
    var payload = store.scholarship(id);
    payload.put("applicants", 7);
    store.jdbc().update("UPDATE scholarships SET payload=? WHERE id=?", store.json(payload), id);
    form.put("name", "Renamed Award");
    scholarships.publish(actor, id, form);
    assertThat(Matching.number(store.scholarship(id), "applicants")).isEqualTo(7);
  }

  @Test
  void minimumPasswordLengthCountsUnicodeCharactersRatherThanUtf16Halves() {
    assertThatThrownBy(() -> Accounts.password("😀".repeat(6)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatCode(() -> Accounts.password("😀".repeat(12))).doesNotThrowAnyException();
  }

  @Test
  void uploadAboveFourMegabytesDoesNotPersistMetadataOrBytes() {
    String student = user("student");
    byte[] bytes = new byte[4 * 1024 * 1024 + 1];
    var file = new MockMultipartFile("file", "oversized.pdf", "application/pdf", bytes);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          try {
                            documents.upload(student, "resume", file);
                          } catch (java.io.IOException ex) {
                            throw new IllegalStateException(ex);
                          }
                        }))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            store
                .jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM documents WHERE owner_id=?", Integer.class, student))
        .isZero();
    assertThat(
            store.rows(
                "SELECT c.document_id FROM document_contents c JOIN documents d ON"
                    + " d.id=c.document_id WHERE d.owner_id=?",
                student))
        .isEmpty();
  }
}
