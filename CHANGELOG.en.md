# Changelog

[中文](CHANGELOG.md) | **English**

The Chinese [CHANGELOG.md](CHANGELOG.md) is canonical.

## Unreleased

## 1.0.1 — 2026-10-11

> Version decision: 1.0.0 → 1.0.1. This batch adds user-visible capabilities and would normally be a minor release under SemVer. Release as 1.0.1 at the user's explicit request, increment `versionCode` to 2, and retain the package ID and signing identity.

- Unify the Cursor / Codex dashboard, service connections, and token management. Overview, Usage, Status, and widgets show both providers; one secure sheet handles credentials and device-unlock-gated reveal / copy. Launcher shortcuts open Token management and Settings
- Use Overview / Usage / Tasks / Resets / Status navigation with provider badges, narrow-screen scrolling, and custom order. Remove the standalone Billing tab, route legacy links to Usage, and preserve cost, cycle, and quota information
- Rename Assistant status monitor to Live activities. Ongoing notifications, Android Live Updates, and HyperIsland show creating / running Cursor tasks and completed, failed, or expired results with a Tasks shortcut. Keep ended results for one hour without extending retention or replaying history on first sync. Observation times share the encrypted account / credential-revision cache. Sync every five minutes in the foreground, roughly fifteen in the background; stale running states become sync-pending
- Combine quota, service, and task attention rows, show the primary state with +N, and keep all expanded details. Prioritize severe incidents and exhausted quota; stale cached incidents never become new failures. Use clear low-quota, maintenance, issue, and sync-pending copy without ongoing usage numbers or amounts, while preserving channel IDs, switches, permissions, and one-shot deduplication
- Add an opt-in Cat-speak setting, with formal copy by default and localized notification, live chip, island, quota-alert, and renewal templates. Sixteen style / state icons cover eight states; composite v7 references add a + badge. Update the launcher to the robot-cat hugging a blue energy orb, including its monochrome themed icon
- Add an on-by-default Auto-update Codex usage switch. Turning it off stops automatic polling and periodic widget requests while preserving manual refresh and token-save requests. Improve foreground freshness, near-reset checks, queued forced refresh, and background window checks; retain cached data on failures
- Add Codex reset management and confirmed-window renewal alerts. Report weekly, calendar-month, or subscription-month statistics with official detail, local observations, and user records; support registration, filtering, classification, and deletion. Encrypt and isolate history by user / workspace; neither lower balances nor expired timers prove redemption or renewal, and the app never redeems resets
- Add public read-only reset records and a weekly-column, seven-row calendar heatmap with 3 / 6 / 12-month windows, source filters, date details, and original-post links. Group by display time zone; public rows do not count as personal use and blank cells do not prove no resets. Send no credentials, skip forecasts / pending plans, and support pagination, deduplication, offline cache, ETag, and rate-limit backoff
- Include Grok Bot, API, SDK, and Remote Control Cursor tasks with repository / status / time / source grouping. Label private local workers without machine pools as Remote Control, omit merged PRs from Tasks, and open conversations through official Custom Tabs
- Load and filter the complete official Codex Web / API / CLI / desktop / extension status feed, marking incomplete components as unknown. Show both providers across usage / status widgets and improve compact layouts, number hierarchy, progress, and larger-font adaptation
- Fix process-wide Cursor account-write races, JWT base64url padding, and public-reset cache failures; reject invalid ETags. Keep Debug review entry points unexported, use isolated credential preferences / keys, restore language / time-zone / theme settings, and improve token validation and localization
- Align privacy and technical documentation with credential backup / transfer exclusions, official API and public-data boundaries, and current notification / artwork behavior. Add usage-heatmap feasibility notes; Debug review code stays out of Release APKs
- `versionCode=2`, `versionName=1.0.1`

## 1.0.0

> Version decision: agenT uses a new package ID and launches as an independent product at 1.0.0 instead of inheriting the older Cursor app's version sequence.

- Bring Cursor and Codex capabilities into one Android app, `agenT`. Existing Cursor usage, billing, tasks, status, notifications, and widgets remain available
- Add a Codex usage surface with encrypted ChatGPT OAuth token storage, 5-hour / weekly / additional rate-limit windows, plan and reset credits, plus an encrypted offline cache
- Use the independent package ID `com.lamuier.agenT` to avoid conflicts with the older Cursor / Codex apps. Credentials from the old apps are not migrated automatically
- Rename the app and release artifacts to agenT and provide a dedicated launcher icon and monochrome themed icon
- `versionCode=1`, `versionName=1.0.0`

