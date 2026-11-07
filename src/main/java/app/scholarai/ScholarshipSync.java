package app.scholarai;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composition: source adapters fetch facts; this service persists them and coordinates refreshes.
 */
@Service
public class ScholarshipSync {
  private final Store store;
  private final OfficialScholarshipSources sources;
  private final TransactionTemplate transaction;
  private final boolean enabled;

  public ScholarshipSync(
      Store store,
      OfficialScholarshipSources sources,
      org.springframework.transaction.PlatformTransactionManager manager,
      @Value("${app.scholarships.sync-enabled:true}") boolean enabled) {
    this.store = store;
    this.sources = sources;
    this.transaction = new TransactionTemplate(manager);
    this.enabled = enabled;
  }

  @Scheduled(
      initialDelayString = "${app.scholarships.initial-delay-ms:1500}",
      fixedDelayString = "${app.scholarships.refresh-delay-ms:21600000}")
  public void scheduled() {
    if (!enabled) return;
    Timestamp previous =
        store
            .jdbc()
            .queryForObject(
                "SELECT MAX(last_attempt_at) FROM scholarship_sources", Timestamp.class);
    // A cold container should serve its durable cache, not scrape every provider again.
    if (previous == null || previous.toInstant().isBefore(Instant.now().minusSeconds(21600)))
      refresh();
  }

