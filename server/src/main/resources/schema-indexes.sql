-- Keep the established names and column order for compatibility with existing H2 files.
CREATE INDEX IF NOT EXISTS idx_risk_event_retention ON risk_event (occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_risk_event_project_time ON risk_event (project_id, behavior_type, occurred_at, user_id);
CREATE INDEX IF NOT EXISTS idx_risk_event_user_history ON risk_event (project_id, user_id, behavior_type, occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_risk_event_user_online ON risk_event (project_id, user_id, behavior_type, received_at, id);
CREATE INDEX IF NOT EXISTS idx_global_batch_expiry ON risk_global_batch(created_at);
CREATE INDEX IF NOT EXISTS idx_global_summary_project_time ON risk_global_summary(project_id, bucket_at, user_id);
CREATE INDEX IF NOT EXISTS idx_global_summary_expiry ON risk_global_summary(bucket_at);
CREATE INDEX IF NOT EXISTS idx_global_state_expiry ON risk_global_state(updated_at);
CREATE INDEX IF NOT EXISTS idx_global_event_project_time ON risk_global_event(project_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_global_event_user_time ON risk_global_event(project_id, user_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_global_event_expiry ON risk_global_event(occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_global_sample_user_time ON risk_global_sample(project_id, user_id, occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_global_sample_expiry ON risk_global_sample(occurred_at, id);
