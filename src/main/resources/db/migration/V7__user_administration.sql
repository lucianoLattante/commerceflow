-- Existing identity/catalog/order data and released V1-V6 remain unchanged.
ALTER TABLE app_users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX ix_user_role_active ON app_users(role,active);
CREATE INDEX ix_user_created_id ON app_users(created_at,id);

-- Serialize membership changes across application instances; no process-local lock.
CREATE TABLE identity_admin_guard (
  id INT NOT NULL PRIMARY KEY,
  CONSTRAINT ck_identity_guard CHECK(id=1)
);
INSERT INTO identity_admin_guard(id) VALUES(1);

-- Administrative state changes only: no passwords, emails or token material.
CREATE TABLE user_admin_audit (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_version BIGINT NOT NULL,
  reason VARCHAR(160) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  before_role VARCHAR(16) NOT NULL,
  before_active BOOLEAN NOT NULL,
  before_version BIGINT NOT NULL,
  after_role VARCHAR(16) NOT NULL,
  after_active BOOLEAN NOT NULL,
  revoked_tokens INT NOT NULL,
  CONSTRAINT fk_user_audit_target FOREIGN KEY(user_id) REFERENCES app_users(id),
  CONSTRAINT fk_user_audit_actor FOREIGN KEY(actor_id) REFERENCES app_users(id),
  CONSTRAINT uq_user_audit_version UNIQUE(user_id,user_version),
  CONSTRAINT ck_user_audit_version CHECK(user_version=before_version+1 AND before_version>=0),
  CONSTRAINT ck_user_audit_roles CHECK(before_role IN ('ADMIN','CUSTOMER') AND after_role IN ('ADMIN','CUSTOMER')),
  CONSTRAINT ck_user_audit_revoked CHECK(revoked_tokens>=0)
);
