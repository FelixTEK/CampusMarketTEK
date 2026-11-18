package com.example.campusmarkettek.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.campusmarkettek.data.local.dao.NotificationDao
import com.example.campusmarkettek.data.remote.MeDto
import com.example.campusmarkettek.ui.admin.AdminQueueScreen
import com.example.campusmarkettek.ui.admin.AdminReviewScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.campusmarkettek.ui.chat.ChatListScreen
import com.example.campusmarkettek.ui.stats.MyStatsScreen
import com.example.campusmarkettek.ui.privacy.PrivacyScreen
import com.example.campusmarkettek.ui.admin.AdminBannersScreen
import com.example.campusmarkettek.ui.admin.AdminBannerFormScreen
import com.example.campusmarkettek.ui.stats.AdminStatsScreen
import com.example.campusmarkettek.ui.profile.SellerProfileScreen
import com.example.campusmarkettek.ui.admin.AdminReportsScreen
import com.example.campusmarkettek.ui.admin.AdminReportDetailScreen
import com.example.campusmarkettek.ui.chat.ChatRoomScreen
import com.example.campusmarkettek.ui.chat.DealsScreen
import com.example.campusmarkettek.data.remote.RealtimeClient
import com.example.campusmarkettek.data.remote.RealtimeEvent
import com.example.campusmarkettek.data.auth.NavRequests
import com.example.campusmarkettek.data.repository.NotificationRepository
import com.example.campusmarkettek.work.NotificationScheduler
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.campusmarkettek.data.repository.ChatRepository
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.LaunchedEffect
import com.example.campusmarkettek.ui.listing.FormResult
import com.example.campusmarkettek.ui.listing.ListingDetailScreen
import com.example.campusmarkettek.ui.listing.ListingFormScreen
import com.example.campusmarkettek.ui.listing.MyListingsScreen
import com.example.campusmarkettek.work.SyncScheduler
import com.example.campusmarkettek.ui.favorites.FavoritesScreen
import com.example.campusmarkettek.ui.home.HomeScreen
import com.example.campusmarkettek.ui.notifications.NotificationsScreen
import com.example.campusmarkettek.ui.profile.ProfileScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 5 แท็บหลัก ตามแถบล่างใน mockup */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "หน้าแรก", Icons.Outlined.Home),
    Chat("chat", "แชต", Icons.Outlined.ChatBubbleOutline),
    Create("create", "ลงขาย", Icons.Outlined.AddCircleOutline),
    Notifications("notifications", "แจ้งเตือน", Icons.Outlined.NotificationsNone),
    Profile("profile", "ฉัน", Icons.Outlined.PersonOutline),
}

