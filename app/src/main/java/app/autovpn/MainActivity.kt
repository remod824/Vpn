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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF080C16)
private val Surface = Color(0xFF121A29)
private val Mint = Color(0xFF58E0B0)
private val Muted = Color(0xFF8995A9)

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

    private fun toggleVpn(mode: String) {
        if (vpnStatus == "VPN подключён" || vpnStatus.startsWith("Запуск VPN")) {
            AutoVpnVpnService.stop(this)
            return
        }
        val nodes = ServerCatalog.load(this)
        val useSpecialProfile = when (mode) {
            "Всегда спец. профиль" -> true
            "Всегда обычный VPN" -> false
            else -> networkLabel != "Wi-Fi"
        }
        // An explicitly chosen server always takes priority over automatic selection.
        val node = ServerCatalog.selected(this) ?: nodes
            .filter { it.isWorking && it.isSpecialProfile == useSpecialProfile }
            .minByOrNull { it.pingMs }
        if (node == null) {
            vpnStatus = if (useSpecialProfile) {
                "Нет доступного сервера с меткой спец. профиля"
            } else {
                "Нет проверенного обычного сервера"
            }
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
private fun AutoVpnApp(networkLabel: String, vpnStatus: String, onToggleVpn: (String) -> Unit) {
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
    onToggleVpn: (String) -> Unit,
    onOpenServers: () -> Unit
) {
    val preferences = remember(context) {
        context.getSharedPreferences("autovpn_settings", Context.MODE_PRIVATE)
    }
    var mode by remember {
        mutableStateOf(preferences.getString("mode", "Автоматически") ?: "Автоматически")
    }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val serverCount = remember { mutableIntStateOf(ServerCatalog.load(context).size) }

    LaunchedEffect(Unit) {
        if (serverCount.intValue == 0) {
            runCatching { ServerCatalog.refresh(context) }
            serverCount.intValue = ServerCatalog.load(context).size
        }
    }

    val profile = when (mode) {
        "Всегда обычный VPN" -> "Обычный VPN"
        "Всегда спец. профиль" -> "Специальный профиль"
        else -> if (networkLabel == "Wi-Fi") "Обычный VPN" else "Специальный профиль"
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(Mint.copy(alpha = .13f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Bolt, null, tint = Mint, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("AutoVPN", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSettings = !showSettings }) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Muted)
            }
        }

        Spacer(Modifier.height(29.dp))
        Text("ВАШЕ СОЕДИНЕНИЕ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(13.dp))
        Text(vpnStatus, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            if (selectedNode == null) {
                "Лучший доступный сервер выбирается автоматически"
            } else {
                "Выбран сервер: ${selectedNode.name}"
            },
            color = Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier.size(228.dp)
                .shadow(22.dp, CircleShape, ambientColor = Mint.copy(alpha = .18f), spotColor = Mint.copy(alpha = .25f))
                .border(1.dp, Mint.copy(alpha = .55f), CircleShape)
                .padding(11.dp)
                .border(1.dp, Mint.copy(alpha = .20f), CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color(0xFF1B3D39), Color(0xFF101C2A), Surface)
                    ),
                    CircleShape
                )
                .clickable { onToggleVpn(mode) },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Bolt, null, tint = Mint, modifier = Modifier.size(45.dp))
                Spacer(Modifier.height(8.dp))
                Text(if (vpnStatus == "VPN подключён") "ОТКЛЮЧИТЬ" else "ВКЛЮЧИТЬ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(5.dp))
                Text("VPN", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(17.dp)).padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ТЕКУЩАЯ СЕТЬ", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(5.dp))
                Text(networkLabel, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Box(Modifier.width(1.dp).height(34.dp).background(Color(0xFF293347)))
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text("ВЫБРАННЫЙ ПРОФИЛЬ", color = Muted, fontSize = 9.sp, letterSpacing = .5.sp)
                Spacer(Modifier.height(5.dp))
                Text(profile, color = Mint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(13.dp))
        Row(
            Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenServers).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Public, null, tint = Mint)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Серверы", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    if (selectedNode == null) "${serverCount.intValue} серверов в каталоге" else "${selectedNode.protocol} • ${selectedNode.host}:${selectedNode.port}",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
            Text("Открыть", color = Mint, fontSize = 11.sp)
        }

        if (showSettings) {
            Spacer(Modifier.height(12.dp))
            ModePicker(mode = mode, onModeChange = { mode = it; preferences.edit().putString("mode", it).apply() })
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Mint.copy(alpha = .7f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("АВТОВЫБОР • УМНОЕ ПОДКЛЮЧЕНИЕ", color = Muted, fontSize = 9.sp, letterSpacing = .8.sp)
        }
    }
}

@Composable
private fun ModePicker(mode: String, onModeChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(13.dp)) {
        Text("РЕЖИМ РАБОТЫ", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        listOf("Автоматически", "Всегда обычный VPN", "Всегда спец. профиль").forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable { onModeChange(option) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(option, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                RadioButton(
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                    colors = RadioButtonDefaults.colors(selectedColor = Mint)
                )
            }
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
    var filter by remember { mutableStateOf("Все страны") }
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
                "Белые списки" -> node.isSpecialProfile
                "Обычные VPN" -> !node.isSpecialProfile
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
        Modifier.fillMaxSize().background(Bg).padding(horizontal = 18.dp, vertical = 14.dp)
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
            listOf("Все страны", "Белые списки", "Обычные VPN", "Избранные").forEach { tab ->
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
            "Проверка TCP показывает доступность порта, но не гарантирует работу VPN или доступ через фильтры оператора.",
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
            Text(node.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                "${node.country} • ${node.protocol} • ${node.host}:${node.port}",
                color = Muted,
                fontSize = 10.sp,
                maxLines = 2
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
        Text("Выбрать", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
