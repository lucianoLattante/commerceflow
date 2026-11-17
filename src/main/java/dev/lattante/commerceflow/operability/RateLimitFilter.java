package dev.lattante.commerceflow.operability;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.identity.Actor;
import dev.lattante.commerceflow.shared.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {
  private final RedisRateLimiter limiter;
  private final ObjectMapper json;

  public RateLimitFilter(RedisRateLimiter limiter, ObjectMapper json) {
    this.limiter = limiter;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String route = request.getRequestURI().substring(request.getContextPath().length());
    var policy = policy(request.getMethod(), route);
    if (policy == null) {
      chain.doFilter(request, response);
      return;
    }
    String identity;
    if (policy == RateLimitSettings.Policy.WRITE) {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth == null || !(auth.getPrincipal() instanceof Actor actor)) {
        chain.doFilter(request, response);
        return;
      }
      identity = actor.id();
    } else {
      // Socket address only. Client-supplied forwarding headers must not select an allowance.
      identity = request.getRemoteAddr();
    }
    try {
      var decision = limiter.acquire(policy, identity);
      if (!decision.allowed()) {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        json.writeValue(
            response.getOutputStream(),
            ApiErrors.problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "Request allowance exceeded; retry later"));
        return;
      }
    } catch (RedisRateLimiter.Unavailable unavailable) {
      response.setStatus(503);
      response.setHeader("Retry-After", "1");
      response.setContentType("application/problem+json");
      json.writeValue(
          response.getOutputStream(),
          ApiErrors.problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "rate_limit_unavailable",
              "Admission protection is temporarily unavailable"));
      return;
    }
    chain.doFilter(request, response);
  }

  static RateLimitSettings.Policy policy(String method, String route) {
    if (method.equals("POST") && route.equals("/api/auth/token"))
      return RateLimitSettings.Policy.LOGIN;
    if (method.equals("POST") && route.equals("/api/auth/register"))
      return RateLimitSettings.Policy.REGISTRATION;
    if (method.equals("POST") && route.equals("/api/auth/logout")) return null;
    if (route.startsWith("/api/")
        && java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(method))
      return RateLimitSettings.Policy.WRITE;
    return null;
  }
}
