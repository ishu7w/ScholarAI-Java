# ScholarAI Java

Read README.md, ARCHITECTURE.md, SECURITY.md and docs/JAVA_MIGRATION_PLAN.md before substantial changes.

- Java 21, Maven, Spring Boot MVC, Thymeleaf, Spring Security, JDBC and Flyway.
- Keep application rules in services, HTTP handling in WebController and security configuration in SecurityConfig.
- Use constructor injection and private dependencies. Explain OOP concepts where the actual code demonstrates them; avoid artificial inheritance or unnecessary interfaces.
- Preserve the original design, fonts, routes and ten fixed scoring weights. Original implementation is preserved at commit b05c58b.
- Never bypass CSRF, role checks, institution approval or scholarship ownership checks.
- Use bound SQL parameters. Profile and scholarship JSON preserve the original domain field names; identity and relationships are normalized SQL columns.
- Reference scholarship dates and external funding calls are not automatically renewed. Never invent deadlines or claim award guarantees.
- Run `mvn verify` and inspect changed Thymeleaf pages in a browser. CI also runs the integration suite against PostgreSQL.
- Use `.env.example` for configuration examples; never commit real secrets or files under data/.
- Do not claim live SMTP, backups, TLS or deployment were tested unless they actually were.
