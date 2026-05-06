# movebank-mirror

Mirrors [Movebank](https://www.movebank.org/) study metadata and event data to a
local file/folder structure. A first run catches up historical data in chunks;
subsequent runs pull only updates.

Ships as both a **command-line tool** (`cli/`) and an embeddable **Java library**
(`lib/`). Built on top of [`movebank-api-client`](https://github.com/mcb77/movebank-api-client).

- Re-runnable: per-(tag, sensor type) cursor + state file means an interrupted
  run resumes cleanly.
- Backoff-aware updates: 5 min → 15 min → 1 h → 6 h → 24 h → 7 d, reset on any
  pass with new rows.
- Single-process safe: `<mirror-dir>/.lock` blocks concurrent runs against the
  same directory.

---

## Quick start (CLI)

Build the CLI distribution and put it on your `PATH`:

```bash
./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/movebank-mirror/bin:$PATH"
export MOVEBANK_USER=...           # Movebank username
export MOVEBANK_PASSWORD=...       # see "Passing the password" below for safer alternatives

# Bootstrap a fresh mirror: pull metadata for every accessible study, then
# start the event-data sync loop (Ctrl-C / SIGTERM stops cleanly).
movebank-mirror -d /var/lib/mvb sync
```

Common shapes:

```bash
# Dry-run: list every accessible study without downloading.
movebank-mirror metadata --list

# Mirror two specific studies' metadata.
movebank-mirror -d /var/lib/mvb metadata --study 2911040 --study 2911107

# Daily cron job: metadata refresh + a single event-data pass, then exit.
movebank-mirror -d /var/lib/mvb sync --once

# Continuous service (e.g. systemd / Docker): event-data loop only,
# assuming metadata is already on disk.
movebank-mirror -d /var/lib/mvb eventdata
```

---

## CLI reference

```
movebank-mirror [GLOBAL OPTS] <command> [COMMAND OPTS]
```

### Commands

| Command     | What it does                                                                          |
|-------------|---------------------------------------------------------------------------------------|
| `metadata`  | Pull study metadata. One JSON file per study, named `%012d.json` under `--mirror-dir`. |
| `eventdata` | Sync event data. Loops forever by default; use `--once` for cron.                     |
| `sync`      | `metadata` pass, then `eventdata` (loop, or `--once`). The "just keep this current" mode. |
| `help`      | Show help for a command (also: `<command> --help`).                                   |

### Global options

| Flag                       | Env var               | Default                              | Notes                                              |
|----------------------------|-----------------------|--------------------------------------|----------------------------------------------------|
| `-u, --user`               | `MOVEBANK_USER`       | (required)                           |                                                    |
| `-p, --password`           | `MOVEBANK_PASSWORD`   |                                      | Discouraged on CLI — visible in `ps`.              |
| `--password-file FILE`     |                       |                                      | Read first line of `FILE`.                         |
| `--password-stdin`         |                       |                                      | Read first line of stdin.                          |
| `--base-url URL`           | `MOVEBANK_BASE_URL`   | `https://www.movebank.org/movebank`  |                                                    |
| `-d, --mirror-dir DIR`     | `MOVEBANK_MIRROR_DIR` | `./movebank-mirror`                  | Directory the mirror reads/writes.                 |
| `-v` / `-vv` / `-q`        |                       | info                                 | `-v` debug, `-vv` trace, `-q` warnings only.       |
| `-h, --help` / `-V, --version` |                   |                                      |                                                    |

### `metadata` options

| Flag                | Notes                                                                          |
|---------------------|--------------------------------------------------------------------------------|
| `--study ID`        | Restrict to specific study ids. Repeatable.                                    |
| `--exclude ID`      | Skip these study ids. Repeatable.                                              |
| `--license MODE`    | `auto` / `record` (default) / `reject`. See [License modes](#license-modes).   |
| `--dry-run`         | Print which studies would be fetched, don't download.                          |
| `--list`            | Print accessible study ids and names, then exit. Implies `--dry-run`.          |

### `eventdata` options

| Flag                       | Notes                                                                  |
|----------------------------|------------------------------------------------------------------------|
| `--once`                   | Single pass, then exit. Pairs with cron / `systemd-timer`.             |
| `--chunk-size N`           | Records per catch-up chunk. Default `50000`.                           |
| `--update-sleep DURATION`  | Sleep between passes once caught up. `5m`, `1h30m`, `250ms`, `PT5M`. Default `5m`. |
| `--study ID`               | Restrict to specific study ids. Repeatable.                            |

### `sync` options

Combines `metadata` + `eventdata` flags: `--once`, `--chunk-size`, `--update-sleep`,
`--license`, `--study`, plus `--skip-metadata` to run only the event-data side.

### License modes

| Mode      | Behavior                                                                              |
|-----------|---------------------------------------------------------------------------------------|
| `auto`    | Accept every license silently. **Read terms first** — you're agreeing on every study. |
| `record`  | Default. Accept and write `<studyId>-license.json` next to the metadata for an audit trail. |
| `reject`  | Refuse acceptance. Studies that require a license fail; useful as a permission probe. |

### Passing the password

In order of preference:

1. `MOVEBANK_PASSWORD` env var (clean for systemd / Docker / shell scripts).
2. `--password-file ~/.movebank.pw` (file mode `600`).
3. `--password-stdin` for piping: `pass show movebank | movebank-mirror --password-stdin sync`.
4. `--password ...` last resort — visible to other users via `ps`.

### Exit codes

| Code | Meaning            |
|------|--------------------|
| `0`  | Success            |
| `1`  | Generic error      |
| `2`  | Usage / bad args   |
| `3`  | Authentication     |
| `4`  | Mirror-dir lock held by another process |
| `5`  | I/O error          |
| `130`| Interrupted (SIGINT/SIGTERM) |

---

## On-disk layout

```
<mirror-dir>/
├── 000002911040.json              study metadata: study, sensors, tags,
│                                  deployments, individuals, attribute lists
│                                  per sensor type
├── 000002911040-license.json      accepted-license record (license=record)
├── .lock                          single-process lock (managed by the CLI)
└── 000002911040/                  one directory per study
    └── <tagId>/                   one directory per tag
        ├── state_<sensorTypeId>.json   sync cursor + backoff state
        ├── <sensorTypeId>_<yyyyMMddHHmmss>.csv         catch-up chunks
        └── <sensorTypeId>_update_<yyyyMMddHHmmss>.csv  update batches
```

Filenames use a 12-digit zero-padded study id so directory listings sort
chronologically by Movebank's id assignment.

---

## How the event-data sync works

For each `(tag, sensorType)` pair the downloader alternates between two modes,
tracked in `state_<sensorTypeId>.json`:

**Catch-up.** Downloads up to `--chunk-size` records per chunk (default 50 000),
using the last seen `timestamp` as the cursor. Repeats until a chunk returns
fewer rows than the limit; then transitions to update mode.

**Update.** Polls for new rows using `update_ts` as the cursor. An exponential
backoff ladder (5 min → 15 min → 1 h → 6 h → 24 h → 7 d) extends the polling
interval after empty passes; any pass with new rows resets the interval to its
minimum.

A pass over the whole mirror yields control between passes for `--update-sleep`
(default 5 min) so the loop is friendly under `systemd` / `screen`.

---

## Library usage

The library (Maven coords `de.firetail.compat.movebank:movebank-mirror`) exposes
the same building blocks the CLI uses:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import de.firetail.compat.movebank.api.client.LicenseChecker;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.mirror.MovebankMirror;
import de.firetail.compat.movebank.mirror.Study;
import de.firetail.compat.movebank.mirror.StudyId;
import de.firetail.compat.movebank.mirror.StudyJson;
import de.firetail.compat.movebank.mirror.eventdata.EventDataDownloader;
import de.firetail.compat.movebank.mirror.eventdata.EventDataSyncLoop;

import java.io.File;
import java.util.Set;

LicenseChecker autoAccept = html -> true;
MovebankApiClient client = new MovebankApiClient(
        "https://www.movebank.org/movebank",
        System.getenv("MOVEBANK_USER"),
        System.getenv("MOVEBANK_PASSWORD"),
        autoAccept);

File baseDir = new File("/var/lib/mvb");
baseDir.mkdirs();

// 1. Pull metadata for every accessible study.
MovebankMirror mirror = new MovebankMirror(client);
ObjectMapper mapper = new ObjectMapper();
for (StudyId studyId : mirror.getAllStudyIds()) {
    Study study = mirror.getStudyRefData(studyId);
    File studyFile = new File(baseDir,
            String.format("%012d.json", Long.parseLong(studyId.studyId())));
    mapper.writerWithDefaultPrettyPrinter()
          .writeValue(studyFile, new StudyJson(study));
}

// 2. Sync event data — tunable chunk size, update sleep, study filter.
EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 50_000);
new EventDataSyncLoop(baseDir, downloader)
        .setUpdateSleepMs(5 * 60 * 1_000L)
        .setStudyIdFilter(Set.of("2911040", "2911107")::contains)
        .run();   // blocking; interrupt the thread to stop
```

Implement your own `LicenseChecker` to plug in a different acceptance policy
(prompt-driven, log-only, deny-by-default, etc.).

---

## Building

Requires JDK 21.

```bash
./gradlew build                         # compile + unit tests for both modules
./gradlew :lib:integrationTest          # hits the Movebank API; needs MOVEBANK_USER/PASSWORD
./gradlew :cli:installDist              # cli/build/install/movebank-mirror/bin/movebank-mirror
./gradlew :cli:distTar :cli:distZip     # release archives in cli/build/distributions/
./gradlew :lib:publish                  # publish library jar (signing keys required)
```

Integration tests self-skip when credentials are not set, so they are safe to
include in `:check`.

---

## Project layout

```
movebank-mirror/
├── lib/        de.firetail.compat.movebank:movebank-mirror — library, published to Maven Central
└── cli/        de.firetail.compat.movebank:movebank-mirror-cli — CLI distribution (application plugin)
```

The library has zero CLI dependencies; consumers who only want the embeddable
API don't pull in picocli.

---

## Local development against an unreleased movebank-api-client

If you have `movebank-api-client` checked out as a sibling directory:

```bash
./gradlew -PlocalMovebankApiClient=true build
```

…or set `localMovebankApiClient=true` in `gradle.properties`. The same pattern
applies downstream: `BasicImport`'s `gradle.properties` exposes
`localMovebankMirror=true` to consume this repo via `includeBuild`.

---

## License

LGPL-2.1, matching `movebank-api-client`. See [LICENSE](LICENSE).
