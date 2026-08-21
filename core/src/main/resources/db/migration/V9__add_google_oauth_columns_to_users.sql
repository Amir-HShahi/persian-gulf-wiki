ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD COLUMN google_sub VARCHAR(255) UNIQUE;
CREATE INDEX idx_users_google_sub ON users (google_sub) WHERE google_sub IS NOT NULL;
