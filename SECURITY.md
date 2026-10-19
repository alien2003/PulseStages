# Security policy

PulseStages reads sleep, heart-rate and related health data from Health Connect and writes its own sleep sessions
back. This file explains what the app is designed not to do, how to report a vulnerability privately, and how the
release APKs are signed. What the app processes and stores is described in [docs/privacy.md](docs/privacy.md).

- [Supported versions](#supported-versions)
- [Reporting a vulnerability](#reporting-a-vulnerability)
- [Scope](#scope)
- [Design: no network access](#design-no-network-access)
- [Signing key policy](#signing-key-policy)
- [Verifying a download](#verifying-a-download)
- [Build and release pipeline](#build-and-release-pipeline)

## Supported versions

Fixes are made on `main` and published in a new release. There are no maintenance branches for older versions, so
only the latest release receives security fixes.

| Version | Supported |
|---|---|
| Latest release (currently 0.1.x) | Yes |
| Older releases, pre-releases, debug and CI builds | No; update to the latest release |

## Reporting a vulnerability

**Do not open a public issue, pull request or discussion for a vulnerability.**

Report it privately through GitHub:

1. Open the repository's **Security** tab and choose **Report a vulnerability**, or go directly to
   <https://github.com/alien2003/PulseStages/security/advisories/new>.
2. Describe the problem, the affected version (Settings → "Versions and licences" shows
   "Version … · algorithm … · model …"), the device and Android version, and the steps to reproduce it. A proof of
   concept helps; do not include real health data.

The report and the discussion stay private between you and the maintainers until a fix and an advisory are published.

**If there is no "Report a vulnerability" button.** GitHub shows it only while private vulnerability reporting is
enabled for the repository. Without it, open a regular issue titled "Security contact request" that asks for a private
contact and contains **no details** of the problem; the maintainer answers with a private channel.

## Scope

In scope:

- Any way for another app, a website or a person without access to the unlocked phone to obtain health data that
  PulseStages read, stored or exported, or to make it read, write or delete data.
- The exported components of the merged manifest of the release APK:
  - the app's own: the launcher activity `MainActivity`, the permission rationale activity
    `PermissionsRationaleActivity` (`ACTION_SHOW_PERMISSIONS_RATIONALE`, no permission required) and its Android 14+
    alias `ViewPermissionUsageActivity` for `VIEW_PERMISSION_USAGE`, which requires the `START_VIEW_PERMISSION_USAGE`
    permission;
  - merged from libraries: `androidx.health.platform.client.impl.sdkservice.HealthDataSdkService` of the Health
    Connect client library (action `androidx.health.platform.client.ACTION_BIND_SDK_SERVICE`, no permission
    required), WorkManager's `SystemJobService` (requires `BIND_JOB_SERVICE`) and `DiagnosticsReceiver` (requires
    `DUMP`), and `androidx.profileinstaller.ProfileInstallReceiver` (requires `DUMP`).

  The `FileProvider` used to share the log is not exported and grants read access only to the file being shared.
  Vulnerabilities inside a library component itself belong to that library's maintainers; how PulseStages exposes or
  uses it is in scope.
- PulseStages writing or deleting Health Connect data that is not its own.
- A release APK that requests the `INTERNET` permission or otherwise sends data off the device.
- The build and release pipeline: a way to get code into a signed release, or to read the signing secrets.

Out of scope:

- The accuracy of the sleep-stage estimates. They are estimates, not measurements; report accuracy problems as
  regular issues ([CONTRIBUTING.md](CONTRIBUTING.md#reporting-a-bug)).
- Vulnerabilities in Health Connect, Android, OHealth or other apps. Report them to their vendors.

## Design: no network access

- The app has **no `INTERNET` permission.** `app/src/main/AndroidManifest.xml` removes it with `tools:node="remove"`
  even if a library declares it, and `MergedManifestTest` fails the build if the merged debug or release manifest
  contains it. Without that permission, Android does not let the app open network sockets.
- All processing happens on the phone. The model is bundled in the APK; nothing is downloaded.
- The app's private data (settings, the ledger of estimated nights, the log) is excluded from Android backup and
  device-to-device transfer (`android:allowBackup="false"`, `res/xml/data_extraction_rules.xml`,
  `res/xml/backup_rules.xml`).
- Data leaves the app only when the user exports or shares it (log, CSV export, settings export) to a destination the
  user chooses, through the sessions it writes to Health Connect, which other apps with sleep read permission can
  read, and through the system log (logcat), which receives a copy of the internal log and can be read over adb when
  USB debugging is on ([docs/privacy.md](docs/privacy.md#5-logs)). "Ask Sleep as Android to sync" (on by default;
  Settings → "Written sessions", where the user can turn it off) sends Sleep as Android's sync broadcast
  (`com.urbandroid.sleep.REQUEST_SYNC`, restricted to its package) once the nights a run wrote or updated are complete
  (usually 1–2 hours after waking up) when Sleep as Android is installed, and nothing when it is not; it carries no
  extras and no data
  ([docs/privacy.md](docs/privacy.md#2-everything-stays-on-the-device)).
- Package visibility is limited to Health Connect, OHealth, Sleep as Android (only to check that it is installed
  before that sync request) and the apps that declare a Health Connect permission rationale; the app does not request
  `QUERY_ALL_PACKAGES` (also checked by `MergedManifestTest`).

## Signing key policy

- **One release key.** Every release APK (`PulseStages-<version>.apk`) is signed with the same release key, so later
  releases install over earlier ones as updates.
- **Where the key lives.** The release keystore exists outside the repository and, for the build, only in the
  repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. It is never committed:
  `.gitignore` excludes `*.jks`, `*.keystore` and `keystore.properties`.
- **When it is used.** Only the release workflow uses it, only for a `v*` tag, and it deletes the decoded keystore at
  the end of every run, also after a failure. A manual dry run builds and verifies a release-signed APK and then
  discards it; release-signed APKs are never stored as Actions artifacts.
- **Debug builds use a different key.** `PulseStages-<version>-debug.apk` is signed either with a throw-away key of
  that run or with an optional fixed debug key (`DEBUG_KEYSTORE_BASE64`). The workflow fails if the release APK is
  unsigned, signed with an Android debug key or signed with the same key as the debug APK. Debug builds are for testing
  only.
- **Backup.** The release keystore and its password are backed up outside the repository and the build machine,
  because Android only installs an update signed with the same key.
- **If the release key is ever lost or compromised**, a new key will be announced in a security advisory and in the
  release notes of the first release signed with it. Because Android refuses updates signed with a different key,
  users would have to uninstall and install the new build, then grant the Health Connect permissions again; the sessions
  PulseStages wrote stay in Health Connect.

How the keys are created, stored as secrets and backed up is described in
[docs/development.md](docs/development.md#signing-secrets).

## Verifying a download

Every release lists the SHA-256 of its release signing certificate in the notes ("Release signing certificate
SHA-256") and ships `SHA256SUMS.txt`:

```sh
sha256sum -c --ignore-missing SHA256SUMS.txt
apksigner verify --print-certs PulseStages-<version>.apk
```

`sha256sum -c` detects a damaged download; `--ignore-missing` (GNU coreutils) skips the line of the APK you did not
download, which would otherwise be reported as missing. The stronger check is the certificate: the `SHA-256 digest` that
`apksigner` prints must equal the value in the release notes, and must be the same for every release. `apksigner` is
part of the Android SDK build-tools.

## Build and release pipeline

- Every GitHub Action is pinned to a full commit SHA. Dependabot proposes pin updates at most once a month, and only
  for releases that are at least 7 days old.
- The workflows default to read-only tokens; only the job that publishes a release gets `contents: write`, and no
  checkout keeps the token in `.git/config` (`persist-credentials: false`).
- Signed builds use neither the Gradle cache that CI writes nor the build cache or configuration cache, so nothing from
  another workflow run can end up in a signed APK.
- Gradle and Python dependencies are pinned to exact versions (`gradle/libs.versions.toml`,
  `tools/train/requirements.txt`) and updated by hand; the Gradle wrapper checks the distribution's SHA-256.
