# agenT

> Bring the Cursor and Codex AI coding assistants into one mobile hub—no third-party backend. Data stays on your device.

[中文](README.md) | **English**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)

agenT brings Cursor and Codex into one AI coding-assistant hub. Overview, Usage, and Status show both services together: Cursor provides usage, cloud tasks, status, notifications, and widgets; Codex shows the 5-hour, weekly, and additional limits from a ChatGPT subscription and official service status. There is no account system or self-hosted backend. Credentials and caches are encrypted locally.

Chinese is the primary language of this repository and the default app locale. English UI is available in Settings, or automatically when the system language is English.

---

## Features

### Two services, one entry point

- Open directly into one dashboard, with Cursor and Codex together on Overview, Usage, and Status.
- Use one secure token-management sheet for Cursor and Codex under Service connections in Settings. Switch providers to save, reveal, copy, or delete credentials. Overview uses matching assistant cards, and Usage supports provider filtering.
- Independent package ID `com.lamuier.agenT`, allowing installation alongside the older Cursor / Codex apps.
- A robot-cat assistant hugging a blue energy orb, optimized for small sizes and circular masks, with a matching simplified monochrome icon on Android 13+ and updated island artwork.

### Usage at a glance

- Total usage, billing cycle, plan quota mix, two usage pools (Cursor models / other models), and Grok Bot weekly quota in one place on the Usage tab.
- Overview rings can switch in Settings between split pools and a combined total. In split mode, if only one pool is exhausted, the outer ring shows the pool that still has quota. Color rules: [docs/overview-ring-colors.en.md](docs/overview-ring-colors.en.md).
- The Usage tab also shows billing-cycle token totals: input / output / cache tokens and cost by model. You can look back by calendar month or past billing cycles, with pool share estimated from token cost. Grok Bot weekly quota is a separate section and is not mixed into monthly pools.
- Default navigation is Overview / Usage / Tasks / Resets / Status, with configurable order and explicit soft-colored scope badges. Overview, Usage, and Status are Shared; Tasks is Cursor-only; Resets is Codex-only. Billing is removed from saved orders without moving surviving tabs, and old Billing links open Usage. Narrow screens scroll horizontally, and older four-tab orders gain Tasks after Usage. Phones use full-width stacked cards, while tablets use two columns when width allows, with shared quota and reset-time styling.
- The Tasks tab lists Cursor cloud tasks (background agents), grouped by repository / status / time / source. Merged branches are hidden. Sources that the website hides by default (Grok Bot, API, SDK, and others) are included. Tapping a task opens the official Agents page in Chrome Custom Tabs for the full conversation.
- Status shows official Cursor and Codex availability together, with selectable component details, active incidents, and history. No token is required. The complete OpenAI component endpoint supplies Codex Web, API, CLI, desktop, and extension status; unrelated OpenAI faults are not reported as Codex faults.
- Rings, progress bars, and key numbers use light motion without clutter.

### Codex quota

- 5-hour, weekly, and additional rate-limit windows with used / remaining percentages and reset times.
- ChatGPT plan type, reset credits, and an encrypted offline cache of the latest successful result.
- Codex OAuth access token and optional ChatGPT Account ID are encrypted with Android Keystore and sent only to the official `chatgpt.com` usage endpoint.

### Codex reset management

