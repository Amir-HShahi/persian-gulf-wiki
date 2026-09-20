package com.persiangulfwiki.core.dev;

// The marker that flags a row as machine-minted and therefore disposable, for the content
// tables that have no natural string field to carry one. Shared by the three things that must
// agree on it per table: the controller that writes it, the delete route that refuses to
// touch anything else, and the sweeper that reclaims them in bulk.
//
// Users don't use this — their marker rides along on the email and username they had to have
// anyway (see DevTestUsers). A subject has no such field, and a source's only string field is
// a title a contributor typed, which a genuine citation really can spell "e2e-something". So
// both carry a dedicated nullable dev_marker column instead, and "minted by a fixture
// endpoint" is the column being non-null rather than a substring of real data.
final class DevTestFixtures {

    static final String MARKER = "e2e";

    private DevTestFixtures() {
    }
}
