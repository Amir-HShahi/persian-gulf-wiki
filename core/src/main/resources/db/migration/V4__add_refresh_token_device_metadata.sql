ALTER TABLE refresh_tokens
    ADD COLUMN device_label VARCHAR(255),
    ADD COLUMN ip_address   VARCHAR(64);
