package app.scholarai;

import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Single responsibility: pure scoring and eligibility calculations, with no HTTP or database code.
 * Keeping this class independent allows the same rules to serve pages, submissions and tests.
 */
@Service
public class Matching {
  static final String[] KEYS = {
    "academic",
    "financialNeed",
    "achievements",
    "research",
    "leadership",
    "projects",
    "community",
    "sop",
    "recommendation",
    "behaviour"
  };
  static final String[] LABELS = {
    "Academic Performance",
    "Financial Need",
    "Achievements",
    "Research Output",
    "Leadership",
    "Projects & Skills",
    "Community Service",
    "Statement of Purpose",
    "Recommendations",
    "Behaviour & Integrity"
  };
  static final double[] WEIGHTS = {.22, .18, .12, .10, .08, .08, .07, .08, .04, .03};

  public static double number(Map<String, ?> value, String key) {
    Object raw = value.get(key);
    return raw instanceof Number n ? n.doubleValue() : 0;
  }

  static boolean flag(Map<String, ?> value, String key) {
    return Boolean.TRUE.equals(value.get(key));
  }

  static double clamp(double n) {
    return Math.max(0, Math.min(100, n));
  }

  static List<?> items(Map<String, ?> value, String key) {
    return value.get(key) instanceof List<?> list ? list : List.of();
  }

