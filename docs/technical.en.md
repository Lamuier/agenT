# agenT technical notes

For developers and contributors. This document covers the stack, build and release flow, and data-security implementation. The README is for end users and keeps only product and usage notes.

[中文](technical.md) | **English**

The Chinese [technical.md](technical.md) is canonical.

## Stack

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.2.10 (JVM 17 toolchain) |
| UI | Jetpack Compose + Material 3 (Compose BOM 2026.08.00) |
| Architecture | AndroidX Lifecycle (StateFlow / ViewModel), Activity Compose, Biometric, Browser Custom Tabs |
| Build | Android Gradle Plugin 9.2.1 · Gradle 9.5.1 |
| Platform | `compileSdk` / `targetSdk` = 37 · `minSdk` = 26 (Android 8.0) |
| Application ID | `com.lamuier.agenT` |
| Source namespace | `com.lamuier.agenT` (matches the application ID) |
| Version | `versionCode` = 2 · `versionName` = 1.0.1 |
| Size | Release uses R8 shrinking and resource shrinking. Dependencies are AndroidX (including Browser Custom Tabs) and Biometric only—no third-party networking or DI |

## Build and release

### Shared assistant UI and status

Service connections in Settings is the shared account / token entry. Overview uses matching Cursor / Codex cards, Usage supports provider filtering, and Status shows both summaries with selectable details. Both widget families bind both providers, refresh independently, and use cached results. Status-only widgets do not request quota APIs.

Public status shares `ServiceStatusRepository`, `ServiceStatus`, and `StatusJsonParser`, with separate caches and in-flight requests per provider. Cursor uses summary / incidents on `status.cursor.com`; Codex uses summary / components / incidents on `status.openai.com`. OpenAI summary may omit Codex components, so the complete list is fetched separately, then scoped to Codex Web / API / CLI / desktop / extension and related incidents. Incomplete components produce unknown status; unrelated OpenAI outages do not become Codex outages. Public status requests carry no credentials.

The project uses a single entry `build.ps1` (Android SDK 37 must be configured first):

```powershell
.\build.ps1                 # Debug build
.\build.ps1 -Install        # Debug build + adb install
.\build.ps1 -Release        # Signed Release package to dist/
.\build.ps1 -SetupSigning   # Configure / rebind Release signing
```

The script looks for the Android SDK in `local.properties` `sdk.dir`, then `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `%LOCALAPPDATA%\Android\Sdk`.

Gradle does not inherit the Windows system proxy. For a proxy, configure `%USERPROFILE%\.gradle\gradle.properties`:

```properties
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
```

Debug runs unit tests and Lint first, then produces `app\build\outputs\apk\debug\app-debug.apk`.

### Release signing and packaging

The current local configuration uses `agent-release.p12`, `release-password.txt`, and the public certificate in the sibling `Android-signing/` directory. Relative paths allow the same key to be used on Windows after copying both sibling directories. The password is read only during the build and is not written to source or logs.

The keystore is not in the repo. Signing metadata defaults to `.signing\` at the project root (override with `-SigningRoot`). The first package run binds the local `debug.keystore` if nothing is configured yet. You can also configure explicitly:

```powershell
.\build.ps1 -SetupSigning                         # reuse debug.keystore
.\build.ps1 -SetupSigning -AdoptKeystore path.p12 -KeyAlias release -StorePassword ***
.\build.ps1 -SetupSigning -GenerateNewKey -ForceRebind   # new key (cannot overwrite old packages)
```

Day-to-day release:

```powershell
.\build.ps1 -Release
```

Clean is off by default (Windows often fails when `app\build` is locked). Add `-Clean` for a clean build, `-Online` to fetch dependencies, `-SkipChecks` to skip tests / Lint. The script checks the signing cert, zipalign, package name, version, permissions, and Manifest, and writes `dist\agenT-v1.0.1-release.apk`.

### Data-security notes

```text
Android App (on device)
  ├─ Cursor Bearer ──▶  https://api2.cursor.sh/...DashboardService/*
  │                      GetCurrentPeriodUsage / GetPlanInfo /
  │                      GetUsageLimitStatusAndActiveGrants /
  │                      GetAggregatedUsageEvents (per-model token totals; startDate/endDate for history)
  │                      GetSandUsageStatus (Grok Bot weekly quota)
  ├─ Cursor session ──▶  https://cursor.com/api/auth/stripe
  │                      https://cursor.com/api/background-composer/list (cloud tasks, including Grok Bot sources)
  ├─ Chrome Custom Tabs ──▶ https://cursor.com/agents?id=<bcId> (full conversation, browser cookies)
  │                      https://cursor.com/agents (official Agents page)
  │                      https://github.com/... (task PR, URL allowlist)
  ├─ Codex Bearer  ──▶  https://chatgpt.com/backend-api/wham/usage
  ├─ Public status (no token) ──▶  https://status.cursor.com/api/v2/summary.json
  │                      https://status.cursor.com/api/v2/incidents.json
  │                      https://status.openai.com/api/v2/summary.json
  │                      https://status.openai.com/api/v2/components.json
  │                      https://status.openai.com/api/v2/incidents.json
  └─ Public resets (no token) ──▶  https://codex-resets.com/api/v1/resets
