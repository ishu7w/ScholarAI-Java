package app.scholarai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Composition: this source adapter combines a bounded HTTP client with provider-specific parsers.
 * Only published provider facts are returned; unknown dates and amounts stay unknown.
 */
@Service
public class OfficialScholarshipSources {
  static final String MELBOURNE = "https://scholarships.unimelb.edu.au/";
  static final String BRISTOL = "https://www.bristol.ac.uk/students/support/finances/scholarships/";
  static final String CHEVENING = "https://www.chevening.org/apply/";
  private static final Set<String> HOSTS =
      Set.of(
          "scholarships.unimelb.edu.au", "www.bristol.ac.uk", "www.chevening.org", "api.cludo.com");
  private static final int MAX_BODY = 2 * 1024 * 1024;
  private static final Pattern DATE =
      Pattern.compile(
          "(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(20\\d{2})\\b");
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .version(HttpClient.Version.HTTP_1_1)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final JsonMapper json = JsonMapper.builder().build();

  public record FetchResult(
      List<Map<String, Object>> scholarships, List<Map<String, Object>> sourceStatuses) {}

  public List<Map<String, Object>> fetch() {
    return fetchWithStatus().scholarships();
  }

  public FetchResult fetchWithStatus() {
    var results = new ArrayList<Map<String, Object>>();
    var statuses = new ArrayList<Map<String, Object>>();
    long deadline = System.nanoTime() + Duration.ofSeconds(210).toNanos();
    for (String id : List.of("bristol", "chevening", "melbourne")) {
      var run = new SourceRun(id, deadline);
      try {
        switch (id) {
          case "bristol" -> bristol(run);
          case "chevening" -> chevening(run);
          case "melbourne" -> melbourne(run);
          default -> throw new IllegalStateException();
        }
        results.addAll(run.listings.values());
        statuses.add(run.status(true));
      } catch (Exception ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        // Partial successes are retained, but a failed/partial run must never delete older source
        // records.
        results.addAll(run.listings.values());
        run.complete = false;
        run.messages.add(safeFailure(ex));
        statuses.add(run.status(false));
      }
    }
    return new FetchResult(List.copyOf(results), List.copyOf(statuses));
  }

  private void bristol(SourceRun run) throws Exception {
    run.robots("https://www.bristol.ac.uk");
    String directory = run.get(BRISTOL);
    Document index = Jsoup.parse(directory, BRISTOL);
    Element app = index.selectFirst("script[src*=scholarship-search][src$=app.js]");
    if (app == null) throw new IOException("Official scholarship directory integration changed.");
    String script = run.get(app.absUrl("src"));
    var ids =
        Pattern.compile(
                "cludoCustomerId\\s*:\\s*(\\d{1,9})\\s*,\\s*cludoEngineId\\s*:\\s*(\\d{1,9})")
            .matcher(script);
    if (!ids.find())
      throw new IOException("Official scholarship search identifiers were not found.");
    String customer = ids.group(1), engine = ids.group(2);
    String publicKey =
        Base64.getEncoder()
            .encodeToString(
                (customer + ":" + engine + ":SearchKey").getBytes(StandardCharsets.UTF_8));
    var discovered = new LinkedHashMap<String, Map<String, Object>>();
    boolean all = true;
    for (int page = 1; page <= 3; page++) {
      String body =
          json.writeValueAsString(
              Map.of(
                  "query",
                  "*",
                  "page",
                  page,
                  "perPage",
                  100,
                  "postFilters",
                  Map.of(),
                  "sort",
                  Map.of("Title", "asc"),
                  "enableFacetFiltering",
                  false,
                  "enableRelatedSearches",
                  false));
      String response =
          run.post(
              "https://api.cludo.com/api/v3/" + customer + "/" + engine + "/search",
              body,
              publicKey);
      var parsed = json.readValue(response, new TypeReference<Map<String, Object>>() {});
      for (var item : parseBristolSearch(response))
        discovered.put(item.get("sourceUrl").toString(), item);
      int total =
          parsed.get("TotalDocument") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
      if (page * 100 >= total) break;
      if (page == 3) all = false;
    }
    int detailed = 0;
    var candidates = new ArrayList<>(discovered.values());
    candidates.sort(
        Comparator.comparingInt(
            item -> Set.of("open", "upcoming").contains(item.get("applicationStatus")) ? 0 : 1));
    for (var indexed : candidates) {
      String indexedText = indexed.get("indexedText").toString();
      String status =
          publishedStatus(indexedText, parseDeadline(indexedText), parseOpening(indexedText));
      // Closed/discontinued records remain closed without spending requests on irrelevant details.
      if (status.equals("closed")) {
        run.add(indexed);
        continue;
      }
      if (detailed >= 35) {
        run.complete = false;
        run.messages.add(
            "More official search records were discovered than this refresh's detail budget;"
                + " unverified fields remain unknown.");
        run.add(indexed);
        continue;
      }
      try {
        var item =
            parseBristolDetail(
                run.get(indexed.get("sourceUrl").toString()), indexed.get("sourceUrl").toString());
        if (item != null) {
          // Several awards explicitly share the University's Additional Funding form.
          String application = Objects.toString(item.get("applicationUrl"), "");
          if (application.startsWith(BRISTOL) && !application.equals(item.get("sourceUrl"))) {
            var linked = parseBristolDetail(run.get(application), application);
            if (linked != null && "direct".equals(linked.get("applicationKind"))) {
              item.put("applicationUrl", linked.get("applicationUrl"));
              item.put("applicationKind", "direct");
            }
          }
          run.add(item);
        }
        detailed++;
      } catch (Exception ex) {
        run.complete = false;
        run.messages.add("A scholarship detail could not be refreshed.");
        run.add(indexed);
      }
    }
    run.complete &= all;
  }

