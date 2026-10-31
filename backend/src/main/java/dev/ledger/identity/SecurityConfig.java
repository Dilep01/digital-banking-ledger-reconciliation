package dev.ledger.identity;

import dev.ledger.shared.Support;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  UserDetailsService userDetailsService(Support s) {
    return email -> {
      var found =
          s.rows(
              "SELECT * FROM users WHERE email=? AND enabled=true",
              email.toLowerCase(java.util.Locale.ROOT));
      if (found.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
      var u = found.getFirst();
      return User.withUsername(u.get("email").toString())
          .password(u.get("password_hash").toString())
          .roles(u.get("role").toString())
          .build();
    };
  }

  @Bean
  FilterRegistrationBean<OncePerRequestFilter> correlationFilter() {
    var registration = new FilterRegistrationBean<OncePerRequestFilter>();
    registration.setFilter(
        new OncePerRequestFilter() {
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            String id = UUID.randomUUID().toString();
            MDC.put("correlationId", id);
            res.setHeader("X-Correlation-ID", id);
            try {
              chain.doFilter(req, res);
            } finally {
              MDC.remove("correlationId");
            }
          }
        });
    registration.setOrder(-200);
    return registration;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, Support s) throws Exception {
    http.headers(
        h ->
            h.contentSecurityPolicy(
                c ->
                    c.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; connect-src"
                            + " 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self';"
                            + " frame-ancestors 'none'; form-action 'self'")));
    var csrf = new HttpSessionCsrfTokenRepository();
    // The token is returned by /auth/csrf and sent as a header; session cookie stays HttpOnly.
    http.csrf(
        c ->
            c.csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers(
                    "/api/v1/auth/csrf",
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/actuator/health",
                    "/",
                    "/index.html",
                    "/assets/**",
                    "/favicon.svg",
                    "/error")
                .permitAll()
                .requestMatchers("/actuator/**")
                .hasRole("AUDITOR")
                .requestMatchers("/api/**")
                .authenticated()
                .anyRequest()
                .denyAll());
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(
                    (req, res, ex) -> reply(res, 401, "UNAUTHENTICATED", "Please sign in."))
                .accessDeniedHandler(
                    (req, res, ex) ->
                        reply(res, 403, "FORBIDDEN", "Permission or CSRF token is missing.")));
    http.formLogin(
        f ->
            f.loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("email")
                .successHandler(
                    (req, res, auth) -> {
                      var user = s.one("SELECT id FROM users WHERE email=?", auth.getName());
                      s.audit(
                          Support.uuid(user.get("id")),
                          "LOGIN",
                          user.get("id"),
                          "SUCCESS",
                          "Session established");
                      res.setContentType("application/json");
                      res.getWriter().write("{\"authenticated\":true}");
                    })
                .failureHandler(
                    (req, res, ex) -> {
                      s.audit(null, "LOGIN", "authentication", "REJECTED", "Invalid credentials");
                      reply(res, 401, "INVALID_CREDENTIALS", "Email or password is incorrect.");
                    }));
    http.logout(
        l ->
            l.logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler(
                    (req, res, auth) -> {
                      if (auth != null) {
                        var u = s.rows("SELECT id FROM users WHERE email=?", auth.getName());
                        if (!u.isEmpty())
                          s.audit(
                              Support.uuid(u.getFirst().get("id")),
                              "LOGOUT",
                              u.getFirst().get("id"),
                              "SUCCESS",
                              "Session ended");
                      }
                      res.setStatus(204);
                    }));
    http.addFilterBefore(
        new OncePerRequestFilter() {
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            if (req.getMethod().equals("POST")
                && (req.getRequestURI().equals("/api/v1/auth/login")
                    || req.getRequestURI().equals("/api/v1/auth/register"))) {
              String key = req.getRemoteAddr() + ":" + (System.currentTimeMillis() / 300000);
              Integer count =
                  s.db.queryForObject(
                      "INSERT INTO login_buckets(bucket_key,attempts,expires_at)"
                          + " VALUES(?,1,now()+interval '5 minutes') ON CONFLICT(bucket_key) DO"
                          + " UPDATE SET attempts=login_buckets.attempts+1 RETURNING attempts",
                      Integer.class,
                      key);
              if (count != null && count > 30) {
                reply(
                    res, 429, "TOO_MANY_ATTEMPTS", "Too many attempts. Try again in five minutes.");
                return;
              }
            }
            if (req.getContentLengthLong() > 1048576) {
              reply(res, 413, "REQUEST_TOO_LARGE", "Maximum request size is 1 MB.");
              return;
            }
            chain.doFilter(req, res);
          }
        },
        UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  private static void reply(HttpServletResponse res, int status, String code, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }
}