```

- The Cursor alias, Cursor access token, Codex token, and optional account ID use a non-exportable Android Keystore key plus AES-GCM.
- The Cursor token is sent only to `api2.cursor.sh` and `cursor.com`. The Codex token is sent only to `chatgpt.com`. Custom service URLs are not allowed. Redirects are rejected. Status-page and public-reset requests do not carry a token.
- Only parsed usage and task fields are cached (task cache is also encrypted per account + credential revision). Cursor raw responses are not stored. Conversations are not fetched in-app; the official website is opened instead. Task list requests include `include_sources` and `include_hidden_sources` (including `BACKGROUND_COMPOSER_SOURCE_GROK_BOT`, `BACKGROUND_COMPOSER_SOURCE_GLASS`, and `BACKGROUND_COMPOSER_SOURCE_LOCAL`) and set `include_workers`. A task with `usePrivateWorker` and no machine pool is labeled Remote Control. After parsing `source`, tasks are grouped by repository / status / time / source. Tasks with a merged PR are omitted from the Tasks tab.
- Absolute times are formatted in the Settings display time zone with a GMT offset (default: follow system; other zones are searchable). Cache timestamps without a zone are read in the system zone at write time, then converted.
- Cursor Overview still uses concentric rings: the outer ring is usage and the inner ring is the billing cycle. Settings switch between split pools and a combined total. The preference stays in on-device SharedPreferences. Colors, health thresholds, and the rule that draws only the remaining pool when one pool is exhausted are in [overview-ring-colors.en.md](overview-ring-colors.en.md).
- UI language follows the system by default: English systems use `values-en`; everything else falls back to the default Chinese resources. Settings can force Simplified Chinese or English. Default `values/strings.xml` is Chinese.
- In-app links use Chrome Custom Tabs and must pass an allowlist. Every URL must be HTTPS and must not include userinfo. `cursor.com` and its subdomains allow any path when there is no query; a query is allowed only on `/agents` and only when it is exactly `id=<bcId>` (`bc` followed by `-` or `_`, then 1–80 letters, digits, underscores, or hyphens). Fragments are rejected. `github.com` and its subdomains, `status.cursor.com`, `status.openai.com`, and `stspg.io` and its subdomains reject queries and fragments. `codex-resets.com` is limited to the default port or port 443. `x.com` and `twitter.com` paths must match `/thsottiaux/status/<digits>`, with an optional trailing slash. Custom Tabs are opened by the system browser and do not carry this app’s token.
- Cursor service status uses Statuspage public JSON (`summary.json` and `incidents.json`). Codex also reads `components.json` from `status.openai.com`. HTML is not parsed and RSS is not subscribed. Status requests do not carry a token.
- Codex usage requests only `https://chatgpt.com/backend-api/wham/usage`, with the Codex token as Bearer and an optional `ChatGPT-Account-ID`. Redirects are rejected.
- Public resets are anonymous GETs to `https://codex-resets.com/api/v1/resets` and never include a token. Page cursors must be 1–1024 characters of letters, digits, underscores, or hyphens. An ETag is kept only when it is 1–512 characters and contains no control characters; invalid values are dropped. A failed cache `commit()` keeps the previous snapshot and surfaces an error.
- Cursor account JSON read-modify-write uses one process-wide lock, so the UI, widgets, and notifications cannot overwrite each other. Codex credential writes, usage-cache writes, and reset history share `CodexStorageLock`. The public-reset cache is a separate plaintext preference file and does not use that lock.
- Codex reset identity pads the JWT payload before base64url decoding, so replacing a token still matches the same on-device history.
- The debug visual-review activity is not exported. Credential checks use separate preference names and keystore aliases. Language, time zone, and theme are restored when the review ends or the next process starts.
- Home screen widgets run in the app process so SharedPreferences writes cannot clobber account ciphertext or usage cache from a stale widget-process copy. Usage and status widgets share one JobService refresh schedule (cache TTL 15 minutes). A separate JobScheduler, about every 15 minutes, refreshes Cursor usage, Codex usage, official status, and Cursor tasks after the cache expires even when no widget is placed. An extra check runs near a Codex window reset. “Auto-update Codex usage” is on by default; turning it off stops those automatic Codex usage requests. Manual refresh and saving the token still request it. Usage sizes are fixed 2×1 / 4×3; status sizes are fixed 2×1 / 2×2 / 4×1 / 4×2 / 4×3. Placing only a status widget does not request usage APIs.

SharedPreferences that hold credentials are excluded from cloud backup and device transfer (`allowBackup=false`, `fullBackupContent=false`, and `sharedpref` excluded in `data_extraction_rules.xml`). Cleartext HTTP is disabled. Tokens are not written to `savedInstanceState`, logs, or usage / task caches.

## Document index

| Document | Contents |
| --- | --- |
| [README.en.md](../README.en.md) | Features and usage |
| [PRIVACY.en.md](../PRIVACY.en.md) | Privacy policy |
| [CHANGELOG.en.md](../CHANGELOG.en.md) | Change history |
| [overview-ring-colors.en.md](overview-ring-colors.en.md) | Overview ring colors and split rules |
| [notification-copy-review.md](notification-copy-review.md) | Notification and island copy (Chinese canonical) |
| [icon-generation.md](icon-generation.md) | Launcher and island artwork (Chinese) |
| [usage-heatmap-feasibility.md](usage-heatmap-feasibility.md) | Usage-heatmap feasibility; not implemented |
| [code-audit-2026-10-10.md](code-audit-2026-10-10.md) | 2026-10-10 code audit and the fixes that followed |
