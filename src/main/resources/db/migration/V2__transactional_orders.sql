CREATE TABLE customer_orders (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  customer_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  total_amount DECIMAL(19,2) NOT NULL,
  currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_order_customer FOREIGN KEY(customer_id) REFERENCES app_users(id),
  CONSTRAINT ck_order_status CHECK (status = 'RESERVED'),
  CONSTRAINT ck_order_amount CHECK (total_amount > 0),
  CONSTRAINT ck_order_currency CHECK (currency IN ('USD','ARS','EUR')),
  INDEX ix_order_customer_created(customer_id,created_at,id)
);
CREATE TABLE order_lines (
  order_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  position INT NOT NULL,
  product_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sku VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(160) NOT NULL,
  unit_amount DECIMAL(19,2) NOT NULL,
  quantity INT NOT NULL,
  line_total DECIMAL(19,2) NOT NULL,
  PRIMARY KEY(order_id,position),
  UNIQUE KEY uq_order_product(order_id,product_id),
  CONSTRAINT fk_line_order FOREIGN KEY(order_id) REFERENCES customer_orders(id),
  CONSTRAINT fk_line_product FOREIGN KEY(product_id) REFERENCES products(id),
  CONSTRAINT ck_line_quantity CHECK (quantity BETWEEN 1 AND 1000),
  CONSTRAINT ck_line_amount CHECK (unit_amount > 0 AND line_total = unit_amount * quantity)
);
CREATE TABLE idempotency_requests (
  request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  customer_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin UNIQUE,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_idempotency_customer_key(customer_id,key_hash),
  CONSTRAINT fk_idempotency_customer FOREIGN KEY(customer_id) REFERENCES app_users(id),
  CONSTRAINT fk_idempotency_order FOREIGN KEY(order_id) REFERENCES customer_orders(id)
);
CREATE TABLE outbox_events (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  order_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_version INT NOT NULL,
  payload JSON NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  published_at TIMESTAMP(6),
  CONSTRAINT fk_outbox_order FOREIGN KEY(order_id) REFERENCES customer_orders(id),
  CONSTRAINT ck_outbox_type CHECK (event_type = 'ORDER_RESERVED' AND event_version = 1),
  INDEX ix_outbox_pending(published_at,occurred_at,id)
);
