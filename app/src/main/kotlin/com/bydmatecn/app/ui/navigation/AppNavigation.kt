package com.bydmatecn.app.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmatecn.app.R
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.bydmatecn.app.data.repository.SettingsRepository
import com.bydmatecn.app.diagnostics.Trace
import com.bydmatecn.app.diagnostics.TraceArea
import com.bydmatecn.app.service.UpdateChecker
import com.bydmatecn.app.ui.charges.ChargesScreen
import com.bydmatecn.app.ui.automation.AutomationScreen
import com.bydmatecn.app.ui.dashboard.DashboardScreen
import com.bydmatecn.app.ui.settings.DonateDialog
import com.bydmatecn.app.ui.settings.DonateEntry
import com.bydmatecn.app.ui.settings.DonationReminder
import com.bydmatecn.app.ui.settings.SettingsScreen
import com.bydmatecn.app.ui.settings.UpdateDialog
import com.bydmatecn.app.ui.settings.UpdateState
import com.bydmatecn.app.ui.theme.*
import com.bydmatecn.app.ui.trips.TripsScreen
import com.bydmatecn.app.ui.welcome.WelcomeScreen

// M3 NavigationBar only sets a default minimum of 80 dp, so an explicit height lowers it; each
// item centres its 32 dp indicator and label in that height, keeping at least 4 dp above and below.
private val NavBarHeight = 64.dp

// Indicator (24 dp icon + 2 x 4 dp), 4 dp to the label, 2 x 4 dp minimum item padding.
private val NavBarItemChrome = 40.dp
private val SideNavigationExpandedWidth = 152.dp
private val SideNavigationCollapsedWidth = 72.dp
private const val SIDE_NAV_PREFS = "navigation-ui"
private const val KEY_SIDE_NAV_COLLAPSED = "side_nav_collapsed"

enum class Screen(val route: String, val labelRes: Int, val icon: ImageVector) {
    Dashboard("dashboard", R.string.nav_tab_dashboard, Icons.Outlined.Home),
    Trips("trips", R.string.nav_tab_trips, Icons.Outlined.DirectionsCar),
    Charges("charges", R.string.nav_tab_charges, Icons.Outlined.BatteryChargingFull),
    Automation("automation", R.string.nav_tab_automation, Icons.Outlined.Bolt),
    Local("local_capabilities", R.string.nav_tab_local, Icons.Outlined.DirectionsCar),
    Settings("settings", R.string.nav_tab_settings, Icons.Outlined.Settings)
}