  private void chevening(SourceRun run) throws Exception {
    run.robots("https://www.chevening.org");
    var pages = new LinkedHashSet<String>();
    pages.add(CHEVENING);
    pages.add(CHEVENING + "?award_type=fellowships");
    int count = 0;
    while (!pages.isEmpty() && count < 14) {
      String page = pages.iterator().next();
      pages.remove(page);
      if (!run.visited.add(page)) continue;
      Document html = Jsoup.parse(run.get(page), page);
      var entries = parseCheveningIndex(html.html(), page);
      if (entries.isEmpty()) throw new IOException("Official application listing format changed.");
      entries.forEach(run::add);
      for (Element link : html.select(".pagination a[href]")) {
        String url = link.absUrl("href");
        if (url.matches(
                "https://www\\.chevening\\.org/apply/(?:page/\\d+/)?(?:\\?award_type=fellowships)?")
            && !run.visited.contains(url)) pages.add(url);
      }
      count++;
    }
    if (!pages.isEmpty()) run.complete = false;
  }

  private void melbourne(SourceRun run) throws Exception {
    run.robots("https://scholarships.unimelb.edu.au");
    Document index = Jsoup.parse(run.get(MELBOURNE), MELBOURNE);
    var urls = new LinkedHashSet<String>();
    for (Element link : index.select("a[href]"))
      if (link.absUrl("href").startsWith(MELBOURNE + "awards/")) urls.add(link.absUrl("href"));
    try {
      Document sitemap =
          Jsoup.parse(
              run.get(MELBOURNE + "sitemap.xml"), MELBOURNE, org.jsoup.parser.Parser.xmlParser());
      var discovered = new ArrayList<Element>(sitemap.select("url"));
      discovered.sort(
          Comparator.comparing((Element entry) -> entry.select("lastmod").text()).reversed());
      for (Element entry : discovered) {
        String url = entry.select("loc").text();
        if (url.startsWith(MELBOURNE + "awards/")) urls.add(url);
      }
      if (discovered.isEmpty()) run.complete = false;
    } catch (Exception ex) {
      run.complete = false;
      run.messages.add("Official sitemap could not be refreshed; directory discoveries only.");
    }
    if (urls.isEmpty())
      throw new IOException("No scholarship detail URLs discovered from the official directory.");
    int count = 0;
    for (String url : urls) {
      if (count++ >= 35) {
        run.complete = false;
        run.messages.add("Official directory exceeds this refresh's detail budget.");
        break;
      }
      try {
        var item = parseMelbourneDetail(run.get(url), url);
        if (item != null) run.add(item);
      } catch (Exception ex) {
        run.complete = false;
        run.messages.add("A scholarship detail could not be refreshed.");
      }
    }
  }

