-- Freeze the billing rule per request; historical requests retain their original per-call rule.
ALTER TABLE ai_requests
 ADD COLUMN billing_strategy varchar(24) NOT NULL DEFAULT 'per_request',
 ADD CONSTRAINT ai_requests_billing_strategy_check
 CHECK (billing_strategy IN ('per_request','cumulative_tokens'));
CREATE INDEX ai_requests_cumulative_bucket
 ON ai_requests(user_id,tokens_per_point)
 WHERE billing_strategy='cumulative_tokens' AND status='succeeded';