@Composable
fun AppNavigation(
    settingsRepository: SettingsRepository,
    updateChecker: UpdateChecker
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        startDestination = if (settingsRepository.isSetupCompleted()) "dashboard" else "welcome"
    }

    if (startDestination == null) return // Loading

    // Автоматическая проверка обновлений при запуске приложения.
    // UpdateChecker сам throttle-ит запросы (10 мин между реальными походами в GitHub).
    val autoCheckContext = LocalContext.current
    val autoCheckScope = rememberCoroutineScope()
    var autoUpdateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var autoUpdateState by remember { mutableStateOf<UpdateState?>(null) }
    var autoUpdateDownloadJob by remember { mutableStateOf<Job?>(null) }

    val currentAppVersion = remember {
        runCatching {
            autoCheckContext.packageManager.getPackageInfo(autoCheckContext.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }
    // Donation prompt: from the second entry of a new version onward (the first entry is taken
    // by the post-install reminder), at most once per version, never after opt-out. Shown
    // before the update dialog — support first, then offer the update once it is dismissed.
    var showDonation by remember { mutableStateOf(false) }
    LaunchedEffect(startDestination) {
        val donationEligible = DonationReminder.enabled && startDestination == "dashboard"
        if (donationEligible &&
            UpdateChecker.getLastSeenVersion(autoCheckContext) == currentAppVersion &&
            DonationReminder.shouldShow(autoCheckContext, currentAppVersion)
        ) {
            DonationReminder.markSeen(autoCheckContext, currentAppVersion)
            showDonation = true
        }
    }

    LaunchedEffect(Unit) {
        if (!UpdateChecker.isAutoCheckEnabled(autoCheckContext)) return@LaunchedEffect
        try {
            val info = updateChecker.checkForUpdate(autoCheckContext, forceCheck = false)
            if (info != null) {
                autoUpdateInfo = info
                autoUpdateState = UpdateState.Available(version = info.version, notes = info.releaseNotes)
            }
        } catch (_: Exception) {
            // тихо игнорируем — оффлайн, rate-limit и т.п.
        }
    }
    if (!showDonation) autoUpdateState?.let { dialogState ->
        UpdateDialog(
            currentVersion = currentAppVersion,
            state = dialogState,
            onCheck = {
                val info = autoUpdateInfo ?: return@UpdateDialog
                autoUpdateState = UpdateState.Downloading(info.version, autoCheckContext.getString(R.string.update_downloading_start))
                autoUpdateDownloadJob = autoCheckScope.launch {
                    try {
                        updateChecker.downloadAndInstall(autoCheckContext, info) { progress ->
                            // Игнорируем поздний прогресс после отмены (нажат Закрыть),
                            // иначе закрытый диалог «воскресает» в Downloading.
                            if (isActive) {
                                autoUpdateState = UpdateState.Downloading(info.version, progress)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e // кооперативная отмена из onDismiss, не ошибка
                    } catch (e: Exception) {
                        autoUpdateState = UpdateState.Error(e.message ?: "Download failed")
                    }
                }
            },
            onDismiss = {
                autoUpdateDownloadJob?.cancel()
                autoUpdateDownloadJob = null
                autoUpdateState = null
                autoUpdateInfo = null
            }
        )
    }

    // Post-install reminder: первый запуск новой версии → напомнить про Disable background Apps.
    var showPostInstallReminder by remember {
        mutableStateOf(UpdateChecker.getLastSeenVersion(autoCheckContext) != currentAppVersion)
    }
    if (showPostInstallReminder) {
        PostInstallReminderDialog(
            version = currentAppVersion,
            onDismiss = {
                UpdateChecker.setLastSeenVersion(autoCheckContext, currentAppVersion)
                showPostInstallReminder = false
            }
        )
    }

    if (DonationReminder.enabled && showDonation) {
        DonateDialog(
            entry = DonateEntry.AUTO,
            onDismiss = { showDonation = false },
            onAlreadySupported = {
                DonationReminder.setOptedOut(autoCheckContext)
                showDonation = false
            },
        )
    }

    val isWelcome = currentDestination?.route == "welcome"
    val wideNavigation = LocalConfiguration.current.screenWidthDp >= 900
    val navigationPrefs = remember(autoCheckContext) {
        autoCheckContext.getSharedPreferences(SIDE_NAV_PREFS, Context.MODE_PRIVATE)
    }
    var sideNavigationCollapsed by rememberSaveable {
        mutableStateOf(navigationPrefs.getBoolean(KEY_SIDE_NAV_COLLAPSED, false))
    }
    val localChildRoutes = setOf("blind_spot", "tool_settings/{section}")
    fun isSelected(screen: Screen): Boolean {
        val exact = currentDestination?.hierarchy?.any { it.route == screen.route } == true
        return exact || (screen == Screen.Local && currentDestination?.route in localChildRoutes)
    }
    fun navigateTo(screen: Screen) {
        Trace.event(TraceArea.USER, "tab", "to" to screen.route)
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
    // Grows past NavBarHeight only once a large text size makes the label too tall for it.
    // The bar pads itself by its bottom window inset inside this height, so the inset is added on top.
    val density = LocalDensity.current
    val navBarHeight = maxOf(
        NavBarHeight,
        with(density) { MaterialTheme.typography.labelMedium.lineHeight.toDp() } + NavBarItemChrome,
    ) + with(density) { NavigationBarDefaults.windowInsets.getBottom(density).toDp() }

    Scaffold(
        containerColor = NavyDark,
        bottomBar = {
            if (!isWelcome && !wideNavigation) {
                NavigationBar(
                    modifier = Modifier.height(navBarHeight),
                    containerColor = NavBarBackground
                ) {
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = stringResource(screen.labelRes)
                                )
                            },
                            label = {
                                Text(
                                    stringResource(screen.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            selected = isSelected(screen),
                            onClick = { navigateTo(screen) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentGreen,
                                selectedTextColor = AccentGreen,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = NavIndicator
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Row(Modifier.fillMaxSize().padding(paddingValues)) {
            if (!isWelcome && wideNavigation) {
                VehicleSideNavigation(
                    selected = ::isSelected,
                    onSelected = ::navigateTo,
                    collapsed = sideNavigationCollapsed,
                    onCollapsedChange = { collapsed ->
                        sideNavigationCollapsed = collapsed
                        navigationPrefs.edit().putBoolean(KEY_SIDE_NAV_COLLAPSED, collapsed).apply()
                    },
                )
            }
            NavHost(
                navController = navController,
                startDestination = startDestination!!,
                modifier = Modifier.weight(1f),
            ) {
            composable("welcome") {
                WelcomeScreen(
                    onComplete = {
                        navController.navigate("dashboard") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Dashboard.route) {
                // Same tab switch as the bottom bar's Settings item.
                val openSettingsTab = {
                    navController.navigate(Screen.Settings.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                DashboardScreen(
                    onOpenTechPanel = { navController.navigate("tech_panel") },
                    onOpenSettings = openSettingsTab,
                    // Settings first: the periods screen takes its view model from that entry,
                    // and Back lands on Settings.
                    onOpenTariffPeriods = {
                        openSettingsTab()
                        // The restored Settings stack may already end on the periods screen.
                        navController.navigate("tariff_periods") { launchSingleTop = true }
                    },
                )
            }
            composable(Screen.Trips.route) {
                TripsScreen(onOpenTemperature = { navController.navigate("trip_temperature") })
            }
            composable(Screen.Charges.route) {
                ChargesScreen(onNavigateSettings = { navController.navigate(Screen.Settings.route) })
            }
            composable(Screen.Local.route) {
                com.bydmatecn.app.local.LocalCapabilitiesScreen(onNavigate = { navController.navigate(it) })
            }
            composable("blind_spot") {
                com.bydmatecn.app.ui.settings.BlindSpotSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("tool_settings/{section}") { entry ->
                com.bydmatecn.app.ui.settings.VehicleToolSettingsScreen(
                    section = entry.arguments?.getString("section") ?: "", onBack = { navController.popBackStack() })
            }
            composable(Screen.Automation.route) { AutomationScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToAgentChat = { navController.navigate("agent_chat") },
                    onNavigateToVoiceJournal = { navController.navigate("voice_journal") },
                    onNavigateToVoiceUserPhrases = { navController.navigate("voice_user_phrases") },
                    onNavigateToTariffPeriods = { navController.navigate("tariff_periods") },
                )
            }
            composable("trip_temperature") {
                com.bydmatecn.app.ui.trips.TripTemperatureScreen(onBack = { navController.popBackStack() })
            }
            composable("tech_panel") {
                com.bydmatecn.app.ui.tech.TechPanelScreen(onBack = { navController.popBackStack() })
            }
            composable("tariff_periods") { entry ->
                // The settings entry's own view model: the periods list, the recalc status and
                // the summary line under the «Открыть» button all live in that one instance.
                val settingsEntry = remember(entry) { navController.getBackStackEntry(Screen.Settings.route) }
                com.bydmatecn.app.ui.settings.TariffPeriodsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel(settingsEntry),
                )
            }
            composable("agent_chat") {
                com.bydmatecn.app.ui.debug.AgentChatScreen(onBack = { navController.popBackStack() })
            }
            composable("voice_journal") {
                com.bydmatecn.app.ui.debug.VoiceJournalScreen(onBack = { navController.popBackStack() })
            }
            composable("voice_user_phrases") {
                com.bydmatecn.app.ui.settings.VoiceUserPhrasesScreen(onBack = { navController.popBackStack() })
            }
        }
        }
    }
}

@Composable
private fun VehicleSideNavigation(
    selected: (Screen) -> Boolean,
    onSelected: (Screen) -> Unit,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
) {
    val width by animateDpAsState(
        targetValue = if (collapsed) SideNavigationCollapsedWidth else SideNavigationExpandedWidth,
        label = "主导航宽度",
    )
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Color(0xFF0A1725))
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.SpaceBetween,
        ) {
            if (!collapsed) {
                Text(
                    "BYDMateCN",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            IconButton(onClick = { onCollapsedChange(!collapsed) }) {
                Icon(
                    imageVector = if (collapsed) Icons.AutoMirrored.Outlined.ArrowForward
                    else Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = if (collapsed) "展开主导航" else "折叠主导航",
                    tint = Color(0xFF9CB1C5),
                )
            }
        }
        Screen.entries.forEach { screen ->
            val active = selected(screen)
            val label = stringResource(screen.labelRes)
            Card(
                onClick = { onSelected(screen) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) Color(0xFF087BC1) else Color.Transparent,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(
                        horizontal = if (collapsed) 0.dp else 12.dp,
                        vertical = 12.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = label,
                        tint = if (active) Color.White else Color(0xFF8298AE),
                        modifier = Modifier.size(23.dp),
                    )
                    if (!collapsed) {
                        Text(
                            label,
                            color = if (active) Color.White else Color(0xFFA8BACB),
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PostInstallReminderDialog(version: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScaledDialogContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .clickable { /* absorb */ }
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.nav_autostart_dialog_title, version), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.nav_autostart_dialog_body),
                            color = TextSecondary, fontSize = 14.sp
                        )
                        Button(
                            onClick = {
                                com.bydmatecn.app.util.AutostartScreen.open(context)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text(stringResource(R.string.nav_autostart_dialog_button), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