  static List<Map<String, Object>> parseBristolSearch(String raw) {
    var root =
        JsonMapper.builder().build().readValue(raw, new TypeReference<Map<String, Object>>() {});
    var result = new ArrayList<Map<String, Object>>();
    if (!(root.get("TypedDocuments") instanceof List<?> documents)) return result;
    for (Object value : documents) {
      if (!(value instanceof Map<?, ?> document)
          || !(document.get("Fields") instanceof Map<?, ?> fields)) continue;
      String source = field(fields, "Url"), title = field(fields, "Title").split("\\|")[0].trim();
      if (!source.startsWith(BRISTOL) || source.equals(BRISTOL) || title.isBlank()) continue;
      String detail = field(fields, "detail");
      if (!Pattern.compile("(?i)scholarship|bursar|studentship|grant|award|fee waiver")
              .matcher(title)
              .find()
          && !detail.toLowerCase(Locale.ROOT).contains("applications")) continue;
      var item =
          base("bristol", source, title, "University of Bristol", field(fields, "Description"));
      LocalDate deadline = parseDeadline(detail), opens = parseOpening(detail);
      dates(item, deadline, opens, publishedStatus(detail, deadline, opens));
      item.put("indexedText", detail);
      item.put("studyLevel", field(fields, "study_level"));
      item.put("eligibilityText", detail);
      result.add(item);
    }
    return result;
  }

  static Map<String, Object> parseBristolDetail(String raw, String source) {
    Document page = Jsoup.parse(raw, source);
    Element main = page.selectFirst("main");
    if (main == null || main.selectFirst("h1") == null) return null;
    String title = main.selectFirst("h1").text();
    var item =
        base(
            "bristol",
            source,
            title,
            "University of Bristol",
            main.select(".lead-in,p").stream()
                .map(Element::text)
                .filter(t -> !t.isBlank())
                .findFirst()
                .orElse(""));
    String deadlineText = section(main, "deadline");
    LocalDate deadline = firstDate(deadlineText), opens = parseOpening(main.text());
    dates(item, deadline, opens, publishedStatus(main.text(), deadline, opens));
    item.put("deadlineText", deadlineText);
    item.put("eligibilityText", section(main, "eligibility"));
    String how = section(main, "how to apply");
    item.put("applicationInstructions", how);
    for (Element link : main.select("a[href]")) {
      String label = link.text().toLowerCase(Locale.ROOT), url = link.absUrl("href");
      if ((label.contains("application form")
              || label.contains("apply for")
              || label.contains("apply to")
              || label.contains("additional funding page"))
          && safeApplicationUrl(url)) {
        item.put("applicationUrl", url);
        item.put(
            "applicationKind",
            url.startsWith("https://www.bristol.ac.uk/") ? "provider-page" : "direct");
        break;
      }
    }
    return item;
  }

  static List<Map<String, Object>> parseCheveningIndex(String raw, String source) {
    Document page = Jsoup.parse(raw, source);
    var results = new ArrayList<Map<String, Object>>();
    for (Element card : page.select(".feed__item")) {
      Element title = card.selectFirst(".feed__item__header__title a[href]");
      if (title == null) continue;
      String url = title.absUrl("href");
      if (!url.startsWith("https://www.chevening.org/scholarship/")
          && !url.startsWith("https://www.chevening.org/fellowship/")) continue;
      var item =
          base(
              "chevening",
              url,
              title.text(),
              "Chevening / UK Foreign, Commonwealth & Development Office",
              card.text());
      Element status = card.selectFirst(".feed__item__header__status");
      String text = status == null ? card.text() : status.text();
      LocalDate deadline = firstDate(text), opens = parseOpening(text);
      dates(item, deadline, opens, publishedStatus(text, deadline, opens));
      item.put("deadlineText", text);
      results.add(item);
    }
    return results;
  }