  public List<Map<String, Object>> components(Map<String, Object> p) {
    double[] raw = {
      number(p, "cgpa") * 8 + number(p, "attendance") * .2,
      p.containsKey("familyIncome")
          ? 100 - (Math.log10(Math.max(number(p, "familyIncome"), 1000)) - 3) * 45
          : 0,
      number(p, "achievements") * 14 + number(p, "hackathons") * 8 + number(p, "sportsLevel") * 10,
      number(p, "researchPapers") * 25,
      number(p, "leadershipRoles") * 28,
      number(p, "projects") * 12 + number(p, "certifications") * 8 + items(p, "skills").size() * 4,
      number(p, "volunteerHours") / 3,
      number(p, "sopQuality"),
      number(p, "recommendationStrength"),
      number(p, "behaviourScore")
    };
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 0; i < raw.length; i++)
      result.add(
          Map.of(
              "key",
              KEYS[i],
              "label",
              LABELS[i],
              "weight",
              WEIGHTS[i],
              "raw",
              Math.round(clamp(raw[i])),
              "weighted",
              Math.round(clamp(raw[i]) * WEIGHTS[i] * 10) / 10.0,
              "detail",
              componentDetail(i, p)));
    return result;
  }

  private String componentDetail(int index, Map<String, Object> p) {
    return switch (index) {
      case 0 ->
          "CGPA "
              + number(p, "cgpa")
              + "/10 × 80% plus attendance "
              + number(p, "attendance")
              + "% × 20%; normalized to 100.";
      case 1 ->
          "Annual income USD "
              + number(p, "familyIncome")
              + "; need = 100 − (log10(max(income, 1000)) − 3) × 45, bounded 0–100.";
      case 2 ->
          number(p, "achievements")
              + " awards × 14 + "
              + number(p, "hackathons")
              + " hackathons × 8 + sports level "
              + number(p, "sportsLevel")
              + " × 10, capped at 100.";
      case 3 -> number(p, "researchPapers") + " publications × 25, capped at 100.";
      case 4 -> number(p, "leadershipRoles") + " leadership roles × 28, capped at 100.";
      case 5 ->
          number(p, "projects")
              + " projects × 12 + "
              + number(p, "certifications")
              + " certifications × 8 + "
              + items(p, "skills").size()
              + " skills × 4, capped at 100.";
      case 6 -> number(p, "volunteerHours") + " volunteer hours ÷ 3, capped at 100.";
      case 7 -> "Reviewer-assessed statement quality: " + number(p, "sopQuality") + "/100.";
      case 8 ->
          "Reviewer-assessed recommendation strength: "
              + number(p, "recommendationStrength")
              + "/100.";
      case 9 -> "Reviewer-assessed conduct: " + number(p, "behaviourScore") + "/100.";
      default -> throw new IllegalArgumentException("Unknown scoring component");
    };
  }

  public long score(Map<String, Object> p) {
    return Math.round(
        components(p).stream().mapToDouble(c -> ((Number) c.get("weighted")).doubleValue()).sum());
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> match(Map<String, Object> p, Map<String, Object> scholarship) {
    if ("official".equals(scholarship.get("sourceType"))) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("scholarship", scholarship);
      result.put("eligible", false);
      result.put("eligibilityUnknown", true);
      result.put("matchScore", null);
      result.put("winProbability", null);
      result.put(
          "missingCriteria",
          List.of("Provider-specific eligibility must be checked on the official website."));
      result.put("reasons", List.of());
      result.put(
          "fairnessNote",
          "No eligibility or selection probability has been inferred from incomplete provider"
              + " data.");
      return result;
    }
    var c = (Map<String, Object>) scholarship.get("criteria");
    List<String> missing = new ArrayList<>(), reasons = new ArrayList<>();
    if (number(p, "cgpa") < number(c, "minCgpa"))
      missing.add("CGPA below required " + c.get("minCgpa"));
    else reasons.add("CGPA clears the academic requirement");
    if (c.get("maxIncome") != null && number(p, "familyIncome") > number(c, "maxIncome"))
      missing.add("Family income exceeds the income cap");
    if (number(p, "attendance") < number(c, "minAttendance"))
      missing.add("Attendance below required " + c.get("minAttendance") + "%");
    if (flag(c, "requiresResearch") && number(p, "researchPapers") == 0)
      missing.add("Requires at least one research publication");
    if (flag(c, "requiresLeadership") && number(p, "leadershipRoles") == 0)
      missing.add("Requires demonstrated leadership");
    if (flag(c, "womenOnly") && !"female".equals(p.get("gender")))
      missing.add("Restricted to women applicants");
    if (flag(c, "minorityOnly") && !flag(p, "minority"))
      missing.add("Restricted to minority-community applicants");
    if (flag(c, "sportsRequired") && number(p, "sportsLevel") == 0)
      missing.add("Requires competitive sports participation");
    if (!items(c, "locations").isEmpty() && !items(c, "locations").contains(p.get("location")))
      missing.add("Limited to locations: " + c.get("locations"));
    if (!items(c, "fields").isEmpty() && !items(c, "fields").contains(p.get("field")))
      missing.add("Limited to fields: " + c.get("fields"));
    double affinity = score(p) * .55;
    if (flag(c, "disabilityPreferred") && flag(p, "disability")) {
      affinity += 8;
      reasons.add("Disability-inclusive preference applies");
    }
    String category = scholarship.get("category").toString();
    affinity +=
        switch (category) {
          case "Research Grant" -> Math.min(number(p, "researchPapers") * 6, 18);
          case "Sports" -> number(p, "sportsLevel") * 8;
          case "Need-based" -> ((Number) components(p).get(1).get("raw")).doubleValue() * .25;
          case "Merit" -> number(p, "cgpa") * 2.5;
          default -> 0;
        };
    if (items(p, "skills").stream()
        .anyMatch(
            s -> items(scholarship, "tags").contains(s.toString().toLowerCase(Locale.ROOT)))) {
      affinity += 6;
      reasons.add("Skill tags overlap with scholarship focus areas");
    }
    boolean eligible = missing.isEmpty();
    long matchScore = Math.round(clamp(eligible ? affinity + 12 : affinity * .5));
    double competition =
        number(scholarship, "applicants") / Math.max(number(scholarship, "seats"), 1);
    long probability =
        Math.round(
            clamp(
                eligible
                    ? matchScore / (1 + Math.log10(Math.max(competition, 1)))
                    : matchScore * .15));
    return Map.of(
        "scholarship",
        scholarship,
        "eligible",
        eligible,
        "matchScore",
        matchScore,
        "winProbability",
        probability,
        "missingCriteria",
        missing,
        "reasons",
        reasons,
        "fairnessNote",
        "Fixed auditable weights; demographic fields apply only to explicit inclusion criteria. Win"
            + " probability is a heuristic, not a calibrated prediction.");
  }

  public List<Map<String, Object>> rank(
      Map<String, Object> profile, List<Map<String, Object>> scholarships) {
    return scholarships.stream()
        .map(s -> match(profile, s))
        .sorted(
            Comparator.comparingLong(
                    (Map<String, Object> m) ->
                        (m.get("matchScore") instanceof Number n ? n.longValue() : -1))
                .reversed())
        .toList();
  }

  public List<String> roadmap(Map<String, Object> p) {
    List<String> result = new ArrayList<>();
    if (number(p, "sopQuality") < 80)
      result.add(
          "Rewrite your statement of purpose with measurable outcomes and a specific five-year"
              + " goal.");
    if (number(p, "projects") < 4)
      result.add("Publish two documented portfolio projects in your field.");
    if (number(p, "certifications") < 3) result.add("Earn a recognized industry certification.");
    if (number(p, "researchPapers") < 2)
      result.add("Join a research group and verify authorship of your publications.");
    if (number(p, "leadershipRoles") < 3)
      result.add("Take a verified leadership role in a club or community project.");
    if (number(p, "volunteerHours") < 150)
      result.add("Build a consistent record of community service.");
    result.add("Verify each program deadline and apply early to your strongest eligible matches.");
    return result;
  }

  public List<String> fraud(Map<String, Object> p) {
    List<String> signals = new ArrayList<>();
    if (number(p, "cgpa") > 9.5 && number(p, "attendance") < 60)
      signals.add("Review high CGPA with low attendance.");
    if (number(p, "familyIncome") < 8000 && number(p, "certifications") > 8)
      signals.add("Review income and certification costs; this is not evidence of fraud.");
    if (number(p, "researchPapers") > 4 && number(p, "year") <= 2)
      signals.add("Verify early publication authorship.");
    if (number(p, "previousScholarships") > 3) signals.add("Check double-funding rules.");
    if (number(p, "behaviourScore") < 40) signals.add("Conduct record needs manual review.");
    return signals;
  }

  public Map<String, Object> analyzeResume(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    var skills =
        Arrays.stream(
                "python,javascript,typescript,react,next.js,node,sql,machine learning,deep learning,data analysis,tensorflow,pytorch,aws,docker,kubernetes,figma,java,c++,research,leadership,communication,excel,tableau,power bi,nlp,statistics"
                    .split(","))
            .filter(lower::contains)
            .toList();
    Map<String, List<String>> extracted = new LinkedHashMap<>();
    extracted.put(
        "skills",
        skills.stream()
            .map(skill -> Character.toUpperCase(skill.charAt(0)) + skill.substring(1))
            .toList());
    Map<String, String> hints =
        Map.of(
            "education",
            "b.tech,bachelor,master,phd,university,college,gpa,cgpa,school",
            "projects",
            "project,built,developed,created,implemented,designed",
            "experience",
            "intern,engineer,analyst,assistant,worked,experience",
            "achievements",
            "award,winner,rank,medal,scholarship,finalist,hackathon",
            "certifications",
            "certified,certification,certificate,course,credential");
    hints.forEach(
        (key, words) ->
            extracted.put(
                key,
                text.lines()
                    .map(String::trim)
                    .filter(
                        l ->
                            l.length() > 8
                                && Arrays.stream(words.split(","))
                                    .anyMatch(l.toLowerCase(Locale.ROOT)::contains))
                    .limit(5)
                    .toList()));
    boolean numbers =
        java.util.regex.Pattern.compile(
                "\\d+%|\\d+x|\\d+ (users|students|projects|people|hours)", 2)
            .matcher(text)
            .find();
    int words = text.trim().split("\\s+").length;
    double ats =
        40
            + Math.min(skills.size() * 4, 24)
            + (extracted.get("education").isEmpty() ? 0 : 8)
            + (extracted.get("experience").isEmpty() ? 0 : 8)
            + (extracted.get("projects").isEmpty() ? 0 : 6)
            + (numbers ? 8 : 0)
            + (words > 150 && words < 900 ? 6 : 0);
    List<String> suggestions = new ArrayList<>();
    if (!numbers) suggestions.add("Quantify your impact with verifiable outcomes.");
    if (skills.size() < 6) suggestions.add("List concrete skills and tools.");
    if (extracted.get("achievements").isEmpty())
      suggestions.add("Add achievements, awards, and ranks.");
    if (extracted.get("certifications").isEmpty())
      suggestions.add("Include certifications, issuers, and dates.");
    if (words < 150) suggestions.add("Expand project and experience descriptions.");
    if (words > 900) suggestions.add("Condense your resume for faster review.");
    if (suggestions.isEmpty())
      suggestions.add("Tailor the top third of your resume to the scholarship focus.");
    return Map.of(
        "atsScore",
        Math.round(clamp(ats)),
        "resumeScore",
        Math.round(
            clamp(
                ats * .6
                    + extracted.get("achievements").size() * 5
                    + extracted.get("certifications").size() * 4)),
        "extracted",
        extracted,
        "suggestions",
        suggestions);
  }
}
