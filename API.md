# Shared capture API (no separate paid host)

The public demo capture runs as an ephemeral GitHub Actions job. Any authorized Zapia or client can call GitHub's REST API; the repo's GitHub credentials stay with that client and are never embedded in the app or sent to game pages. This is the free shared execution route; it is not an anonymous public endpoint.

## Authorization

Use a fine-grained GitHub token scoped to this repository with **Actions: write** to start jobs and **Actions: read** to check runs/download artifacts. Keep the token in the caller's secure GitHub connector/credential store; never place it in Pages JavaScript, app source, URLs, chat, or a capture request. Each Zapia that calls the API needs its own authorized GitHub connection.

## Start a capture

`POST https://api.github.com/repos/florenciaamor36-ship-it/web-asset-scraper/actions/workflows/capture-public-game.yml/dispatches`

Headers: `Accept: application/vnd.github+json`, `Authorization: Bearer <caller-held GitHub token>`, `X-GitHub-Api-Version: 2022-11-28`, `Content-Type: application/json`.

Body:

```json
{
  "ref": "main",
  "inputs": {
    "request_id": "venus-20261116-a1",
    "target_url": "https://demogamesfree.pragmaticplay.net/...",
    "play_seconds": "300"
  }
}
```

The workflow accepts public demo hosts explicitly allowlisted in `tools/capture_game.py`; the duration is limited to 1–600 seconds. The dispatch returns HTTP 204 when accepted. Give every request a unique `request_id` containing only letters, numbers, `_` or `-` (max 64 chars).

## Track and download

1. List recent runs: `GET https://api.github.com/repos/florenciaamor36-ship-it/web-asset-scraper/actions/runs?per_page=20` and match `display_title` to `Capture <request_id>`. Poll until `status` is `completed`; inspect `conclusion`.
2. List artifacts for the run: `GET https://api.github.com/repos/florenciaamor36-ship-it/web-asset-scraper/actions/runs/<run_id>/artifacts`. Match `webasset-capture-<request_id>`; its `archive_download_url` returns the ZIP.
3. Check `manifest.json`, especially `playSeconds`, `capturedAssets`, `capturedBytes`, `errors` and each asset's `available`/HTTP status. A successful job does not mean every requested asset was served by the vendor.

Artifacts are temporary (30 days). This mechanism has no always-on Render browser and does not need the user's phone. GitHub may enforce its public-repository Actions usage policies/limits; do not submit unrelated or unapproved targets. The capture is limited to public demo traffic and does not bypass authentication, DRM or partial-range protections.
