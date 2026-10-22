package queue

import _ "embed"

// Schema is the SQL that creates the jobs table, its indexes and the trigger
// that wakes workers on enqueue. The worker applies it at startup through
// internal/install, and tests apply it to a scratch database.
//
//go:embed schema.sql
var Schema string
