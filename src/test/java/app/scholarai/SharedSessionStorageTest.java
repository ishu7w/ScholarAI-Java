package app.scholarai;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;

class SharedSessionStorageTest {
  private HikariDataSource database;
  private FindByIndexNameSessionRepository<Session> firstInstance;
  private FindByIndexNameSessionRepository<Session> secondInstance;

  @BeforeEach
  void sharedDatabase() {
    database = new HikariDataSource();
    database.setJdbcUrl(
        "jdbc:h2:mem:sessions-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    database.setUsername("sa");
    Flyway.configure().dataSource(database).load().migrate();
    var jdbc = new JdbcTemplate(database);
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
    firstInstance = repository(jdbc, transactions);
    secondInstance = repository(jdbc, transactions);
  }

  @SuppressWarnings("unchecked")
  private FindByIndexNameSessionRepository<Session> repository(
      JdbcTemplate jdbc, TransactionTemplate transactions) {
    var repository = new JdbcIndexedSessionRepository(jdbc, transactions);
    repository.setTableName("spring_session");
    // Use the public Session contract; the JDBC implementation's concrete session is
    // package-private.
    return (FindByIndexNameSessionRepository<Session>)
        (FindByIndexNameSessionRepository<?>) repository;
  }

  @AfterEach
  void closeDatabase() {
    database.close();
  }

  @Test
  void authenticatedSessionSurvivesASecondContainerAndLogoutRevokesItEverywhere() {
    var principal =
        User.withUsername("session-user@example.test").password("unused").roles("STUDENT").build();
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, principal.getAuthorities());
    var session = firstInstance.createSession();
    session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
    session.setAttribute("passwordVersion", "server-set-password-version");
    firstInstance.save(session);

    var reloaded = secondInstance.findById(session.getId());
    assertThat(reloaded).isNotNull();
    SecurityContextImpl security = reloaded.getAttribute("SPRING_SECURITY_CONTEXT");
    assertThat(security.getAuthentication().getName()).isEqualTo("session-user@example.test");
    assertThat((String) reloaded.getAttribute("passwordVersion"))
        .isEqualTo("server-set-password-version");
    assertThat(
            secondInstance.findByIndexNameAndIndexValue(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                "session-user@example.test"))
        .containsKey(session.getId());
    secondInstance.deleteById(session.getId());
    assertThat(firstInstance.findById(session.getId())).isNull();
  }

  @Test
  void expiredSessionsDoNotAuthenticateARequestOnAnotherContainer() {
    var session = firstInstance.createSession();
    session.setMaxInactiveInterval(Duration.ofSeconds(1));
    session.setLastAccessedTime(Instant.now().minusSeconds(10));
    session.setAttribute("owner", "test-user");
    firstInstance.save(session);
    assertThat(secondInstance.findById(session.getId())).isNull();
  }
}
