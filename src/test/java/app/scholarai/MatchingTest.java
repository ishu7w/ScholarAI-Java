package app.scholarai;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MatchingTest {
  final Matching engine = new Matching();

  @Test
  void originalWeightedModelAndBoundaryConditions() {
    assertThat(Arrays.stream(Matching.WEIGHTS).sum())
        .isEqualTo(1.0, org.assertj.core.data.Offset.offset(.00001));
    var profile = new HashMap<String, Object>();
    profile.put("cgpa", 10);
    profile.put("attendance", 100);
    profile.put("familyIncome", 1000);
    profile.put("achievements", 10);
    profile.put("researchPapers", 4);
    profile.put("leadershipRoles", 4);
    profile.put("projects", 10);
    profile.put("volunteerHours", 300);
    profile.put("sopQuality", 100);
    profile.put("recommendationStrength", 100);
    profile.put("behaviourScore", 100);
    assertThat(engine.score(profile)).isEqualTo(100);
    assertThat(engine.score(Map.of()))
        .isEqualTo(18); // The original log-need curve gives income=0 the maximum need score.
    profile.put("cgpa", 0);
    assertThat(engine.score(profile)).isEqualTo(82);
  }

  @Test
  void eligibilityAndDemographicInclusionApplyOnlyWhenExplicit() {
    Map<String, Object> criteria = new HashMap<>();
    criteria.put("minCgpa", 8);
    criteria.put("minAttendance", 75);
    criteria.put("maxIncome", 20000);
    criteria.put("fields", List.of("Engineering"));
    criteria.put("locations", List.of("India"));
    Map<String, Object> scholarship =
        new HashMap<>(
            Map.of(
                "criteria",
                criteria,
                "category",
                "Merit",
                "seats",
                10,
                "applicants",
                100,
                "tags",
                List.of()));
    var p =
        new HashMap<String, Object>(
            Map.of(
                "cgpa",
                8,
                "attendance",
                75,
                "familyIncome",
                20000,
                "field",
                "Engineering",
                "location",
                "India"));
    assertThat(engine.match(p, scholarship).get("eligible")).isEqualTo(true);
    var original = engine.match(p, scholarship);
    p.put("gender", "female");
    p.put("minority", true);
    p.put("disability", true);
    assertThat(engine.match(p, scholarship).get("matchScore"))
        .isEqualTo(original.get("matchScore"));
    criteria.put("womenOnly", true);
    p.put("gender", "male");
    assertThat(engine.match(p, scholarship).get("eligible")).isEqualTo(false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void matchesOriginalEngineAcrossReferenceFixtures() throws Exception {
    String json =
        new org.springframework.core.io.ClassPathResource("original-engine-fixtures.json")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var fixtures =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    for (var entry : (List<Map<String, Object>>) fixtures.get("profiles")) {
      var profile = (Map<String, Object>) entry.get("profile");
      assertThat(engine.score(profile)).isEqualTo(((Number) entry.get("total")).longValue());
      for (var expected : (List<Map<String, Object>>) entry.get("matches")) {
        var actual = engine.match(profile, (Map<String, Object>) expected.get("scholarship"));
        assertThat(actual.get("eligible")).isEqualTo(expected.get("eligible"));
        for (String key : List.of("matchScore", "winProbability"))
          assertThat(((Number) actual.get(key)).longValue())
              .isEqualTo(((Number) expected.get(key)).longValue());
      }
    }
    for (var resume : (List<Map<String, Object>>) fixtures.get("resumes")) {
      var expected = (Map<String, Object>) resume.get("analysis");
      var actual = engine.analyzeResume(resume.get("text").toString());
      for (String key : List.of("atsScore", "resumeScore"))
        assertThat(((Number) actual.get(key)).longValue())
            .isEqualTo(((Number) expected.get(key)).longValue());
    }
  }
}
