package com.persiangulfwiki.core.dev;

// What marks a row as part of the demo dataset minted by POST /api/dev/demo-content, shared by the
// seeder that writes it and the wipe that removes it.
//
// Deliberately not DevTestFixtures.MARKER ("e2e") or DevTestUsers.EMAIL_PREFIX ("e2e-"): every
// DevTest*Sweeper deletes e2e rows older than its TTL and every fixture DELETE accepts any e2e id,
// so demo data under that marker would vanish within hours or be removable one row at a time by an
// E2E suite. Under its own marker neither population can reach the other's rows.
final class DevDemoContent {

    static final String MARKER = "demo";

    // Demo accounts are demo-<name>@dev.local: the wipe matches prefix and domain together, so
    // an address someone registers by hand under another domain is never matched.
    static final String EMAIL_PREFIX = "demo-";
    static final String EMAIL_DOMAIN = "@dev.local";
    static final String USERNAME_PREFIX = "demo_";

    // Where the shipped image library is uploaded once in the media bucket. Each gallery item gets
    // server-side copies under its own public/{mediaId}/ prefix, which is the only place the read
    // path builds a public URL for.
    static final String LIBRARY_PREFIX = "demo-library/";

    // Seeds the java.util.Random behind every "pick one of these" choice, so two runs produce the
    // same dataset and a frontend screenshot stays comparable between runs. Determinism, not
    // security: nothing random here is a secret, a token or an identifier anyone relies on.
    static final long RANDOM_SEED = 1_404L;

    static final String CLASSPATH_ROOT = "dev/demo/";

    private DevDemoContent() {
    }
}
