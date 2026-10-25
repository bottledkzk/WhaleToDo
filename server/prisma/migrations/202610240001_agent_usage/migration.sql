ALTER TABLE tasks ADD COLUMN category varchar(24) NOT NULL DEFAULT 'none'
 CHECK(category IN ('none','study','exercise','entertainment','work','social','travel','rest'));
ALTER TABLE ai_requests DROP CONSTRAINT ai_requests_mode_check;
ALTER TABLE ai_requests ADD CONSTRAINT ai_requests_mode_check CHECK(mode IN ('chat','plan','review','diary','share_copy','edit'));
ALTER TABLE ai_requests ADD COLUMN tokens_per_point integer NOT NULL DEFAULT 1000 CHECK(tokens_per_point>0);
ALTER TABLE ai_requests ADD COLUMN charged_points integer CHECK(charged_points>=0);
ALTER TABLE ai_requests ADD COLUMN input_tokens integer CHECK(input_tokens>=0);
ALTER TABLE ai_requests ADD COLUMN output_tokens integer CHECK(output_tokens>=0);
ALTER TABLE ai_requests ADD COLUMN continuation boolean NOT NULL DEFAULT false;
ALTER TABLE ai_requests ADD COLUMN compacted boolean NOT NULL DEFAULT false;
ALTER TABLE ai_requests ADD COLUMN proposal jsonb CHECK(octet_length(proposal::text)<=32768);
