package app.scholarai;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/** Text comparisons reproduce the original review workflow; they cannot prove authenticity. */
@Service
public class DocumentChecks {
  // Encapsulation: callers receive a result value, without access to PDF parsing internals.
  public record Result(String status, List<String> flags, Map<String, Object> extracted) {}

  public String extract(byte[] bytes) {
    try (var pdf = Loader.loadPDF(bytes)) {
      if (pdf.getNumberOfPages() > 50) return "";
      var stripper = new PDFTextStripper();
      var output = new BoundedTextWriter(200000);
      stripper.writeText(pdf, output);
      return output.text().trim();
    } catch (IOException ex) {
      return "";
    } // Scanned, encrypted, or unreadable PDFs require human review.
  }

  public Result check(
      String kind, String media, byte[] bytes, Map<String, Object> profile, String name) {
    String text = media.equals("application/pdf") ? extract(bytes) : "";
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("characters", text.length());
    fields.put("textExtracted", !text.isBlank());
    if (text.isBlank())
      return new Result(
          "pending",
          List.of(
              "No usable PDF text was extracted within processing limits. A shorter PDF, OCR or"
                  + " manual review is required; nothing has been verified."),
          fields);
    List<String> flags = new ArrayList<>();
    if (kind.equals("income_cert")) {
      // Compare explicitly USD-labelled annual figures only: never silently compare INR with USD
      // profile income.
      var match =
          Pattern.compile(
                  "(?im)^.*(?:income|salary|earnings|per"
                      + " annum|annually).*?(?:USD|\\$)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?).*$")
              .matcher(text);
      if (!match.find())
        flags.add(
            "No explicitly USD-labelled income found. Confirm currency and annual amount"
                + " manually.");
      else {
        double income = Double.parseDouble(match.group(1).replace(",", ""));
        if (!Pattern.compile("(?i)annual|annum|yearly|per year").matcher(match.group()).find()
            || Pattern.compile("(?i)monthly|per month|weekly|per week")
                .matcher(match.group())
                .find())
          flags.add("Income period is not explicitly annual. Confirm the annual amount manually.");
        if (!Double.isFinite(income))
          flags.add("Income amount is outside the supported numeric range.");
        else fields.put("incomeFound", income);
        double declared = Matching.number(profile, "familyIncome");
        fields.put("declaredIncome", declared);
        if (declared <= 0 || Math.abs(income - declared) / declared > .2)
          flags.add(
              "Income read as USD "
                  + income
                  + ", declared USD "
                  + declared
                  + ". Review the difference.");
      }
    } else if (kind.equals("marksheet")) {
      var match =
          Pattern.compile(
                  "(?i)(?:cgpa|sgpa|gpa|cpi|grade point"
                      + " average)\\s*[:=-]?\\s*(\\d{1,2}(?:\\.\\d{1,3})?)\\s*(?:/\\s*(\\d{1,2}(?:\\.\\d{1,2})?))?")
              .matcher(text);
      if (!match.find()) flags.add("No labelled CGPA found. Review the academic result manually.");
      else {
        double raw = Double.parseDouble(match.group(1));
        double scale = match.group(2) == null ? 10 : Double.parseDouble(match.group(2));
        fields.put(
            "scaleAssumption",
            match.group(2) == null ? "Assumed out of 10" : "Printed scale " + scale);
        double normalized = scale > 0 ? raw / scale * 10 : Double.NaN;
        fields.put("declaredCgpa", Matching.number(profile, "cgpa"));
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 10)
          flags.add("Academic score has an invalid scale.");
        else {
          fields.put("cgpaFound", normalized);
          if (Math.abs(normalized - Matching.number(profile, "cgpa")) > .2)
            flags.add(
                "CGPA read as "
                    + normalized
                    + "/10; declared "
                    + Matching.number(profile, "cgpa")
                    + "/10. Review the difference.");
        }
      }
    } else if (kind.equals("id")) {
      var tokens =
          Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"))
              .filter(
                  t -> t.length() >= 3 && !Set.of("mrs", "shri", "smt", "the", "and").contains(t))
              .toList();
      var missing =
          tokens.stream()
              .filter(
                  t ->
                      !Pattern.compile(
                              "(?<!\\p{L})" + Pattern.quote(t) + "(?!\\p{L})",
                              Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                          .matcher(text)
                          .find())
              .toList();
      fields.put("missingNameTokens", missing);
      if (tokens.isEmpty() || !missing.isEmpty())
        flags.add("Profile name could not be fully matched to this document.");
    }
    fields.put(
        "meaning",
        "Automated text consistency only. This does not verify issuer, identity, or authenticity.");
    return new Result(flags.isEmpty() ? "verified" : "flagged", flags, fields);
  }

  /**
   * Inheritance: enforce an output limit while PDFBox writes, before accumulating an oversized
   * string.
   */
  private static final class BoundedTextWriter extends Writer {
    private final StringBuilder value = new StringBuilder();
    private final int limit;

    BoundedTextWriter(int limit) {
      this.limit = limit;
    }

    @Override
    public void write(char[] text, int offset, int length) throws IOException {
      if (length > limit - value.length())
        throw new IOException("PDF text exceeds the extraction limit.");
      value.append(text, offset, length);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    String text() {
      return value.toString();
    }
  }
}
