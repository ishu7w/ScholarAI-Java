package app.scholarai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ServiceConsistencyTest {
  private Store store;
  private TransactionTemplate transactions;
  private Accounts accounts;
  private HikariDataSource database;

  @BeforeEach
  void database() {
    database = new HikariDataSource();
    database.setMaximumPoolSize(8);
    database.setJdbcUrl(
        "jdbc:h2:mem:services-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    database.setUsername("sa");
    Flyway.configure().dataSource(database).load().migrate();
    store = new Store(new JdbcTemplate(database));
    transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
    accounts = mock(Accounts.class);
    when(accounts.user(anyString()))
        .thenAnswer(
            call -> store.one("SELECT * FROM users WHERE id=?", call.getArgument(0, String.class)));
  }

  @AfterEach
  void closeDatabase() {
    database.close();
  }

  private void createUser(String id, String role) {
    store
        .jdbc()
        .update(
            "INSERT INTO users(id,name,email,password,role,approved) VALUES (?,?,?,?,?,TRUE)",
            id,
            "Test " + id,
            id + "@example.test",
            "unused",
            role);
  }

  @Test
  void simultaneousInitialFundingPlansAllSaveWithoutDuplicateInsertFailures() throws Exception {
    createUser("student", "student");
    var aid = new FinancialAid(store);
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<?>> saves = new ArrayList<>();
      for (int i = 1; i <= 8; i++) {
        int cost = i * 1000;
        saves.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  transactions.executeWithoutResult(
                      status -> {
                        try {
                          aid.save(
                              "student",
                              Map.of(
                                  "currency",
                                  "USD",
                                  "cost",
                                  "" + cost,
                                  "confirmed",
                                  "" + cost / 4,
                                  "contribution",
                                  "0"),
                              List.of("aid-income"));
                        } catch (java.io.IOException ex) {
                          throw new UncheckedIOException(ex);
                        }
                      });
                  return null;
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var save : saves) save.get(10, TimeUnit.SECONDS);
    }
    assertThat(
            store
                .jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM aid_plans WHERE user_id='student'", Integer.class))
        .isEqualTo(1);
    var plan = aid.load("student");
    assertThat(Matching.number(plan, "cost")).isBetween(1000.0, 8000.0);
    assertThat(Matching.number(plan, "confirmed")).isEqualTo(Matching.number(plan, "cost") / 4);
    assertThat(aid.gap(plan)).isEqualTo(Matching.number(plan, "cost") * .75);
  }

  @Test
  void staleDisabledAdministratorCannotChangeAnotherAccount() {
    createUser("admin", "admin");
    createUser("student", "student");
    var staleActor = accounts.user("admin");
    store.jdbc().update("UPDATE users SET disabled=TRUE WHERE id='admin'");
    var administration = new Administration(store, accounts);
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> administration.updateUser(staleActor, "student", "disable")))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(accounts.user("student").get("disabled")).isEqualTo(false);
  }

  @Test
  void bootstrapNormalizesConfiguredEmailWithoutPromotingExistingUser() throws Exception {
    var encoder = mock(PasswordEncoder.class);
    when(encoder.encode(anyString())).thenReturn("test-encoded-password");
    var bootstrap = new Bootstrap(store, encoder, "  ADMIN@EXAMPLE.TEST  ", "A-long-test-password");
    bootstrap.run();
    assertThat(store.one("SELECT role FROM users WHERE email='admin@example.test'").get("role"))
        .isEqualTo("admin");
    createUser("existing", "student");
    new Bootstrap(store, encoder, " EXISTING@EXAMPLE.TEST ", "").run();
    assertThat(accounts.user("existing").get("role")).isEqualTo("student");
    verify(encoder, times(1)).encode(anyString());
  }

  @Test
  void invalidBootstrapIdentityFailsBeforeSeeding() {
    var bootstrap =
        new Bootstrap(store, mock(PasswordEncoder.class), "invalid-email", "A-long-test-password");
    assertThatThrownBy(bootstrap::run).isInstanceOf(ResponseStatusException.class);
    assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM scholarships", Integer.class))
        .isZero();
  }

  @Test
  void copilotUsesRoleSpecificPersistedCountsInsteadOfInventingStudentScores() {
    createUser("institution", "institution");
    createUser("admin", "admin");
    createUser("student", "student");
    store
        .jdbc()
        .update(
            "INSERT INTO scholarships(id,owner_id,status,payload) VALUES"
                + " ('program','institution','active','{}')");
    store
        .jdbc()
        .update(
            "INSERT INTO applications(id,student_id,scholarship_id,snapshot) VALUES"
                + " ('application','student','program','{}')");
    var copilot = new Copilot(store, new Matching(), mock(Scholarships.class));
    assertThat(copilot.answer("institution", "What is my score?"))
        .contains("1 scholarship program(s)", "1 application(s)", "institution workspace")
        .doesNotContain("18/100");
    assertThat(copilot.answer("admin", "How can I improve?"))
        .contains("3 registered account(s)", "1 application(s)", "platform administration")
        .doesNotContain("18/100");
    store.jdbc().update("UPDATE users SET approved=FALSE WHERE id='institution'");
    assertThat(copilot.answer("institution", "Help me publish"))
        .contains("awaiting administrator approval");
  }
}
