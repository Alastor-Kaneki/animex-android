package com.alastor.orbitlauncher

import android.app.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.alastor.orbitlauncher.data.AppRepository
import com.alastor.orbitlauncher.model.LauncherApp
import com.alastor.orbitlauncher.ui.SphereNode
import com.alastor.orbitlauncher.ui.buildSphereNodes
import com.alastor.orbitlauncher.ui.theme.OrbitLauncherTheme
import com.alastor.orbitlauncher.ui.theme.OrbitPurple
import com.alastor.orbitlauncher.ui.theme.OrbitRed
import com.alastor.orbitlauncher.ui.theme.SoftWhite
import com.alastor.orbitlauncher.ui.theme.Void
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OrbitLauncherTheme {
                LauncherScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherScreen() {
    val context = LocalContext.current
    val repository = remember { AppRepository(context.applicationContext) }
    val preferences = remember {
        context.getSharedPreferences("orbit_launcher", Context.MODE_PRIVATE)
    }

    var refreshToken by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<LauncherApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by remember {
        mutableStateOf(preferences.getStringSet("favorites", emptySet()).orEmpty().toSet())
    }

    val defaultLauncherRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(refreshToken) {
        loading = true
        apps = withContext(Dispatchers.IO) { repository.loadApps() }
        loading = false
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshToken++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    BackHandler(enabled = query.isEmpty()) { /* A launcher stays on its home surface. */ }

    val filteredApps = remember(apps, query, favorites) {
        val normalized = query.trim()
        apps.filter {
            normalized.isEmpty() ||
                it.label.contains(normalized, ignoreCase = true) ||
                it.packageName.contains(normalized, ignoreCase = true)
        }.sortedWith(
            compareByDescending<LauncherApp> { it.id in favorites }
                .thenBy { it.label.lowercase() },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF211434), Void, Color.Black),
                    radius = 1400f,
                ),
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            OrbitPurple.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                    ),
                    radius = size.minDimension * 0.46f,
                    center = center,
                )
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LauncherHeader(
                query = query,
                onQueryChange = { query = it },
                onRefresh = { refreshToken++ },
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = OrbitPurple,
                    )
                    filteredApps.isEmpty() -> EmptyState(
                        modifier = Modifier.align(Alignment.Center),
                        query = query,
                    )
                    else -> AppSphere(
                        apps = filteredApps,
                        favorites = favorites,
                        onLaunch = { launchApp(context, it) },
                        onToggleFavorite = { app ->
                            favorites = if (app.id in favorites) {
                                favorites - app.id
                            } else {
                                favorites + app.id
                            }
                            preferences.edit().putStringSet("favorites", favorites).apply()
                        },
                    )
                }
            }

            BottomBar(
                appCount = filteredApps.size,
                favoriteCount = favorites.size,
                onChooseLauncher = {
                    requestHomeRole(context) { intent ->
                        defaultLauncherRequest.launch(intent)
                    }
                },
            )
        }
    }
}

@Composable
private fun LauncherHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Find an app") },
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRefresh) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh apps")
        }
    }
}