## 2.8.3

> Version: 2.8.2 → 2.8.3. This release changes how the split Overview ring looks when one pool is used up. No new capability and no breaking change, so a patch bump under SemVer.

- Overview split ring: when one pool is used up and the other still has quota, only the remaining pool is shown, with a note naming the exhausted pool. This cycle no longer switches to the combined total
- `versionCode=29`, `versionName=2.8.3`

## 2.8.2

> Version: 2.8.1 → 2.8.2. This release fixes status links opening in an external browser; they now open in an in-app Chrome Custom Tab, matching Tasks. No new capability and no breaking change, so a patch bump under SemVer.

- Official status and incident links open in an in-app Chrome Custom Tab, matching the Tasks tab
- `versionCode=28`, `versionName=2.8.2`

## 2.8.1

> Version: 2.8.0 → 2.8.1. This release fixes widgets overwriting local account storage from a separate process, failed usage-history looking empty, boot coroutines, and fail-fast secure writes. No new capability and no breaking change, so a patch bump under SemVer.

- Widgets share the app process so a stale widget-process SharedPreferences copy cannot overwrite account ciphertext or usage cache
- Failed usage-history loads show an error and Reload on the Usage tab instead of looking like an empty window
- Boot / app-update notification restore uses the application coroutine scope
- Usage widgets bind donut, refresh, and details from kind flags instead of an `else` that swallows new sizes
- Account-migration cleanup writes now fail fast, matching other secure preference commits
- `versionCode=27`, `versionName=2.8.1`

## 2.8.0

> Version: 2.7.0 → 2.8.0. This release adds 4×1 / 4×2 / 2×2 status widget sizes. Backward-compatible new capability, so a minor bump under SemVer.

- Status widgets add 4×1 wide bar, 4×2 card, and 2×2 square; existing 2×1 mini bar and 4×3 detail card remain
- Added Overview ring color notes (`docs/overview-ring-colors.md`)
- `versionCode=26`, `versionName=2.8.0`

## 2.7.0

> Version: 2.6.2 → 2.7.0. This release auto-collapses the split Overview ring when the own pool is exhausted, adds 15-minute widget background refresh, and syncs widgets after the app loads live data. Backward-compatible new capability, so a minor bump under SemVer.

- When Overview uses the split ring and the own pool is exhausted while the third-party pool still has quota, this cycle switches to the combined ring and reverts next cycle
- Desktop widgets now refresh on a 15-minute background schedule; opening the app and loading latest usage/status also pushes the widgets immediately
- Pull-to-refresh keeps only the content indicator; the toolbar refresh button spins only when tapped, so the two animations no longer appear together
- `versionCode=25`, `versionName=2.7.0`

## 2.6.2

> Version: 2.6.1 → 2.6.2. This release restores the split-ring cycle legend, keeps the shorter arc visible while breathing, and restyles cycle dates. No new capability and no breaking change, so a patch bump under SemVer.

- Overview split ring: restore the inner-ring cycle-percent legend, matching combined total mode
- Overview billing cycle sits on the same center axis as the ring: remaining time as the lead line, date range as caption, without the list-row title, chip, or divider
- Overview cycle dates split from the reset clock: `09/09 — 10/09` above, `23:15 GMT+8` below
- Display dates use slashes (`09/09`, `2026/12/01`); storage and APIs stay ISO with hyphens
- Overview split ring: keep the shorter arc on top so its breath is visible instead of popping when the longer arc covers it
- `versionCode=24`, `versionName=2.6.2`

## 2.6.1

> Version: 2.6.0 → 2.6.1. This release keeps the longer Overview split-ring arc from covering the shorter one. No new capability and no breaking change, so a patch bump under SemVer.

- Overview split ring: own-pool and third-party arcs breathe in opposite phase so the longer one no longer covers the shorter one
- `versionCode=23`, `versionName=2.6.1`

## 2.6.0

> Version: 2.5.7 → 2.6.0. This release adds an Overview ring style switch and searchable time-zone picking. Backward-compatible new capability, so a minor bump under SemVer.

