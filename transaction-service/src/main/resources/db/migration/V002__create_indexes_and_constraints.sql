-- the last guard: the service never lets a balance go below zero
ALTER TABLE wallets ADD CONSTRAINT ck_wallets_balance_not_negative CHECK (balance >= 0);

-- confirm claims its requestId with ON CONFLICT on this key
ALTER TABLE transactions ADD CONSTRAINT uk_transactions_user_request UNIQUE (user_uid, request_id);

CREATE INDEX idx_wallets_user_uid ON wallets (user_uid);

CREATE INDEX idx_transactions_user_created ON transactions (user_uid, created_at);

-- the producer reads only unsent events, oldest first
CREATE INDEX idx_transaction_events_unsent ON transaction_events (created_at) WHERE sent_at IS NULL;
