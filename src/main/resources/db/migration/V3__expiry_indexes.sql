CREATE INDEX rate_limits_expiry_idx ON rate_limits(expires_at);
CREATE INDEX password_resets_expiry_idx ON password_resets(expires_at);
