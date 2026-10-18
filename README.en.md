# agenT

> A native Android dashboard for Cursor and Codex usage, tasks, resets, and service status.

[中文](README.md) | **English**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)

Project version: **1.3.0** (`versionCode` 5). This README describes the current source; see the [1.3.0 release notes](CHANGELOG.en.md#130--2026-10-14). Package ID: `com.lamuier.agenT`.

There is no app account system, self-hosted backend, advertising, or analytics SDK. Credentials and personal caches stay on the device; queries go directly to provider endpoints, while public reset announcements use an anonymous third-party source.

## Features and entry points

| Page | Current functionality |
| --- | --- |
| Overview | Matching Cursor / Codex cards, quota and reset times; Cursor quota always shows separate own-model and other-model pools |
| Usage | Compact quota dials for both services, a four-cell token scorecard, model cost/token bars, labeled pace bars, equal-period comparison charts and encrypted local observation trends; explanations stay collapsed |
| Tasks | Cursor cloud tasks grouped by repository, status, time or source; includes Grok Bot, API, SDK and Remote Control; hides merged PRs; the top + action opens Cursor Agents to create tasks, and conversations open on the official website |
| Resets | Compact reset/grant counts, composition ring, daily bars and calendar heatmap; week/month/subscription filters and expandable records |
| Status | Official Cursor / Codex components, ongoing incidents and history; top cards and detail summaries show each service's last update time and cache label; no token required |

Reorderable navigation uses a bottom bar and swiping on phones, with a rail on wider windows. Settings are grouped into Service connections, Appearance, Notifications, System entry points and General. The UI supports Chinese / English, light / dark themes, dynamic color, display time zones, large fonts. Foldables use left/right or top/bottom content regions, task list/details and fold-safe dialogs, with support for split-screen and floating windows. Tablets add an information grid to Overview, complete task metadata and content-specific sidebar widths. See [foldable adaptation](docs/foldables.md). Settings use predictive back; navigation, filtering and scroll state can be restored.

- **Live activities and alerts:** ongoing Cursor tasks and recently ended results, plus provider quota and service attention items. Ended results remain for 15 minutes; tap Confirm to clear the currently displayed ended task messages. Quota thresholds and confirmed Codex window renewals trigger independent alerts. Cat-speak is optional and off by default; native Live Updates and Xiaomi HyperIsland presentation depend on the system. [Notification behavior](docs/notifications.md)
- **System entry points:** seven dual-provider widgets (two usage, five status), sample previews and pinning; Token management, Settings, Overview and Tasks shortcuts; an overview Quick Settings tile.
- **Haptics:** enabled by default, with a General switch. Light selections and one confirmation after a successful foreground-entry refresh, manual refresh, save, copy or delete. Foreground refresh must include a real update and complete successful queries; failures, cache-only checks, periodic polling and background sync stay silent. System preferences take precedence.
- **Refresh and offline display:** checks freshness on resume and shows progress in the app bar while refreshing. Generally refreshes every five minutes in the foreground and roughly fifteen in the background, with extra checks near Codex resets. Cached data remains available after failure. Load failures use a brief notice instead of inline exception details; cached content and retry actions remain available. Disable automatic Codex usage refresh while keeping manual refresh and token-save queries.

The 4×3 status widget groups component lists and incident summaries by provider; the usage widget draws each quota meter independently and preserves all three quota windows and reset times, with compact layouts for smaller hosts and large text.

Cold startup restores local caches in parallel; network refresh and system-surface maintenance begin after the first frame. History is loaded for the visible page and range, and charts reuse drawing data. See [startup and performance](docs/performance.md) for measurement details.

## Credentials

Open Settings → Service connections → Token management, then select Cursor or Codex. Saved tokens stay masked; revealing them requires device unlock. Copies are marked sensitive where supported. Do not share tokens and clear the clipboard after pasting.

| Provider | Source and fields |
| --- | --- |
| Cursor | In-app Windows extraction instructions; desktop state database entry `cursorAuth/accessToken`, commonly at `%APPDATA%\Cursor\User\globalStorage\state.vscdb` on Windows |
| Codex | `~/.codex/auth.json` after Codex CLI login (`%USERPROFILE%\.codex\auth.json` on Windows); use `tokens.access_token` and optionally `tokens.account_id` |

An OpenAI platform API key does not replace a Codex / ChatGPT subscription token. Update expired or invalid credentials from the corresponding provider. Official status and anonymous public resets do not require a token.

## Data and limits

- Credentials go only to the corresponding fixed official domains. Status and public-reset requests carry no token. Accounts, usage, tasks and personal resets use Android Keystore AES-GCM and are excluded from backup and device transfer.
- Only parsed usage / task summaries are cached. Full task conversations stay on the official website; checked links open in the system browser.
- The Resets page displays `regular` resets and `banked` reset credit grants from the `codex-resets.com` API, with separate counts and heatmap colors. It does not infer 5-hour / weekly renewals or personal reset-credit use from quota changes. This third-party summary is not official account history; missing dates do not prove no reset occurred. The page never redeems resets.
- Local trends record cumulative values observed during successful refreshes, encrypted for 90 days. Lines break at period changes, decreases or gaps over 30 minutes. They are not complete daily token usage, and quota percentages cannot be converted to token counts. Disable collection or clear history in Settings → Service connections.
- Provider usage endpoints may change. Battery and network restrictions can delay background work and alerts; exact timing is not guaranteed. Android 8.0+ and arm64 only.

See the full [Privacy Policy](PRIVACY.en.md) for data handling and permissions.

## Build and documentation

Use `./build.sh` on macOS / Linux or `.\build.ps1` on Windows. Tests, Lint and build run by default; Release uses the existing signing key and validates the APK. Changes must be followed by a fresh Release export. Official APK names use the app name and version; unarchived builds append a Beijing-time build timestamp to the same base name. Environment, options and signing are documented in the canonical [build.md](build.md) (Chinese).

At the owner's request, current builds use the shared `release.keystore`, alias `release`, matching ScheduleTimeline and AndroidApkTemplate. APKs signed with the former independent agenT certificate cannot be updated directly with this signature. Handle local data before migration; the build scripts never uninstall or install the app automatically.

```sh
./build.sh --doctor
./build.sh
./build.sh --release
```

| Document | Contents |
| --- | --- |
| [Documentation index](docs/README.md) | Canonical topic locations (Chinese developer docs) |
| [Build guide](build.md) | Environment, checks, signing and artifacts |
| [Architecture](docs/architecture.md) | Files, data flow, endpoints and security |
| [UI and resources](docs/ui.md) | Back, haptics, rings, icons and JVM review |
| [Notifications](docs/notifications.md) | Live activities, deduplication, cat-speak and HyperIsland |
| [Privacy](PRIVACY.en.md) / [Third-party notices](THIRD_PARTY_NOTICES.md) | Data handling, permissions and licenses |
| [Changelog](CHANGELOG.en.md) | Published history and pending changes |

## License and contact

agenT is an unofficial, personally maintained tool under [MIT](LICENSE), with no affiliation to Cursor (Anysphere) or OpenAI. Use project GitHub issues for questions and suggestions; review the privacy policy and applicable service terms before use.
