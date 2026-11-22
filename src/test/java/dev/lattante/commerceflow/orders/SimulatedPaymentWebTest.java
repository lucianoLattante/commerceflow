package dev.lattante.commerceflow.orders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.shared.ApiErrors;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP binding/validation only; real Bearer authorization and MySQL are covered by OrderFlowIT. */
class SimulatedPaymentWebTest {
  private final OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
  private final Actor owner = new Actor("owner", "owner@example.test", AppUser.Role.CUSTOMER);
  private final String id = UUID.randomUUID().toString();
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(owner, null));
    mvc = MockMvcBuilders.standaloneSetup(new OrderController(mock(OrderService.class), lifecycle))
        .setControllerAdvice(new ApiErrors())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
  }

  @AfterEach
  void clearContext() { SecurityContextHolder.clearContext(); }

  @Test
  void documentedExampleReachesThePaymentService() throws Exception {
    when(lifecycle.pay(id, owner, "demo-payment-001", PaymentDecision.APPROVED))
        .thenReturn(new OrderLifecycleService.PaymentResult(null, false));
    mvc.perform(post("/api/orders/" + id + "/simulated-payment")
        .header("Idempotency-Key", "demo-payment-001").contentType(MediaType.APPLICATION_JSON)
        .content("{\"decision\":\"APPROVED\"}"))
        .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "false"));
    verify(lifecycle).pay(id, owner, "demo-payment-001", PaymentDecision.APPROVED);
  }

  @Test
  void missingKeyNamesTheRequiredHeader() throws Exception {
    mvc.perform(post("/api/orders/" + id + "/simulated-payment")
        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"))
        .andExpect(jsonPath("$.detail").value("Required header is missing: Idempotency-Key"));
    verifyNoInteractions(lifecycle);
  }

  @Test
  void missingDecisionNamesTheField() throws Exception {
    mvc.perform(post("/api/orders/" + id + "/simulated-payment")
        .header("Idempotency-Key", "demo-payment-001").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Request validation failed for: decision"));
    verifyNoInteractions(lifecycle);
  }

  @Test
  void invalidDecisionGivesAllowedValuesWithoutEchoingInput() throws Exception {
    mvc.perform(post("/api/orders/" + id + "/simulated-payment")
        .header("Idempotency-Key", "demo-payment-001").contentType(MediaType.APPLICATION_JSON)
        .content("{\"decision\":\"private-fixture-input\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("decision must be APPROVED or DECLINED"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-fixture-input"))));
    verifyNoInteractions(lifecycle);
  }

  @Test
  void invalidOrderIdExplainsTheRequiredUuid() throws Exception {
    mvc.perform(post("/api/orders/string/simulated-payment")
        .header("Idempotency-Key", "demo-payment-001").contentType(MediaType.APPLICATION_JSON)
        .content("{\"decision\":\"APPROVED\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Parameter 'id' must be a valid resource UUID"));
    verifyNoInteractions(lifecycle);
  }

  @Test
  void malformedJsonHasANonSensitiveMessage() throws Exception {
    mvc.perform(post("/api/orders/" + id + "/simulated-payment")
        .header("Idempotency-Key", "demo-payment-001").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Request body must contain valid JSON with valid field values"));
    verifyNoInteractions(lifecycle);
  }
}