- A seven-row calendar heatmap uses weekly columns and regular / banked / other colors, with 3 / 6 / 12-month windows, horizontal scrolling, earlier / newer navigation, and date details. Blank cells mean no supplied records, not proof that no reset occurred. Dates follow the display time zone independently of the personal statistics period.
- Merge local account records with public announcements / observations from [Codex Resets](https://codex-resets.com), including source filters and original-post links. Public records are not account history or personal reset counts; forecasts / pending plans are excluded. No API key or token is sent. Support pagination, deduplication, offline cache, and rate-limit backoff.
- The [usage heatmap assessment](docs/usage-heatmap-feasibility.md) documents Cursor / Codex daily-data routes and permission / coverage limits. No new usage collector or bridge is introduced in this change.
- A dedicated Resets tab covers Monday-based weeks, calendar months, and subscription months with a user-configured ChatGPT billing start day; short months clamp to month end, and boundaries follow the display time zone.
- Separate personal usage, registered official adjustments, observed 5-hour / weekly renewals, unclassified early changes, and credit-balance changes. Sources are labeled as official detail, local observation, or user record. Register, classify, filter, and delete local records.
- The returned official balance is authoritative, not the detail count. Show grant / expiration / status only when supplied. Missing official totals remain unavailable; balance decreases are not proof of redemption and offline resets are not backfilled. This page does not redeem credits.
- Keep up to 500 encrypted, token-free records isolated by Codex user and workspace. Token rotation preserves records only when both user and workspace are known; unknown identities or workspaces are isolated by token. Deleting the Codex credential also removes all reset history.

### Assistant status notifications

- Settings → Notifications includes an opt-in Cat-speak switch. Formal copy remains the default; enabling it updates ongoing notifications, Live Update chips, the island, and future one-shot quota / renewal reminders. Preview the six captions below the switch. The local preference persists, and switching only refreshes ongoing presentation without network calls, permission requests, or replaying reminders.
- Island artwork distinguishes both style and all six states: a flat monochrome cat in formal mode or the colored cat in cat-speak mode, with check / warning / empty-battery / cross / wrench / clock badges and matching accents. Every combination has its own picture-cache reference; launcher artwork is unchanged.
- Multiple conditions retain the highest-priority caption with +N other attention rows and a second + icon badge. Expanded details keep every quota, service, and pending-data row; independent windows count separately even when their states match. Stale outages become update-pending rather than fresh failures, and confirmed incidents can coexist with incomplete feeds. The count is not usage; v7 single / composite picture keys stay stable as the count changes.
- Persistent notifications share the island attention logic: Patrol, Quota low / empty, Service issue / Maintenance, or Sync pending. No usage numbers, amounts, or progress metrics; both providers refresh roughly every 15 minutes. See the bilingual [copy review](docs/notification-copy-review.md) for the complete current text.
- Independent one-shot alerts at 80% / 100%, deduplicated by provider, credential revision, and reset time.
- Opt into Codex reset reminders in Settings to receive independent 5-hour / weekly renewal notices after fresh-response confirmation; timers alone, old history, and manual records do not trigger recovery claims.
- Check freshness on foreground return and near reset deadlines. Background refresh runs roughly every 15 minutes with additional reset-time checks, subject to network and OS battery delays; notifications are not exact-to-the-second alarms.
- Xiaomi HyperOS 3 shows the app icon and attention status, with affected providers / quota windows in expanded alerts. Exact usage remains in the app and widgets.
- Persistent notifications and Android Live Updates use the same native text-state template with affected providers / windows and no percentages. One-shot threshold and reset reminders remain available.

### Home screen widgets

- Every Material 3 usage and status widget shows Cursor and Codex together. Usage comes in a horizontal 2×1 mini bar and a vertically stacked 4×3 detail card, with Cursor quota and Codex 5-hour / weekly quota; status comes in 2×1, 2×2, 4×1, 4×2, and 4×3. Android 12+ follows system colors. Status widgets do not need an Access Token.

### App shortcuts

- Long-press the icon for Token management and Settings. Token management supports both Cursor and Codex; saved tokens require fingerprint / lock-screen verification, remain masked by default, and support secure copying.

### Everyday details

- Auto-refresh on launch and when returning to the foreground; silent sync every 5 minutes in the foreground; manual refresh is also available.
- 64-bit only. High refresh rate is used in the foreground and released when you leave.
- Light / dark follow the system. Accessibility and large-screen layouts are supported. Primary-tab order, Cursor quota display, time zone, and UI language can be changed in Settings (time zone defaults to follow system; other zones are searchable). Default copy is Simplified Chinese; Follow system uses English on English devices and falls back to Chinese otherwise.
- Built-in Windows guide for extracting a Token.

---

## Privacy and security

- **Official-only credentials**: Usage and availability use their official endpoints. Public reset records are requested anonymously from `codex-resets.com`, with no token or Account ID. No analytics, ads, or personal-record uploads.
- **Encrypted locally**: Account and token are stored with the system keystore on your device. They are not backed up and are not transmitted in the clear.
- **Minimal cache**: Only parsed usage numbers and task summaries are cached. Raw API responses are not saved. Conversations are viewed on the official website; the app does not fetch chat content.

Full details: [PRIVACY.en.md](PRIVACY.en.md) (canonical Chinese: [PRIVACY.md](PRIVACY.md)). Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

> Cursor usage APIs have no public stability guarantee. The app may need an update when APIs change.
> Codex’s `/backend-api/wham/usage` endpoint also has no public stability guarantee.

---

## How to get a Cursor Access Token

In the app, tap “How to get an Access Token” for the full steps. Windows users can also extract it manually:

The state database is usually at `%APPDATA%\Cursor\User\globalStorage\state.vscdb`. The key is `cursorAuth/accessToken`.

```powershell
# sqlite3 installed
sqlite3 "$env:APPDATA\Cursor\User\globalStorage\state.vscdb" "SELECT value FROM ItemTable WHERE key='cursorAuth/accessToken';" | Set-Clipboard

# Python only
python -c 'import os,sqlite3,json; from pathlib import Path; p=Path(os.environ["APPDATA"])/"Cursor"/"User"/"globalStorage"/"state.vscdb"; c=sqlite3.connect(p.as_uri()+"?mode=ro",uri=True); v=c.execute("select value from ItemTable where key=?",("cursorAuth/accessToken",)).fetchone()[0]; print(json.loads(v) if v.startswith("\"") else v,end="")' | Set-Clipboard
```

After pasting into the app, clear the clipboard: `Set-Clipboard -Value $null`. Do not send the token to anyone or commit it to a repository.

## How to get a Codex Access Token

Install Codex CLI and run `codex login`. Credentials are normally stored in `%USERPROFILE%\.codex\auth.json` on Windows or `~/.codex/auth.json` on macOS / Linux. Enter `tokens.access_token` under Settings → Service connections → Token management → Codex in agenT; `tokens.account_id` is optional. Clear the clipboard afterward.

---

## Documents

| Document | Contents |
| --- | --- |
| [PRIVACY.en.md](PRIVACY.en.md) | Privacy policy |
| [CHANGELOG.en.md](CHANGELOG.en.md) | Change history |
| [docs/technical.en.md](docs/technical.en.md) | Build, APIs, and data security |
| [docs/overview-ring-colors.en.md](docs/overview-ring-colors.en.md) | Overview ring colors |
| [docs/notification-copy-review.md](docs/notification-copy-review.md) | Notification and island copy (Chinese) |
| [docs/icon-generation.md](docs/icon-generation.md) | Icon generation notes (Chinese) |
| [docs/usage-heatmap-feasibility.md](docs/usage-heatmap-feasibility.md) | Usage-heatmap feasibility (not implemented) |
| [docs/code-audit-2026-10-10.md](docs/code-audit-2026-10-10.md) | 2026-10-10 code audit and the fixes that followed (Chinese) |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | Third-party open-source notices |

## License and disclaimer

- **License**: Open source under the [MIT License](LICENSE).
- **Disclaimer**: agenT is an unofficial, personally maintained tool and is not affiliated with Cursor (Anysphere) or OpenAI. Use is at your own risk.

---

*Build and contributor notes: [docs/technical.en.md](docs/technical.en.md) (canonical Chinese: [docs/technical.md](docs/technical.md)).*
