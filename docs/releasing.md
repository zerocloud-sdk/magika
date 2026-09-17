# Signed candidates and recoverable publication

This is the #9 rehearsal path. Formal Maven Central publication and the versioned
GitHub tag/Release are executed and accepted separately in
[#10](https://github.com/zerocloud-sdk/magika/issues/10). Rehearsal never uploads to
Central and never creates a tag, Release, or release-ledger branch.

## Source, version and gates

Commit the intended version in `pom.xml` before dispatch. Supply its complete
40-character source commit SHA; branch names and mutable tags are not accepted as
the source input. The workflow checks out that SHA in every job. It does not edit
the version or commit generated build files. The current coordinate is
`net.zerocloud:magika:0.1.0`, with public packages under `net.zerocloud.magika`.

The existing [verification workflow](../.github/workflows/verify.yml) is also the
reusable release gate. Each Ubuntu 24.04 x64 / CPU job builds with JDK 21, targets
Java 8, and executes the complete real-ORT regression suite on Java 8, 17 or 21.
It retains XML reports, actual JVM/OS versions, build logs, the standalone
offline-consumer log, and the four unsigned distribution files. No resource
threshold, numerical tolerance or test is relaxed for release.

All three builds must have the same source and identical JAR, sources, Javadoc and
POM SHA-256 values. Only then does `maven-central` sign one candidate. All four
signatures are checked cryptographically in a new public-key-only GnuPG home,
against the separately configured full primary-key fingerprint. Each signed
artifact and signature is also checked against the upload ZIP. Three independent
Maven repositories then consume that exact signed candidate on Java 8, 17 and 21.
The publish job depends on every gate and is disabled in `rehearsal` mode.

The package inspector checks exact assets against the pinned source manifest,
class version 52, public source and Javadoc coverage, all three JARs' LICENSE and
NOTICE, POM coordinates and publication metadata, and the fixed runtime/test
dependency boundary. Sources omit the large ONNX binary; the main JAR includes
the entire authenticated asset set. Release tools and their fixture keys are
outside production artifacts and are not SDK or regular Maven build dependencies.

## Configuration

The GitHub Environment is `maven-central`:

| Kind | Name | Purpose |
| --- | --- | --- |
| Secret | `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored release signing key |
| Secret | `MAVEN_GPG_PASSPHRASE` | Signing key passphrase |
| Variable | `MAVEN_GPG_FINGERPRINT` | Trusted full primary-key fingerprint, uppercase |
| Secret | `CENTRAL_USERNAME` | Central Portal user-token username |
| Secret | `CENTRAL_PASSWORD` | Central Portal user-token password |

Provision these through a secure local file or the GitHub secret UI, never a
command-line literal, issue, commit, or log. The scripts do not echo credentials,
HTTP bodies or child-process signing diagnostics. Signing imports into a temporary
mode-0700 GnuPG home and removes it and its agent on exit. The signed candidate
contains only a public key. The currently verified release-key fingerprint is
`C5149FD6B5EF7C2126F1FD0FCC1A12E348E171D8`.

Release tooling uses Node **24.18.0**, GnuPG **2.4.4** and Ubuntu's existing
zip/unzip tools, without npm packages. Signing checks the GnuPG version. All
Actions are pinned to commit SHAs; the only additional Maven plugin is Source
Plugin **3.4.0**. Existing runtime dependencies remain ORT **1.30.0** and Gson
**2.13.2** (with its existing Error Prone annotation dependency).

## Rehearsal

From the repository, with the source already committed and pushed:

```sh
gh workflow run release.yml --repo zerocloud-sdk/magika --ref main \
  -f version=0.1.0 -f source_commit="$(git rev-parse HEAD)" -f mode=rehearsal
gh run list --repo zerocloud-sdk/magika --workflow release.yml
gh run download RUN_ID --repo zerocloud-sdk/magika -D /tmp/magika-rehearsal-evidence
```

Keep `signed-candidate/`, `signature-verification`, all three
`verification-java-*` and all three `signed-consumer-java-*` artifacts together.
`candidate.json` records coordinate, immutable source SHA, model/upstream identity,
asset sizes/digests, four artifact hashes, four signature hashes, trusted signer
and the upload bundle hash. `SHA256SUMS` offers an additional convenient check;
the verifier authenticates signatures as well as hashes. Actions artifacts are
retained for 90 days for the signed candidate; download them for longer retention.

For a local reproduction, use a clean checkout of the same source and make the
three signing variables above available through a secure environment. Choose
new output paths for each run:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp -Dtest.java.home=/usr/lib/jvm/java-8-openjdk-amd64 clean verify
node scripts/release/candidate.mjs stage 0.1.0 "$(git rev-parse HEAD)" /tmp/magika-candidate
node scripts/release/candidate.mjs sign /tmp/magika-candidate
node scripts/release/candidate.mjs verify /tmp/magika-candidate
bash scripts/consume-candidate.sh /tmp/magika-candidate \
  /usr/lib/jvm/java-8-openjdk-amd64 /tmp/magika-consumer-java-8
```

Repeat the full `mvn verify` with Java 17/21 as `test.java.home`, preserving each
runtime's XML and logs before the next run. Consume the **same** signed candidate
with each runtime and a different empty directory. CI performs this complete
matrix automatically.

The consumer is copied outside the SDK checkout, has no parent POM, and installs
the four candidate files into a fresh `maven.repo.local` using the supplied POM.
Both global and user Maven settings are replaced with empty settings. Before and
after dependency resolution, the installed and copied SDK bytes must match the
verified candidate. An offline Maven build follows dependency installation.
Then `verify-offline.sh` runs the examples inside a Java-only chroot and a fresh
network namespace. The JVM prints its loaded SDK JAR location and SHA-256 and
checks the expected hash. No SDK reactor output, source directory, user cache,
Python, Maven or external network is available to the isolated Java process.
Examples exercise all three single inputs, all prediction modes, ordered batch
delivery/errors, and shared-instance shutdown.

## Formal publication and recovery (#10)

Only during #10, dispatch the same workflow with `mode=publish`. This automatically
publishes after verification; it is not a validation-only upload. The workflow:

1. Checks GitHub tag/Release, Central's published flag, public artifact bytes and
   any prior durable release record before upload.
2. Atomically preserves the signed bundle, public key, candidate manifest and
   initial state in an independent `release-ledger` branch, under
   `releases/VERSION/`. This branch has no release tag and does not alter main.
3. Persists `uploading` intent before uploading. The deployment name includes
   version, full source SHA and signed-bundle SHA-256. It persists the returned
   deployment ID before further work, and prints that public ID for recovery.
4. Uses Central `USER_MANAGED` validation, compares all eight validated files
   against the signed candidate, then automatically calls the publish endpoint.
5. Polls the same deployment until `PUBLISHED`, waits until all eight exact files
   are available from Central, then creates `vVERSION` at the original source SHA
   and its matching GitHub Release. The Release links the durable record.

Re-run with the **same version and source SHA**. The signing step first restores
the exact previous bundle and signatures from the ledger, so a retry does not
generate different signature bytes. The verifier checks the restored bundle
against the newly built unsigned bytes and the configured trusted fingerprint.
The publish step re-reads Central and GitHub, rather than trusting the last local
phase. Identical completed operations are left intact. A mismatched source,
artifact, signature, signer, tag, or Release identity fails explicitly; the
workflow never overwrites a published coordinate or moves an existing tag.

| Interruption | Retry behavior |
| --- | --- |
| Upload response or deployment-ID write lost | Search all pages of the authenticated Central deployment list for the exact recorded name; bind the unique match to the existing record |
| Ambiguous upload, no deployment yet visible | Stop without another upload; inspect Portal and re-observe the same operation |
| Known deployment ID needs reconciliation | Supply `recovery_deployment_id`; status name/coordinates must match, and validated/published bytes are checked before completion |
| Validation/publishing takes too long, or observation has a network error | Retain ID and bundle; re-run polls that same deployment |
| Central validation is `FAILED` | Retain diagnostics in Portal and terminal identity in the ledger; no automatic drop, replacement or re-upload |
| Central is `PUBLISHED`, mirror incomplete | Wait for matching artifact and signature bytes; do not create GitHub records yet |
| Central is published, GitHub tag/Release missing | Verify existing Central bytes, then finish only the missing GitHub operations |
| Central deployment history has expired | The saved deployment ID, authenticated published flag and all eight matching public files prove completion; the old status entry is not required |
| Tag created, Release creation failed | Check the tag's resolved SHA, then create the missing matching Release |

An empty deployment list after an uncertain request is not proof of failed
upload. If Portal/support confirms that no upload ever occurred, an operator can
review the durable record and explicitly return its phase to `prepared` on the
ledger branch while retaining the candidate; this is a documented manual repair,
not an automatic retry. Failed validation requiring different bytes requires a
new candidate/version decision; the script never silently rewrites its identity.
Do not delete the ledger, candidate or deployment while investigating failure.
Central's [deployment retention](https://central.sonatype.org/faq/what-happened-to-my-deployments/)
does not replace the durable release ledger.

Recovery tests use actual local HTTP endpoints implementing the documented
Central and GitHub boundaries, including response loss, authentication failures,
partial completion, state persistence failure, content conflicts and delayed
visibility. They do not publish a real version:

```sh
mvn -B -ntp -DskipTests package   # build artifacts for the cryptographic verifier test
node --test scripts/release/*.test.mjs
node scripts/release/supported-types.mjs --check
```

The cryptographic test creates a disposable **test-only** key; release evidence
must come from the configured release key, never this fixture.

## Official interfaces checked for this implementation

- [Central bundle requirements](https://central.sonatype.org/publish/requirements/):
  four distribution files, detached signatures, required MD5/SHA-1 checksums and POM metadata.
- [Central Publisher API](https://central.sonatype.org/publish/publish-portal-api/)
  and [its OpenAPI schema](https://central.sonatype.com/api-doc): upload/status,
  validated downloads, automatic client-triggered publication, published checks
  and paginated deployment reconciliation.
- [GitHub Git references](https://docs.github.com/en/rest/git/refs),
  [Git data](https://docs.github.com/en/rest/git),
  [repository contents](https://docs.github.com/en/rest/repos/contents) and
  [Releases](https://docs.github.com/en/rest/releases/releases): durable commits,
  compare-and-update state and final source identity.
- [Maven Source Plugin](https://maven.apache.org/plugins/maven-source-plugin/jar-no-fork-mojo.html):
  attached sources without a forked lifecycle.
- [Node 24.18.0 distribution](https://nodejs.org/download/release/v24.18.0/SHASUMS256.txt).

SDK/model version rules remain in the [README](../README.md): fixes raise patch;
capabilities, model/configuration changes and breaking 0.x API changes raise at
least minor, with migration notes for breaking changes. Replace the entire asset
set and rerun compatibility acceptance before changing models. The unresolved
historical descriptor assertion in [#6's record](verification-issue-6.md) remains
an explicit limitation; later passing runs are not a claimed fix.
