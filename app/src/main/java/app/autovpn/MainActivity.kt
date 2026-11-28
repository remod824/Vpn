package app.autovpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF050506)
private val Surface = Color(0xFF101012)
private val Mint = Color(0xFFB9363C)
private val HawkRed = Color(0xFFFF3B30)
private val Muted = Color(0xFF85858C)

class MainActivity : ComponentActivity() {
    private lateinit var connectivity: ConnectivityManager
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private var pendingConfig: String? = null
    private var vpnStatus by mutableStateOf("Готов к подключению")
    private var networkLabel by mutableStateOf("Определяем сеть…")
    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            vpnStatus = intent.getStringExtra(AutoVpnVpnService.EXTRA_STATUS) ?: vpnStatus
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread { networkLabel = networkName(caps) }
        }

        override fun onLost(network: Network) {
            runOnUiThread { networkLabel = "Нет сети" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingConfig?.let { AutoVpnVpnService.start(this, it) }
            } else {
                vpnStatus = "Разрешение VPN не выдано"
            }
            pendingConfig = null
        }
        ContextCompat.registerReceiver(
            this,
            vpnStatusReceiver,
            IntentFilter(AutoVpnVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        networkLabel = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let(::networkName) ?: "Нет сети"
        connectivity.registerDefaultNetworkCallback(networkCallback)
        RefreshServersWorker.schedule(this)
        setContent { AutoVpnApp(networkLabel, vpnStatus, ::toggleVpn) }
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(vpnStatusReceiver) }
        super.onDestroy()
    }

    private fun toggleVpn() {
        if (vpnStatus == "VPN подключён" || vpnStatus.startsWith("Запуск VPN")) {
            AutoVpnVpnService.stop(this)
            return
        }
        val nodes = ServerCatalog.load(this)
        val node = ServerCatalog.selected(this) ?: nodes
            .filter { it.isWorking }
            .minByOrNull { it.pingMs }
        if (node == null) {
            vpnStatus = "Нет доступных серверов. Обнови список и попробуй снова."
            return
        }
        val config = runCatching { SingBoxConfigBuilder.build(node) }.getOrElse {
            vpnStatus = "Не удалось собрать конфигурацию: ${it.message}"
            return
        }
        val permission = VpnService.prepare(this)
        if (permission != null) {
            pendingConfig = config
            vpnPermissionLauncher.launch(permission)
        } else {
            AutoVpnVpnService.start(this, config)
        }
        vpnStatus = "Подключение к ${node.name}…"
    }

    private fun networkName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
        else -> "Другая сеть"
    }
}

@Composable
private fun AutoVpnApp(networkLabel: String, vpnStatus: String, onToggleVpn: () -> Unit) {
    val context = LocalContext.current
    var showServers by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf(ServerCatalog.selected(context)) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Surface,
            primary = Mint,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        if (showServers) {
            ServerPickerScreen(
                onBack = { showServers = false },
                onSelect = { node ->
                    ServerCatalog.select(context, node)
                    selectedNode = node
                    showServers = false
                }
            )
        } else {
            AutoVpnHome(
                networkLabel = networkLabel,
                selectedNode = selectedNode,
                vpnStatus = vpnStatus,
                onToggleVpn = onToggleVpn,
                onOpenServers = { showServers = true }
            )
        }
    }
}

