package com.lamuier.agenT.qa

import com.lamuier.agenT.R

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.lamuier.agenT.codex.*
import com.lamuier.agenT.data.EncryptedAccountStore
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.*
import com.lamuier.agenT.ui.DashboardScreen
import com.lamuier.agenT.ui.TokenManagementContent
import com.lamuier.agenT.ui.SettingsSheet
import com.lamuier.agenT.data.NotificationSettings
import com.lamuier.agenT.data.PercentDisplayMode
import com.lamuier.agenT.ui.theme.AgenTTheme
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.ui.theme.ThemeMode
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.widget.AgenTWidgetUpdater
import com.lamuier.agenT.notification.QuotaNotificationRenderer
import com.lamuier.agenT.notification.QuotaNoticeMetric
import com.lamuier.agenT.notification.QuotaNoticeSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Debug-only native visual review. Not exported. Storage checks use a separate prefs file and key alias. */
class UiReviewActivity : FragmentActivity() {
    private val zone = ZoneId.of("Asia/Shanghai")

    private companion object {
        const val QA_CURSOR_PREFERENCES = "agentt_qa_cursor_accounts_v1"
        const val QA_CURSOR_KEY_ALIAS = "agentt_qa_cursor_key_v1"
    }
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(AppLocale.wrap(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val language = if (intent.getStringExtra("language") == "en") AppLanguage.English else AppLanguage.Chinese
        val oldLanguage = AppLocale.stored(this)
        val light = intent.getBooleanExtra("light", false)
        val theme = ThemeSettings(themeMode = if (light) ThemeMode.Light else ThemeMode.Dark)
        UiReviewPreferenceHold.captureAndApply(this, language, zone.id, theme)
        if (oldLanguage != language) { recreate(); return }
        if (intent.getStringExtra("mode") == "storage-check") {
            runStorageChecks()
            return
        }
        if (intent.getStringExtra("mode") == "public-api-check") {
            lifecycleScope.launch {
                try {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        CodexPublicResetRepository(this@UiReviewActivity).fetch(force = true)
                    }
                    check(result.events.map { it.id }.distinct().size == result.events.size)
                    val cached = CodexPublicResetRepository(this@UiReviewActivity).cached()
                    check(cached?.events == result.events)
                    Log.i("AgenTUiQa", "Public reset API parsed and cached: records=${result.events.size}, truncated=${result.truncated}; no credentials")
                } catch (error: Exception) { Log.e("AgenTUiQa", "Public reset API check failed: ${error.javaClass.simpleName}") }
                finish()
            }
            return
        }
        if (intent.getStringExtra("mode") == "notification-reset-check") {
            runResetNotificationChecks()
            return
        }
        val fixture = ReviewFixture.create(zone)
        if (intent.getStringExtra("mode") == "quota-icons") {
            showQuotaIcons()
            return
        }
        if (intent.getStringExtra("mode") == "notifications") {
            showNotifications(fixture)
            return
        }
        val wrapped = AppLocale.wrap(this)
        if (intent.getStringExtra("mode") == "widgets") {
            showWidgets(fixture)
            return
        }
        val requestedTab = DashboardTab.fromId(intent.getStringExtra("tab").orEmpty()) ?: DashboardTab.Overview
        setContent {
            CompositionLocalProvider(LocalContext provides wrapped,
                LocalConfiguration provides wrapped.resources.configuration, LocalDisplayZone provides zone) {
                AgenTTheme(theme) {
                    var settingsOpen by remember { mutableStateOf(intent.getStringExtra("mode") == "settings") }
                    var pendingTab by remember { mutableStateOf<DashboardTab?>(requestedTab) }
                    var resetLedger by remember { mutableStateOf(fixture.codex.resetLedger!!) }
                    val codexFixture = fixture.codex.copy(resetLedger = resetLedger)
                    var tokensOpen by remember { mutableStateOf(intent.getStringExtra("mode") == "tokens") }
                    var tokenProvider by remember { mutableStateOf(
                        if (intent.getStringExtra("provider") == "codex") TokenProvider.Codex else TokenProvider.Cursor) }
                    if (tokensOpen) {
                        // Empty inputs and inert callbacks only; no saved token is read or displayed here.
                        Surface(Modifier.fillMaxSize().statusBarsPadding()) {
                            TokenManagementContent(tokenProvider, fixture.cursor.accounts.first(), fixture.codex.credential,
                                busy = false, error = null, onProviderChange = { tokenProvider = it }, onClearError = {},
                                onDismiss = { finish() },
                                onSave = { provider, _, _, done -> Log.i("AgenTUiQa", "Token form save provider=$provider"); done() },
                                onDeleteRequest = {},
                                onRevealSavedToken = { error("Fixture preview does not read credentials") })
                        }
                        return@AgenTTheme
                    }
                    DashboardScreen(
                        state = fixture.cursor, codexState = codexFixture,
                        snackbarHostState = remember { SnackbarHostState() },
                        tabOrder = DashboardTab.DEFAULT_ORDER, pendingOpenTab = pendingTab,
                        onOpenTabConsumed = { pendingTab = null },
                        onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {},
                        onManageCodex = { settingsOpen = true }, onManageAccount = { settingsOpen = true },
                        onShowSettings = { settingsOpen = true }, onShowTokenHelp = {},
                        onSubscriptionDay = { day ->
                            resetLedger = resetLedger.copy(subscriptionDay = day)
                            Log.i("AgenTUiQa", "Reset fixture subscription day=$day; no network requests")
                        },
                    )
                    if (settingsOpen) SettingsSheet(
                        settings = theme, notificationSettings = NotificationSettings(), onDismiss = { settingsOpen = false },
                        onThemeModeChange = {}, onPaletteChange = {}, timeZoneId = zone.id, onTimeZoneChange = {},
                        language = language, onLanguageChange = {}, onLiveUpdatesToggle = {}, onThresholdRemindersToggle = {},
                        percentDisplayMode = PercentDisplayMode.Used, onPercentDisplayModeChange = {},
                        tabOrder = DashboardTab.DEFAULT_ORDER, onTabOrderChange = {}, onTabOrderReset = {},
                        onManageTokens = { settingsOpen = false; tokensOpen = true },
                        cursorAccount = fixture.cursor.accounts.first(), codexState = fixture.codex,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) UiReviewPreferenceHold.restore(this)
        super.onDestroy()
    }

    private fun runStorageChecks() {
        val cursor = EncryptedAccountStore(
            this,
            preferencesName = QA_CURSOR_PREFERENCES,
            keyAlias = QA_CURSOR_KEY_ALIAS,
        )
        val codex = CodexQuotaRepository(this, isolated = true)
        val payload = "{\"sub\":\"native-qa-only\",\"exp\":${Instant.now().epochSecond + 86400}}"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val first = "eyJhbGciOiJub25lIn0.$encoded.native_qa_only_signature_A"
        val replacement = "eyJhbGciOiJub25lIn0.$encoded.native_qa_only_signature_B"
        var cursorId: Int? = null
        try {
            val account = cursor.create("Native QA fixture", first)
            cursorId = account.id
            check(cursor.revealAccessToken(account.id) == first)
            val revision = cursor.revision(account.id)
            cursor.update(account.id, "Native QA fixture", replacement)
            check(cursor.revealAccessToken(account.id) == replacement)
            check(cursor.revision(account.id) > revision)
            codex.saveCredential(first, "qa_workspace")
            check(codex.revealToken() == first)
            check(codex.credentialInfo()?.accountId == "qa_workspace")
            val codexRevision = codex.tokenRevision()
            val entry = CodexResetEvent("native-qa-reset", CodexResetKind.ManualUse, CodexResetSource.Registered, Instant.now().epochSecond)
            codex.registerReset(codexRevision, entry)
            codex.setSubscriptionDay(codexRevision, 31)
            check(codex.resetHistory()?.events?.single() == entry)
            codex.saveCredential(replacement, "qa_workspace")
            check(codex.revealToken() == replacement)
            check(codex.tokenRevision() > codexRevision)
            check(codex.resetHistory()?.events?.single() == entry)
            check(codex.resetHistory()?.subscriptionDay == 31)
            check(runCatching { codex.registerReset(codexRevision, entry) }.isFailure)
            codex.saveCredential(replacement, "qa_workspace_other")
            check(codex.resetHistory()?.events?.isEmpty() == true)
            codex.saveCredential(replacement, "qa_workspace")
            check(codex.resetHistory()?.events?.single() == entry)
            codex.deleteReset(codex.tokenRevision(), entry.id)
            check(codex.resetHistory()?.events?.isEmpty() == true)
            Log.i("AgenTUiQa", "Android Keystore: Cursor and Codex save/read/replace verified; no network requests")
            Log.i("AgenTUiQa", "Reset Keystore: persistence, rotation, workspace isolation, revision guard, anchor and deletion verified")
        } finally {
            try {
                runCatching { cursorId?.let(cursor::delete) }
                runCatching { codex.clearCredential() }
                check(codex.credentialInfo() == null)
                codex.saveCredential(first, "qa_workspace")
                check(codex.resetHistory() == CodexResetLedger())
                codex.clearCredential()
                getSharedPreferences(QA_CURSOR_PREFERENCES, MODE_PRIVATE).edit().clear().commit()
                Log.i("AgenTUiQa", "Android Keystore: fixture credentials deleted")
            } finally {
                listOf(
                    QA_CURSOR_KEY_ALIAS,
                    "agentt_qa_codex_token_key_v1",
                    "agentt_qa_codex_cache_key_v1",
                    "agentt_qa_codex_reset_history_key_v1",
                ).forEach(::deleteKeystoreAlias)
            }
        }
        setContentView(TextView(this).apply { text = "Local storage checks passed" })
    }

    private fun deleteKeystoreAlias(alias: String) {
        runCatching {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun runResetNotificationChecks() = lifecycleScope.launch {
        val prefs = com.lamuier.agenT.data.NotificationPreferences.get(this@UiReviewActivity)
        val saved = prefs.read().codexResetRemindersEnabled
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val coordinator = com.lamuier.agenT.notification.AgenTNotificationCoordinator.get(this@UiReviewActivity)
        val now = Instant.now().epochSecond
        val owner = CodexResetIdentity.hash("native-qa-only")
        val nonce = java.util.UUID.randomUUID().toString()
        val events = listOf(
            CodexResetEvent("$nonce-primary", CodexResetKind.AutomaticPrimary, CodexResetSource.Observed, now),
            CodexResetEvent("$nonce-weekly", CodexResetKind.AutomaticWeekly, CodexResetSource.Observed, now))
        try {
            prefs.setCodexResetRemindersEnabled(true)
            coordinator.onCodexQuotaObserved(ReviewFixture.create(zone).codex.usage!!, events, owner, 1, false)
            check(events.all { prefs.resetAlreadyNotified(CodexResetIdentity.hash("$owner|${it.id}")) })
            withTimeout(10_000) { while (!manager.activeNotifications.map { it.id }.containsAll(listOf(4501, 4502))) delay(50) }
            manager.cancel(4501); manager.cancel(4502)
            withTimeout(10_000) { while (manager.activeNotifications.any { it.id in listOf(4501, 4502) }) delay(50) }
            coordinator.onCodexQuotaObserved(ReviewFixture.create(zone).codex.usage!!, events, owner, 1, false)
            delay(300)
            check(manager.activeNotifications.none { it.id in listOf(4501, 4502) })
            Log.i("AgenTUiQa", "Codex reset notices: independent primary/weekly posts, dedupe after cancellation, preference restoration; fixtures only/no network")
        } finally { prefs.setCodexResetRemindersEnabled(saved); coordinator.clearCodexResetAlerts() }
        setContentView(TextView(this@UiReviewActivity).apply { text = "Reset notification checks passed" })
    }

    private fun showNotifications(fixture: ReviewFixture) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val channel = "agentt_native_notification_review"
        manager.createNotificationChannel(android.app.NotificationChannel(channel, "Native review", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val codex = fixture.codex.usage!!.copy(
            primary = fixture.codex.usage.primary!!.copy(usedPercent = 100.0),
            secondary = fixture.codex.usage.secondary!!.copy(usedPercent = 16.0),
        )
        val provider = intent.getStringExtra("provider")
        val cursor = fixture.cursor.usage.takeUnless { provider == "codex" }?.let {
            if (intent.getBooleanExtra("cached", false)) it.copy(fromCache = true) else it
        }
        val quota = codex.takeUnless { provider == "cursor" }
        val mode = if (intent.getBooleanExtra("remaining", false)) PercentDisplayMode.Remaining else PercentDisplayMode.Used
        val pending = android.app.PendingIntent.getActivity(this, 9001,
            android.content.Intent(this, UiReviewActivity::class.java).putExtra("mode", "notifications"),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val renderer = QuotaNotificationRenderer(this, intent.getBooleanExtra("catLanguage", false))
        val live = requireNotNull(renderer.live(cursor, quota, 1, mode, zone, pending, channel))
        check(live.contentView == null && live.bigContentView == null)
        check(live.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        check(!live.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("%"))
        check(!live.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("%"))
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            check(live.hasPromotableCharacteristics())
            Log.i("AgenTUiQa", "Live Update: native attention template, no quota values or custom views, promotable=true")
        }
        val reminder = renderer.reminder(QuotaNoticeMetric(QuotaNoticeSource.CodexPrimary, 100.0, null), 100, pending, channel)
        manager.notify(9001, live)
        if (intent.getBooleanExtra("postReminder", false)) manager.notify(9002, reminder)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, (36 * resources.displayMetrics.density).toInt(), 24, 30)
            setBackgroundColor(if (intent.getBooleanExtra("light", false)) Color.rgb(240, 242, 246) else Color.rgb(30, 34, 41))
        }
        fun add(title: String, view: android.widget.RemoteViews?) {
            if (view == null) return
            outer.addView(TextView(this).apply { text = title; setTextColor(Color.GRAY); textSize = 12f })
            val frame = FrameLayout(this)
            frame.addView(view.apply(this, frame), FrameLayout.LayoutParams(-1, -1))
            val height = if (title.contains("collapsed")) (80 * resources.displayMetrics.density * resources.configuration.fontScale).toInt() else -2
            outer.addView(frame, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = 24 })
            frame.post {
                val ellipsized = mutableListOf<String>()
                fun inspect(child: View) {
                    if (child is TextView && child.text.isNotEmpty() && child.visibility == View.VISIBLE) {
                        val layout = child.layout
                        if (layout != null && (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 })
                            ellipsized += child.text.toString()
                    }
                    if (child is android.view.ViewGroup) for (index in 0 until child.childCount) inspect(child.getChildAt(index))
                }
                inspect(frame)
                Log.i("AgenTUiQa", "Notification template=$title sdk=${android.os.Build.VERSION.SDK_INT} fontScale=${resources.configuration.fontScale} ellipsized=$ellipsized")
            }
        }
        val builder = android.app.Notification.Builder.recoverBuilder(this, live)
        add("Native expanded · fixture only", builder.createBigContentView())
        add("Native collapsed · fixture only", builder.createContentView())
        add("Native quota reminder · fixture only", android.app.Notification.Builder.recoverBuilder(this, reminder).createBigContentView())
        setContentView(android.widget.ScrollView(this).apply { addView(outer) })
    }

    private fun showQuotaIcons() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(235, 240, 245))
        }
        outer.addView(TextView(this).apply {
            text = "App icon + attention states · fixture\nNot Xiaomi system rendering"
            textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
        com.lamuier.agenT.notification.IslandMonitorState.entries.forEach { state ->
            val capsule = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.BLACK); cornerRadius = dp(40).toFloat()
                }
            }
            capsule.addView(android.widget.ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
                LinearLayout.LayoutParams(dp(30), dp(30)))
            capsule.addView(TextView(this).apply {
                text = AppLocale.wrap(this@UiReviewActivity).getString(state.labelRes)
                textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.END; maxLines = 1
            }, LinearLayout.LayoutParams(0, -2, 1f))
            outer.addView(capsule, LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(16) })
        }
        setContentView(outer)
    }

    private fun showWidgets(fixture: ReviewFixture) {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        window.statusBarColor = Color.TRANSPARENT
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            setBackgroundColor(Color.rgb(211, 220, 228))
        }
        val label = TextView(this).apply {
            text = "Native Android widgets · fixture data"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(20))
        }
        outer.addView(label)
        listOf(false, true).forEach { mini ->
            val frame = FrameLayout(this)
            val availableWidth = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32
            val width = if (mini) 174 else minOf(intent.getIntExtra("widgetWidth", 334), availableWidth.toInt())
            val height = if (mini) 68 else intent.getIntExtra("widgetHeight", 252)
            val remote = AgenTWidgetUpdater.renderUsagePreview(this, fixture.cursor.usage, fixture.codex.usage, mini, width, height)
            val view = remote.apply(this, frame)
            // Review views are inert; production pending intents are not fired from the fixture screen.
            view.isClickable = false
            frame.addView(view, FrameLayout.LayoutParams(-1, -1))
            outer.addView(frame, LinearLayout.LayoutParams(dp(width), dp(height)).apply {
                bottomMargin = dp(24)
            })
            frame.post {
                val clipped = mutableListOf<String>()
                fun check(child: View) {
                    if (child is TextView && child.visibility == View.VISIBLE && child.text.isNotEmpty()) {
                        val layout = child.layout
                        val visible = android.graphics.Rect()
                        val croppedByParent = !child.getGlobalVisibleRect(visible) || visible.height() < child.height || visible.width() < child.width
                        if (layout != null && (layout.height > child.height - child.compoundPaddingTop - child.compoundPaddingBottom ||
                                (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 } || croppedByParent)) {
                            clipped += resources.getResourceEntryName(child.id)
                            Log.i("AgenTUiQa", "measure ${resources.getResourceEntryName(child.id)} view=${child.height} layout=${layout.height} lines=${layout.lineCount} ellipsis=${(0 until layout.lineCount).map { layout.getEllipsisCount(it) }}")
                        }
                    }
                    if (child is android.view.ViewGroup) for (index in 0 until child.childCount) check(child.getChildAt(index))
                }
                check(view)
                Log.i("AgenTUiQa", "widget mini=$mini size=${width}x${height} fontScale=${resources.configuration.fontScale} clipped=$clipped")
            }
        }
        setContentView(outer)
    }
}

