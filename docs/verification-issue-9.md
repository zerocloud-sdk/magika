# Issue #9: signed candidate rehearsal

Candidate source: `5f74b9884329703cce5b888408fc741d1ba8d444`.
Fixed review baseline: `0d115930896a3c30d61a54418085420833c974fb`.
Coordinate: `net.zerocloud:magika:0.1.0`; model: `standard_v3_3`.

The implementation and compiler pin were reviewed and committed before the final
same-source rehearsal below. A later evidence-only commit archives these results;
the candidate continues to identify the exact source above. Formal Central upload,
publication and GitHub version tag/Release belong to #10 and were not performed.

## Final workflow evidence

[Signed rehearsal](https://github.com/zerocloud-sdk/magika/actions/runs/35283223620)
and [regular full CI](https://github.com/zerocloud-sdk/magika/actions/runs/35283191076)
both completed successfully at the candidate source. Rehearsal's `publish` job
was skipped. Counts below were read from the archived Surefire/Failsafe XML;
each runtime has zero failures, errors and skipped tests.

| Runtime | Actual JVM version | Unit tests | Integration tests | Signed offline consumer |
| --- | --- | ---: | ---: | --- |
| Java 8 | 1.8.0_504-b01 | 39 | 17 | Passed |
| Java 17 | 17.0.20.1+1 | 39 | 17 | Passed |
| Java 21 | 21.0.12.1+1-LTS | 39 | 17 | Passed |

The compiler was Eclipse Temurin **21.0.12.1+1-LTS**, pinned in both workflows.
The actual runner was **Ubuntu 24.04.5 LTS, x86_64 / CPU**. All three unsigned
builds had identical distribution hashes before signing. The Java 21 job also
passed all **28 release-tool tests**: 26 external HTTP boundary scenarios and
2 packaged-artifact/cryptographic and matrix-integrity tests. The Node test
suite's disposable fixture key is separate from the real candidate signing key.

## Candidate and signatures

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| magika-0.1.0-javadoc.jar | 172460 | `6b3469187bdb03e978b8e289ed44ddb8f0684c7f8eee6f769976fd6e5cf50470` |
| magika-0.1.0-sources.jar | 45033 | `d5493ab816bba5eab4c7aa633c1d9afeff8c43618318915f1e507fc768dd2c1c` |
| magika-0.1.0.jar | 2978079 | `f1332489436cd33ce3daa50952090d6c0c3bf525d099a41fb208733c5350cd33` |
| magika-0.1.0.pom | 7063 | `8bbb2fb0ad0b4a7a13a9d79374cc6def2d7e52d6c0b5427959cf3b7cdd0bfb7b` |

Content identity: `ba0b335229004f31c9ad6c9e3e68efdcf9f5e638fc6d51085ade8819a6d1a297`.
Signed Central-format bundle SHA-256: `017d2a8d4e9ed93b5eba5b53c766129794b1bf29d1a69c5a478e6f141196a581`.

All four files have actual detached signatures from the configured release key,
verified in a separate public-key-only GnuPG home. The trusted primary fingerprint
is `C5149FD6B5EF7C2126F1FD0FCC1A12E348E171D8`. The archive contains the public key
alongside a bundle holding four distribution files, four signatures and
MD5/SHA-1/SHA-256/SHA-512 checksums; it contains no private key or credentials.

The package checker verified Java 8 class version 52, public sources and Javadoc,
Apache-2.0/NOTICE, POM metadata and production/test dependency separation. The
main JAR's three model assets retain the exact #1 source, byte sizes and digests,
recorded in `candidate.json`. Runtime dependencies remain ORT 1.30.0 and Gson
2.13.2 with the existing annotation dependency; identification behavior is unchanged.

## Independent consumption

Each runtime consumed the same signed candidate using a copied standalone Maven
project, a fresh local repository and empty global/user settings. Candidate,
installed SDK, copied dependency and actual JVM-loaded JAR hashes all match the
main-JAR hash above. Dependencies were installed before an offline Maven build
and the Java-only chroot run. The latter has an isolated network namespace and
contains no Python, Maven, source checkout or external interface.

The consumer checked byte[], Path and InputStream, all three prediction modes,
ordered batch success/file errors, callback partial-delivery accounting and
shared-instance close. The isolated root deliberately lacks /proc and CA
certificates, so ORT emits CPU-discovery and telemetry warnings; real inference
and every result assertion pass. Original logs are in each `java-*/` directory.

The downloaded final candidate was also independently verified and consumed
locally with Java `1.8.0_492-8u492-ga~us2-0ubuntu1~24.04.1-b09`, another new
repository and the same Java-only, no-external-network boundary. See
`local-signed-consumer-java-8.log` in the archive.

## Evidence and reproduction

[Machine-readable summary](rehearsal/issue-9.json) and
[archived signed candidate, raw XML and logs](rehearsal/issue-9-verification.tar.gz).
Archive SHA-256: `7c3c2478a702f2aeb37ec22edd69fb0b08e31ab96b401b0b281a9c4e3f8a6b4a`.

The archive retains one copy of the signed bundle to avoid duplicate JAR bytes.
From this repository, extract it and restore the candidate directory:

```sh
rehearsal_dir=$(mktemp -d)
tar -xzf docs/rehearsal/issue-9-verification.tar.gz -C "$rehearsal_dir"
(cd "$rehearsal_dir/issue-9" && sha256sum -c SHA256SUMS)
git worktree add --detach "$rehearsal_dir/source" 5f74b9884329703cce5b888408fc741d1ba8d444
unzip -q "$rehearsal_dir/issue-9/candidate/central-bundle.zip" -d "$rehearsal_dir/bundle"
cp "$rehearsal_dir/bundle/net/zerocloud/magika/0.1.0/"* "$rehearsal_dir/issue-9/candidate/"
export MAVEN_GPG_FINGERPRINT=C5149FD6B5EF7C2126F1FD0FCC1A12E348E171D8
node "$rehearsal_dir/source/scripts/release/candidate.mjs" verify "$rehearsal_dir/issue-9/candidate"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
bash "$rehearsal_dir/source/scripts/consume-candidate.sh" "$rehearsal_dir/issue-9/candidate" \
  /usr/lib/jvm/java-8-openjdk-amd64 "$rehearsal_dir/consumer-java-8"
```

Use the installed JDK paths for the machine and a new consumer directory for
Java 17 and 21. Verifying/consuming this archived candidate needs only its public
key. Building a new candidate with identical Javadoc requires the pinned Temurin
compiler. See [the release guide](releasing.md) for complete rebuild, dispatch,
configuration and recovery commands. Extraction, archive checksums and all four
cryptographic signatures were also rechecked locally from this archive.

## Configuration and recovery

The `maven-central` Environment has all four required secrets and the separately
trusted fingerprint variable. Key unlocking, real signing and independent
verification succeeded. Read-only authenticated Central checks returned HTTP 200;
`net.zerocloud:magika:0.1.0` remained unpublished with no deployment. GitHub had no
`v0.1.0` tag, corresponding Release or `release-ledger` branch. The public key
retrieved from keyserver.ubuntu.com matched the configured fingerprint. Safe
status-only evidence is archived in `resources.json`; credential values are absent.

The workflow accepts immutable source/version inputs, runs full gates, preserves
the exact signed bundle and deployment identity, and implements automatic
publication followed by matching GitHub records. Recovery tests cover lost
upload/publish responses, failed persistence, transient status failure, validation
failure, observation deadlines, mirror delay, content/source/tag conflicts,
paginated reconciliation and expired deployment history. Actual Central upload
and formal-release acceptance remain untested here, intentionally assigned to #10.

## Public contract and version policy

| Required contract | Evidence |
| --- | --- |
| Input ownership and size bounds | README input examples/limits and public Javadoc for `Magika`, `MagikaOptions` and package overview |
| Scores, final-label rewrites and unknown results | `DetectionResult`, `RawPrediction`, `OverwriteReason` and package Javadoc |
| Errors, partial delivery and callback side effects | `MagikaException`, `BatchIdentificationException`, `BatchProgress`, README batch examples |
| Shared use and close lifecycle | `Magika.close()` and package Javadoc; unchanged concurrency and packaged tests |
| SDK/model versions, offline use and JNI requirements | `ModelInfo`, package Javadoc, README and signed offline consumers |
| Actual supported labels and platform | [214 final result labels](supported-types.md), derived from fixed assets/mapping/rules rather than all 353 knowledge-base entries; Ubuntu 24.04 x64 / CPU matrix above |
| Java 8 source/API/dependencies/Javadoc | `--release 8`, strict Javadoc source level 8, packaged class/dependency inspection and full real Java 8 run |
| Independent version policies | README retains patch for fixes, at least minor for capabilities/model/config changes and breaking 0.x APIs, migration notes, whole-asset updates and renewed compatibility acceptance |

## Standards

One initial low-priority duplication finding was fixed with a shared bundle-entry
helper. Follow-up review found zero hard violations and zero actionable smells.
The compiler pin and final evidence were also reviewed.

## Spec

The initial P1 pipeline gate finding was fixed with explicit Bash/pipefail in both
workflows. The initial P2 expired-history recovery finding was fixed using the
durable ID, authenticated published flag and eight matching public files without
requiring the old status entry. Follow-up review found zero remaining findings.
The compiler pin and final evidence were also reviewed.

Standards: 0 remaining; Spec: 0 remaining. Both axes independently reviewed the
fixed-baseline diff and rechecked the fixes. No maintainer approval is implied.

## Handoff and limitations

#10 chooses its final source, runs formal publication, waits for published,
creates the matching GitHub tag/Release and verifies a fresh consumer downloading
from Central. It incorporates #8's existing performance evidence. This rehearsal
makes no claim of those formal-publication results.

The historical file-descriptor assertion in [#6's record](verification-issue-6.md)
remains unresolved; later passing runs are not a claimed fix. Original resource
bounds and numerical tolerances are unchanged. The 24 pre-existing untracked files
retain their content, paths and untracked status. Only #9 implementation and
evidence files were committed; no Issue, PR or other repository was changed.