@HiltViewModel
class MainViewModel @Inject constructor(
    notifications: NotificationDao,
    draftSync: SyncScheduler,
    private val chat: ChatRepository,
    private val notificationRepo: NotificationRepository,
    notificationScheduler: NotificationScheduler,
    navRequests: NavRequests,
    val realtime: RealtimeClient,
) : ViewModel() {
    /** คำขอไปหน้าอื่นจากนอกแอป (แตะแจ้งเตือนของระบบ) */
    val navRoutes = navRequests.routes

    init {
        // เข้าแอปทีไร ส่งแบบร่างประกาศที่ค้างอยู่ (ถ้ามี) — ทำงานเมื่อมีเน็ต
        draftSync.enqueue()
        notificationScheduler.schedule() // ดึงแจ้งเตือนเบื้องหลังทุก ≥ 15 นาที
        viewModelScope.launch { chat.refreshUnread() }
        viewModelScope.launch { runCatching { notificationRepo.refresh() } }
        // เหตุการณ์สดจาก WebSocket (ข้อความ/ดีล) → เขียนลง Room ที่เดียว หน้าจอทุกหน้าอ่านจาก Room
        viewModelScope.launch {
            realtime.events.collect { e ->
                chat.onEvent(e)
                // ดีลเปลี่ยน/ต่อใหม่ = อาจมีแถวแจ้งเตือนใหม่ (สร้างใน transaction เดียวกับดีล) → ดึงมาให้ badge ขึ้นทันที
                if (e is RealtimeEvent.Deal || e is RealtimeEvent.Connected) runCatching { notificationRepo.refresh() }
            }
        }
    }

    /** ตัวเลขข้อความที่ยังไม่อ่านบนแท็บแชต */
    val unreadChats: StateFlow<Int> = chat.unreadTotal

    /** จำนวนแจ้งเตือนที่ยังไม่อ่าน มาจากตาราง notifications (is_read = false) ในแคช */
    val unreadNotifications: StateFlow<Int> = notifications.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

private fun isTabRoute(route: String?) = Tab.entries.any { it.route == route }

@Composable
fun MainShell(
    me: MeDto,
    onMeChanged: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val unread by viewModel.unreadNotifications.collectAsStateWithLifecycle()
    val unreadChats by viewModel.unreadChats.collectAsStateWithLifecycle()

    // แตะแจ้งเตือนของระบบ → ไปหน้าที่เกี่ยวข้อง
    LaunchedEffect(Unit) { viewModel.navRoutes.collect { navController.navigate(it) } }

    // Android 13+: ขออนุญาตแจ้งเตือนของระบบครั้งเดียวตอนเข้าแอป (ปฏิเสธก็ใช้แอปได้ — badge ในแอปยังทำงาน)
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // เปิดแอปอยู่หน้าจอ = ต่อ WebSocket · ออกไปพื้นหลัง = ตัด (ข้อความตอนนั้นไปซิงก์ตอนกลับมา)
    LifecycleResumeEffect(Unit) {
        viewModel.realtime.start()
        onPauseOrDispose { viewModel.realtime.stop() }
    }
    val currentTab = Tab.entries.firstOrNull { it.route == backStack?.destination?.route }
    var lastTab by remember { mutableStateOf(Tab.Home) }
    if (currentTab != null) lastTab = currentTab
    else if (backStack?.destination?.route == "chat/{id}") lastTab = Tab.Chat // ห้องแชตนับเป็นแท็บแชต

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { tab ->
                    // หน้าลูก (รายละเอียด รายการโปรด ประกาศของฉัน ผู้ดูแล ...) นับเป็นแท็บที่เดินเข้ามา
                    val selected = tab == (currentTab ?: lastTab)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                // สลับแท็บแล้วจำสถานะของแต่ละแท็บไว้ (เลื่อนค้าง ฟอร์มที่พิมพ์)
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            BadgedBox(
                                badge = {
                                    val n = when (tab) { Tab.Notifications -> unread; Tab.Chat -> unreadChats; else -> 0 }
                                    if (n > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                                            Text(if (n > 99) "99+" else n.toString())
                                        }
                                    }
                                },
                            ) { Icon(tab.icon, contentDescription = null) }
                        },
                        label = { Text(tab.label) },
                        colors = if (tab == Tab.Create) {
                            // "ลงขาย" เน้นด้วยสีหลักเสมอ เหมือน mockup
                            NavigationBarItemDefaults.colors(
                                unselectedIconColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            NavigationBarItemDefaults.colors()
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Home.route,
            modifier = Modifier.padding(innerPadding),
            // เปลี่ยนแท็บ = เฟดสั้น ๆ · เข้าหน้าลูก = เลื่อนจากขวา (ย้อนกลับเลื่อนกลับ) — ให้รู้ว่าลึกลงไปหรือกลับขึ้นมา
            enterTransition = { if (isTabRoute(targetState.destination.route)) fadeIn(tween(150)) else slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220)) },
            exitTransition = { if (isTabRoute(targetState.destination.route)) fadeOut(tween(150)) else slideOutHorizontally(tween(220)) { -it / 6 } + fadeOut(tween(220)) },
            popEnterTransition = { if (isTabRoute(targetState.destination.route)) fadeIn(tween(150)) else slideInHorizontally(tween(220)) { -it / 6 } + fadeIn(tween(220)) },
            popExitTransition = { slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(220)) },
        ) {
            composable(Tab.Home.route) {
                HomeScreen(
                    onOpenListing = { id -> navController.navigate("listing/$id") },
                    onOpenFavorites = { navController.navigate("favorites") },
                )
            }
            composable("favorites") {
                FavoritesScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { id -> navController.navigate("listing/$id") },
                )
            }
            composable(Tab.Chat.route) { ChatListScreen(onOpen = { id -> navController.navigate("chat/$id") }) }
            composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ChatRoomScreen(
                    onBack = { navController.popBackStack() },
                    onOpenListing = { id -> navController.navigate("listing/$id") },
                    onOpenProfile = { id -> navController.navigate("seller/$id") },
                )
            }
            composable("seller/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                SellerProfileScreen(onBack = { navController.popBackStack() })
            }
            composable("admin/reports") {
                AdminReportsScreen(onBack = { navController.popBackStack() }, onOpen = { id -> navController.navigate("admin/reports/$id") })
            }
            composable("admin/reports/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                AdminReportDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenListing = { id -> navController.navigate("listing/$id") },
                )
            }
            composable("admin/banners") { AdminBannersScreen(onBack = { navController.popBackStack() }, onAdd = { navController.navigate("admin/banners/new") }) }
            composable("admin/banners/new") {
                AdminBannerFormScreen(
                    onBack = { navController.popBackStack() },
                    onAdded = { navController.navigate("admin/banners") { popUpTo("admin/banners") { inclusive = true } } },
                )
            }
            composable("privacy") { PrivacyScreen(onBack = { navController.popBackStack() }, onAccountDeleted = onSignOut) }
            composable("stats") { MyStatsScreen(onBack = { navController.popBackStack() }) }
            composable("admin/stats") { AdminStatsScreen(onBack = { navController.popBackStack() }) }
            composable("deals") {
                DealsScreen(onBack = { navController.popBackStack() }, onOpenRoom = { id -> navController.navigate("chat/$id") })
            }
            composable(Tab.Create.route) {
                ListingFormScreen(onResult = { navController.navigate("my-listings") })
            }
            composable(Tab.Notifications.route) { NotificationsScreen(onOpenRoute = { navController.navigate(it) }) }
            composable(Tab.Profile.route) {
                ProfileScreen(
                    me = me,
                    onMeChanged = onMeChanged,
                    onSignOut = onSignOut,
                    onOpenAdmin = { navController.navigate("admin/verifications") },
                    onOpenMyListings = { navController.navigate("my-listings") },
                    onOpenDeals = { navController.navigate("deals") },
                    onOpenAdminReports = { navController.navigate("admin/reports") },
                    onOpenStats = { navController.navigate("stats") },
                    onOpenAdminStats = { navController.navigate("admin/stats") },
                    onOpenBanners = { navController.navigate("admin/banners") },
                    onOpenPrivacy = { navController.navigate("privacy") },
                )
            }
            composable("my-listings") {
                MyListingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { id -> navController.navigate("listing/$id") },
                    onOpenDraft = { id -> navController.navigate("draft/$id") },
                )
            }
            composable("listing/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ListingDetailScreen(
                    isAdmin = me.isAdmin,
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate("listing/$id/edit") },
                    onOpenChat = { id -> navController.navigate("chat/$id") },
                    onOpenSeller = { id -> navController.navigate("seller/$id") },
                )
            }
            composable("listing/{id}/edit", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ListingFormScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { navController.popBackStack() },
                )
            }
            composable("draft/{draftId}", arguments = listOf(navArgument("draftId") { type = NavType.StringType })) {
                ListingFormScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { navController.popBackStack("my-listings", inclusive = false) },
                )
            }
            composable("admin/verifications") {
                AdminQueueScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { id -> navController.navigate("admin/verifications/$id") },
                )
            }
            composable(
                "admin/verifications/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                AdminReviewScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack("admin/verifications", inclusive = false) },
                )
            }
        }
    }
}