@Composable
private fun AppSphere(
    apps: List<LauncherApp>,
    favorites: Set<String>,
    onLaunch: (LauncherApp) -> Unit,
    onToggleFavorite: (LauncherApp) -> Unit,
) {
    var yaw by rememberSaveable { mutableFloatStateOf(0f) }
    var pitch by rememberSaveable { mutableFloatStateOf(0.08f) }
    val scope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val velocityTracker = remember { VelocityTracker() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(apps.size) {
                detectDragGestures(
                    onDragStart = {
                        flingJob?.cancel()
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity()
                        flingJob = scope.launch {
                            coroutineScope {
                                launch {
                                    var previous = 0f
                                    Animatable(0f).animateDecay(
                                        initialVelocity = velocity.x,
                                        animationSpec = exponentialDecay(frictionMultiplier = 2.15f),
                                    ) {
                                        val delta = value - previous
                                        previous = value
                                        yaw += delta * 0.0042f
                                    }
                                }
                                launch {
                                    var previous = 0f
                                    Animatable(0f).animateDecay(
                                        initialVelocity = velocity.y,
                                        animationSpec = exponentialDecay(frictionMultiplier = 2.6f),
                                    ) {
                                        val delta = value - previous
                                        previous = value
                                        pitch = (pitch + delta * 0.0032f).coerceIn(-1.22f, 1.22f)
                                    }
                                }
                            }
                        }
                    },
                    onDragCancel = { velocityTracker.resetTracking() },
                    onDrag = { change: PointerInputChange, dragAmount: Offset ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        yaw += dragAmount.x * 0.0042f
                        pitch = (pitch + dragAmount.y * 0.0032f).coerceIn(-1.22f, 1.22f)
                    },
                )
            },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val centerX = widthPx / 2f
        val centerY = heightPx / 2f
        val sphereRadius = minOf(widthPx * 0.40f, heightPx * 0.41f)

        val nodes = remember(apps, yaw, pitch, widthPx, heightPx) {
            buildSphereNodes(
                items = apps,
                yaw = yaw,
                pitch = pitch,
                centerX = centerX,
                centerY = centerY,
                radius = sphereRadius,
            )
        }

        nodes.sortedBy { it.depth }.forEach { node ->
            AppNode(
                node = node,
                isFavorite = node.item.id in favorites,
                onLaunch = onLaunch,
                onToggleFavorite = onToggleFavorite,
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .size(7.dp),
            shape = CircleShape,
            color = SoftWhite.copy(alpha = 0.35f),
        ) {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppNode(
    node: SphereNode<LauncherApp>,
    isFavorite: Boolean,
    onLaunch: (LauncherApp) -> Unit,
    onToggleFavorite: (LauncherApp) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val halfNodeWidthPx = with(density) { 47.dp.toPx() }
    val iconSize = (54f * node.scale).coerceIn(31f, 74f).dp
    val labelAlpha = ((node.depth + 0.25f) / 1.25f).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .offset {
                IntOffset(
                    (node.x - halfNodeWidthPx).roundToInt(),
                    (node.y - with(density) { iconSize.toPx() } / 2f).roundToInt(),
                )
            }
            .width(94.dp)
            .graphicsLayer {
                alpha = node.alpha
                shadowElevation = if (node.depth > 0.35f) 12f else 0f
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .then(
                    if (isFavorite) {
                        Modifier.border(2.dp, OrbitPurple, CircleShape).padding(3.dp)
                    } else {
                        Modifier
                    },
                )
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { onLaunch(node.item) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleFavorite(node.item)
                    },
                ),
        ) {
            Image(
                bitmap = node.item.icon.asImageBitmap(),
                contentDescription = node.item.label,
                modifier = Modifier.fillMaxSize(),
            )
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = "Favorite",
                    tint = OrbitPurple,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size((15f * node.scale.coerceAtLeast(0.8f)).dp)
                        .background(Void.copy(alpha = 0.72f), CircleShape)
                        .padding(2.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        BasicText(
            text = node.item.label,
            modifier = Modifier.alpha(labelAlpha),
            style = TextStyle(
                color = SoftWhite,
                fontSize = (10.5f * node.scale.coerceIn(0.85f, 1.15f)).sp,
                fontWeight = if (node.depth > 0.45f) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BottomBar(
    appCount: Int,
    favoriteCount: Int,
    onChooseLauncher: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "$appCount apps",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "$favoriteCount favorites · hold an icon to star",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
        }
        FilledTonalIconButton(onClick = onChooseLauncher) {
            Icon(Icons.Rounded.Home, contentDescription = "Set as default launcher")
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, query: String) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = OrbitRed,
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (query.isBlank()) "No launchable apps found" else "No apps match “$query”",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

private fun launchApp(context: Context, app: LauncherApp) {
    val intent = Intent.makeMainActivity(app.componentName).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
    runCatching { context.startActivity(intent) }
}

private fun requestHomeRole(context: Context, launch: (Intent) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true &&
            !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        ) {
            launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
            return
        }
    }

    val settingsIntent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(settingsIntent) }
}

