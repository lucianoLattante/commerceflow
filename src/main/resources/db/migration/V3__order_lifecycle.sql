-- Additive upgrade: existing reservations keep their creation-based 15 minute lease.
ALTER TABLE customer_orders
  ADD COLUMN expires_at TIMESTAMP(6) NULL,
  ADD COLUMN completed_at TIMESTAMP(6) NULL,
  ADD COLUMN payment_key_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN payment_decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL;
UPDATE customer_orders SET expires_at = TIMESTAMPADD(MINUTE,15,created_at);
ALTER TABLE customer_orders
  MODIFY COLUMN expires_at TIMESTAMP(6) NOT NULL,
  DROP CHECK ck_order_status,
  ADD CONSTRAINT ck_order_status CHECK (
    status IN ('RESERVED','PAID','PAYMENT_DECLINED','CANCELLED','EXPIRED')),
  ADD CONSTRAINT ck_order_deadline CHECK (expires_at >= created_at),
  ADD CONSTRAINT ck_order_completion CHECK (
    (status = 'RESERVED' AND completed_at IS NULL)
    OR (status <> 'RESERVED' AND completed_at IS NOT NULL AND completed_at >= created_at)),
  ADD CONSTRAINT ck_order_payment CHECK (
    (status IN ('RESERVED','CANCELLED','EXPIRED')
      AND payment_key_hash IS NULL AND payment_decision IS NULL)
    OR (status IN ('PAID','PAYMENT_DECLINED')
      AND payment_key_hash IS NOT NULL AND CHAR_LENGTH(payment_key_hash) = 64
      AND payment_decision IS NOT NULL
      AND ((status = 'PAID' AND payment_decision = 'APPROVED')
        OR (status = 'PAYMENT_DECLINED' AND payment_decision = 'DECLINED')))),
  ADD INDEX ix_order_expiry(status,expires_at,id);
ALTER TABLE outbox_events
  DROP CHECK ck_outbox_type,
  ADD CONSTRAINT ck_outbox_type CHECK (
    event_type IN ('ORDER_RESERVED','ORDER_PAID','ORDER_PAYMENT_DECLINED','ORDER_CANCELLED','ORDER_EXPIRED')
    AND event_version = 1);
