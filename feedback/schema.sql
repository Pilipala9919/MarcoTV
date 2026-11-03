-- Copyright (C) 2026 Pilipala
-- SPDX-License-Identifier: GPL-3.0-only
CREATE TABLE IF NOT EXISTS reports (
 id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
 state TEXT NOT NULL DEFAULT 'received' CHECK(state IN ('received','investigating','resolved')),
 payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS reports_expiry ON reports(expires_at);
CREATE TABLE IF NOT EXISTS feedback_limits (bucket TEXT PRIMARY KEY,n INTEGER NOT NULL,expires_at INTEGER NOT NULL);
