package com.notrace.messenger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notrace.messenger.NoTraceApplication
import com.notrace.messenger.network.webrtc.CallState
import com.notrace.messenger.ui.screens.CallScreen
import com.notrace.messenger.ui.screens.ChatListScreen
import com.notrace.messenger.ui.screens.ChatThreadScreen
import com.notrace.messenger.ui.screens.ContactAddScreen
import com.notrace.messenger.ui.screens.GroupCreateScreen
import com.notrace.messenger.ui.screens.GroupInfoScreen
import com.notrace.messenger.ui.screens.SettingsScreen
import com.notrace.messenger.ui.screens.SplashScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object Routes {
    const val SPLASH = "splash"
    const val CHAT_LIST = "chat_list"
    const val CHAT_THREAD = "chat_thread/{numericId}"
    const val SETTINGS = "settings"
    const val CALL = "call"
    const val CONTACT_ADD = "contact_add" // Phase 5
    const val GROUP_CREATE = "group_create" // Phase 5
    const val GROUP_INFO = "group_info/{groupId}" // Phase 5
    fun chatThread(numericId: String) = "chat_thread/$numericId"
    fun groupInfo(groupId: String) = "group_info/$groupId"
}

@Composable
fun NoTraceNavGraph(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val callManager = (context.applicationContext as NoTraceApplication).container.callManager

    // Covers BOTH directions: a user-initiated call from ChatThreadScreen's
    // call button (via CallManager.startCall) navigates here too, but an
    // INCOMING call has no button press to hook — this observer is what
    // actually shows the ringing UI when a peer calls us from anywhere else
    // in the app.
    LaunchedEffect(Unit) {
        callManager.callState
            .map { it != CallState.Idle }
            .distinctUntilChanged()
            .collect { inCall -> if (inCall) navController.navigate(Routes.CALL) { launchSingleTop = true } }
    }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onIdentityReady = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CHAT_LIST) {
            ChatListScreen(
                onOpenChat = { numericId -> navController.navigate(Routes.chatThread(numericId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onAddContact = { navController.navigate(Routes.CONTACT_ADD) },
                onNewGroup = { navController.navigate(Routes.GROUP_CREATE) },
            )
        }
        composable(
            Routes.CHAT_THREAD,
            arguments = listOf(navArgument("numericId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val numericId = backStackEntry.arguments?.getString("numericId") ?: return@composable
            ChatThreadScreen(
                conversationId = numericId,
                onBack = { navController.popBackStack() },
                onOpenGroupInfo = { groupId -> navController.navigate(Routes.groupInfo(groupId)) },
            )
        }
        composable(Routes.CONTACT_ADD) {
            ContactAddScreen(
                onContactFound = { numericId ->
                    navController.navigate(Routes.chatThread(numericId)) {
                        popUpTo(Routes.CONTACT_ADD) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.GROUP_CREATE) {
            GroupCreateScreen(
                onGroupCreated = { groupId ->
                    navController.navigate(Routes.chatThread(groupId)) {
                        popUpTo(Routes.GROUP_CREATE) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.GROUP_INFO,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            GroupInfoScreen(
                groupId = groupId,
                onBack = { navController.popBackStack() },
                onLeft = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.CHAT_LIST) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onWipedAndRestart = {
                    navController.navigate(Routes.SPLASH) {
                        popUpTo(0) // clear the whole back stack — everything it referenced is now gone
                    }
                },
            )
        }
        composable(Routes.CALL) {
            CallScreen(onCallEnded = { navController.popBackStack() })
        }
    }
}
