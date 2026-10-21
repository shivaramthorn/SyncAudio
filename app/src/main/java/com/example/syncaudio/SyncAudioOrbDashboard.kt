package com.example.syncaudio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AudioRouteOption(
    val id: Int,
    val type: String,
    val name: String
)

private val SyncBg = Color(0xFF050914)
private val SyncPanel = Color(0xFF0A1428)
private val SyncBlue = Color(0xFF16C8FF)
private val SyncPurple = Color(0xFFB34DFF)
private val SyncTeal = Color(0xFF28E3D1)
private val SyncWhite = Color(0xFFF5F7FF)
private val SyncMuted = Color(0xFF9DAAC7)

@Composable
fun SyncAudioOrbDashboard(
    senderStatus: String,
    receiverStatus: String,
    intercomStatus: String,
    roomName: String,
    devices: List<IntercomDevice>,
    connectedDevices: List<String>,
    onSenderClick: () -> Unit,
    onReceiverClick: () -> Unit,
    onHostClick: () -> Unit,
    onDiscoverClick: () -> Unit,
    onConnectDevice: (IntercomDevice) -> Unit,
    audioStarted: Boolean,
    onStartAudio: () -> Unit,
    onStopAudio: () -> Unit,
    onStopIntercom: () -> Unit,
    audioRoutes: List<AudioRouteOption> = emptyList(),
    selectedAudioRoute: String = "speaker",
    onSelectAudioRoute: (AudioRouteOption) -> Unit = {}
) {
    var selectedAction by remember { mutableStateOf("Ready") }

    val actionScale by animateFloatAsState(
        targetValue = if (selectedAction == "Ready") 1f else 1.06f,
        label = "selected action scale"
    )

    // Continuous, gentle pulse for the living orb.
    val orbTransition = rememberInfiniteTransition(label = "living orb")
    val orbPulse by orbTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "living orb pulse"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF030711), SyncBg, Color(0xFF080D20))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        val compact = maxHeight < 700.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "Sync",
                    color = SyncWhite,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Audio",
                    color = SyncBlue,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                "S O U N D   T O G E T H E R",
                color = SyncMuted,
                fontSize = 9.sp,
                letterSpacing = 1.4.sp
            )

            Spacer(Modifier.height(if (compact) 12.dp else 22.dp))

            // Circular controls orbit around the main status orb.
            // A regular Box is sufficient because this scope does not
            // use the inner BoxWithConstraints dimensions.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 350.dp else 380.dp),
                contentAlignment = Alignment.Center
            ) {
                val orbSize = if (compact) 116.dp else 132.dp
                val sideSize = if (compact) 80.dp else 88.dp
                val bottomSize = if (compact) 88.dp else 96.dp

                // Decorative concentric rings.
                Box(
                    Modifier
                        .size(if (compact) 294.dp else 324.dp)
                        .border(
                            1.dp,
                            SyncBlue.copy(alpha = 0.28f),
                            CircleShape
                        )
                )

                Box(
                    Modifier
                        .size(if (compact) 246.dp else 270.dp)
                        .border(
                            1.dp,
                            SyncPurple.copy(alpha = 0.24f),
                            CircleShape
                        )
                )

                Box(
                    Modifier
                        .size(if (compact) 186.dp else 214.dp)
                        .border(
                            1.dp,
                            SyncBlue.copy(alpha = 0.16f),
                            CircleShape
                        )
                )

                // Before a feature is selected, show the idle Sync Orb.
                // After selection, that feature's button animates into the center.
                if (selectedAction == "Ready") {
                    Surface(
                        modifier = Modifier
                            .size(orbSize)
                            .scale(orbPulse)
                            .border(1.5.dp, SyncBlue.copy(alpha = 0.8f), CircleShape),
                        shape = CircleShape,
                        color = Color.Transparent,
                        shadowElevation = 18.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color(0xFF164B83),
                                            Color(0xFF102344),
                                            Color(0xFF050914)
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "◉",
                                    color = SyncBlue,
                                    fontSize = 38.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "SYNC ORB",
                                    color = SyncWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    "Ready",
                                    color = SyncMuted,
                                    fontSize = 9.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                val senderX by animateDpAsState(
                    targetValue = 0.dp,
                    animationSpec = tween(420),
                    label = "Sender horizontal position"
                )
                val senderY by animateDpAsState(
                    targetValue = if (selectedAction == "Sender") 0.dp else (-135).dp,
                    animationSpec = tween(420),
                    label = "Sender vertical position"
                )
                val senderSize by animateDpAsState(
                    targetValue = if (selectedAction == "Sender") 142.dp else sideSize,
                    animationSpec = tween(420),
                    label = "Sender size"
                )

                val receiverX by animateDpAsState(
                    targetValue = if (selectedAction == "Receiver") 0.dp else (-135).dp,
                    animationSpec = tween(420),
                    label = "Receiver horizontal position"
                )
                val receiverY by animateDpAsState(
                    targetValue = if (selectedAction == "Receiver") 0.dp else 18.dp,
                    animationSpec = tween(420),
                    label = "Receiver vertical position"
                )
                val receiverSize by animateDpAsState(
                    targetValue = if (selectedAction == "Receiver") 142.dp else sideSize,
                    animationSpec = tween(420),
                    label = "Receiver size"
                )

                val hostX by animateDpAsState(
                    targetValue = if (selectedAction == "Host Room") 0.dp else 135.dp,
                    animationSpec = tween(420),
                    label = "Host Room horizontal position"
                )
                val hostY by animateDpAsState(
                    targetValue = if (selectedAction == "Host Room") 0.dp else 18.dp,
                    animationSpec = tween(420),
                    label = "Host Room vertical position"
                )
                val hostSize by animateDpAsState(
                    targetValue = if (selectedAction == "Host Room") 142.dp else sideSize,
                    animationSpec = tween(420),
                    label = "Host Room size"
                )

                val connectX by animateDpAsState(
                    targetValue = 0.dp,
                    animationSpec = tween(420),
                    label = "Connect Host horizontal position"
                )
                val connectY by animateDpAsState(
                    targetValue = if (selectedAction == "Connect Host") 0.dp else 135.dp,
                    animationSpec = tween(420),
                    label = "Connect Host vertical position"
                )
                val connectSize by animateDpAsState(
                    targetValue = if (selectedAction == "Connect Host") 142.dp else bottomSize,
                    animationSpec = tween(420),
                    label = "Connect Host size"
                )

                OrbButton(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = senderX, y = senderY),
                    size = senderSize,
                    title = "Sender",
                    symbol = "♫",
                    tint = SyncBlue,
                    selected = selectedAction == "Sender",
                    subtitle = if (selectedAction == "Sender") "Sending music" else null,
                    onClick = {
                        selectedAction = "Sender"
                        onSenderClick()
                    }
                )

                OrbButton(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = receiverX, y = receiverY),
                    size = receiverSize,
                    title = "Receiver",
                    symbol = "⌁",
                    tint = SyncPurple,
                    selected = selectedAction == "Receiver",
                    subtitle = if (selectedAction == "Receiver") "Receiving music" else null,
                    onClick = {
                        selectedAction = "Receiver"
                        onReceiverClick()
                    }
                )

                OrbButton(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = hostX, y = hostY),
                    size = hostSize,
                    title = "Host Room",
                    symbol = "●●",
                    tint = SyncPurple,
                    selected = selectedAction == "Host Room",
                    subtitle = if (selectedAction == "Host Room") "Broadcast & invite" else null,
                    onClick = {
                        selectedAction = "Host Room"
                        onHostClick()
                    }
                )

                OrbButton(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = connectX, y = connectY),
                    size = connectSize,
                    title = "Connect Host",
                    symbol = "↔",
                    tint = SyncTeal,
                    selected = selectedAction == "Connect Host",
                    subtitle = if (selectedAction == "Connect Host") "Searching nearby" else null,
                    onClick = {
                        selectedAction = "Connect Host"
                        onDiscoverClick()
                    }
                )
            }

            Spacer(Modifier.height(4.dp))

            // Shared mascot area. Keep artwork inside a compact reserved row
            // so it never overlaps the orb controls or the status card.
            val isIntercomMode = selectedAction == "Host Room" ||
                    selectedAction == "Connect Host"
            val mascotScale by animateFloatAsState(
                targetValue = if (selectedAction == "Ready") 1f else 1.04f,
                label = "mascot reaction"
            )
            val mascotTransition = rememberInfiniteTransition(label = "mascot idle")
            val mascotBob by mascotTransition.animateFloat(
                initialValue = 0f,
                targetValue = -5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "mascot bob"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 86.dp else 104.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val mascotResource = when {
                        // Intercom has priority when communication is active
                        isIntercomMode && audioStarted ->
                            R.drawable.panda_intercom_active

                        // Show the linked-device mascot
                        isIntercomMode && connectedDevices.isNotEmpty() ->
                            R.drawable.panda_connected

                        // Communication modes
                        selectedAction == "Host Room" ->
                            R.drawable.cat_host_room

                        selectedAction == "Connect Host" ->
                            R.drawable.panda_connect_host

                        // Music modes
                        selectedAction == "Receiver" ->
                            R.drawable.panda_music_receiver

                        selectedAction == "Sender" ->
                            R.drawable.panda_idle

                        // Default
                        else ->
                            R.drawable.panda_idle
                    }

                    val mascotDescription = when {
                        isIntercomMode && audioStarted -> "Panda communicating through active intercom"
                        isIntercomMode && connectedDevices.isNotEmpty() -> "Panda showing connected devices"
                        selectedAction == "Host Room" -> "Panda broadcasting with a megaphone"
                        selectedAction == "Connect Host" -> "Panda searching with binoculars"
                        else -> "Panda DJ"
                    }

                    Image(
                        painter = painterResource(id = mascotResource),
                        contentDescription = mascotDescription,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .size(if (compact) 76.dp else 92.dp)
                            .scale(mascotScale)
                            .offset(y = mascotBob.dp)
                    )

                    Text(
                        text = when {
                            isIntercomMode && audioStarted -> "Panda • Intercom Active"
                            isIntercomMode && connectedDevices.isNotEmpty() -> "Panda • Connected"
                            selectedAction == "Sender" -> "Panda DJ • Music Sender"
                            selectedAction == "Receiver" -> "Panda DJ • Music Receiver"
                            selectedAction == "Host Room" -> "Panda • Host Room"
                            selectedAction == "Connect Host" -> "Panda • Connect Host"
                            else -> "Panda DJ • Ready"
                        },
                        color = SyncMuted,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SyncPanel.copy(alpha = 0.94f),
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 2.dp
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        when (selectedAction) {
                            "Sender" -> "SENDER MODE"
                            "Receiver" -> "RECEIVER MODE"
                            "Host Room" -> "HOST ROOM"
                            "Connect Host" -> "CONNECT TO A DEVICE"
                            else -> "READY"
                        },
                        color = SyncBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )

                    Spacer(Modifier.height(5.dp))

                    Text(
                        when (selectedAction) {
                            "Sender" -> senderStatus
                            "Receiver" -> receiverStatus
                            "Host Room" -> "$roomName\n$intercomStatus"
                            "Connect Host" -> intercomStatus
                            else -> "Choose an orb to get started"
                        },
                        color = SyncWhite,
                        fontSize = 14.sp
                    )

                    if (selectedAction == "Connect Host") {
                        Spacer(Modifier.height(8.dp))

                        if (devices.isEmpty()) {
                            Text(
                                "No devices found yet. Tap Connect Host again to search.",
                                color = SyncMuted,
                                fontSize = 12.sp
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 150.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    devices,
                                    key = { it.endpointId }
                                ) { device ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onConnectDevice(device)
                                            },
                                        color = Color(0xFF10223D),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            SyncBlue.copy(alpha = 0.55f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "●",
                                                color = SyncTeal,
                                                fontSize = 14.sp
                                            )

                                            Spacer(Modifier.width(10.dp))

                                            Text(
                                                device.name,
                                                color = SyncWhite,
                                                modifier = Modifier.weight(1f),
                                                fontSize = 13.sp
                                            )

                                            Text(
                                                "Connect  ›",
                                                color = SyncBlue,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (connectedDevices.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))

                        Text(
                            "Connected: ${connectedDevices.size}",
                            color = SyncTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (
                        selectedAction == "Host Room" ||
                        selectedAction == "Connect Host"
                    ) {
                        Spacer(Modifier.height(10.dp))

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (audioStarted) {
                                        onStopAudio()
                                    } else {
                                        onStartAudio()
                                    }
                                },
                            shape = RoundedCornerShape(14.dp),
                            color = if (audioStarted) {
                                Color(0xFF421C32)
                            } else {
                                Color(0xFF0A3041)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (audioStarted) SyncPurple else SyncTeal
                            )
                        ) {
                            Text(
                                if (audioStarted) {
                                    "■  Stop Hands-Free Audio"
                                } else {
                                    "▶  Start Hands-Free Audio"
                                },
                                modifier = Modifier.padding(13.dp),
                                color = SyncWhite,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Text(
                            if (audioStarted) {
                                "Intercom audio is marked active"
                            } else {
                                "Connect to a device before starting audio"
                            },
                            color = SyncMuted,
                            fontSize = 11.sp
                        )

                        if (audioStarted) {
                            Spacer(Modifier.height(10.dp))
                            AudioOutputSelector(
                                routes = audioRoutes,
                                selectedRoute = selectedAudioRoute,
                                onSelect = onSelectAudioRoute
                            )
                        }

                        Text(
                            "Stop Intercom",
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .clickable { onStopIntercom() }
                                .padding(8.dp),
                            color = SyncMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "MUSIC STREAMING  •  WIRELESS INTERCOM",
                color = SyncMuted,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AudioOutputSelector(
    routes: List<AudioRouteOption>,
    selectedRoute: String,
    onSelect: (AudioRouteOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = routes.firstOrNull { it.type == selectedRoute }?.name
        ?: when (selectedRoute) {
            IntercomAudioService.ROUTE_EARPIECE -> "Phone earpiece"
            IntercomAudioService.ROUTE_BLUETOOTH -> "Bluetooth audio"
            else -> "Phone loudspeaker"
        }

    Column {
        Text(
            "AUDIO OUTPUT",
            color = SyncBlue,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(5.dp))
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                color = Color(0xFF10223D),
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, SyncBlue.copy(alpha = 0.65f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("♫", color = SyncTeal, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(currentName, color = SyncWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("⌄", color = SyncBlue, fontSize = 18.sp)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                routes.forEach { route ->
                    DropdownMenuItem(
                        text = { Text(route.name) },
                        onClick = {
                            expanded = false
                            onSelect(route)
                        }
                    )
                }
                if (routes.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No audio routes reported yet") },
                        onClick = { expanded = false },
                        enabled = false
                    )
                }
            }
        }
        Text(
            "Bluetooth appears when Android exposes it for communication audio.",
            color = SyncMuted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun OrbButton(
    modifier: Modifier,
    size: Dp,
    title: String,
    symbol: String,
    tint: Color,
    selected: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.10f else 1f,
        label = "$title scale"
    )

    Surface(
        modifier = modifier
            .size(size)
            .scale(scale)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = tint.copy(alpha = if (selected) 1f else 0.7f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
        shadowElevation = if (selected) 20.dp else 8.dp
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.radialGradient(
                        listOf(
                            tint.copy(alpha = 0.32f),
                            Color(0xFF0A1227),
                            Color(0xFF050914)
                        )
                    )
                )
                .padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    symbol,
                    color = tint,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    title,
                    color = SyncWhite,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = if (selected) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Medium
                    }
                )

                if (selected && subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = SyncMuted,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

