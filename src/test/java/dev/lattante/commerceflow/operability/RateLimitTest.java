package dev.lattante.commerceflow.operability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitTest {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void keyIsStablePolicyScopedAndContainsNoRawIdentity() {
    String identity = "203.0.113.42";
    String key = RedisRateLimiter.key(RateLimitSettings.Policy.LOGIN, identity);
    assertThat(key).matches("cf:rate:login:[0-9a-f]{64}").doesNotContain(identity);
    assertThat(RedisRateLimiter.key(RateLimitSettings.Policy.REGISTRATION, identity))
        .isNotEqualTo(key);
    assertThat(RedisRateLimiter.key(RateLimitSettings.Policy.LOGIN, identity)).isEqualTo(key);
  }

  @Test
  void publicReadsAndLogoutDoNotSpendWriteAllowance() {
    assertThat(RateLimitFilter.policy("GET", "/api/products")).isNull();
    assertThat(RateLimitFilter.policy("POST", "/api/auth/logout")).isNull();
    assertThat(RateLimitFilter.policy("POST", "/api/orders"))
        .isEqualTo(RateLimitSettings.Policy.WRITE);
    assertThat(RateLimitFilter.policy("POST", "/api/auth/token"))
        .isEqualTo(RateLimitSettings.Policy.LOGIN);
    assertThat(RateLimitFilter.policy("POST", "/api/auth/register"))
        .isEqualTo(RateLimitSettings.Policy.REGISTRATION);
  }

  @Test
  void exhaustedAllowanceReturns429AndNeverReachesTheController() throws Exception {
    var limiter = mock(RedisRateLimiter.class);
    when(limiter.acquire(RateLimitSettings.Policy.LOGIN, "127.0.0.1"))
        .thenReturn(new RedisRateLimiter.Decision(false, 0, 37));
    var request = new MockHttpServletRequest("POST", "/api/auth/token");
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Forwarded-For", "198.51.100.2");
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);
    new RateLimitFilter(limiter, new ObjectMapper()).doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("37");
    assertThat(response.getContentAsString())
        .contains("rate_limited")
        .doesNotContain("198.51.100.2");
    verifyNoInteractions(chain);
    verify(limiter).acquire(RateLimitSettings.Policy.LOGIN, "127.0.0.1");
  }

  @Test
  void unavailableStoreFailsClosedWithoutDisclosingInfrastructure() throws Exception {
    var limiter = mock(RedisRateLimiter.class);
    when(limiter.acquire(any(), anyString())).thenThrow(new RedisRateLimiter.Unavailable());
    var request = new MockHttpServletRequest("POST", "/api/auth/token");
    request.setRemoteAddr("127.0.0.1");
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);
    new RateLimitFilter(limiter, new ObjectMapper()).doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString())
        .contains("rate_limit_unavailable")
        .doesNotContain("redis://");
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    verifyNoInteractions(chain);
  }

  @Test
  void redisFailureIsCountedAndDoesNotGrantAnAllowance() {
    var redis = mock(StringRedisTemplate.class);
    var metrics = new SimpleMeterRegistry();
    when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
    var limiter = new RedisRateLimiter(redis, new RateLimitSettings(60, 5, 3, 60), metrics);
    assertThatThrownBy(() -> limiter.acquire(RateLimitSettings.Policy.LOGIN, "fixture"))
        .isInstanceOf(RedisRateLimiter.Unavailable.class);
    assertThat(
            metrics
                .get("commerceflow.rate.limit.requests")
                .tags("policy", "login", "outcome", "unavailable")
                .counter()
                .count())
        .isEqualTo(1);
  }
}
