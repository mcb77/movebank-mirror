# TODO

Known gaps and planned improvements. See the
["Known limitations" section of the README](README.md#known-limitations)
for the gap descriptions framed for users; this file is the working
backlog for contributors, ordered roughly by priority.

Each item names the underlying gap (link to the README section), and the
likely shape of the fix. Open an issue if a real
use case bumps an item up.

---

## High-priority gaps

### 1. Refresh cached study metadata on demand

**Gap:** Metadata is downloaded once per study and never re-fetched, so
upstream changes (new tags, edited deployment windows, added sensor
types, licence changes) silently don't reach the mirror.

**Fix shape:** A `movebank-mirror metadata --refresh` flag that, for the
studies listed (or all currently mirrored if no `--study` is passed),
re-fetches metadata via `MovebankMirror.getStudyRefData(...)` and
overwrites the cached `<studyId>.json`. Existing event data is left
untouched — newly-mirrored sensor types are picked up on the next
event-data pass automatically.

The refresh pass should also detect **upstream-deleted** metadata
records (tags / individuals / deployments / sensor types that were
present in the cached JSON but are absent from the freshly-fetched
one), and the **study-level deletion** case (a previously-mirrored
study no longer appears in `getAllStudyIds()`). The default policy is
to write the new JSON exactly as upstream returned it and log the
diff to stderr — destructive overwrite of metadata is acceptable
because the previous version is recoverable via the local mirror's
`event_id` history and per-pair state files. Event-row deletion is
the harder case and is its own item below.

Optional sub-feature: a `--max-age 7d` filter so cron-driven calls
only refresh metadata older than N days.

**Acceptance:** integration test that mirrors metadata, mutates the
on-disk JSON, calls `metadata --refresh`, verifies the mutation is
overwritten. Plus a test that simulates a deployment being deleted
upstream and verifies the diff is logged.

---

### 2. Detect retroactive event-row edits via `update_ts` + `event_id` dedupe

**Gap:** Update mode picks up rows whose `update_ts` increased upstream,
but those rows may also exist in older catch-up chunks on disk. Readers
see both versions with no precedence rule. Deletions are invisible
because Movebank emits no tombstones.

**Fix shape:** Since `movebank-mirror` 0.0.2 we already request
`event_id` at download time (per
`EventDataDownloader.ALWAYS_REQUESTED`). The post-update reconciliation
becomes a per-(tag, sensor type) sweep:

1. After an update batch writes `<sensorType>_update_<ts>.csv`, read its
   `event_id` set.
2. For each older `<sensorType>_<ts>.csv` chunk in the same tag dir,
   rewrite it dropping any row whose `event_id` is in the update set
   (the newer version supersedes).
3. Persist a `last_reconciled` field in `state_<sensorType>.json` so
   repeated passes don't re-scan.

Deletions remain undetected (no tombstones, no signal to act on). The
roadmap entry for that is the `verify` subcommand below.

**Acceptance:** unit test that synthesises a chunk with `event_id=42`,
then an update with `event_id=42` (modified row), and verifies the
post-reconciliation chunk has the modified row only once.

---

### 3. `movebank-mirror verify` — row-count reconciliation against live API

**Gap:** No way to confirm a mirror is faithful. Long-term archives and
paper-supporting mirrors need this confidence.

**Fix shape:** A new CLI subcommand:

```
movebank-mirror verify -d /var/lib/movebank-mirror [--study <id>] [--sample <n>]
```

For each (study, tag, sensor type) in the mirror, query the live API
for the row count over a fixed time window (e.g. the full known
deployment range) and compare to the local CSV row count. Report
mismatches; non-zero exit on any divergence beyond a configurable
tolerance.

`--sample` picks N random (tag, sensor type) pairs per study rather
than full coverage — keeps verification cheap for large mirrors run
on a schedule.

**Acceptance:** integration test against a small fixture mirror that
artificially drops a row and confirms `verify` reports the
discrepancy.

---

### 4. Event-row deletion detection + on-deletion policy

**Gap:** Movebank deletes event rows (curator correction, embargo,
withdrawal, accident) without emitting any tombstone signal in the
API. The row simply isn't there on the next query. The update_ts
mechanism in TODO #2 cannot detect this — there is no `update_ts`
bump for a row that no longer exists.

This is the hardest of the drift cases and the one that touches
research integrity most directly: papers cite specific event sets,
and an undetected deletion means the cited data becomes
unverifiable.

**Detection strategy.** Since `movebank-mirror` 0.0.2 we download
the `event_id` column with every chunk. That lets us frame deletion
detection as a *set diff* on event_ids: ask upstream for the
event_id set over a time window, diff against the local set, anything
in local but not upstream has been deleted.

Three detection modes, layered by cost:

* **Sentinel windows (cheap, recurring).** Per (tag, sensor type),
  for fixed recent windows (e.g. last 7 / 30 / 365 d), fetch upstream
  with `attributes=event_id` only. Diff against local. The response is
  tiny (8 bytes per id). Catches >99% of deletions in actively-curated
  studies; misses long-tail deletions of historical data.

* **Full row-set diff (expensive, one-shot).** Same comparison but
  unbounded time window. Catches every deletion. For a 100M-event
  study this is ~1 GB of event_ids, hours of API. Reserve for explicit
  pre-publication audits.

* **Bloom-filter probe (speculative).** Build a Bloom filter of local
  event_ids; sample random subsets and confirm upstream still has
  them. Statistical drift detection without full-diff cost. Skip for
  v1.0; revisit if (a) and (b) prove insufficient.

**On-deletion policy.** Detecting the deletion is half the problem.
What the mirror should *do* about it is the other half — and it
genuinely depends on the user's purpose. The CLI should expose
three policies, defaulting to the safe middle:

```
movebank-mirror verify --deep --on-deletion=mirror      # propagate: remove locally
movebank-mirror verify --deep --on-deletion=quarantine  # default: move to sidecar
movebank-mirror verify --deep --on-deletion=preserve    # archive: keep + flag
```

- **mirror.** Delete the local row. Matches upstream semantics. Right
  default for transient working mirrors (developing an analysis).
- **quarantine.** Move the row to `<study>/<tag>/_quarantine/<sensorType>_<ts>.csv`
  with a `_quarantine_log.jsonl` recording the deletion timestamp and
  the upstream-query result that triggered it. Recoverable; out of
  the read path of normal queries. **Safe default.**
- **preserve.** Keep the row in place; write a `_deletions.jsonl`
  sidecar recording which event_ids were observed to be deleted
  upstream and when. The local CSV stays unchanged; downstream tools
  that care about provenance can read the sidecar. Right default for
  institutional / archival mirrors supporting paper reproducibility.

**Why this is more than an engineering concern.** Movebank's curation
model is *trust-the-publisher* — researchers can retroactively edit
or withdraw their data for any reason. That's a feature of the
platform, not a bug. But it creates a tension with reproducibility:
papers cite specific queries and the queries must remain
re-executable. A mirror that preserves what upstream loses is
genuinely valuable as a research-integrity layer — analogous to the
Internet Archive's relationship with the live web. The
`--on-deletion=preserve` policy is the new capability `movebank-mirror`
could add to the ecosystem that Movebank itself doesn't offer.

**Acceptance:** integration test against a mirror with a known
event_id set. Synthesise an "upstream" response that omits some ids,
confirm: (a) detection logs the deletions, (b) each
`--on-deletion` policy produces the right local state, (c)
quarantined rows are recoverable, (d) preserve-mode's `_deletions.jsonl`
is readable as a stream.

**Depends on:** TODO #3 (`verify` infrastructure — the CLI surface and
the live-API querying scaffolding land there first; this item extends
both).

