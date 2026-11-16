package dev.lattante.commerceflow.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.shared.ApiErrors;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  OpenAPI openAPI() {
    return new OpenAPI()
        .info(new Info().title("CommerceFlow").version("0.1.0"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, IdentityService identity, ObjectMapper json)
      throws Exception {
    // Authentication exclusively uses an explicit Bearer header: no auth cookies, Basic or form
    // login.
    http.csrf(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/token")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/products",
                        "/api/products/*",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/docs")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/products")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/orders")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/orders", "/api/orders/*")
                    .authenticated()
                    .requestMatchers("/api/users/me", "/api/auth/logout")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setContentType("application/problem+json");
                          json.writeValue(
                              res.getOutputStream(),
                              ApiErrors.problem(
                                  HttpStatus.UNAUTHORIZED,
                                  "unauthorized",
                                  "Authentication required or invalid"));
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          res.setStatus(403);
                          res.setContentType("application/problem+json");
                          json.writeValue(
                              res.getOutputStream(),
                              ApiErrors.problem(
                                  HttpStatus.FORBIDDEN, "forbidden", "Operation is not permitted"));
                        }))
        .addFilterBefore(
            new BearerTokenFilter(identity, json), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  ApplicationRunner bootstrap(
      IdentityService identity,
      @Value("${CF_BOOTSTRAP_ADMIN_EMAIL:}") String email,
      @Value("${CF_BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
    return args -> {
      if (email.isBlank() && password.isBlank()) return;
      if (email.isBlank() || password.isBlank())
        throw new IllegalStateException("Both bootstrap settings are required");
      identity.bootstrap(email, password);
    };
  }
}
