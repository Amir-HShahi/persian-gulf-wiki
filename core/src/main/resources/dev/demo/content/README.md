# Demo content

The articles, sources and accounts the dev demo seeder (`POST /api/dev/demo-content`) creates. Demo
text for building and testing the frontend, written to read like real wiki content: names, places and
scientific names are real, but figures and details are approximate and unverified. Never import any of
it into real content.

- `users.json` — the demo accounts (`demo-<key>@dev.local`, the documented dev password).
- `sources.json` — citations. Real publishers and titles; every URL is under `https://example.org/demo/`
  so no made-up deep link looks real.
- `articles/<group>/<key>.json` — one article per file, in fa, en and ar, with its subject (when it has
  one) and complete infobox. `DevDemoDataset` documents the format and `DevDemoDatasetTests` checks it.

Inside a paragraph: `[[subject-key|shown text]]` links a subject (only articles that have a subject can be
linked), `{{source-key}}` cites a source, `**text**` is bold.