  static Map<String, Object> parseMelbourneDetail(String raw, String source) {
    Document page = Jsoup.parse(raw, source);
    Element title = page.selectFirst("h1");
    if (title == null) return null;
    var item =
        base(
            "melbourne", source, title.text(), "University of Melbourne", section(page, "summary"));
    var labels = new LinkedHashMap<String, String>();
    for (Element label : page.select("dt")) {
      Element next = label.nextElementSibling();
      if (next != null) labels.put(label.text().toLowerCase(Locale.ROOT), next.text());
    }
    // Read only labelled values; dates elsewhere on a page are not application deadlines.
    for (String label :
        List.of("applications close", "applications open", "application status", "benefit amount"))
      if (!labels.containsKey(label)) labels.put(label, labelValue(page, label));
    LocalDate deadline = firstDate(labels.getOrDefault("applications close", "")),
        opens = firstDate(labels.getOrDefault("applications open", ""));
    String status = labels.getOrDefault("application status", page.text());
    dates(item, deadline, opens, publishedStatus(status, deadline, opens));
    item.put("deadlineText", labels.getOrDefault("applications close", ""));
    item.put("amountText", labels.getOrDefault("benefit amount", ""));
    item.put("eligibilityText", section(page, "eligibility"));
    for (Element link : page.select("a[href]"))
      if (link.text().toLowerCase(Locale.ROOT).contains("submit an application")
          && safeApplicationUrl(link.absUrl("href"))) {
        item.put("applicationUrl", link.absUrl("href"));
        item.put("applicationKind", "direct");
        break;
      }
    return item;
  }

  private static String labelValue(Document page, String label) {
    for (Element element : page.getAllElements())
      if (element.ownText().trim().equalsIgnoreCase(label)) {
        Element next = element.nextElementSibling();
        if (next != null && next.text().length() < 300) return next.text();
        if (element.parent() != null) {
          String text = element.parent().text();
          if (text.length() < 300 && text.toLowerCase(Locale.ROOT).startsWith(label))
            return text.substring(label.length()).trim();
        }
      }
    return "";
  }

  private static Map<String, Object> base(
      String sourceId, String source, String name, String provider, String description) {
    var item = new LinkedHashMap<String, Object>();
    item.put("id", "official-" + sourceId + "-" + Accounts.hash(source).substring(0, 20));
    item.put("sourceId", sourceId);
    item.put("name", name);
    item.put("provider", provider);
    item.put("description", description);
    item.put("sourceUrl", source);
    item.put("applicationUrl", source);
    item.put("applicationKind", "provider-page");
    item.put("sourceType", "official");
    item.put("lastFetchedAt", Instant.now().toString());
    return item;
  }

  private static void dates(
      Map<String, Object> item, LocalDate deadline, LocalDate opens, String status) {
    item.put("deadline", deadline == null ? null : deadline.toString());
    item.put("opensAt", opens == null ? null : opens.toString());
    item.put("applicationStatus", status);
  }

  private static String field(Map<?, ?> fields, String name) {
    Object field = fields.get(name);
    return field instanceof Map<?, ?> map && map.get("Value") != null
        ? Jsoup.parse(map.get("Value").toString()).text()
        : "";
  }

