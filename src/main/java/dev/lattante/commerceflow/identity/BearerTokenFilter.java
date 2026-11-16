package dev.lattante.commerceflow.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.shared.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class BearerTokenFilter extends OncePerRequestFilter {
  private final IdentityService identity;
  private final ObjectMapper json;

  BearerTokenFilter(IdentityService identity, ObjectMapper json) {
    this.identity = identity;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      var actor =
          header.startsWith("Bearer ")
              ? identity.authenticate(header.substring(7))
              : java.util.Optional.<Actor>empty();
      if (actor.isEmpty()) {
        response.setStatus(401);
        response.setContentType("application/problem+json");
        json.writeValue(
            response.getOutputStream(),
            ApiErrors.problem(
                HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required or invalid"));
        return;
      }
      var principal = actor.get();
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  principal,
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))));
    }
    chain.doFilter(request, response);
  }
}
