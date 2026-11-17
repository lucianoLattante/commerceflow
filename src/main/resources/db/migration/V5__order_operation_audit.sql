-- Audit starts with this migration. Historical actor attribution is deliberately not fabricated.
CREATE TABLE order_operation_audit (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  order_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sequence_no INT NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_audit_order FOREIGN KEY(order_id) REFERENCES customer_orders(id),
  CONSTRAINT fk_audit_actor FOREIGN KEY(actor_id) REFERENCES app_users(id),
  CONSTRAINT ck_audit_action CHECK(
    (action='ORDER_RESERVED' AND sequence_no=1)
    OR (action IN ('ORDER_PAID','ORDER_PAYMENT_DECLINED','ORDER_CANCELLED','ORDER_EXPIRED') AND sequence_no=2)),
  UNIQUE KEY uq_audit_order_sequence(order_id,sequence_no),
  INDEX ix_audit_action_time(action,occurred_at,id)
);
