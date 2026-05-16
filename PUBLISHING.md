# Publishing workflow

This repo ships **two** artifacts on different channels:

| Module             | Artifact                                                       | Channel              |
|--------------------|----------------------------------------------------------------|----------------------|
| `:lib`             | `de.firetail.compat.movebank:movebank-mirror:<version>`        | **Maven Central**    |
| `:cli`             | `movebank-mirror` CLI distribution archive (`.tar` + `.zip`)   | **GitHub Releases**  |

The library is what other Java projects depend on; the CLI is what
users download to mirror studies from the command line. Different
channels, different release commands, same git tag.

For the family-wide publishing reference (one-time setup of Sonatype
Central account, PGP key, namespace registration, etc.), see
[`movebank-api-client/PUBLISHING.md`](https://github.com/mcb77/movebank-api-client/blob/master/PUBLISHING.md).
Sections below cover what's specific to this repo.

---

## Part 1. One-time setup

All shared with `movebank-api-client`. Abbreviated here; see the
canonical doc above for full detail.

### 1.1 Sonatype Central account + namespace

The `de.firetail` namespace is already verified at
<https://central.sonatype.com> (one DNS TXT record on `firetail.de`).
All sub-namespaces are covered — you don't need to re-register
`de.firetail.compat.movebank`. Same approval.

### 1.2 Central user token

If you don't have one yet:
<https://central.sonatype.com/account> → *User Tokens → Generate Token*.
The plugin wants the `username:password` pair **base64-encoded**:

```bash
printf '%s' '<token-username>:<token-password>' | base64 -w0
```

The result is the value of `mavenCentralAuthToken` (passed via env in
§1.4).

### 1.3 PGP signing key

Already created. The 4096-bit RSA key published to
`hkps://keys.openpgp.org`, `keyserver.ubuntu.com`, and `pgp.mit.edu`.
The same key signs every artifact in the family. The secret half lives
in the `PGP_KEY` env var; never committed.

### 1.4 Environment variables

Same three properties as `movebank-api-client`. The `lib/build.gradle`
reads each via `findProperty(...)`:

| Property                  | Source                                                              |
|---------------------------|---------------------------------------------------------------------|
| `mavenCentralAuthToken`   | base64 of `<token-user>:<token-pass>`                              |
| `PGP_KEY`                 | ASCII-armored secret key block                                      |
| `PGP_PASSPHRASE`          | passphrase for the PGP key                                          |

The `ORG_GRADLE_PROJECT_*` env-var convention is the canonical way to
feed these to Gradle:

```bash
export ORG_GRADLE_PROJECT_mavenCentralAuthToken="$(printf '%s' '<user>:<pass>' | base64 -w0)"
export PGP_KEY="$(cat /path/to/pgp-secret.asc)"
export PGP_PASSPHRASE='your-passphrase'
```

For GitHub Actions, set `MAVEN_CENTRAL_AUTH_TOKEN`, `PGP_KEY`,
`PGP_PASSPHRASE` as repository secrets and surface via `env:` in the
publish workflow.

---

## Part 2. Releasing `movebank-mirror:lib` to Maven Central

### 2.1 Pre-flight: confirm the prerequisite

`movebank-mirror:lib` declares an `api` dependency on
`de.firetail.compat.movebank:movebank-api-client:<version>` (currently
`0.0.2`). The dependency name appears in the generated POM. **The
listed `movebank-api-client` version must already be `PUBLISHED` on
Central** — otherwise downstream Gradle/Maven resolution of
`movebank-mirror` will fail with "could not find movebank-api-client."

Confirm before publishing:

```bash
curl -sI https://repo1.maven.org/maven2/de/firetail/compat/movebank/movebank-api-client/0.0.2/movebank-api-client-0.0.2.pom \
  | head -1
# expect: HTTP/2 200
```

`movebank-api-client:0.0.2` was published earlier in the family
rollout; this check should pass. If you're cutting a release that
depends on a newer api-client version, publish that one first and
wait for it to reach `PUBLISHED` state on the Portal.

### 2.2 Pre-flight: disable local includeBuilds

`gradle.properties` exposes a `localMovebankApiClient=true` toggle for
sibling-checkout development. **This toggle must be commented out at
publish time** — otherwise Gradle substitutes the
`movebank-api-client` dependency with `project(':')` of the local
checkout, and the published POM will be wrong (the dep coordinate gets
lost).

```bash
grep '^local' gradle.properties
# All matching lines must be commented (start with #).
```

### 2.3 Update the version

In the root `build.gradle`:

```groovy
allprojects {
    group = 'de.firetail.compat.movebank'
    version = '0.0.2'                            // bump per release
}
```

Both modules (`:lib` and `:cli`) inherit the version. The library
publication will carry it as the Maven coordinate version; the CLI
distribution archive's filename will include it.

### 2.4 Smoke-test signing locally

Before pushing anything to Central, confirm signing produces the
expected `.asc` files:

```bash
./gradlew :lib:publishToMavenLocal
ls ~/.m2/repository/de/firetail/compat/movebank/movebank-mirror/<version>/
```

Expect eight files:

```
movebank-mirror-<version>.jar              .jar.asc
movebank-mirror-<version>.pom              .pom.asc
movebank-mirror-<version>-sources.jar      -sources.jar.asc
movebank-mirror-<version>-javadoc.jar      -javadoc.jar.asc
```

If any `.asc` is missing, the `signing` block isn't picking up
`PGP_KEY` — re-check §1.4. If a jar is missing entirely, the
`withSourcesJar()` / `withJavadocJar()` calls in `lib/build.gradle`
need verification.

While you're there, eyeball the generated POM:

```bash
cat ~/.m2/repository/de/firetail/compat/movebank/movebank-mirror/<version>/movebank-mirror-<version>.pom
```

Look for: correct `<groupId>de.firetail.compat.movebank</groupId>`,
correct `<artifactId>movebank-mirror</artifactId>`, the
`movebank-api-client` dep with matching group, and complete
`<licenses>` / `<developers>` / `<scm>` blocks.

### 2.5 Run tests (defensive)

Publishing doesn't trigger tests by default, but failing in production
is worse than failing in dev:

```bash
./gradlew :lib:test
```

Integration tests self-skip without `MOVEBANK_USER` / `MOVEBANK_PASSWORD`
env vars, so this is safe to run unattended.

### 2.6 Publish

The `tech.yanand.maven-central-publish` plugin adds the upload task:

```bash
./gradlew :lib:publishToMavenCentralPortal
```

**Note the `:lib:` prefix** — `./gradlew publishToMavenCentralPortal`
without it tries to publish from the root project, which isn't
configured for publication. The `:lib:` form targets the library
subproject.

Successful output ends with:

```
Upload success, response body: <deploymentId>
Checking deployment status, response body: {... "deploymentState":"PUBLISHING" ...}
Upload file success! current status: PUBLISHING.
```

### 2.7 Watch the deployment finish

The state machine on the Portal:

```
VALIDATING → VALIDATED → PUBLISHING → PUBLISHED
                                  ↓
                              (FAILED at any stage with errors[])
```

`lib/build.gradle` sets `publishingType = 'AUTOMATIC'`, so VALIDATED
transitions to PUBLISHING without a manual click. Live view at
<https://central.sonatype.com/publishing/deployments>. Typical
end-to-end time: 15–60 minutes.

If a stage fails, the Portal lists the errors per file (missing
`.asc`, unverified signing key, malformed POM, etc.). Fix locally and
re-run `:lib:publishToMavenCentralPortal`.

### 2.8 Verify resolvability

Once `PUBLISHED`:

```bash
curl -sI https://repo1.maven.org/maven2/de/firetail/compat/movebank/movebank-mirror/<version>/movebank-mirror-<version>.pom \
  | head -1
# expect: HTTP/2 200
```

Confirm a downstream consumer resolves from Central rather than via
includeBuild:

```bash
cd /home/mcb/devel/firetail/movebank-mirror-api
# (this repo has a localMovebankMirror toggle for sibling-checkout dev)
sed -i.bak 's/^localMovebankMirror=true/#&/' gradle.properties
./gradlew :compileJava --refresh-dependencies
mv gradle.properties.bak gradle.properties
```

The dependency line should resolve from Central, not from
`project(':lib')`.

### 2.9 Tag the git release

```bash
git tag -a v<version> -m "Release <version>"
git push origin v<version>
```

Tag name matches the artifact version. Note: this tag also serves as
the version-marker for the CLI release (Part 3) — same tag, both
artifacts.

---

## Part 3. Releasing the CLI to GitHub Releases

`:cli` is a runnable application, not a library. Distribute via
GitHub Releases (`bin/movebank-mirror` inside a `.tar` or `.zip`
archive). No Maven coordinates.

### 3.1 Build distribution archives

```bash
./gradlew :cli:distTar :cli:distZip
ls cli/build/distributions/
# → movebank-mirror-cli-<version>.tar
# → movebank-mirror-cli-<version>.zip
```

Each archive contains:

```
movebank-mirror-cli-<version>/
├── bin/movebank-mirror          ← launcher (sh + bat)
└── lib/*.jar                    ← runtime classpath
```

Users untar and run `bin/movebank-mirror` directly. JDK 21 must be on
their PATH.

### 3.2 Smoke-test the launcher

```bash
./gradlew :cli:installDist
./cli/build/install/movebank-mirror/bin/movebank-mirror --help
# Expect usage banner.
```

If `--help` works, the launcher's classpath is correct.

### 3.3 Create the GitHub release

Using `gh` CLI:

```bash
gh release create v<version> \
    cli/build/distributions/movebank-mirror-cli-<version>.tar \
    cli/build/distributions/movebank-mirror-cli-<version>.zip \
    --title "v<version>" \
    --notes "$(cat <<'EOF'
movebank-mirror <version>.

Java library: published to Maven Central as
de.firetail.compat.movebank:movebank-mirror:<version>.

CLI: attached to this release. Requires JDK 21. Untar/unzip and run
`bin/movebank-mirror --help` to get started.

See README.md and TODO.md for known limitations and the roadmap.
EOF
)"
```

Same tag `v<version>` as the library release in §2.9. GitHub will
attach the archives to the existing tag, no separate tag needed.

### 3.4 Verify the archive

```bash
gh release download v<version> -p '*.tar'
tar -xf movebank-mirror-cli-<version>.tar
./movebank-mirror-cli-<version>/bin/movebank-mirror --help
```

If this prints usage cleanly, the release is consumable.

---

## Part 4. Troubleshooting

### `BUILD SUCCESSFUL` but nothing in the Portal

Did you run `./gradlew publish` instead of `:lib:publishToMavenCentralPortal`?
`publish` only invokes `maven-publish`'s tasks, which without a declared
`repositories { ... }` block do nothing externally.

List discoverable tasks:

```bash
./gradlew :lib:tasks --all | grep -iE 'central|portal|bundle'
```

### `401 Invalid token`

Token must be **base64-encoded** `username:password`. Plain
`<user>:<pass>` or just the password fails:

```bash
ORG_GRADLE_PROJECT_mavenCentralAuthToken="$(printf '%s' '<user>:<pass>' | base64 -w0)"
```

Sanity-check the round-trip:

```bash
echo -n "$ORG_GRADLE_PROJECT_mavenCentralAuthToken" | base64 -d
# should print:  <user>:<pass>
```

### `403 Namespace not allowed`

Either the namespace is unverified or the artifact's `groupId` doesn't
match. Both should be fine — `de.firetail` is verified, and the root
`build.gradle` sets `group = 'de.firetail.compat.movebank'` for both
modules. If you see this error:

```bash
./gradlew :lib:generatePomFileForMavenJavaPublication
grep '<groupId>' lib/build/publications/mavenJava/pom-default.xml | head -1
# expect: <groupId>de.firetail.compat.movebank</groupId>
```

### Published POM depends on `project(':')` instead of `movebank-api-client`

You left `localMovebankApiClient=true` enabled in `gradle.properties`.
Comment it out, re-run `:lib:publishToMavenLocal`, re-check the POM,
then re-publish to Central. If the published version is already on
Central with the bad POM, you'll need a version bump and re-publish —
Central does not allow overwrites of published versions.

### Validation fails on missing `.asc` for sources/javadoc

`lib/build.gradle`'s `java { ... }` block must include
`withSourcesJar()` + `withJavadocJar()`, and the `signing` block must
`sign publishing.publications.mavenJava`. Confirm via
`:lib:publishToMavenLocal` and inspect the `.m2` directory — eight
files expected (§2.4).

### Key not visible to Central

After `gpg --send-keys`, propagation can take minutes. Verify:

```bash
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys $KEY_ID
```

For `keys.openpgp.org` specifically, the server strips UIDs until the
email on the UID is verified by clicking a link sent to it. Without
that click, Central sees the key but no identity attached and rejects
signatures as "unverified."

### Integration tests time out / hang during publish

They shouldn't run during publish at all — `:lib:publishToMavenCentralPortal`
doesn't trigger them. If they're hanging, something else (the
`build` task in a CI pipeline?) is running them. Check the workflow
file for a stray `./gradlew build` before the publish step.

---

## Part 5. Per-release checklist

Copy this when cutting a release:

```
[ ] movebank-api-client version this depends on is PUBLISHED on Central
    (curl repo1.maven.org/maven2/.../movebank-api-client-X.Y.Z.pom — HTTP 200)
[ ] gradle.properties: localMovebankApiClient commented out
[ ] root build.gradle: version bumped
[ ] CHANGELOG entry (if you keep one) or git log message clear
[ ] ./gradlew :lib:publishToMavenLocal — verify 8 files in ~/.m2
    (jar, pom, sources, javadoc + each .asc)
[ ] ./gradlew :lib:test passes
[ ] ./gradlew :lib:publishToMavenCentralPortal — Upload success
[ ] watch https://central.sonatype.com/publishing/deployments → PUBLISHED
[ ] curl repo1.maven.org pom — HTTP 200
[ ] downstream resolves from Central
    (with localMovebankMirror disabled in sibling repos)
[ ] ./gradlew :cli:distTar :cli:distZip
[ ] ./gradlew :cli:installDist && bin/movebank-mirror --help works
[ ] git tag -a v<version> -m '…' && git push origin v<version>
[ ] gh release create v<version> cli/build/distributions/*.{tar,zip} --title … --notes …
[ ] verify gh release download works for a stranger
```
