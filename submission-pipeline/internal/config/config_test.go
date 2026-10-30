package config

import (
	"strings"
	"testing"
	"time"
)

// setRequired gives Load every required variable, so a test only sets what it
// is about.
func setRequired(t *testing.T) {
	t.Helper()
	for _, key := range []string{
		"DB_USERNAME", "DB_PASSWORD", "MINIO_ENDPOINT", "MINIO_ACCESS_KEY",
		"MINIO_SECRET_KEY", "MINIO_RAW_BUCKET", "MINIO_DERIVED_BUCKET",
	} {
		t.Setenv(key, "x")
	}
}

func TestJobRetentionDefaultsToThirtyAndNinetyDays(t *testing.T) {
	setRequired(t)
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Jobs.KeepDone != 30*24*time.Hour || cfg.Jobs.KeepFailed != 90*24*time.Hour {
		t.Errorf("keep done %s, failed %s", cfg.Jobs.KeepDone, cfg.Jobs.KeepFailed)
	}
}

func TestJobRetentionIsZeroOrAtLeastADay(t *testing.T) {
	for value, ok := range map[string]bool{
		"0":    true, // keep forever
		"24h":  true,
		"720h": true,
		"10m":  false, // meant 10 days, would delete results nobody has read
		"23h":  false,
		"-24h": false,
	} {
		t.Run(value, func(t *testing.T) {
			setRequired(t)
			t.Setenv("JOBS_KEEP_DONE", value)
			_, err := Load()
			if ok && err != nil {
				t.Errorf("refused: %v", err)
			}
			if !ok && (err == nil || !strings.Contains(err.Error(), "JOBS_KEEP_DONE")) {
				t.Errorf("accepted, or the error does not name the setting: %v", err)
			}
		})
	}
}
