package app.scholarai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ScholarshipSyncTest {
  HikariDataSource database;
  Store store;
  OfficialScholarshipSources sources;
  ScholarshipSync sync;

  @BeforeEach
  void setup() {
    database = new HikariDataSource();
    database.setJdbcUrl(
        "jdbc:h2:mem:sync-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    database.setUsername("sa");
    Flyway.configure().dataSource(database).load().migrate();
    store = new Store(new JdbcTemplate(database));
    sources = mock(OfficialScholarshipSources.class);
    sync = new ScholarshipSync(store, sources, new DataSourceTransactionManager(database), false);
  }

  @AfterEach
  void close() {
    database.close();
  }

  Map<String, Object> listing(String id, String status, String deadline) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("id", id);
    s.put("name", "Provider-published award");
    s.put("provider", "Official University");
    s.put("description", "Published eligibility and application details.");
    s.put("sourceId", "test-provider");
    s.put("sourceUrl", "https://university.example.test/scholarships/" + id);
    s.put("applicationUrl", "https://university.example.test/apply/" + id);
    s.put("deadline", deadline);
    s.put("opensAt", null);
    s.put("applicationStatus", status);
    return s;
  }

  Map<String, Object> status(boolean success, boolean complete) {
    return Map.of(
        "sourceId",
        "test-provider",
        "sourceUrl",
        "https://university.example.test/scholarships",
        "success",
        success,
        "complete",
        complete,
        "message",
        success ? "Fetched from provider" : "Provider unavailable");
  }

  void reply(List<Map<String, Object>> records, boolean success, boolean complete) {
    when(sources.fetchWithStatus())
        .thenReturn(
            new OfficialScholarshipSources.FetchResult(
                records, List.of(status(success, complete))));
  }

  @Test
  void importsOnlyRealProviderFactsAndFiltersClosedOrExpired() {
    assertThat(store.rows("SELECT * FROM scholarships")).isEmpty();
    reply(
        List.of(
            listing("open", "open", "2099-12-31"),
            listing("closed", "closed", "2099-12-31"),
            listing("expired", "open", "2020-01-01"),
            listing("future", "upcoming", null)),
        true,
        true);
    assertThat(sync.refresh().get("success")).isEqualTo(true);
    var catalogue = new Scholarships(store, new Matching()).catalogue("");
    assertThat(catalogue).extracting(s -> s.get("id")).containsExactlyInAnyOrder("open", "future");
    var s = store.scholarship("open");
    assertThat(s).doesNotContainKeys("criteria", "amount", "seats", "applicants");
    assertThat(s.get("sourceType")).isEqualTo("official");
    assertThat(s.get("applicationUrl")).isEqualTo("https://university.example.test/apply/open");
    assertThat(s.get("stale")).isEqualTo(false);
    assertThat(new Matching().match(Map.of(), s).get("matchScore")).isNull();
  }

  @Test
  void providerFailureRetainsPreviouslyFetchedDataAndMarksItStale() {
    reply(List.of(listing("cached", "open", "2099-12-31")), true, true);
    sync.refresh();
    reply(List.of(), false, false);
    assertThat(sync.refresh().get("success")).isEqualTo(false);
    assertThat(store.scholarship("cached").get("status")).isEqualTo("active");
    assertThat(store.scholarship("cached").get("stale")).isEqualTo(true);
    assertThat(
            store
                .one("SELECT status FROM scholarship_sources WHERE id='test-provider'")
                .get("status"))
        .isEqualTo("error");
  }

  @Test
  void partialRefreshKeepsOldRecordsButNewlyFetchedRecordsAreFresh() {
    reply(List.of(listing("old", "open", "2099-12-31")), true, true);
    sync.refresh();
    reply(List.of(listing("new", "open", "2099-12-31")), true, false);
    sync.refresh();
    assertThat(store.scholarship("old").get("status")).isEqualTo("active");
    assertThat(store.scholarship("old").get("stale")).isEqualTo(true);
    assertThat(store.scholarship("new").get("stale")).isEqualTo(false);
  }

  @Test
  void completeRefreshRetiresMissingListingsAndLeasePreventsDuplicateFetch() {
    reply(List.of(listing("old", "open", "2099-12-31")), true, true);
    sync.refresh();
    reply(List.of(), true, true);
    sync.refresh();
    assertThat(store.scholarship("old").get("status")).isEqualTo("closed");
    clearInvocations(sources);
    store
        .jdbc()
        .update(
            "UPDATE sync_leases SET locked_until=? WHERE id='scholarships'",
            Timestamp.from(Instant.now().plusSeconds(60)));
    assertThat(sync.refresh().get("status")).isEqualTo("already_running");
    verifyNoInteractions(sources);
  }

  @Test
  void invalidProviderUrlCannotBePublishedAndNoGuessedFallbackIsCreated() {
    var invalid = listing("bad", "open", "2099-12-31");
    invalid.put("applicationUrl", "javascript:alert(1)");
    reply(List.of(invalid), true, true);
    assertThat(sync.refresh().get("success")).isEqualTo(false);
    assertThat(store.rows("SELECT * FROM scholarships")).isEmpty();
  }

  @Test
  void coldContainerUsesRecentPersistedRefreshInsteadOfRepeatingProviderRequests() {
    reply(List.of(listing("cached", "open", "2099-12-31")), true, true);
    sync.refresh();
    clearInvocations(sources);
    var resumed =
        new ScholarshipSync(store, sources, new DataSourceTransactionManager(database), true);
    resumed.scheduled();
    verifyNoInteractions(sources);
  }
}
