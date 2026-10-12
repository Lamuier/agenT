# agenT

> A native Android dashboard for Cursor and Codex usage, tasks, resets, and service status.

[中文](README.md) | **English**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)

Project version: **1.1.0** (`versionCode` 3). This README describes the current source; see the [1.1.0 release notes](CHANGELOG.en.md#110--2026-10-12). Package ID: `com.lamuier.agenT`.

There is no app account system, self-hosted backend, advertising, or analytics SDK. Credentials and personal caches stay on the device; queries go directly to provider endpoints, while public reset announcements use an anonymous third-party source.

## Features and entry points

| Page | Current functionality |
| --- | --- |
| Overview | Matching Cursor / Codex cards, quota and reset times; Cursor quota always shows separate own-model and other-model pools |
| Usage | Cursor billing periods, plans, quota pools, per-model tokens / cost, historical windows and Grok Bot weekly quota; Codex 5-hour / weekly and additional windows |
| Tasks | Cursor cloud tasks grouped by repository, status, time or source; includes Grok Bot, API, SDK and Remote Control; hides merged PRs and opens conversations on the official website |
| Resets | Codex official fields, on-device observations and manual records; weekly / calendar-month / subscription-month statistics, personal and public reset calendars, filters and date details |
| Status | Official Cursor / Codex components, ongoing incidents and history; no token required |

Reorderable navigation uses a bottom bar and swiping on phones, with a rail on wider windows. Settings are grouped into Service connections, Appearance, Notifications, System entry points and General. The UI supports Chinese / English, light / dark themes, dynamic color, display time zones, large fonts and safe fold regions. Settings use predictive back; navigation, filtering and scroll state can be restored.

- **Live activities and alerts:** ongoing Cursor tasks and recently ended results, plus provider quota and service attention items. Ended results remain for one hour. Quota thresholds and confirmed Codex window renewals trigger independent alerts. Cat-speak is optional and off by default; native Live Updates and Xiaomi HyperIsland presentation depend on the system. [Notification behavior](docs/notifications.md)
- **System entry points:** seven dual-provider widgets (two usage, five status), sample previews and pinning; Token management, Settings, Overview and Tasks shortcuts; an overview Quick Settings tile.
- **Haptics:** enabled by default, with a General switch. Light selections and one confirmation after successful manual refresh, save, copy or reset-record operations. All requests in a batch must succeed; failures, cached fallback and background sync stay silent. System preferences take precedence.
- **Refresh and offline display:** checks freshness on resume, generally refreshes every five minutes in the foreground and roughly fifteen in the background, with extra checks near Codex resets. Cached data remains available after failure. Disable automatic Codex usage refresh while keeping manual refresh and token-save queries.

The 4×3 status widget groups component lists and incident summaries by provider; the usage widget preserves all three quota windows and reset times, with compact layouts for smaller hosts and large text.

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
- The Resets page manages records and does not redeem resets. Public announcements / observations from `codex-resets.com` are not official account history and do not count toward personal totals. Missing dates do not prove that no reset occurred.
- There is no daily token-usage heatmap; quota percentages cannot be converted directly into daily consumption.
- Provider usage endpoints may change. Battery and network restrictions can delay background work and alerts; exact timing is not guaranteed. Android 8.0+ and arm64 only.

See the full [Privacy Policy](PRIVACY.en.md) for data handling and permissions.

## Build and documentation

Use `./build.sh` on macOS / Linux or `.\build.ps1` on Windows. Tests, Lint and build run by default; Release uses the existing signing key and validates the APK. Environment, options and signing are documented in the canonical [build.md](build.md) (Chinese).

At the owner's request, current builds use the shared `release.keystore`, alias `release`, matching ScheduleTimeline and AndroidApkTemplate. APKs signed with the former independent agenT certificate cannot be updated directly with this signature. Handle local data before migration; the build scripts never uninstall or install the app automatically.

```sh
./build.sh --doctor
./build.sh
./build.sh --release --artifact-suffix local-build
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