internal data class ReviewFixture(val cursor: AppUiState, val codex: CodexQuotaUiState) {
    companion object {
        fun create(zone: ZoneId, now: Instant = Instant.now()): ReviewFixture {
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(now)
            val end = now.plusSeconds(2 * 86400 + 20 * 3600)
            val usage = AgenTOverview(
                accountId = 1, alias = "Personal", isTeam = false,
                plan = PlanInfo("Pro", "\$20", 20.0, end.toString()),
                billingCycle = BillingCycle(end.minusSeconds(30 * 86400).toString(), end.toString()),
                usage = UsageMetrics(99.74, TotalFormat.Percent, 19.95, 19.95, 0.0, 20.0, 0.05, 100.0, 97.0, null, false),
                credits = Credits(0.0, 0.0, 0.0), onDemand = null, subscription = Subscription(null, null),
                fetchedAt = stamp, fromCache = false, cacheAgeSeconds = 0,
                tokenUsage = TokenUsageBreakdown(emptyList(), 800000, 50000, 1000000, 3000000, 19.95),
            )
            fun status(codex: Boolean) = ServiceStatus(
                description = "", indicator = if (codex) StatusIndicator.None else StatusIndicator.Minor,
                pageUpdatedAt = now.toString(), pageUrl = if (codex) "https://status.openai.com" else "https://status.cursor.com",
                components = if (codex) listOf("Codex Web", "Codex API", "CLI", "Codex in ChatGPT Desktop", "VS Code extension")
                    .mapIndexed { index, name -> StatusComponent("codex-$index", name, ComponentStatus.Operational, index) }
                else listOf(StatusComponent("ide", "IDE", ComponentStatus.Operational, 0),
                    StatusComponent("agents", "Cloud Agents", ComponentStatus.DegradedPerformance, 1)),
                activeIncidents = emptyList(), scheduledMaintenances = emptyList(), recentIncidents = emptyList(),
                fetchedAt = stamp, fromCache = false,
            )
            val account = CursorAccount(1, "Personal", false, stamp, stamp)
            return ReviewFixture(
                AppUiState(stage = AppStage.Dashboard, accounts = listOf(account), selectedAccountId = 1,
                    tasks = com.lamuier.agenT.model.AgenTasks(1, (1..3).map { index ->
                        com.lamuier.agenT.model.AgentTask("bc_fixture_$index", listOf("Improve adaptive navigation", "Polish quota cards", "Review accessibility")[index - 1],
                            if (index == 1) com.lamuier.agenT.model.AgentTaskStatus.Running else com.lamuier.agenT.model.AgentTaskStatus.Finished,
                            "https://github.com/Lamuier/agenT", "ui-review", null, null, 36, 8, 3, "default", false,
                            now.minusSeconds(3600L * index).toEpochMilli(), now.toEpochMilli(), null,
                            com.lamuier.agenT.model.AgentTaskSource.Api)
                    }, stamp, false),
                    usage = usage, serviceStatus = status(false)),
                CodexQuotaUiState(credential = CodexCredentialInfo(false, false, stamp),
                    usage = CodexQuotaOverview("plus",
                        CodexQuotaWindow(12.0, 18000, 4 * 3600, now.epochSecond + 4 * 3600),
                        CodexQuotaWindow(48.0, 604800, 4 * 86400, now.epochSecond + 4 * 86400),
                        emptyList(), 1, stamp, resetCredits = CodexResetCredits(1,
                            listOf(CodexResetCredit("fixture-credit", "available", now.epochSecond - 7200, now.epochSecond + 20 * 86400, "Codex reset credit")))),
                    resetLedger = CodexResetLedger(startedAt = now.epochSecond - 86400, events = listOf(
                        CodexResetEvent("fixture-unknown", CodexResetKind.WindowChanged, CodexResetSource.Observed, now.epochSecond - 600, before = 98.0, after = 0.0),
                        CodexResetEvent("fixture-personal", CodexResetKind.ManualUse, CodexResetSource.Registered, now.epochSecond - 3600, count = 2),
                        CodexResetEvent("fixture-official", CodexResetKind.OfficialExtra, CodexResetSource.Registered, now.epochSecond - 7200),
                        CodexResetEvent("fixture-auto", CodexResetKind.AutomaticPrimary, CodexResetSource.Observed, now.epochSecond - 10800))),
                    publicResets = CodexPublicResetSnapshot(listOf(
                        CodexPublicReset("fixture-public-banked", "banked", now.epochSecond - 1800,
                            "Public reset-credit grant fixture. Not an account redemption.", "x_post", "thsottiaux",
                            "https://x.com/thsottiaux/status/2108040921044639779"),
                        CodexPublicReset("fixture-public-observed", "regular", now.epochSecond - 5400,
                        "Public reset observation fixture.", "observed", null, "https://codex-resets.com")) +
                        (1..28).map { index -> CodexPublicReset("fixture-heatmap-$index", if (index % 5 == 0) "banked" else "regular",
                            now.minusSeconds(index * 5L * 86400).epochSecond, "Calendar heatmap fixture.", "observed", null, "https://codex-resets.com") },
                        now.toEpochMilli()),
                    booting = false, serviceStatus = status(true), loadingStatus = false),
            )
        }
    }
}
