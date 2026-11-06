package app.scholarai;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService userDetailsService(Store store) {
    return email -> {
      var row =
          store.one(
              "SELECT * FROM users WHERE email=?", email.trim().toLowerCase(java.util.Locale.ROOT));
      if (row == null) throw new UsernameNotFoundException("Invalid credentials");
      return User.withUsername(row.get("email").toString())
          .password(row.get("password").toString())
          .disabled(Boolean.TRUE.equals(row.get("disabled")))
          .authorities(
              List.of(
                  new SimpleGrantedAuthority(
                      "ROLE_" + row.get("role").toString().toUpperCase(java.util.Locale.ROOT))))
          .build();
    };
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, UserDetailsService users, PasswordEncoder encoder, Accounts accounts)
      throws Exception {
    var provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    http.exceptionHandling(
        errors ->
            errors.authenticationEntryPoint(
                (request, response, exception) -> {
                  if (request.getRequestURI().startsWith("/api/")) response.sendError(401);
                  else response.sendRedirect(request.getContextPath() + "/login");
                }));
    http.authenticationProvider(provider)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/styles.css",
                        "/original-design.css",
                        "/home.js",
                        "/app.js",
                        "/fonts/**",
                        "/error",
                        "/health")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/",
                        "/login",
                        "/register",
                        "/forgot-password",
                        "/reset-password",
                        "/catalogue",
                        "/scholarships",
                        "/scholarships/*")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/register", "/forgot-password", "/reset-password")
                    .permitAll()
                    .requestMatchers("/dashboard/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/dashboard/institution/**", "/applications/*/decision")
                    .hasRole("INSTITUTION")
                    .requestMatchers(
                        "/dashboard/student/**", "/scholarships/*/apply", "/scholarships/*/save")
                    .hasRole("STUDENT")
                    .anyRequest()
                    .authenticated())
        .formLogin(
            form ->
                form.loginPage("/login")
                    .usernameParameter("email")
                    .successHandler(
                        (request, response, authentication) -> {
                          var account = accounts.byEmail(authentication.getName());
                          request
                              .getSession()
                              .setAttribute("passwordVersion", account.get("password"));
                          response.sendRedirect("/dashboard");
                        })
                    .permitAll())
        .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'; img-src"
                                + " 'self' data:; font-src 'self'; frame-ancestors 'none'; base-uri"
                                + " 'self'; form-action 'self'")))
        // Inheritance and method overriding: specialize Spring's filter base class for account
        // checks.
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                if ("POST".equals(req.getMethod())
                    && "/login"
                        .equals(req.getRequestURI().substring(req.getContextPath().length()))) {
                  String email = req.getParameter("email");
                  if (!accounts.allow(
                      "login:"
                          + (email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT)),
                      8,
                      Duration.ofMinutes(15))) {
                    res.sendError(429, "Too many login attempts. Try again later.");
                    return;
                  }
                }
                var context =
                    org.springframework.security.core.context.SecurityContextHolder.getContext();
                var authentication = context.getAuthentication();
                if (authentication != null
                    && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getPrincipal())) {
                  var current = accounts.byEmail(authentication.getName());
                  // Re-read identity on each request so role/disable/password changes revoke
                  // existing sessions.
                  boolean stale = current == null || Boolean.TRUE.equals(current.get("disabled"));
                  if (current != null) {
                    stale |=
                        !authentication
                            .getAuthorities()
                            .contains(
                                new SimpleGrantedAuthority(
                                    "ROLE_"
                                        + current
                                            .get("role")
                                            .toString()
                                            .toUpperCase(java.util.Locale.ROOT)));
                    var session = req.getSession(false);
                    if (session != null) {
                      Object version = session.getAttribute("passwordVersion");
                      if (version != null) stale |= !current.get("password").equals(version);
                    }
                  }
                  if (stale) {
                    context.setAuthentication(null);
                    var session = req.getSession(false);
                    if (session != null) session.invalidate();
                    res.sendRedirect("/login");
                    return;
                  }
                }
                chain.doFilter(req, res);
              }
            },
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
