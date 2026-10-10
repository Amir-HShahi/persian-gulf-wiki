-- Live environmental readings for the homepage data cards (sea surface temperature, coral
-- bleaching stress, air quality), pulled from keyless public providers by
-- MarineDataIngestJob. Keys are stable machine identifiers; labels and units are rendered by
-- the frontend.

CREATE TABLE marine_metric_reading (
    id           BIGSERIAL PRIMARY KEY,
    -- One of MarineMetric's keys. Plain text rather than a CHECK, so retiring a metric from
    -- the enum never needs a migration; the read side skips keys it does not know.
    metric_key   VARCHAR(64)    NOT NULL,
    station_key  VARCHAR(64)    NOT NULL,
    -- The moment the value describes (an hour for Open-Meteo, a day's 12:00Z for NOAA), not
    -- when it was fetched.
    observed_at  TIMESTAMPTZ    NOT NULL,
    value        NUMERIC(12, 4) NOT NULL,
    source_key   VARCHAR(32)    NOT NULL,
    fetched_at   TIMESTAMPTZ    NOT NULL,
    -- The upsert target: providers re-send the same hours/days on every call, and a re-sent
    -- value overwrites rather than duplicates. Column order also makes this the chart index
    -- (metric + station equality, observed_at range).
    CONSTRAINT uq_marine_metric_reading UNIQUE (metric_key, station_key, observed_at)
);

-- One row per (source, station), because one station's call can fail while the others
-- succeed. last_success_at is what the API reports as lastSyncedAt; last_error is for
-- operators only and is never exposed through the API.
CREATE TABLE marine_source_sync (
    source_key       VARCHAR(32)  NOT NULL,
    station_key      VARCHAR(64)  NOT NULL,
    last_attempt_at  TIMESTAMPTZ  NOT NULL,
    last_success_at  TIMESTAMPTZ,
    last_error       VARCHAR(500),
    PRIMARY KEY (source_key, station_key)
);
