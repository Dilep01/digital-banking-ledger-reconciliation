ALTER TABLE scheduled_transfers ADD COLUMN creation_key VARCHAR(100) NOT NULL;
ALTER TABLE scheduled_transfers ADD COLUMN request_hash VARCHAR(64) NOT NULL;
ALTER TABLE scheduled_transfers ADD CONSTRAINT schedule_key UNIQUE(actor_id,creation_key);
