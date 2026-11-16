CREATE TABLE app_users (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  email VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT ck_user_role CHECK (role IN ('ADMIN','CUSTOMER'))
);
CREATE TABLE access_tokens (
  digest VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_token_user FOREIGN KEY(user_id) REFERENCES app_users(id),
  INDEX ix_token_expiry(expires_at)
);
CREATE TABLE products (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  sku VARVARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  name VARCHAR(160) NOT NULL,
  amount DECIMAL(19,2) NOT NULL,
  currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  on_hand INT NOT NULL,
  reserved INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT ck_product_price CHECK (amount > 0),
  CONSTRAINT ck_product_currency CHECK (currency IN ('USD','ARS','EUR')),
  CONSTRAINT ck_product_stock CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand),
  INDEX ix_product_name(name)
);