- Settings adds an Overview ring option: keep the default split own / third-party outer ring, or restore the original combined total-usage ring (center shows total usage and health; legend is usage / cycle)
- Settings keeps only Follow system in the time-zone list; other zones are chosen through a search box
- `versionCode=22`, `versionName=2.6.0`

## 2.5.7

> Version: 2.5.6 → 2.5.7. This release keeps the Overview billing-cycle dates from being ellipsized by the remaining chip. No new capability and no breaking change, so a patch bump under SemVer.

- Overview billing cycle is two lines: title + remaining chip on the first, full-width start/end on the second; the range shows the timezone offset once at the end (`09-09 — 10-09 15:41 GMT+8`) instead of `09-09 GMT+8 — 10-…`
- The Billing tab cycle card uses the same range label
- `versionCode=21`, `versionName=2.5.7`

## 2.5.6

> Version: 2.5.5 → 2.5.6. This release shows Overview own-pool and third-party usage on one outer ring (both arcs start at the same point and the later one covers the overlap) with billing-cycle progress on the inner ring. No new capability and no breaking change, so a patch bump under SemVer.

- Overview hero outer ring draws own (`autoPercentUsed`) and third-party (`apiPercentUsed`) together from 12 o'clock; where they overlap, the arc drawn later covers the earlier one; the inner ring is still billing-cycle progress
- The legend lists own and third-party; cycle dates and remaining time stay in the row under the rings
- `versionCode=20`, `versionName=2.5.6`

## 2.5.5

> Version: 2.5.4 → 2.5.5. This release adjusts the status widget recent-incident line and indicator. No new capability and no breaking change, so a patch bump under SemVer.

- Status detail widget recent-incident row now shows the affected component and time instead of the incident title; ongoing events and scheduled maintenance still use the official title
- Status mini bar / detail card replace the progress ring with a color status indicator (ok / degraded / outage / maintenance); usage widgets still use a progress ring
- `versionCode=19`, `versionName=2.5.5`

## 2.5.4

> Version: 2.5.3 → 2.5.4. This release removes the Overview plan-quota card. No new capability and no breaking change, so a patch bump under SemVer.

- Overview no longer shows the one plan / used / remaining quota card; quota mix remains on the Usage tab
- `versionCode=18`, `versionName=2.5.4`

## 2.5.3

> Version: 2.5.2 → 2.5.3. This release aligns Overview and Usage plan-quota numbers. No new capability and no breaking change, so a patch bump under SemVer.

- Overview quota tiles are now plan quota / used / remaining, sharing the same official numbers as the Usage quota mix
- The Usage quota mix is normalized to the plan quota: included + in-quota bonus + remaining = Overview plan quota; bonus outside the plan is listed separately
- When the period `limit` is missing, both surfaces fall back to the plan `includedAmount`
- Own-pool / third-party token costs stay on Overview as their own row, labeled as estimates rather than plan quota
- `versionCode=17`, `versionName=2.5.3`

## 2.5.2

> Version: 2.5.1 → 2.5.2. This release fixes status widgets opening the wrong tab. No new capability and no breaking change, so a patch bump under SemVer.

- Tapping the status mini or details widget opens the app on the Status tab; usage widgets still open Overview
- Works when the app is already in the background (`singleTask` / `onNewIntent`)
- `versionCode=16`, `versionName=2.5.2`

## 2.5.1

> Version: 2.5.0 → 2.5.1. This release adjusts existing usage-level thresholds and warning conditions. No new capability and no breaking change, so a patch bump under SemVer.

- “Watch usage” now starts at 80% to match notification alerts
- The same warning also appears when usage percent is ahead of billing-cycle progress (90% / 100% remain “almost out” / “exhausted”)
- `versionCode=15`, `versionName=2.5.1`

## 2.5.0

> Version: 2.4.0 → 2.5.0. This release adds a display time zone and English UI. Backward-compatible user-visible capability, so a minor version bump under SemVer.

- Settings can switch the display time zone (default: follow system). Absolute times on screen include a GMT offset and are converted to the selected zone
- The Tasks tab no longer lists merged branches (`prStatus=merged` or `isPrMerged`)
- English UI: default resources are Simplified Chinese. Settings can choose Follow system / Simplified Chinese / English. Follow system shows English on English devices and falls back to Chinese otherwise
- English docs added (`README.en.md`, `PRIVACY.en.md`, `CHANGELOG.en.md`, `docs/technical.en.md`). Chinese docs remain primary
- `versionCode=14`, `versionName=2.5.0`

