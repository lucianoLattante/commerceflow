package dev.lattante.commerceflow.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
class IdempotencyClaims {
  private final JdbcTemplate jdbc;

  IdempotencyClaims(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record Claim(String requestId, boolean fresh, String orderId) {}

  private record Stored(String id, String hash, String orderId) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public Claim claim(String customerId, String key, String requestHash, Instant now) {
    String candidate = UUID.randomUUID().toString(), keyHash = OrderSubmission.keyHash(key);
    // A duplicate-key UPDATE takes the same row lock as the initial INSERT. No exception/retry
    // transaction is used to decide which concurrent caller owns the claim.
    jdbc.update(
        """
        INSERT INTO idempotency_requests(request_id,customer_id,key_hash,request_hash,created_at)
        VALUES(?,?,?,?,?)
        ON DUPLICATE KEY UPDATE request_id=idempotency_requests.request_id
        """,
        candidate,
        customerId,
        keyHash,
        requestHash,
        Timestamp.from(now));
    Stored stored =
        jdbc.queryForObject(
            """
            SELECT request_id,request_hash,order_id FROM idempotency_requests
            WHERE customer_id=? AND key_hash=? FOR UPDATE
            """,
            (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3)),
            customerId,
            keyHash);
    if (!stored.hash().equals(requestHash)) throw new IdempotencyConflict();
    boolean fresh = stored.id().equals(candidate);
    if (!fresh && stored.orderId() == null)
      throw new IllegalStateException("Committed claim has no order");
    return new Claim(stored.id(), fresh, stored.orderId());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(String requestId, String orderId) {
    int updated =
        jdbc.update(
            "UPDATE idempotency_requests SET order_id=? WHERE request_id=? AND order_id IS NULL",
            orderId,
            requestId);
    if (updated != 1)
      throw new IllegalStateException("Idempotency completion must update exactly one claim");
  }
}
