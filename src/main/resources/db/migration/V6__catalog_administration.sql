-- Released migrations V1-V5 remain unchanged. Existing products stay available.
ALTER TABLE products ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- Append-only through the application API; database operators are still trusted.
-- No fabricated history for product creation or stock changes from orders.
CREATE TABLE product_change_audit (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  product_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  product_version BIGINT NOT NULL,
  reason VARCHAR(160) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  before_state JSON NOT NULL,
  after_state JSON NOT NULL,
  CONSTRAINT fk_product_audit_product FOREIGN KEY(product_id) REFERENCES products(id),
  CONSTRAINT fk_product_audit_actor FOREIGN KEY(actor_id) REFERENCES app_users(id),
  CONSTRAINT ck_product_audit_version CHECK(product_version > 0),
  CONSTRAINT uq_product_audit_version UNIQUE(product_id,product_version)
);