## 2.4.0

> Version: 2.3.0 → 2.4.0. This release adds Tasks grouping and includes cloud tasks started from Grok Bot. Backward-compatible user-visible capability, so a minor version bump under SemVer.

- Tasks tab can group by repository / status / time / source. Groups collapse, and the choice is stored on device
- Task list and relative times sort by latest activity / update / create descending
- Cloud task list requests include `include_sources` (including `BACKGROUND_COMPOSER_SOURCE_GROK_BOT`). Sources the website hides by default (Grok Bot / API / SDK) appear in the list
- Task cards show a source chip. Grok Bot tasks are highlighted, and the header shows the Grok Bot count
- `versionCode=13`, `versionName=2.4.0`

## 2.3.0

> Version: 2.2.1 → 2.3.0. This release adds Grok Bot weekly quota on Overview / Usage. Backward-compatible user-visible capability, so a minor version bump under SemVer.

- Grok Bot weekly quota: calls Cursor `DashboardService/GetSandUsageStatus` and shows weekly percent used plus reset countdown on Overview and Usage
- This quota is independent of monthly Cursor-model / other-model pools. Hidden for enterprise shared pools, when the feature is off, or when the cap is 0
- Failures of this API (including 401/403) do not affect spend or percent usage. Overage still uses existing on-demand
- Usage tab history token totals: switch calendar month vs billing cycle, both with previous/next (up to 12 windows), via `GetAggregatedUsageEvents` with `startDate` / `endDate`
- History windows estimate Cursor-model / other-model share from token cost (not official independent quota percents). Official two-pool percents remain current-cycle only. Failures do not affect current-cycle usage
- `versionCode=12`, `versionName=2.3.0`

## 2.2.1

> Version: 2.2.0 → 2.2.1. Overview quota cards and token cards layout polish. No new capability, no breaking change, so a patch bump under SemVer.

- Overview quota card is three columns: plan quota, own-pool spend, and third-party pool cost, aligned with the token card below. Spend is split by model—Composer / Grok / Auto in the own pool, other third-party models in the third-party pool. Shows “—” when token details are unavailable
- Input / output / cache token half-width cards merge into one full-width card with three equal columns
- `versionCode=11`, `versionName=2.2.1`

## 2.2.0

> Version: 2.1.0 → 2.2.0. Usage tab adds per-model token details for the current billing cycle. Backward-compatible user-visible capability, so a minor version bump under SemVer.

- Token usage: calls Cursor `DashboardService/GetAggregatedUsageEvents` and shows total input / output / cache tokens, total cost, and sorted per-model rows on Usage
- Token API failure does not affect spend or percent usage. Old caches without the field show a temporary-unavailable hint; a successful refresh writes the encrypted usage cache
- `versionCode=10`, `versionName=2.2.0`

## 2.1.0

> Version: 2.0.0 → 2.1.0. Adds the Tasks tab (list overview, Chrome Custom Tabs for the official conversation) and customizable tab order. Backward-compatible user-visible capability, so a minor version bump under SemVer.

- Tasks tab: Cursor cloud tasks (background agents) for the current account—name, status (creating / running / finished / error / expired), repo and branch, PR status (tappable), line diffs, files changed, model, and last activity
- Tapping a task opens the official Agents conversation in Chrome Custom Tabs. Full chat uses the browser login, so the app is not limited to the first prompt
- “Open Cursor Agents on the web” and “View PR” also use Custom Tabs after an allowlist check
- Main tab order is customizable in Settings (move up / down, restore default). The current tab moves with the order. Order is stored on device
- Task data reuses the existing Access Token session (same auth as billing). No extra credentials. Updates with silent foreground refresh and manual refresh. Encrypted on-device cache supports offline display
- Parsing is resilient: missing fields or new server enums fall back to placeholders without breaking other tabs
- `versionCode=9`, `versionName=2.1.0`

## 2.0.0

> Version: 1.3.2 → 2.0.0. Includes a package-name change and other breaking changes. Installed users cannot overlay-upgrade; on-device data is not migrated. Major bump under SemVer (planned 1.4.0 was not shipped).

