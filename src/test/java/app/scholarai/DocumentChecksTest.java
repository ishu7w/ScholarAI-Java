package app.scholarai;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;

class DocumentChecksTest {
  final DocumentChecks checks = new DocumentChecks();

  static byte[] pdf(String text) throws Exception {
    try (var pdf = new PDDocument();
        var bytes = new ByteArrayOutputStream()) {
      var page = new PDPage();
      pdf.addPage(page);
      try (var content = new PDPageContentStream(pdf, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(40, 700);
        content.showText(text);
        content.endText();
      }
      pdf.save(bytes);
      return bytes.toByteArray();
    }
  }

  @Test
  void readableTextAndAcademicDifferenceAreReported() throws Exception {
    var bytes = pdf("Test Student CGPA: 8.5/10");
    assertThat(checks.extract(bytes)).contains("CGPA: 8.5/10");
    assertThat(
            checks
                .check("marksheet", "application/pdf", bytes, Map.of("cgpa", 8.5), "Test Student")
                .status())
        .isEqualTo("verified");
    var mismatch =
        checks.check("marksheet", "application/pdf", bytes, Map.of("cgpa", 9.5), "Test Student");
    assertThat(mismatch.status()).isEqualTo("flagged");
    assertThat(mismatch.flags()).anyMatch(f -> f.contains("9.5"));
  }

  @Test
  void unreadableDataNeverPassesVerification() {
    assertThat(
            checks
                .check("resume", "application/pdf", new byte[] {1, 2, 3}, Map.of(), "Test")
                .status())
        .isEqualTo("pending");
    assertThat(checks.check("id", "image/jpeg", new byte[] {1, 2, 3}, Map.of(), "Test").status())
        .isEqualTo("pending");
  }

  @Test
  void incomeCurrencyAndIdentityMustBeExplicit() throws Exception {
    assertThat(
            checks
                .check(
                    "income_cert",
                    "application/pdf",
                    pdf("Annual income USD 12000"),
                    Map.of("familyIncome", 12000),
                    "Test")
                .status())
        .isEqualTo("verified");
    assertThat(
            checks
                .check(
                    "income_cert",
                    "application/pdf",
                    pdf("Annual income INR 12000"),
                    Map.of("familyIncome", 12000),
                    "Test")
                .status())
        .isEqualTo("flagged");
    assertThat(
            checks
                .check(
                    "id",
                    "application/pdf",
                    pdf("Student name: Test Student"),
                    Map.of(),
                    "Other Person")
                .status())
        .isEqualTo("flagged");
  }
}
