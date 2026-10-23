# Demo image library

The images the dev demo seeder (`POST /api/dev/demo-content`) publishes. Each folder holds one image's
variants exactly as the submission pipeline makes them: `w320/w640/w1280/w1920.webp` for a photo,
`2k/4k/8k.webp` for a 360° panorama. `manifest.json` describes every folder, and `ATTRIBUTION.md` credits
every original.

The variants ship pre-made, rather than going through the pipeline at seed time, because core still
queues image jobs on Redis while the worker now reads Postgres (`submission-pipeline/CORE_CHANGES.md`,
section 1), so nothing core queues is processed today. They are made by the worker's own
`internal/imagepipe` code, so they are what a real upload would produce.

## Licences

- Photos: public domain or CC0 only. The app shows no credit line, so nothing that requires one.
- Panoramas: CC BY-SA allowed (no public-domain 8k panoramas of the region exist); credited in
  `ATTRIBUTION.md`, which ships alongside them.

## Regenerating

Needs Docker, nothing else; the build happens in the pipeline's own build stage, so no Go or libvips
on the host, and no file in `submission-pipeline/` changes.

1. Put the originals in a scratch folder with a `chosen.json` listing `{key, kind, file, tags, title,
   author, license, page}` per image (`kind` is `IMAGE` or `PANORAMA_360`).
2. Build the pipeline's build stage: `docker build --target build -t pgw-demo-imgpipe-build -f submission-pipeline/Dockerfile .`
3. In a container from that image, copy a small `main` into `/app/cmd/demovariants/` that calls
   `imagepipe.Process` with `DefaultOptions()` for photos and `PanoramaOptions()` for panoramas, shrinking
   any source over the preset's `MaxPixels` to 16384 px wide first, and build it with `-tags vips`.
   (On Fedora, run the container with `--security-opt label=disable` so it can read the bind mounts.)
4. Copy each output folder here and regenerate `manifest.json`: `sha256` is the standard base64 SHA-256
   of the original file, `placeholder` and every variant's size and bytes come from the pipeline's result.

Keep the folder under 50 MB: it ships in every image, production included, even though only the `dev`
profile ever reads it.
