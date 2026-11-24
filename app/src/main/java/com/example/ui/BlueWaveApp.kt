package com.example.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluetooth.BluetoothPermissionHelper
import com.example.data.local.ThemeMode
import com.example.ui.components.MeshPeersPanel
import com.example.ui.screens.MeshConsoleScreen
import com.example.ui.theme.MeshNetTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlueWaveApp(
    viewModel: BlueWaveViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPeersSheet by remember { mutableStateOf(false) }

    val systemDark = isSystemInDarkTheme()
    val isDark = when (uiState.settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    BackHandler(enabled = uiState.activeRoomId != "#mesh") {
        viewModel.selectRoom("#mesh")
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshMeshState()
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshMeshState()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshMeshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val launchPermissions = {
        permissionLauncher.launch(BluetoothPermissionHelper.requiredPermissions().toTypedArray())
    }

    val launchEnableBluetooth = {
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    val openAppSettings = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        context.startActivity(intent)
    }

    MeshNetTheme(darkTheme = isDark) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val showSidePeersPane = maxWidth >= 840.dp

            Row(modifier = Modifier.fillMaxSize()) {
                MeshConsoleScreen(
                    uiState = uiState,
                    snackbarHostState = snackbarHostState,
                    currentlyDark = isDark,
                    showPeersButton = !showSidePeersPane,
                    onToggleTheme = { viewModel.cycleThemeMode(isDark) },
                    onOpenPeersSheet = { showPeersSheet = true },
                    onSelectRoom = { viewModel.selectRoom(it) },
                    onJoinChannel = { viewModel.joinChannel(it) },
                    onUpdateNickname = { viewModel.updateNickname(it) },
                    onOpenDmWithPeer = { viewModel.openDmWithPeer(it) },
                    onSubmitInput = { viewModel.submitInput(it) },
                    onSendPing = { viewModel.sendPing() },
                    onClearRoom = { viewModel.clearCurrentRoom() },
                    onPanicWipe = { viewModel.panicWipe() },
                    onGrantPermissions = launchPermissions,
                    onOpenAppSettings = openAppSettings,
                    onEnableBluetooth = launchEnableBluetooth,
                    modifier = Modifier.weight(1f)
                )

                if (showSidePeersPane) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Vertical + WindowInsetsSides.End
                                )
                            )
                    ) {
                        MeshPeersPanel(
                            uiState = uiState,
                            onOpenDm = { viewModel.openDmWithPeer(it) },
                            onRefreshRadio = { viewModel.refreshMeshState() },
                            onToggleVibrateOnPing = { viewModel.setVibrateOnPing(it) },
                            onSelectThemeMode = { viewModel.setThemeMode(it) }
                        )
                    }
                }
            }

            if (showPeersSheet && !showSidePeersPane) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showPeersSheet = false },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    MeshPeersPanel(
                        uiState = uiState,
                        onOpenDm = { peerId ->
                            viewModel.openDmWithPeer(peerId)
                            showPeersSheet = false
                        },
                        onRefreshRadio = { viewModel.refreshMeshState() },
                        onToggleVibrateOnPing = { viewModel.setVibrateOnPing(it) },
                        onSelectThemeMode = { viewModel.setThemeMode(it) }
                    )
                }
            }
        }
    }
}
