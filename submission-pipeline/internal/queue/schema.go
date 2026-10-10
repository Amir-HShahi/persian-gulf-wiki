package queue

import _ "embed"

// Schema is the SQL that creates the jobs table, its indexes and the trigger
// that wakes workers on enqueue. It belongs in core's Flyway migrations; it is
// embedded here so tests can apply it to a scratch database.
//
//go:embed schema.sql
var Schema string
