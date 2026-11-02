-- Preserve all existing rows while admitting longer model responses.
SET search_path TO whaletodo, public;
ALTER TABLE ai_requests DROP CONSTRAINT ai_requests_response_text_check;
ALTER TABLE ai_requests ADD CONSTRAINT ai_requests_response_text_check
  CHECK (octet_length(response_text) <= 4194304);
