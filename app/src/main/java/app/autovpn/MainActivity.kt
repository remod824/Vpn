package app.autovpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
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

private val Bg = Color(0xFF080C16)
private val Surface = Color(0xFF121A29)
private val Mint = Color(0xFF58E0B0)
private val Muted = Color(0xFF8995A9)

class MainActivity : ComponentActivity() {
    private lateinit var connectivity: ConnectivityManager
    private var networkLabel by mutableStateOf("Определяем сеть…")
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
        networkLabel = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let(::networkName) ?: "Нет сети"
        connectivity.registerDefaultNetworkCallback(networkCallback)
        setContent { AutoVpnHome(networkLabel) }
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }

    private fun networkName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
        else -> "Другая сеть"
    }
}

@Composable
private fun AutoVpnHome(networkLabel: String) {
    var mode by remember { mutableStateOf("Автоматически") }
    var showSettings by remember { mutableStateOf(false) }
    var showServers by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Готов к подключению") }

    val profile = when (mode) {
        "Всегда обычный VPN" -> "Обычный VPN"
        "Всегда спец. профиль" -> "Специальный профиль"
        else -> if (networkLabel == "Wi-Fi") "Обычный VPN" else "Специальный профиль"
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Surface, primary = Mint,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(
            modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(Mint.copy(alpha = .13f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Bolt, null, tint = Mint, modifier = Modifier.size(25.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("AutoVPN", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Muted)
                }
            }

            Spacer(Modifier.height(38.dp))
            Text("ВАШЕ СОЕДИНЕНИЕ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(13.dp))
            Text(message, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(
                "Лучший доступный сервер выбирается автоматически",
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 10.dp)
            )
            Spacer(Modifier.height(27.dp))

            // Main connect control — deliberately styled as the primary focus of the original concept.
            Box(
                modifier = Modifier.size(238.dp)
                    .shadow(22.dp, CircleShape, ambientColor = Mint.copy(alpha = .18f), spotColor = Mint.copy(alpha = .25f))
                    .border(1.dp, Mint.copy(alpha = .55f), CircleShape)
                    .padding(11.dp)
                    .border(1.dp, Mint.copy(alpha = .20f), CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(Color(0xFF1B3D39), Color(0xFF101C2A), Surface)
                        ), CircleShape
                    )
                    .clickable { message = "VPN-ядро ещё не подключено" },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Bolt, null, tint = Mint, modifier = Modifier.size(45.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("ВКЛЮЧИТЬ", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("VPN", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }

            Spacer(Modifier.height(27.dp))
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
                    .clickable { showServers = !showServers }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Public, null, tint = Mint)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Серверы", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Автоматический выбор или вручную", color = Muted, fontSize = 11.sp)
                }
                Text(if (showServers) "Скрыть" else "Открыть", color = Mint, fontSize = 11.sp)
            }

            if (showSettings) {
                Spacer(Modifier.height(12.dp))
                ModePicker(mode = mode, onModeChange = { mode = it })
            }
            if (showServers) {
                Spacer(Modifier.height(10.dp))
                Text("Здесь появятся страны, избранное, пинг и фильтр рабочих серверов.", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Mint.copy(alpha = .7f), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text("АВТОВЫБОР • УМНОЕ ПОДКЛЮЧЕНИЕ", color = Muted, fontSize = 9.sp, letterSpacing = .8.sp)
            }
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
                RadioButton(selected = mode == option, onClick = { onModeChange(option) }, colors = RadioButtonDefaults.colors(selectedColor = Mint))
            }
        }
    }
}