  public Map<String, Object> refresh() {
    Instant now = Instant.now();
    if (store
            .jdbc()
            .update(
                "UPDATE sync_leases SET locked_until=? WHERE id='scholarships' AND locked_until <"
                    + " ?",
                Timestamp.from(now.plusSeconds(600)),
                Timestamp.from(now))
        == 0) return Map.of("success", true, "status", "already_running");
    try {
      var fetched = sources.fetchWithStatus();
      int[] stored = {0};
      for (var status : fetched.sourceStatuses()) {
        String sourceId = status.get("sourceId").toString();
        var entries =
            fetched.scholarships().stream()
                .filter(s -> sourceId.equals(s.get("sourceId")))
                .toList();
        transaction.executeWithoutResult(
            tx -> {
              boolean success = Boolean.TRUE.equals(status.get("success"));
              for (var entry : entries) {
                upsert(entry, now);
                stored[0]++;
              }
              var currentIds =
                  entries.stream()
                      .map(s -> s.get("id").toString())
                      .collect(java.util.stream.Collectors.toSet());
              for (var old :
                  store.rows(
                      "SELECT id FROM scholarships WHERE source_id=? AND source_type='official'",
                      sourceId)) {
                if (!currentIds.contains(old.get("id").toString())) {
                  if (success && Boolean.TRUE.equals(status.get("complete")))
                    store
                        .jdbc()
                        .update(
                            "UPDATE scholarships SET status='closed',stale=TRUE WHERE id=?",
                            old.get("id"));
                  else
                    store
                        .jdbc()
                        .update("UPDATE scholarships SET stale=TRUE WHERE id=?", old.get("id"));
                }
              }
              String state =
                  success
                      ? (Boolean.TRUE.equals(status.get("complete")) ? "success" : "partial")
                      : "error";
              String message = String.valueOf(status.getOrDefault("message", ""));
              if (message.length() > 1000) message = message.substring(0, 1000);
              String name = String.valueOf(status.getOrDefault("name", sourceId));
              String url = String.valueOf(status.get("sourceUrl"));
              int count = entries.size();
              int updated =
                  store
                      .jdbc()
                      .update(
                          "UPDATE scholarship_sources SET"
                              + " name=?,source_url=?,status=?,message=?,record_count=?,last_attempt_at=?"
                              + " WHERE id=?",
                          name,
                          url,
                          state,
                          message,
                          count,
                          Timestamp.from(now),
                          sourceId);
              if (updated == 0)
                store
                    .jdbc()
                    .update(
                        "INSERT INTO"
                            + " scholarship_sources(id,name,source_url,status,message,record_count,last_attempt_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                        sourceId,
                        name,
                        url,
                        state,
                        message,
                        count,
                        Timestamp.from(now));
              if (success)
                store
                    .jdbc()
                    .update(
                        "UPDATE scholarship_sources SET last_success_at=? WHERE id=?",
                        Timestamp.from(now),
                        sourceId);
            });
      }
      // Dates supplied by providers expire naturally; failed requests never invent replacement
      // dates.
      store
          .jdbc()
          .update(
              "UPDATE scholarships SET status='closed' WHERE source_type='official' AND deadline_on"
                  + " < ?",
              java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)));
      boolean succeeded =
          fetched.sourceStatuses().stream().anyMatch(s -> Boolean.TRUE.equals(s.get("success")))
              || stored[0] > 0;
      return Map.of(
          "success",
          succeeded,
          "status",
          succeeded ? "refreshed" : "providers_unavailable",
          "records",
          stored[0],
          "sources",
          fetched.sourceStatuses());
    } catch (RuntimeException ex) {
      org.slf4j.LoggerFactory.getLogger(ScholarshipSync.class)
          .warn("Scholarship refresh failed: {}", ex.getClass().getSimpleName());
      return Map.of(
          "success",
          false,
          "status",
          "failed",
          "message",
          "Provider refresh failed. Previously retrieved listings remain available with their"
              + " original dates.");
    } finally {
      store
          .jdbc()
          .update(
              "UPDATE sync_leases SET locked_until=? WHERE id='scholarships'",
              Timestamp.from(Instant.now()));
    }
  }

  void upsert(Map<String, Object> original, Instant now) {
    var entry = new LinkedHashMap<>(original);
    String id = entry.get("id").toString();
    String sourceId = entry.get("sourceId").toString();
    String sourceUrl = Scholarships.officialUrl(entry.get("sourceUrl").toString());
    String applicationUrl =
        entry.get("applicationUrl") == null
            ? null
            : Scholarships.officialUrl(entry.get("applicationUrl").toString());
    entry.put("category", entry.getOrDefault("category", "Official scholarship"));
    entry.putIfAbsent("tags", List.of());
    entry.put("sourceType", "official");
    LocalDate deadline =
        entry.get("deadline") == null ? null : LocalDate.parse(entry.get("deadline").toString());
    LocalDate opens =
        entry.get("opensAt") == null ? null : LocalDate.parse(entry.get("opensAt").toString());
    String availability = entry.getOrDefault("applicationStatus", "unknown").toString();
    String status = Set.of("open", "upcoming").contains(availability) ? "active" : "closed";
    if (deadline != null && deadline.isBefore(LocalDate.now(ZoneOffset.UTC))) status = "closed";
    Object deadlineSql = deadline == null ? null : java.sql.Date.valueOf(deadline),
        opensSql = opens == null ? null : java.sql.Date.valueOf(opens);
    int updated =
        store
            .jdbc()
            .update(
                "UPDATE scholarships SET"
                    + " payload=?,status=?,source_type='official',source_id=?,source_url=?,application_url=?,opens_on=?,deadline_on=?,synced_at=?,stale=FALSE"
                    + " WHERE id=? AND source_type='official'",
                store.json(entry),
                status,
                sourceId,
                sourceUrl,
                applicationUrl,
                opensSql,
                deadlineSql,
                Timestamp.from(now),
                id);
    if (updated == 0)
      store
          .jdbc()
          .update(
              "INSERT INTO"
                  + " scholarships(id,payload,status,source_type,source_id,source_url,application_url,opens_on,deadline_on,synced_at,stale)"
                  + " VALUES (?,?,?,'official',?,?,?,?,?,?,FALSE)",
              id,
              store.json(entry),
              status,
              sourceId,
              sourceUrl,
              applicationUrl,
              opensSql,
              deadlineSql,
              Timestamp.from(now));
  }
}