@Composable
private fun AutoVpnHome(
    networkLabel: String,
    selectedNode: ServerNode?,
    vpnStatus: String,
    onToggleVpn: () -> Unit,
    onOpenServers: () -> Unit
) {
    val context = LocalContext.current
    val serverCount = remember { mutableIntStateOf(ServerCatalog.load(context).size) }
    val connected = vpnStatus == "VPN подключён"
    val busy = vpnStatus.startsWith("Подключение") || vpnStatus.startsWith("Запуск VPN")
    val hawkActive = connected || busy
    val hawkTint by androidx.compose.animation.animateColorAsState(
        targetValue = if (hawkActive) HawkRed else Color(0xFF77777D),
        animationSpec = tween(if (hawkActive) 950 else 450),
        label = "hawk-color"
    )
    val hawkTransition = rememberInfiniteTransition(label = "hawk-red-glow")
    val hawkPulse by hawkTransition.animateFloat(
        initialValue = .88f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "hawk-light"
    )
    val hawkAlpha by animateFloatAsState(
        targetValue = if (hawkActive) 1f else .10f,
        animationSpec = tween(if (hawkActive) 900 else 450),
        label = "hawk-visibility"
    )
    val infinite = rememberInfiniteTransition(label = "connect-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (connected || busy) 1.018f else 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (busy) 650 else 1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connect-scale"
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (connected) 1.015f else 1f,
        animationSpec = tween(260),
        label = "button-scale"
    )

    LaunchedEffect(Unit) {
        if (serverCount.intValue == 0) {
            runCatching { ServerCatalog.refresh(context) }
            serverCount.intValue = ServerCatalog.load(context).size
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFF050506), Color(0xFF0D0D10), Color(0xFF050506))
                )
            )
    ) {
        val buttonSize = minOf(maxWidth * .68f, maxHeight * .34f).coerceIn(154.dp, 248.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(0xFF9E2028).copy(alpha = .28f), Color(0xFF4A1118).copy(alpha = .18f))
                            ),
                            RoundedCornerShape(14.dp)
                        )
                        .border(1.dp, Mint.copy(alpha = .20f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(painter = painterResource(R.drawable.hawk_logo), contentDescription = "Hawk", modifier = Modifier.size(30.dp), contentScale = ContentScale.Fit)
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("HAWK VPN", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.4).sp)
                    Text("PRIVATE CONNECTION", color = Muted, fontSize = 8.sp, letterSpacing = 1.8.sp)
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    color = Color(0xFF172333),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3B4D))
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(6.dp).background(if (connected) Mint else Muted, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(if (connected) "ЗАЩИЩЕНО" else "НЕ ПОДКЛЮЧЕНО", color = if (connected) Mint else Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Text("ВАШЕ СОЕДИНЕНИЕ", color = Muted, fontSize = 10.sp, letterSpacing = 2.1.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = vpnStatus,
                label = "vpn-status"
            ) { status ->
                Text(
                    status,
                    color = when {
                        status == "VPN подключён" -> Mint
                        status.startsWith("Ошибка") -> Color(0xFFFF7C86)
                        status.startsWith("Подключение") || status.startsWith("Запуск") -> Color(0xFFFFC56D)
                        else -> Color.White
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (selectedNode == null) "Подберём доступный сервер автоматически" else "Выбран сервер: ${selectedNode.name}",
                color = Muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .graphicsLayer {
                        scaleX = pulse * buttonScale
                        scaleY = pulse * buttonScale
                    }
                    .shadow(28.dp, CircleShape, ambientColor = HawkRed.copy(alpha = if (hawkActive) .52f else .06f), spotColor = HawkRed.copy(alpha = if (hawkActive) .58f else .08f))
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            if (hawkActive) listOf(Color(0xFF57171C), Color(0xFF241014), Color(0xFF09090B)) else listOf(Color(0xFF171719), Color(0xFF101012), Color(0xFF080809))
                        ),
                        CircleShape
                    )
                    .border(1.5.dp, HawkRed.copy(alpha = if (hawkActive) .95f else .24f), CircleShape)
                    .padding(10.dp)
                    .border(1.dp, Color.White.copy(alpha = .10f), CircleShape)
                    .padding(8.dp)
                    .background(Color(0xFF0E1A28).copy(alpha = .70f), CircleShape)
                    .clickable(enabled = !busy) { onToggleVpn() },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Keep the hawk in the upper half so it never collides with the controls.
                    Image(
                        painter = painterResource(R.drawable.hawk_logo),
                        contentDescription = "Ястреб",
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(hawkTint),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 18.dp)
                            .fillMaxSize(.50f)
                            .graphicsLayer {
                                alpha = hawkAlpha
                                scaleX = if (hawkActive) hawkPulse else 1f
                                scaleY = if (hawkActive) hawkPulse else 1f
                            }
                    )
                    Column(
                        modifier = Modifier.align(Alignment.Center).offset(y = 42.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    AnimatedContent(
                        targetState = connected,
                        label = "connect-icon"
                    ) { isConnected ->
                        Icon(
                            if (isConnected) Icons.Default.CheckCircle else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(buttonSize * .19f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AnimatedContent(
                        targetState = when {
                            busy -> "ПОДКЛЮЧАЕМ"
                            connected -> "ОТКЛЮЧИТЬ"
                            else -> "ВКЛЮЧИТЬ"
                        },
                        label = "connect-label"
                    ) { label ->
                        Text(label, color = Color.White, fontSize = if (buttonSize < 180.dp) 15.sp else 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .8.sp, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (connected) "HAWK PROTECTED" else "ONE TAP TO CONNECT", color = if (hawkActive) HawkRed else Mint, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoTile(
                    modifier = Modifier.weight(1f),
                    label = "СЕТЬ",
                    value = networkLabel,
                    icon = Icons.Default.Wifi,
                    accent = Color(0xFF75BDF4)
                )
                InfoTile(
                    modifier = Modifier.weight(1f),
                    label = "СЕРВЕРОВ",
                    value = serverCount.intValue.toString(),
                    icon = Icons.Default.Public,
                    accent = Mint
                )
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenServers),
                color = Color(0xFF121D2C),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26384A))
            ) {
                Row(
                    Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).background(Mint.copy(alpha = .12f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Public, null, tint = Mint, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Серверы", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            if (selectedNode == null) "${serverCount.intValue} servers • auto select" else "${ServerCatalog.countryFlag(selectedNode.country)} ${selectedNode.country} • ${selectedNode.protocol}",
                            color = Muted,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                    Text("ОТКРЫТЬ", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .7.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = Mint, modifier = Modifier.size(17.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = Mint.copy(alpha = .7f), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text("HAWK CORE • SMART CONNECTION", color = Muted, fontSize = 8.sp, letterSpacing = .8.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun InfoTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF121D2C),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26384A))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = Muted, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun ServerPickerScreen(
    onBack: () -> Unit,
    onSelect: (ServerNode) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(ServerCatalog.load(context)) }
    var filter by remember { mutableStateOf("Все серверы") }
    var search by remember { mutableStateOf("") }
    var onlyWorking by remember { mutableStateOf(false) }
    var sortByPing by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val refresh: () -> Unit = {
        if (!refreshing) {
            scope.launch {
                refreshing = true
                status = "Загружаю подписки и проверяю серверы…"
                try {
                    val result = ServerCatalog.refresh(context)
                    servers = ServerCatalog.load(context)
                    status = "Получено: ${result.total} • Проверено: ${result.checked} • Доступно по TCP: ${result.reachable}"
                } catch (error: Exception) {
                    status = "Не удалось обновить: ${error.message ?: "ошибка сети"}"
                } finally {
                    refreshing = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (servers.isEmpty()) refresh()
    }

    val visibleServers = remember(servers, filter, search, onlyWorking, sortByPing) {
        val filtered = servers.filter { node ->
            val matchesTab = when (filter) {
                "Избранные" -> node.isFavorite
                else -> true
            }
            val needle = search.trim().lowercase(Locale.ROOT)
            val matchesSearch = needle.isBlank() ||
                node.name.lowercase(Locale.ROOT).contains(needle) ||
                node.host.lowercase(Locale.ROOT).contains(needle) ||
                node.country.lowercase(Locale.ROOT).contains(needle) ||
                node.protocol.lowercase(Locale.ROOT).contains(needle)
            matchesTab && matchesSearch && (!onlyWorking || node.isWorking)
        }
        if (sortByPing) filtered.sortedWith(
            compareBy<ServerNode> { if (it.pingMs >= 0L) 0 else 1 }
                .thenBy { if (it.pingMs >= 0L) it.pingMs else Long.MAX_VALUE }
        ) else filtered
    }

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("Серверы", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${servers.size} в каталоге", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = refresh, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Mint, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, "Обновить", tint = Mint)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Поиск страны, сервера или протокола", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Mint,
                unfocusedBorderColor = Color(0xFF293347),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Mint
            )
        )

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf("Все серверы", "Избранные").forEach { tab ->
                FilterChip(
                    selected = filter == tab,
                    onClick = { filter = tab },
                    label = { Text(tab, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Mint.copy(alpha = .18f),
                        selectedLabelColor = Mint
                    )
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = onlyWorking,
                onClick = { onlyWorking = !onlyWorking },
                label = { Text("Только доступные", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Mint.copy(alpha = .18f),
                    selectedLabelColor = Mint
                )
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { sortByPing = !sortByPing }) {
                Text(if (sortByPing) "Сначала быстрые" else "Без сортировки", color = Mint, fontSize = 11.sp)
            }
        }

        if (status.isNotBlank()) {
            Text(status, color = Muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        } else {
            val updated = ServerCatalog.lastUpdated(context)
            val time = if (updated > 0L) SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(updated)) else "ещё не обновлялись"
            Text("Обновление подписок: $time", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
        }

        if (visibleServers.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (servers.isEmpty()) "Каталог пока пуст. Нажми обновить." else "По этому фильтру серверов нет.",
                    color = Muted,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                items(visibleServers, key = { it.id }) { node ->
                    ServerRow(
                        node = node,
                        onFavorite = { favorite ->
                            servers = ServerCatalog.setFavorite(context, node.id, favorite)
                        },
                        onSelect = { onSelect(node) }
                    )
                }
            }
        }
        Text(
            "Проверка TCP показывает только доступность порта. Фактическое подключение зависит от состояния сервера и сети.",
            color = Muted,
            fontSize = 9.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ServerRow(
    node: ServerNode,
    onFavorite: (Boolean) -> Unit,
    onSelect: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(15.dp))
            .clickable(onClick = onSelect).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("${ServerCatalog.countryFlag(node.country)}  ${if (node.country == "Unknown") "Unknown location" else node.country}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                "${node.protocol} • ${node.host}:${node.port}",
                color = Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            val latency = when {
                node.pingMs >= 0L -> "TCP ${node.pingMs} мс"
                node.isWorking -> "Доступен"
                else -> "Не проверен / нет ответа"
            }
            Text(
                latency,
                color = if (node.isWorking) Mint else Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        IconButton(onClick = { onFavorite(!node.isFavorite) }) {
            Icon(
                if (node.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Избранное",
                tint = if (node.isFavorite) Mint else Muted
            )
        }
        TextButton(onClick = onSelect, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Выбрать", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}
