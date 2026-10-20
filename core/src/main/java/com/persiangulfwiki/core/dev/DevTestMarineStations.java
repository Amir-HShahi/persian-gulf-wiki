package com.persiangulfwiki.core.dev;

// The station-key prefix that marks marine readings as machine-minted. Readings have no
// dev_marker column, but every reading already carries a station key, and real station keys
// come from application.yaml — none of which can start with this. Shared by the mint route,
// the delete route that refuses anything else, and the sweeper.
final class DevTestMarineStations {

    static final String STATION_PREFIX = "dev-test-";

    private DevTestMarineStations() {
    }
}
