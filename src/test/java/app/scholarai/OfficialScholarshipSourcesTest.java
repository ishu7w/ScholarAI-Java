package app.scholarai;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class OfficialScholarshipSourcesTest {
  private String fixture(String name) throws Exception {
    return new ClassPathResource("official-sources/" + name)
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void discoversPreviouslyUnconfiguredOfficialProgramsFromSearchIndex() throws Exception {
    var entries = OfficialScholarshipSources.parseBristolSearch(fixture("bristol-search.json"));
    assertThat(entries).hasSize(3);
    var think =
        entries.stream()
            .filter(e -> e.get("sourceUrl").toString().contains("think-big-scholarships"))
            .findFirst()
            .orElseThrow();
    assertThat(think.get("deadline")).isEqualTo("2027-03-19");
    assertThat(think.get("applicationStatus")).isEqualTo("open");
    assertThat(think).doesNotContainKeys("seats", "criteria", "amount", "applicants", "matchScore");
    assertThat(
            entries.stream()
                .filter(e -> e.get("sourceUrl").toString().contains("access-postgrad"))
                .findFirst()
                .orElseThrow()
                .get("applicationStatus"))
        .isEqualTo("closed");
    var unite =
        entries.stream()
            .filter(e -> e.get("sourceUrl").toString().contains("/unite/"))
            .findFirst()
            .orElseThrow();
    assertThat(unite.get("opensAt")).isEqualTo("2027-01-01");
    assertThat(unite.get("deadline")).isNull();
  }

  @Test
  void extractsProviderPublishedFormAndDeadlineWithoutOfferAcceptanceConfusion() throws Exception {
    var entry =
        OfficialScholarshipSources.parseBristolDetail(
            fixture("bristol-think-big.html"),
            OfficialScholarshipSources.BRISTOL + "think-big-scholarships/");
    assertThat(entry.get("name")).isEqualTo("Think Big Scholarships for international students");
    assertThat(entry.get("deadline")).isEqualTo("2027-03-19");
    assertThat(entry.get("applicationUrl").toString())
        .isEqualTo("https://internationalscholarships.fillout.com/thinkbig2026");
    assertThat(entry.get("applicationKind")).isEqualTo("direct");
    var additional =
        OfficialScholarshipSources.parseBristolDetail(
            fixture("bristol-additional.html"),
            OfficialScholarshipSources.BRISTOL + "additional-funding/");
    assertThat(additional.get("deadline")).isEqualTo("2027-05-09");
    assertThat(additional.get("sourceType")).isEqualTo("official");
  }

  @Test
  void closedCheveningCallsNeverBecomeFutureOpenByAddingAYear() throws Exception {
    var calls =
        OfficialScholarshipSources.parseCheveningIndex(
            fixture("chevening-listing.html"), OfficialScholarshipSources.CHEVENING);
    assertThat(calls)
        .isNotEmpty()
        .allSatisfy(
            c -> {
              assertThat(c.get("applicationStatus")).isEqualTo("closed");
              assertThat(c.get("deadline")).isNull();
              assertThat(c.get("applicationKind")).isEqualTo("provider-page");
            });
  }

  @Test
  void unknownPartialOrYearlessDatesStayUnknown() {
    assertThat(OfficialScholarshipSources.firstDate("opens January 2027")).isNull();
    assertThat(OfficialScholarshipSources.firstDate("15 March")).isNull();
    assertThat(OfficialScholarshipSources.firstDate("31 February 2027")).isNull();
    assertThat(
            OfficialScholarshipSources.parseDeadline("The closing date is 11.59 pm on 9 May 2027."))
        .isEqualTo(LocalDate.of(2027, 5, 9));
    assertThat(OfficialScholarshipSources.publishedStatus("Deadline depends on course", null, null))
        .isEqualTo("unknown");
    assertThat(
            OfficialScholarshipSources.publishedStatus(
                "Applications will open", null, LocalDate.now(ZoneOffset.UTC).plusDays(1)))
        .isEqualTo("upcoming");
  }

  @Test
  void outboundFetchIsRestrictedAndRobotsRulesAreRespected() {
    assertThat(
            OfficialScholarshipSources.allowedFetch(
                URI.create("https://www.bristol.ac.uk/students/support/finances/scholarships/")))
        .isTrue();
    for (String url :
        List.of(
            "http://www.bristol.ac.uk/",
            "https://127.0.0.1/",
            "https://www.bristol.ac.uk.evil.test/",
            "https://user:pass@www.bristol.ac.uk/",
            "https://www.bristol.ac.uk:8443/"))
      assertThat(OfficialScholarshipSources.allowedFetch(URI.create(url))).isFalse();
    String rules = "User-agent: *\nDisallow: /private/\nAllow: /private/public\n";
    assertThat(OfficialScholarshipSources.robotsAllows(rules, "/private/student")).isFalse();
    assertThat(OfficialScholarshipSources.robotsAllows(rules, "/private/public/info")).isTrue();
  }
}
