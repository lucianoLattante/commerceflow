-- Separate transport state from immutable business events. No released migration is edited.
CREATE TABLE message_dispatch (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  aggregate_sequence INT NULL,
  topic VARCHAR(128) NOT NULL,
  message_key VARCHAR(128) NULL,
  payload MEDIUMTEXT NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  retry_cycles INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL,
  lease_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  sent_at TIMESTAMP(6) NULL,
  last_error_code VARCHAR(32) NULL,
  UNIQUE KEY uq_dispatch_order_sequence(aggregate_id,aggregate_sequence),
  CONSTRAINT ck_dispatch_kind CHECK(kind IN ('ORDER','DLQ','REPLAY')),
  CONSTRAINT ck_dispatch_state CHECK(state IN ('PENDING','IN_FLIGHT','SENT','FAILED')),
  CONSTRAINT ck_dispatch_attempts CHECK(attempts BETWEEN 0 AND 5 AND retry_cycles BETWEEN 0 AND 3),
  CONSTRAINT ck_dispatch_lease CHECK(
    (state='IN_FLIGHT' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (state<>'IN_FLIGHT' AND lease_token IS NULL AND lease_until IS NULL)),
  CONSTRAINT ck_dispatch_aggregate CHECK(
    (kind='ORDER' AND aggregate_id IS NOT NULL AND aggregate_sequence IS NOT NULL AND aggregate_sequence IN (1,2))
    OR (kind<>'ORDER' AND aggregate_id IS NULL AND aggregate_sequence IS NULL)),
  INDEX ix_dispatch_due(state,next_attempt_at,lease_until),
  INDEX ix_dispatch_source(kind,source_id)
);
INSERT INTO message_dispatch(id,kind,source_id,aggregate_id,aggregate_sequence,topic,message_key,
  payload,state,next_attempt_at,created_at,sent_at)
SELECT id,'ORDER',id,order_id,IF(event_type='ORDER_RESERVED',1,2),'commerceflow.orders.v1',order_id,
  JSON_OBJECT('eventId',id,'orderId',order_id,'type',event_type,'version',event_version,
    'sequence',IF(event_type='ORDER_RESERVED',1,2),
    'occurredAt',JSON_UNQUOTE(JSON_EXTRACT(payload,'$.occurredAt')),'payload',payload),
  IF(published_at IS NULL,'PENDING','SENT'),occurred_at,occurred_at,published_at
FROM outbox_events;

CREATE TABLE message_inbox (
  event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  payload_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  processing_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  processed_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE order_projections (
  order_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  customer_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(24) NOT NULL,
  sequence_no INT NOT NULL,
  event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  occurred_at TIMESTAMP(6) NULL,
  received_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT ck_projection_state CHECK(
    (sequence_no=0 AND status='PENDING') OR (sequence_no=1 AND status='RESERVED')
    OR (sequence_no=2 AND status IN ('PAID','PAYMENT_DECLINED','CANCELLED','EXPIRED')))
);
CREATE TABLE message_dead_letters (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  source_topic VARCHAR(128) NOT NULL,
  source_partition INT NOT NULL,
  source_offset BIGINT NOT NULL,
  message_key VARCHAR(128) NULL,
  payload MEDIUMTEXT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  replayable BOOLEAN NOT NULL,
  reason_code VARCHAR(32) NOT NULL,
  replay_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_dead_letter_source(source_topic,source_partition,source_offset),
  CONSTRAINT ck_dead_letter_replay CHECK(replay_count BETWEEN 0 AND 3)
);
CREATE TABLE message_operator_requests (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(16) NOT NULL,
  key_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  dispatch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_operator_idempotency(target_id,action,key_hash),
  CONSTRAINT fk_operator_dispatch FOREIGN KEY(dispatch_id) REFERENCES message_dispatch(id),
  CONSTRAINT fk_operator_actor FOREIGN KEY(actor_id) REFERENCES app_users(id),
  CONSTRAINT ck_operator_action CHECK(action IN ('RETRY','REPLAY'))
);
