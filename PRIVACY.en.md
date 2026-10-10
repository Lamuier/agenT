# Privacy Policy

> This policy applies to the agenT Android app (package `com.lamuier.agenT`). [中文](PRIVACY.md) | **English**

**Effective date: 2026-10-08**

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
| Cursor service status (component names, availability, incident summaries) | Status tab and widgets | Latest successful result cached in plaintext in the app private directory (public information, no token) | Requested only from the official Cursor status page; public status data briefly leaves the device to reach yours |
| Codex usage summary (plan, percentages, windows, and reset times) | Codex usage UI and offline display | Encrypted AndroidKeyStore (AES-GCM) cache | Does not leave the device (parsed summary only; no raw response) |

## Where data goes

- Public announcements / observations are read from third-party `https://codex-resets.com/api/v1/resets`, without a key, token, Account ID, or personal records. Fetch on the Resets page and refresh as needed; cache up to 500 parsed public records in plaintext in the private app directory. Public records do not count as personal resets. Removing the Codex credential preserves unrelated public cache; uninstalling removes it. Checked source / credit links open `codex-resets.com` or `x.com` / `twitter.com` original posts in the system browser without app tokens.
- Reset reminders retain only irreversible event identifiers locally for deduplication. Reminder records are not uploaded and scheduled checks never redeem reset credits.
- Codex reset records (time, category, source, count, note, subscription anchor, and window observations) are encrypted with Android Keystore AES-GCM in the app private directory. Up to 500 records are retained, isolated by an irreversible user / workspace digest, without tokens or uploads. Credit caches store parsed fields, not raw responses.
- The reset page does not execute or redeem resets. Manual registrations are not sent to OpenAI. Deleting the Codex credential removes all local Codex reset records; uninstalling also clears them. Records are excluded from backups and device transfer.

- Usage and status requests use official hosts: `api2.cursor.sh`, `cursor.com`, `status.cursor.com`, `status.openai.com`, and `chatgpt.com`; public reset records use anonymous requests to third-party `codex-resets.com`
- Official Codex component status and related incidents come from public JSON on `status.openai.com`, used only for Status, widgets, and offline display. Requests never include a token or Account ID; the public cache contains no credentials.
- The Access Token is used only for usage and task-list APIs (`api2.cursor.sh`, `cursor.com`). It is not sent with status-page requests or Custom Tabs
- The Codex token is used only for `chatgpt.com/backend-api/wham/usage`; it is never sent to Cursor or another host
- Opening a task conversation or PR uses Chrome Custom Tabs to a checked `cursor.com` / `github.com` page. Those requests are made by the system browser with the browser’s own login, without this app’s Access Token
- All connections initiated by this app require HTTPS. Cleartext is forbidden. Redirects are rejected
- No personal credentials, records, or analytics are uploaded to third-party or developer-operated servers. Public API requests carry only pagination / cache parameters and standard network request information.

## Data security

- Token and usage cache are written with the system keystore; keys do not leave secure hardware. Public service-status cache has no secrets and is stored in plaintext in the app private directory
- Cloud backup and device transfer are disabled (`allowBackup=false`). A new phone or cloud restore will not bring your token
- While a token is shown in plain text, `FLAG_SECURE` is enabled to block screenshots, recording, and Recents thumbnails
- Copied tokens (Android 13+) are marked sensitive so system clipboard previews show a placeholder

## Permissions

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Request official usage, tasks, status, and third-party public reset announcements |
| `ACCESS_NETWORK_STATE` | Detect connectivity and avoid useless requests |
| `USE_BIOMETRIC` | Fingerprint / lock-screen verification before showing a token in plain text |
| `POST_NOTIFICATIONS` | Ongoing assistant status monitor, quota threshold alerts, and Codex window reset reminders |
| `RECEIVE_BOOT_COMPLETED` | Restore notification refresh scheduling after boot |
| `POST_PROMOTED_NOTIFICATIONS` | Android 16+ live notification progress style |

## Uninstall

Uninstalling the app deletes all local data (accounts, token, caches). Nothing remains.

## Changes

Material changes to this policy will be noted with the app update in [CHANGELOG.en.md](CHANGELOG.en.md) (canonical Chinese: [CHANGELOG.md](CHANGELOG.md)).

## Contact

Contact the developer by opening an issue in this project's GitHub repository.