---

## Medium-priority gaps

### 5. On-disk schema-version tag

**Gap:** The mirror tree has no `_format_version` marker. If the format
changes in a future release, tools that consume mirrors won't be able to
distinguish layouts.

**Fix shape:** Write a tiny `_format_version` file in the mirror root on
first-ever write (e.g. `{"version": 1, "tool": "movebank-mirror 0.0.2"}`),
and check it on every CLI invocation. Mismatches log a clear migration
hint.

The current layout is `version: 1`. We change the version only when
the layout changes incompatibly — adding new columns to event CSVs
doesn't count.

**Acceptance:** unit test that constructs a `_format_version: 2`
mirror dir and confirms the v1 CLI exits with a helpful error.

---

### 6. `movebank-mirror prune --study <id>`

**Gap:** "Re-mirror from scratch" is currently `rm -rf
<mirror>/<studyId>/`. Works, but is unfriendly and risks
fat-fingering the wrong directory.

**Fix shape:** A `prune` subcommand that removes the event data
(`<studyId>/<tagId>/*.csv`, `state_*.json`) for a study or set of
studies, optionally also removing the `<studyId>.json` metadata.
Confirmation prompt by default; `--yes` to skip.

```
movebank-mirror prune -d /var/lib/movebank-mirror --study 2911040
movebank-mirror prune -d /var/lib/movebank-mirror --study 2911040 --include-metadata
movebank-mirror prune -d /var/lib/movebank-mirror --older-than 365d  # rare cleanup of stale studies
```

Useful both as the "re-download from scratch" workaround for gap #2
and for managing disk usage.

**Acceptance:** unit test against a tmpdir mirror that verifies
expected files are removed and others preserved.

---

## Low-priority / nice-to-have

### 7. Concurrent (tag, sensor type) downloads

Currently the loop processes pairs strictly sequentially within a
study. For studies with hundreds of tags this means each pair waits
for all earlier pairs. A small fixed-size worker pool (e.g. 4) would
parallelise without becoming a noisy neighbour to the live API.

### 8. Per-study `last_sync_summary.json`

For dashboarding / monitoring: a small summary file per study
containing last successful sync time, row counts per (tag, sensor
type), and any non-fatal errors from the last pass. Trivial to write,
useful for ops.

### 9. Resumable interrupted update batches

The catch-up phase is resumable; the update phase generally
completes in one short request, but very large update batches (months
of late-arriving data) currently re-download from scratch if
interrupted. Adding cursor persistence within an update batch closes
that.

---

## What is not a gap

For completeness, these are sometimes raised but are working as
intended; do not file them as bugs:

- **Network errors mid-chunk.** The partial CSV is deleted if no rows
  were written; the next sync retries from the same cursor.
- **Concurrent runs against the same mirror dir.** Blocked by the
  `.lock` file in the mirror root; the second invocation exits cleanly.
- **Studies you no longer have access to.** The cached `<studyId>.json`
  stays on disk; the next event-data pass fails-with-log for that
  study but keeps going with the others.
- **License-acceptance changes upstream.** Out of scope for
  `movebank-mirror`; the licence callback is invoked at each metadata
  fetch, so a `metadata --refresh` (gap #1) will re-prompt.
