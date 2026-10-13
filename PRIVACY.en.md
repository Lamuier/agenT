# Privacy Policy

> This policy applies to the agenT Android app (package `com.lamuier.agenT`). [中文](PRIVACY.md) | **English**

**Effective date: 2026-10-11**

agenT is a local-first Cursor and Codex usage app: it does not ship third-party analytics, ads, or crash SDKs, has no account system, and has no self-hosted backend. This is the full description of how data is handled.

The Chinese [PRIVACY.md](PRIVACY.md) is the canonical policy text.

## What we collect

**The developer collects no personal data.** Credentials and personal records stay local. Official providers and the third-party public API below can receive standard network information such as IP address and User-Agent.

## Data the app processes

| Data | Purpose | Storage | Where it leaves the device |
| --- | --- | --- | --- |
| Cursor Access Token | Call official Cursor APIs for usage and cloud task lists | Encrypted with AndroidKeyStore (AES-GCM) in the app private directory | Sent only as a credential to official Cursor APIs |
| Codex / ChatGPT OAuth access token and optional Account ID | Call the official ChatGPT endpoint for Codex rate-limit windows | Encrypted with AndroidKeyStore (AES-GCM) in the app private directory | Sent only as a credential to `chatgpt.com/backend-api/wham/usage` |
| Account alias | Local account label | Encrypted with AndroidKeyStore (AES-GCM) | Does not leave the device |
| Usage summary (quota, percents, Credits, per-model tokens, Grok Bot weekly quota, and similar numbers) | UI, widgets, notifications | Encrypted AndroidKeyStore (AES-GCM) cache | Does not leave the device (parsed summary only; no raw response) |
| Cloud task summary (name, status, source, branch, PR link, line diffs, and similar) | Tasks tab | Encrypted AndroidKeyStore (AES-GCM) cache | Does not leave the device (parsed summary only; no raw response) |
| Cursor / Codex service status (component names, availability, incident summaries) | Status tab and widgets | Latest successful result cached in plaintext in the app private directory (public information, no token) | Requested from the corresponding official public status endpoint without a token or Account ID |
| Codex usage summary (plan, percentages, windows, and reset times) | Codex overview, usage and offline display | Encrypted AndroidKeyStore (AES-GCM) cache | Does not leave the device (parsed summary only; no raw response) |

## Where data goes

- Public announcements / observations are read from third-party `https://codex-resets.com/api/v1/resets`, without a key, token, Account ID, or personal records. Fetch at app startup and refresh as needed; cache up to 500 parsed public records in plaintext in the private app directory. The page, statistics and heatmap display API `regular` resets and `banked` credit grants with separate counts. Legacy caches that may lack grants trigger a full fetch; failures preserve existing cached records. Public records do not count as personal resets. Removing the Codex credential preserves unrelated public cache; uninstalling removes it. Checked source / credit links open `codex-resets.com` or `x.com` / `twitter.com` original posts in the system browser without app tokens.
- Reset reminders retain only irreversible event identifiers locally for deduplication. Reminder records are not uploaded and scheduled checks never redeem reset credits.
- Legacy personal Codex reset history and subscription anchor days remain Android Keystore AES-GCM encrypted in app-private storage, with up to 500 legacy events, isolated by irreversible user/workspace digests, without tokens or uploads. Usage sync overwrites the latest observation for independent window reminders; it no longer appends renewal, balance or credit-event history. Legacy personal history is no longer displayed. Credit usage caches retain parsed fields only.
- The reset page never executes or redeems resets and offers no manual registration. Deleting the Codex credential removes all local Codex history and observation snapshots; uninstalling also clears them. Private records are excluded from backup and device transfer.

- Usage and status requests use official hosts: `api2.cursor.sh`, `cursor.com`, `status.cursor.com`, `status.openai.com`, and `chatgpt.com`; public reset records use anonymous requests to third-party `codex-resets.com`
- Official Codex component status and related incidents come from public JSON on `status.openai.com`, used only for Status, widgets, and offline display. Requests never include a token or Account ID; the public cache contains no credentials.
- The Access Token is used only for usage and task-list APIs (`api2.cursor.sh`, `cursor.com`). It is not sent with status-page requests or Custom Tabs
- The Codex token is used only for `chatgpt.com/backend-api/wham/usage`; it is never sent to Cursor or another host
- Opening a task conversation, pull request, official status page, or allowed public-reset link uses Chrome Custom Tabs to a checked HTTPS page: `cursor.com` and its subdomains (any path with no query, or only `/agents?id=<bcId>`), `github.com` and its subdomains, `status.cursor.com`, `status.openai.com`, `stspg.io` and its subdomains, `codex-resets.com`, and `x.com` / `twitter.com` posts whose path is `/thsottiaux/status/<digits>`. Those requests are made by the system browser with the browser’s own login, without this app’s Access Token
- All connections initiated by this app require HTTPS. Cleartext is forbidden. Redirects are rejected
- No personal credentials, records, or analytics are uploaded to third-party or developer-operated servers. Public API requests carry only pagination / cache parameters and standard network request information.

## Data security

- Token and usage caches are encrypted with non-exportable keys managed by Android Keystore. Hardware protection depends on the device and is not guaranteed on every device. Public service-status caches contain no credentials and are stored in plaintext in the app private directory
- SharedPreferences that hold credentials are excluded from cloud backup and device transfer (`allowBackup=false`, `fullBackupContent=false`, and `sharedpref` excluded in `data_extraction_rules.xml`). A new phone or cloud restore will not bring your token
- While a token is shown in plain text, `FLAG_SECURE` is enabled to block screenshots, recording, and Recents thumbnails
- Unsaved Token drafts stay only in memory to continue editing across configuration changes such as folding or rotation. Closing the form, changing providers or updating credentials clears the draft; it is not written to saved state, disk or logs.
- Copied tokens (Android 13+) are marked sensitive so system clipboard previews show a placeholder

## Permissions

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Request official usage, tasks, status, and third-party public reset announcements |
| `ACCESS_NETWORK_STATE` | Detect connectivity and avoid useless requests |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | Fingerprint / lock-screen verification before showing a token in plain text |
| `POST_NOTIFICATIONS` | Ongoing live activities, quota threshold alerts, and Codex window reset reminders |
| `RECEIVE_BOOT_COMPLETED` | Restore notification refresh scheduling after boot |
| `POST_PROMOTED_NOTIFICATIONS` | Request native Live Update promotion on Android 16+; the system decides actual presentation |

AndroidX also merges `com.lamuier.agenT.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level internal broadcast permission that does not read personal data. Haptics use the system feedback API without a vibration permission.

## Uninstall

Uninstalling removes this app’s local accounts, tokens, caches and records. It does not revoke provider tokens; the system browser’s own login state is managed separately.

## Changes

Material changes to this policy will be noted with the app update in [CHANGELOG.en.md](CHANGELOG.en.md) (canonical Chinese: [CHANGELOG.md](CHANGELOG.md)).

## Contact

Contact the developer by opening an issue in this project's GitHub repository.
