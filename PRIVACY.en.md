# Privacy Policy

> This policy applies to the agenT Android app (package `com.lamuier.agenT`). [中文](PRIVACY.md) | **English**

**Effective date: 2026-10-17**

agenT is a local-first AI service usage app that displays Cursor, Codex, DeepSeek API, Kimi Code and Antigravity usage, tasks, resets and service status on your device: it does not ship third-party analytics, ads, or crash SDKs, has no account system, and has no self-hosted backend. This is the full description of how data is handled.

The Chinese [PRIVACY.md](PRIVACY.md) is the canonical policy text.

## What we collect

**The developer collects no personal data.** The app processes credentials, caches and observations locally and sends authentication credentials to the corresponding official service to query data. Official services and the third-party public API can receive standard request information such as IP address and User-Agent; their handling is governed by their own policies.

## Data the app processes

| Data | Purpose | Storage | What the app sends or uploads |
| --- | --- | --- | --- |
| Cursor Access Token | Query Cursor account, usage and cloud task lists | Android Keystore AES-GCM encryption in app-private storage | Sent only as a credential to official Cursor APIs |
| Codex / ChatGPT OAuth access token and optional Account ID | Query Codex rate-limit windows | Android Keystore AES-GCM encryption in app-private storage | Sent only as authentication information to `chatgpt.com/backend-api/wham/usage` |
| DeepSeek API key, Kimi Code API key and region, and Antigravity OAuth credentials | Query balances, subscription quotas or model-group quotas | Android Keystore AES-GCM encryption in app-private storage | Sent only to the corresponding fixed official endpoints (see dedicated sections below) |
| Account alias | Local account label | Android Keystore AES-GCM encryption | Not uploaded |
| Cursor / Codex usage summaries (plan, quota, percentages, windows and reset times, Credits, per-model tokens, Grok Bot weekly quota and similar fields) | UI, widgets, notifications and offline display | Android Keystore AES-GCM encrypted cache of parsed summaries | Summaries and raw responses are not uploaded |
| Local usage observations (parsed numbers, UTC timestamps, period boundaries and reset times) | Observation trends and conditional pace estimates | Android Keystore AES-GCM encryption in the no-backup directory | Not uploaded; see [Local usage observations](#local-usage-observations) for controls and retention |
| Cloud task summaries and local observation / acknowledgement state (name, status, source, branch, PR link, line changes and observation times) | Tasks and live activities; avoid repeated notices | Android Keystore AES-GCM encrypted cache isolated by account and credential revision | Local observations and acknowledgements are not uploaded; full task conversations are not fetched |
| Legacy personal Codex reset history, subscription anchor day and latest window observation snapshot | Storage compatibility, subscription-month filters and window reset alerts | Android Keystore AES-GCM encryption, isolated by user / workspace identity digests | Not uploaded |
| Public Cursor / Codex / DeepSeek / Kimi service status (components, states and incident summaries) | Status, Overview, widgets and notifications | Latest successful result cached in plaintext in app-private storage | Public requests include no token, API key or Account ID |
| Public reset announcements / observations | Reset statistics, lists and heatmaps | Parsed public fields cached in plaintext in app-private storage | Anonymous requests include no token, Account ID or personal records |
| UI / notification preferences, widget page preferences and reminder deduplication identifiers | Preserve settings and avoid repeated alerts | App-private SharedPreferences; these settings and deduplication metadata are unencrypted and contain no token | Not uploaded |

## Local usage observations

Collection is **enabled by default**. Successful live Cursor / Codex usage requests can record parsed numbers, UTC timestamps and period information; cached displays and failed requests produce no samples. Records are isolated by service and a one-way account identity digest and contain no raw tokens or complete responses. Collection reuses existing foreground, notification and widget refreshes and adds no polling.

Observations are stored by UTC day and support viewing the latest 90 days. Samples in the same five-minute bucket and quota window are coalesced when values have not decreased, with at most 1000 points per daily file. Expired daily files are removed on the next history read or sample write. Records use Android Keystore AES-GCM encryption in the app-private no-backup directory.

Disable collection or clear history in Settings → Service connections. Disabling keeps existing history and invalidates earlier in-flight collection; clearing deletes both services' observation histories and invalidates in-flight collection. Removing an account or credential clears all observations for the corresponding service. An identified same-account token renewal preserves history; a credential update that changes identity clears that service's history.

Observations reflect cumulative values seen during successful refreshes and cannot serve as complete daily consumption or reset-event details. Trend lines break at period changes, decreases or gaps over 30 minutes. Conditional estimates are computed on-device from sufficient fresh continuous samples; quota percentages cannot be converted to token counts.

## Reset records and retention

Public announcements / observations are read from third-party `https://codex-resets.com/api/v1/resets` at startup and refreshed as needed, with up to 500 cached records. The page, statistics and heatmap separately display API `regular` resets and `banked` credit grants. Legacy caches that may lack grants trigger a full fetch; failures preserve existing cached records. This is a third-party summary: missing dates do not prove no reset occurred, and grant record counts are not credit quantities or balances.

Up to 500 legacy personal Codex reset events remain encrypted for compatibility but are no longer displayed; the subscription anchor day is still used for filtering. Usage sync only overwrites the latest window observation for confirmed window-reset alerts and no longer appends renewal, balance or credit-event history. Reset reminders store up to 500 one-way sent-event identifiers locally for deduplication.

Current usage, task and public-status caches preserve the latest successful results and update after subsequent successful queries. UI / notification preferences and reminder deduplication metadata remain local. The reset page offers no manual registration and never executes or redeems official reset credits. See [Data controls](#data-controls) and [Uninstall](#uninstall) for deletion rules.

## Where data goes

| Request | Host / endpoint | Authentication |
| --- | --- | --- |
| Cursor account, usage and tasks | `api2.cursor.sh`, `cursor.com` | Cursor token |
| Codex usage | `chatgpt.com/backend-api/wham/usage` | Codex token and optional Account ID |
| Official Cursor / Codex status | `status.cursor.com`, `status.openai.com` | No token or Account ID |
| Third-party public reset records | `codex-resets.com/api/v1/resets` | No token, Account ID or personal records; only pagination / conditional-cache parameters |

Credentials are sent only to the corresponding service and are not attached to public-status requests, public-reset requests or web links. The app's own HTTP clients require HTTPS, forbid cleartext and reject redirects.

Task conversations, PRs, official status pages and public-reset source links are checked against an allowlist before opening in the system browser's Custom Tabs, with a system-browser fallback. Allowed sources include `cursor.com` and its subdomains (any path with no query, or only `/agents?id=<bcId>`), `github.com` and its subdomains, `status.cursor.com`, `status.openai.com`, `stspg.io` and its subdomains, `codex-resets.com`, and path-validated `thsottiaux` posts on `x.com` / `twitter.com` (`/thsottiaux/status/<digits>`). Web requests use the browser's own login state and are governed by the browser's and website's data policies.

The app uploads no personal credentials, records or analytics to developer-operated servers or to the third-party public API.

Antigravity task entry opens `https://antigravity.google.com/` in the system browser. Connection and usage guides use fixed pages under `www.antigravity.google/docs`, and support opens `https://antigravity.google/support`. Website Google sign-in and subsequent website requests are handled by the browser. agenT does not read browser cookies or sessions, or attach credentials from other services.

Antigravity usage uses a manually imported OAuth grant or access token. Access tokens, optional refresh tokens and OAuth client configuration, account binding, project metadata and minimal quota cache are encrypted with Android Keystore AES-GCM and excluded from backup, transfer, logs, reports and saved UI state. Revealing credentials requires device unlock. Deleting a connection removes local credentials and quota cache; it does not revoke other grants belonging to that OAuth client at Google. No client secrets are bundled; the app does not read desktop credentials, enroll accounts or purchase subscriptions.

Access tokens go only to fixed `www.googleapis.com/oauth2/v3/userinfo` for account verification and `cloudcode-pa.googleapis.com/v1internal:loadCodeAssist`, `retrieveUserQuotaSummary` and `retrieveUserQuota` for quotas. Refresh credentials go only to `oauth2.googleapis.com/token` as form data. Redirects are disabled, and these grants are never attached to task webpages. Only parsed quota groups, fractions and reset times are cached, not raw identity or account responses. Foreground refresh and roughly fifteen-minute installed usage-widget refresh share the connection. Status-only widgets do not fetch or renew Antigravity usage. Failures retain the original cache time. No token history, task bodies or Antigravity alerts are collected or generated.

## DeepSeek API and Kimi Code

API keys, Kimi region, credential revision and the latest account usage response are encrypted locally with Android Keystore AES-GCM. They are excluded from backup and device transfer, logs, reports and saved UI state. Revealing a saved key requires device unlock; copied keys use the sensitive clipboard flag. Replacing a key or region starts a new account cache; deleting a connection removes its key and account cache.

DeepSeek keys go only to `api.deepseek.com/user/balance`. Kimi Code keys go only to the explicitly selected `api.kimi.com/coding/v1/usages` or `api.kimi.ai/coding/v1/usages`, with no automatic region fallback. The app does not read cookies, browser sessions, desktop credentials or OAuth sessions. Public status requests to `status.deepseek.com` and `status.moonshot.cn` carry no key; their public caches contain no credentials.

These sources refresh in the foreground, after user actions, and when relevant widgets are installed. Widget jobs use the existing roughly fifteen-minute system schedule and request only configured sources and installed widget data types. Paging reads cache only; widget jobs stop when no widgets remain. Notification alert scope is unchanged. Page preferences store only widget and source IDs. The fixed console links `https://platform.deepseek.com/` and `https://www.kimi.com/code/console` open in the system browser without app keys.

## Data security

- Credentials, aliases, private usage / task caches, personal reset history and local usage observations are encrypted with non-exportable keys managed by Android Keystore. Hardware protection depends on the device and is not guaranteed on every device. Public caches, ordinary settings and reminder deduplication metadata are held in app-private storage.
- The app sets `allowBackup=false` and `fullBackupContent=false`, and excludes SharedPreferences from cloud backup and device transfer through `data_extraction_rules.xml`. Local observations use `noBackupFilesDir`; credentials and private records are not exported through app backup or device transfer.
- Revealing a saved token requires device unlock. While plain-text tokens are shown, `FLAG_SECURE` blocks screenshots, screen recording and Recents thumbnails.
- Unsaved token drafts stay only in memory to continue editing after configuration changes. Closing the form, changing services, updating credentials or ending the editing session removes them; drafts are not written to saved state, disk or logs.
- On Android 13+, copied tokens are marked sensitive. The system manages the clipboard; clear it after pasting.

## Permissions

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Request official account, usage, tasks and status, and third-party public reset announcements |
| `ACCESS_NETWORK_STATE` | Detect network availability |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | Fingerprint / lock-screen verification before revealing tokens |
| `POST_NOTIFICATIONS` | Live activities, quota threshold alerts and Codex window reset reminders |
| `RECEIVE_BOOT_COMPLETED` | Restore notification refresh scheduling after boot |
| `POST_PROMOTED_NOTIFICATIONS` | Request native Live Update promotion on Android 16+; the system decides presentation |

AndroidX also merges `com.lamuier.agenT.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level internal broadcast permission that does not read personal data. Haptics use the system feedback API without a vibration permission.

## Data controls

| Action | Effect on local data |
| --- | --- |
| Disable observation collection in Settings → Service connections | Stop appending observations and keep existing history; service queries still follow their refresh settings |
| Clear observation history in the same location | Delete Cursor and Codex observation histories and invalidate in-flight collection; subsequent samples follow the collection switch |
| Delete a Cursor account | Delete that account's credentials and usage / task caches, and clear all local Cursor usage observations |
| Delete the Codex credential | Delete credentials, usage cache, legacy personal reset records, subscription settings, window observation snapshots and all local Codex usage observations; unrelated public-reset cache remains |
| Delete a DeepSeek API, Kimi Code or Antigravity connection | Delete the corresponding credentials and private usage / quota cache; unrelated public-status caches remain |
| Confirm ended task messages in live activities | Save local acknowledgement state and clear the corresponding notices; existing task records remain in the task list |

Deleting local credentials does not revoke provider tokens; manage revocation in the corresponding service. Clearing app data or uninstalling removes ordinary settings and reminder deduplication metadata.

## Uninstall

Uninstalling deletes this app's local accounts, tokens, caches, observation history and settings. Manage provider-token revocation and the system browser's login state in the corresponding service and browser.

## Changes

Material policy changes will update the effective date and both language versions and be recorded in [CHANGELOG.en.md](CHANGELOG.en.md) (canonical Chinese: [CHANGELOG.md](CHANGELOG.md)).

## Contact

Contact the developer through this project's [GitHub issues](https://github.com/Lamuier/agenT/issues).