  static LocalDate firstDate(String text) {
    if (text == null) return null;
    Matcher match = DATE.matcher(text);
    while (match.find())
      try {
        String month = match.group(2).substring(0, 3);
        return LocalDate.parse(
            match.group(1) + " " + month + " " + match.group(3),
            new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("d MMM uuuu")
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT));
      } catch (DateTimeException ignored) {
      }
    return null;
  }

  static LocalDate parseDeadline(String text) {
    if (text == null) return null;
    var matcher =
        Pattern.compile(
                "(?i)(?:closing date|application deadline|deadline|applications? (?:will"
                    + " )?close)[^!?]{0,220}")
            .matcher(text);
    while (matcher.find()) {
      LocalDate date = firstDate(matcher.group());
      if (date != null) return date;
    }
    return null;
  }

  static LocalDate parseOpening(String text) {
    if (text == null) return null;
    var matcher =
        Pattern.compile(
                "(?i)(?:applications? (?:will )?open|application cycle will open)[^!?]{0,100}")
            .matcher(text);
    while (matcher.find()) {
      LocalDate date = firstDate(matcher.group());
      if (date != null) return date;
    }
    return null;
  }

  static String publishedStatus(String text, LocalDate deadline, LocalDate opens) {
    String lower = text.toLowerCase(Locale.ROOT);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    if (lower.trim().equals("closed")
        || Pattern.compile(
                "(?i)closed for applications|applications?[^.]{0,70}(?:closed|not"
                    + " available)|discontinued|currently paused|will not be running")
            .matcher(text)
            .find()) return opens != null && opens.isAfter(today) ? "upcoming" : "closed";
    if (deadline != null && deadline.isBefore(today)) return "closed";
    if (opens != null && opens.isAfter(today)) return "upcoming";
    if (Pattern.compile(
            "(?i)(?:applications?[^.]{0,90}(?:now open|are open)|open for applications)")
        .matcher(text)
        .find()) return "open";
    return "unknown";
  }

  private static String section(Element root, String wanted) {
    for (Element heading : root.select("h2,h3,h4"))
      if (heading.text().toLowerCase(Locale.ROOT).contains(wanted)) {
        var values = new ArrayList<String>();
        for (Element next = heading.nextElementSibling();
            next != null && !next.tagName().matches("h[1-4]");
            next = next.nextElementSibling()) values.add(next.text());
        return String.join(" ", values);
      }
    return "";
  }

  static boolean safeApplicationUrl(String value) {
    try {
      URI uri = URI.create(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && (uri.getPort() == -1 || uri.getPort() == 443);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  static boolean allowedFetch(URI uri) {
    return safeApplicationUrl(uri.toString()) && HOSTS.contains(uri.getHost());
  }

  private static String safeFailure(Exception ex) {
    Throwable cause =
        ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
    return cause instanceof IOException
        ? cause.getMessage()
        : cause instanceof TimeoutException
            ? "Official provider request timed out."
            : "Official provider could not be refreshed.";
  }

  private final class SourceRun {
    final String id;
    final Map<String, Map<String, Object>> listings = new LinkedHashMap<>();
    final Set<String> visited = new HashSet<>();
    final List<String> messages = new ArrayList<>();
    final Map<String, String> robots = new HashMap<>();
    final Map<String, Long> lastRequest = new HashMap<>();
    final Map<String, String> cache = new HashMap<>();
    boolean complete = true;
    final long deadlineNanos;

    SourceRun(String id, long deadlineNanos) {
      this.id = id;
      this.deadlineNanos = deadlineNanos;
    }

    void add(Map<String, Object> item) {
      item.remove("indexedText");
      listings.put(item.get("id").toString(), item);
    }

    Map<String, Object> status(boolean success) {
      return Map.of(
          "sourceId",
          id,
          "sourceUrl",
          id.equals("bristol") ? BRISTOL : id.equals("chevening") ? CHEVENING : MELBOURNE,
          "success",
          success,
          "complete",
          complete,
          "count",
          listings.size(),
          "name",
          id.equals("bristol")
              ? "University of Bristol"
              : id.equals("chevening") ? "Chevening" : "University of Melbourne",
          "message",
          messages.isEmpty()
              ? "Refreshed from the official provider."
              : String.join(" ", new LinkedHashSet<>(messages)),
          "lastFetchedAt",
          Instant.now().toString());
    }

    void robots(String origin) throws Exception {
      String rules = request(URI.create(origin + "/robots.txt"), null, null);
      robots.put(URI.create(origin).getHost(), rules);
    }

    String get(String url) throws Exception {
      if (cache.containsKey(url)) return cache.get(url);
      URI uri = URI.create(url);
      if (robots.containsKey(uri.getHost())
          && !robotsAllows(robots.get(uri.getHost()), uri.getPath()))
        throw new IOException("Provider robots rules do not permit scholarship discovery.");
      String response = request(uri, null, null);
      cache.put(url, response);
      return response;
    }

    String post(String url, String body, String siteKey) throws Exception {
      return request(URI.create(url), body, siteKey);
    }

    String request(URI uri, String body, String siteKey) throws Exception {
      for (int redirect = 0; redirect < 4; redirect++) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0)
          throw new IOException("The overall official-source refresh time budget was reached.");
        if (!allowedFetch(uri))
          throw new IOException("Provider redirect left the approved HTTPS source hosts.");
        if (robots.containsKey(uri.getHost())
            && !robotsAllows(robots.get(uri.getHost()), uri.getPath()))
          throw new IOException("Provider robots rules do not permit this refresh path.");
        long wait =
            1000 - (System.currentTimeMillis() - lastRequest.getOrDefault(uri.getHost(), 0L));
        if (wait > 0) {
          if (Duration.ofMillis(wait).toNanos() >= remaining)
            throw new IOException("The overall official-source refresh time budget was reached.");
          Thread.sleep(wait);
        }
        remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0)
          throw new IOException("The overall official-source refresh time budget was reached.");
        lastRequest.put(uri.getHost(), System.currentTimeMillis());
        var builder =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofNanos(Math.min(Duration.ofSeconds(18).toNanos(), remaining)))
                .header("User-Agent", "ScholarAI/1.0 (+official-scholarship-refresh)")
                .header(
                    "Accept",
                    body == null
                        ? "text/html,application/xml;q=0.9,text/plain;q=0.8"
                        : "application/json");
        if (body == null) builder.GET();
        else
          builder
              .header("Content-Type", "application/json")
              .header("Authorization", "SiteKey " + siteKey)
              .POST(HttpRequest.BodyPublishers.ofString(body));
        var future = client.sendAsync(builder.build(), info -> new LimitedBody());
        HttpResponse<byte[]> response;
        try {
          response =
              future.get(
                  Math.min(Duration.ofSeconds(20).toNanos(), remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
          future.cancel(true);
          throw ex;
        }
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
          String location =
              response
                  .headers()
                  .firstValue("location")
                  .orElseThrow(() -> new IOException("Provider redirect lacked a location."));
          URI next = uri.resolve(location);
          if (!next.getHost().equals(uri.getHost()))
            throw new IOException("Provider redirected to a different host.");
          uri = next;
          continue;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300)
          throw new IOException("Official provider returned HTTP " + response.statusCode() + ".");
        return new String(response.body(), StandardCharsets.UTF_8);
      }
      throw new IOException("Too many provider redirects.");
    }
  }

  static boolean robotsAllows(String rules, String path) {
    var groups = new ArrayList<RobotGroup>();
    var agents = new ArrayList<String>();
    var directives = new ArrayList<String[]>();
    for (String raw : rules.split("\\R")) {
      String line = raw.split("#", 2)[0].trim();
      int colon = line.indexOf(':');
      if (colon < 0) continue;
      String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      if (key.equals("user-agent")) {
        if (!directives.isEmpty()) {
          groups.add(new RobotGroup(List.copyOf(agents), List.copyOf(directives)));
          agents.clear();
          directives.clear();
        }
        agents.add(value.toLowerCase(Locale.ROOT));
      } else if (!agents.isEmpty() && (key.equals("allow") || key.equals("disallow"))) {
        directives.add(new String[] {key, value});
      }
    }
    if (!agents.isEmpty()) groups.add(new RobotGroup(List.copyOf(agents), List.copyOf(directives)));
    int best = -1;
    var selected = new ArrayList<String[]>();
    for (var group : groups) {
      int specificity =
          group.agents().stream()
              .mapToInt(
                  agent ->
                      agent.equals("*")
                          ? 0
                          : "scholarai/1.0".startsWith(agent) ? agent.length() : -1)
              .max()
              .orElse(-1);
      if (specificity < best || specificity < 0) continue;
      if (specificity > best) {
        selected.clear();
        best = specificity;
      }
      selected.addAll(group.rules());
    }
    int allow = -1, deny = -1;
    for (var rule : selected) {
      String value = rule[1];
      if (value.isEmpty()) continue;
      boolean end = value.endsWith("$");
      String pattern = end ? value.substring(0, value.length() - 1) : value;
      String regex = "^" + Pattern.quote(pattern).replace("*", "\\E.*\\Q") + (end ? "$" : "");
      if (Pattern.compile(regex).matcher(path).find()) {
        int length = pattern.replace("*", "").length();
        if (rule[0].equals("allow")) allow = Math.max(allow, length);
        else deny = Math.max(deny, length);
      }
    }
    return allow >= deny;
  }

  private record RobotGroup(List<String> agents, List<String[]> rules) {}

  /**
   * Polymorphism: the HTTP client feeds bounded byte buffers through its BodySubscriber contract.
   */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> result = new CompletableFuture<>();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> chunks) {
      try {
        for (ByteBuffer chunk : chunks) {
          if (chunk.remaining() > MAX_BODY - bytes.size())
            throw new IOException("Official provider response exceeded the size limit.");
          byte[] part = new byte[chunk.remaining()];
          chunk.get(part);
          bytes.write(part);
        }
        subscription.request(1);
      } catch (IOException ex) {
        subscription.cancel();
        result.completeExceptionally(ex);
      }
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
