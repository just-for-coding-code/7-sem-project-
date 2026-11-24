package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.bluetooth.RadioHardwareState
import com.example.data.local.MessageEntity
import com.example.ui.BlueWaveUiState
import com.example.ui.components.BluetoothDisabledCard
import com.example.ui.components.HardwareUnavailableCard
import com.example.ui.components.PermissionGateCard
import com.example.ui.theme.LocalTelemetryColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshConsoleScreen(
    uiState: BlueWaveUiState,
    snackbarHostState: SnackbarHostState,
    currentlyDark: Boolean,
    showPeersButton: Boolean,
    onToggleTheme: () -> Unit,
    onOpenPeersSheet: () -> Unit,
    onSelectRoom: (String) -> Unit,
    onJoinChannel: (String) -> Unit,
    onUpdateNickname: (String) -> Unit,
    onOpenDmWithPeer: (String) -> Unit,
    onSubmitInput: (String) -> Unit,
    onSendPing: () -> Unit,
    onClearRoom: () -> Unit,
    onPanicWipe: () -> Unit,
    onGrantPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var draft by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showNickDialog by remember { mutableStateOf(false) }
    var nickInput by remember { mutableStateOf(uiState.settings.nickname) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var joinInput by remember { mutableStateOf("") }
    var showPanicDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val telemetry = LocalTelemetryColors.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    val activeTab = uiState.roomTabs.firstOrNull { it.roomId == uiState.activeRoomId }
    val isDmRoom = uiState.activeRoomId.startsWith("dm:")

    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= total - 2
            }
        }
    }

    // Keep the newest messages visible on room change, incoming/outgoing messages, and when the soft keyboard opens.
    LaunchedEffect(uiState.activeRoomId) {
        if (uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.lastIndex)
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    LaunchedEffect(imeBottomPx) {
        if (imeBottomPx > 0 && uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.lastIndex)
        }
    }

    val sendDraft = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            onSubmitInput(text)
            draft = ""
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            nickInput = uiState.settings.nickname
                                            showNickDialog = true
                                        }
                                        .testTag("edit_nick_chip")
                                ) {
                                    Text(
                                        text = "@${uiState.settings.nickname}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            val meshReady = uiState.telemetry.hardwareState == RadioHardwareState.READY
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                uiState.telemetry.directNeighborCount > 0 -> telemetry.connected
                                                meshReady -> telemetry.listening
                                                else -> telemetry.warning
                                            }
                                        )
                                )
                                Text(
                                    text = when {
                                        uiState.telemetry.directNeighborCount > 0 ->
                                            "${uiState.livePeers.size} peers nearby · ${uiState.telemetry.directNeighborCount} direct"
                                        meshReady -> "Bluetooth mesh ready"
                                        uiState.telemetry.hardwareState == RadioHardwareState.DISABLED -> "Bluetooth is off"
                                        uiState.telemetry.hardwareState == RadioHardwareState.PERMISSIONS_REQUIRED -> "Bluetooth permission needed"
                                        else -> "Bluetooth unavailable"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onToggleTheme,
                            modifier = Modifier.testTag("toggle_theme_button")
                        ) {
                            Icon(
                                imageVector = if (currentlyDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = if (currentlyDark) "Switch to light mode" else "Switch to dark mode"
                            )
                        }
                        if (showPeersButton) {
                            IconButton(
                                onClick = onOpenPeersSheet,
                                modifier = Modifier.testTag("open_peers_sheet_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Group,
                                        contentDescription = stringResource(R.string.action_peers)
                                    )
                                    if (uiState.livePeers.isNotEmpty()) {
                                        Text(
                                            text = uiState.livePeers.size.toString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = telemetry.connected
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("more_options_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "More options"
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Change handle") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Person, contentDescription = null)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        nickInput = uiState.settings.nickname
                                        showNickDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Send mesh ping") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Bolt, contentDescription = null)
                                    },
                                    enabled = uiState.telemetry.hardwareState == RadioHardwareState.READY,
                                    onClick = {
                                        showOverflowMenu = false
                                        onSendPing()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear conversation") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onClearRoom()
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.action_panic_wipe),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteSweep,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showPanicDialog = true
                                    },
                                    modifier = Modifier.testTag("panic_wipe_button")
                                )
                            }
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Room / #channel / E2EE DM strip
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(items = uiState.roomTabs, key = { it.roomId }) { tab ->
                    val selected = tab.roomId == uiState.activeRoomId
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectRoom(tab.roomId) },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        leadingIcon = if (tab.isDm) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = "Encrypted DM",
                                    modifier = Modifier.size(14.dp),
                                    tint = telemetry.connected
                                )
                            }
                        } else {
                            null
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                if (tab.unreadCount > 0) {
                                    Badge {
                                        Text(tab.unreadCount.toString())
                                    }
                                }
                            }
                        },
                        modifier = Modifier.testTag("room_tab_${tab.roomId}")
                    )
                }

                item(key = "join_channel_chip") {
                    AssistChip(
                        onClick = {
                            joinInput = ""
                            showJoinDialog = true
                        },
                        shape = RoundedCornerShape(999.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("Join channel") },
                        modifier = Modifier.testTag("join_channel_button")
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Hardware / Permission gate if BLE cannot run yet
            when (uiState.telemetry.hardwareState) {
                RadioHardwareState.PERMISSIONS_REQUIRED -> {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        PermissionGateCard(
                            onGrantPermissions = onGrantPermissions,
                            onOpenAppSettings = onOpenAppSettings
                        )
                    }
                }
                RadioHardwareState.DISABLED -> {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        BluetoothDisabledCard(onEnableBluetooth = onEnableBluetooth)
                    }
                }
                RadioHardwareState.UNAVAILABLE -> {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        HardwareUnavailableCard()
                    }
                }
                RadioHardwareState.READY -> Unit
            }

            // Message feed area fills available space above composer (resizes cleanly above keyboard)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (uiState.messages.isEmpty()) {
                    EmptyMeshRoomView(
                        roomId = uiState.activeRoomId,
                        roomLabel = activeTab?.label ?: uiState.activeRoomId,
                        isDm = isDmRoom,
                        onEditNick = {
                            nickInput = uiState.settings.nickname
                            showNickDialog = true
                        },
                        onSendPing = onSendPing,
                        canPing = uiState.telemetry.hardwareState == RadioHardwareState.READY,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = uiState.messages,
                            key = { _, msg -> msg.id }
                        ) { index, msg ->
                            val prev = uiState.messages.getOrNull(index - 1)
                            val next = uiState.messages.getOrNull(index + 1)

                            val sameAsPrev = prev != null &&
                                !prev.isSystem &&
                                !msg.isSystem &&
                                prev.senderId == msg.senderId &&
                                prev.isOutgoing == msg.isOutgoing &&
                                (msg.timestamp - prev.timestamp) < 120_000L

                            val sameAsNext = next != null &&
                                !next.isSystem &&
                                !msg.isSystem &&
                                next.senderId == msg.senderId &&
                                next.isOutgoing == msg.isOutgoing &&
                                (next.timestamp - msg.timestamp) < 120_000L

                            if (index > 0 && !sameAsPrev) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            ChatMessageBubble(
                                message = msg,
                                showSenderHeader = !sameAsPrev,
                                showFooter = !sameAsNext,
                                onPeerHandleClick = { peerId ->
                                    if (peerId != uiState.settings.identity.peerId) {
                                        onOpenDmWithPeer(peerId)
                                    }
                                },
                                onCopyBody = { bodyText ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Message", bodyText))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Copied to clipboard")
                                    }
                                }
                            )
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isAtBottom,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    if (uiState.messages.isNotEmpty()) {
                                        listState.animateScrollToItem(uiState.messages.lastIndex)
                                    }
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Scroll to latest messages"
                            )
                        }
                    }
                }
            }

            // Native message composer pinned cleanly above IME via parent Scaffold safeDrawing padding
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    AnimatedVisibility(visible = draft.startsWith("/")) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "/nick " to "/nick",
                                "/join #" to "/join #",
                                "/msg @" to "/msg @",
                                "/who" to "/who",
                                "/ping" to "/ping",
                                "/clear" to "/clear",
                                "/help" to "/help"
                            ).forEach { (insert, label) ->
                                AssistChip(
                                    onClick = {
                                        if (insert.endsWith(" ") || insert.endsWith("#") || insert.endsWith("@")) {
                                            draft = insert
                                        } else {
                                            onSubmitInput(insert)
                                            draft = ""
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (draft.isEmpty()) {
                                    Text(
                                        text = if (isDmRoom) {
                                            stringResource(
                                                R.string.hint_dm_input,
                                                activeTab?.label?.removePrefix("@").orEmpty()
                                            )
                                        } else {
                                            stringResource(R.string.hint_mesh_input, uiState.activeRoomId)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 5,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        imeAction = ImeAction.Send
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSend = { sendDraft() }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("mesh_message_input")
                                )
                            }
                        }

                        IconButton(
                            onClick = sendDraft,
                            enabled = draft.isNotBlank(),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("mesh_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.action_send_message)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNickDialog) {
        AlertDialog(
            onDismissRequest = { showNickDialog = false },
            title = { Text("Display Handle") },
            text = {
                OutlinedTextField(
                    value = nickInput,
                    onValueChange = { nickInput = it },
                    singleLine = true,
                    prefix = { Text("@") },
                    label = { Text("Nickname") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nick_dialog_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateNickname(nickInput)
                        showNickDialog = false
                    },
                    modifier = Modifier.testTag("confirm_nick_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNickDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Join Channel") },
            text = {
                OutlinedTextField(
                    value = joinInput,
                    onValueChange = { joinInput = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Tag, contentDescription = null) },
                    label = { Text("Channel name (e.g. campus)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("join_channel_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (joinInput.isNotBlank()) {
                            onJoinChannel(joinInput)
                        }
                        showJoinDialog = false
                    },
                    modifier = Modifier.testTag("confirm_join_channel_button")
                ) {
                    Text("Join")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            title = { Text("Wipe Identity & History?") },
            text = {
                Text(
                    "Deletes all local messages, clears known peers, and generates a fresh encryption identity."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPanicWipe()
                        showPanicDialog = false
                    },
                    modifier = Modifier.testTag("confirm_panic_wipe_button")
                ) {
                    Text("Wipe Now", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageBubble(
    message: MessageEntity,
    showSenderHeader: Boolean,
    showFooter: Boolean,
    onPeerHandleClick: (String) -> Unit,
    onCopyBody: (String) -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeStr = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    if (message.isSystem) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Text(
                    text = "${message.body} · $timeStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        return
    }

    val isOutgoing = message.isOutgoing
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = 18.dp,
            bottomEnd = if (showFooter) 4.dp else 18.dp
        )
    } else {
        RoundedCornerShape(
            topStart = if (showSenderHeader) 4.dp else 18.dp,
            topEnd = 18.dp,
            bottomStart = 18.dp,
            bottomEnd = 18.dp
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        if (!isOutgoing && showSenderHeader) {
            Text(
                text = "@${message.senderNick}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 6.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onPeerHandleClick(message.senderId) }
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        Surface(
            shape = bubbleShape,
            color = if (isOutgoing) telemetry.outgoingBubble else telemetry.incomingBubble,
            border = if (isOutgoing) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onCopyBody(message.body) }
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isOutgoing) telemetry.onOutgoingBubble else telemetry.onIncomingBubble
                )

                if (showFooter || message.isEncrypted || (!isOutgoing && message.hopCount > 0)) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (!isOutgoing && message.hopCount > 0) {
                            Text(
                                text = "${message.hopCount} ${if (message.hopCount == 1) "hop" else "hops"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (message.isEncrypted) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = "End-to-end encrypted",
                                tint = if (isOutgoing) {
                                    telemetry.onOutgoingBubble.copy(alpha = 0.85f)
                                } else {
                                    telemetry.connected
                                },
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing) {
                                telemetry.onOutgoingBubble.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMeshRoomView(
    roomId: String,
    roomLabel: String,
    isDm: Boolean,
    onEditNick: () -> Unit,
    onSendPing: () -> Unit,
    canPing: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isDm -> Icons.Rounded.Lock
                        roomId == "#mesh" -> Icons.Rounded.Sensors
                        else -> Icons.Rounded.Tag
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }

            Text(
                text = if (isDm) "Encrypted chat with $roomLabel" else "Welcome to $roomId",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = if (isDm) {
                    "Direct messages are end-to-end encrypted and relayed across nearby Bluetooth devices."
                } else {
                    "Nearby phones running Mesh Net automatically discover each other and relay messages over Bluetooth without internet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onEditNick) {
                    Text("Set handle")
                }
                if (canPing && !isDm) {
                    OutlinedButton(onClick = onSendPing) {
                        Text("Ping nearby")
                    }
                }
            }
        }
    }
}