**Breaking changes (read before upgrading)**

- The older Cursor app changed its package ID. Android treats this as a new app: **you cannot overlay-install over the old package**. Uninstall the old app first
- Old-package on-device data is not migrated: encrypted accounts (Access Token), widget and notification prefs, and local caches are removed with uninstall. This app sets `allowBackup=false`, so system backup/restore also does not apply. Add the Access Token again after install
- Placed usage/status widgets, shortcuts, and notification permission must be set up again after reinstall
- Display name changed from “CursorUsage” to “Cursor Assistant” (launcher, top bar, and splash)

**Other changes**

- Status tab: official Cursor availability (overview, components, active incidents, scheduled maintenance, recent history) from Statuspage public JSON (`status.cursor.com/api/v2`). No Access Token
- Status home-screen widgets: 2×1 mini bar and 4×3 detail card, sharing refresh scheduling with usage widgets. No Access Token; usable without an account
- Usage load failure still allows the Status tab so you can read official incident copy
- Incident cards and “Open official status page” may only open `status.cursor.com` / `stspg.io`
- The older Cursor app aligned its repository, Release artifacts, code identifiers, and theme names under one brand. Old `CursorUsage-v*` artifacts are cleaned on package
- `versionCode=8`, `versionName=2.0.0`

## 1.3.2 (preview)

> Preview GitHub Release (marked Pre-release, not Latest). Promoted to stable after real-device verification.

- Compliance: added `PRIVACY.md` and `THIRD_PARTY_NOTICES.md`; README Privacy section links to them
- Fix: network User-Agent no longer hardcodes `1.10`; uses `BuildConfig.VERSION_NAME` (debug builds append `-debug`)
- Build: `build.ps1 -Release` cleans old `CursorUsage-v*` artifacts in `dist/`
- Build: `compose.ui.tooling.preview` moved from `implementation` to `debugImplementation`
- `versionCode=6`, `versionName=1.3.2`

## 1.3.1

- Security: `FLAG_SECURE` while an Access Token is shown in plain text
- Security: copy Access Token via `ClipboardManager` with `EXTRA_IS_SENSITIVE` on Android 13+
- Security: Gradle wrapper `distributionSha256Sum` pin
- Security: `KeystoreCrypto` decrypt uses an existing key only; missing keys fail immediately
- Dependencies: Compose BOM `2024.12.01` → `2026.08.00`, plus other AndroidX updates

## 1.3.0

- Static app shortcuts (Android 8.0+): View Access Token, Manage Cursor account, Settings
- `MainActivity` is `singleTask` and handles `onNewIntent` so shortcuts work when the app is already in the background
- `ShortcutAction` parses shortcut intent actions
- `versionCode=4`, `versionName=1.3.0`

## 1.2.0

- Redrawn app icon (AI usage visual)
- Android 17 Live Update uses `Notification.MetricStyle` on API 37+; API 36 keeps `ProgressStyle`
- Live Update semantic colors on API 37+
- Network security config enables ECH on API 37+ and forbids cleartext
- `build.ps1` Release packaging fixes
- `versionCode=3`, `versionName=1.2.0`

## 1.1.0

- Android 17 (API 37): `compileSdk` / `targetSdk` 37
- Build chain: Gradle 8.13 → 9.5.1, AGP 8.13.2 → 9.2.1, built-in Kotlin 2.2.10
- Release signing switched to an external certificate; signing assets live in `.signing/`
- Billing-cycle reset countdown precise to the minute; percents to two decimals
- Dropped `x86_64`; `arm64-v8a` only
- `versionCode=2`, `versionName=1.1.0`

## 1.0.0

Initial public release (rebranded as CursorUsage; version origin for this repo).

- Cursor subscription usage: total, plan quota, billing cycle, Credits, two pools, on-demand budget
- Dashboard Overview / Usage / Billing tabs
- Persistent usage-monitor Live Update plus Xiaomi HyperOS 3 Dynamic Island
- Threshold reminders at 80% / 100%
- 2×1 and 4×3 Material 3 widgets
- 64-bit ABIs: `arm64-v8a` and `x86_64`
- Alias and Access Token encrypted with Android Keystore + AES-GCM
- Package `com.lamuier.cursorusage`, `versionCode=1`, `versionName=1.0.0`
