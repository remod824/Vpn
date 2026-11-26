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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF090D18)
private val Panel = Color(0xFF141B2B)
private val Accent = Color(0xFF65E6B4)
private val Muted = Color(0xFF8994A8)

class MainActivity : ComponentActivity() {
    private lateinit var connectivity: ConnectivityManager
    private var networkLabel by mutableStateOf("Определяем сеть…")
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread {
                networkLabel = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
                    else -> "Другая сеть"
                }
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread { networkLabel = "Нет подключения к сети" }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        networkLabel = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Мобильная сеть"
            else -> "Нет подключения"
        }
        connectivity.registerDefaultNetworkCallback(callback)
        setContent { AutoVpnScreen(networkLabel) }
    }
    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        super.onDestroy()
    }
}

@Composable
private fun AutoVpnScreen(networkLabel: String) {
    var mode by remember { mutableStateOf("Автоматически") }
    var showServers by remember { mutableStateOf(false) }
    val selectedProfile = when (mode) {
        "Всегда обычный VPN" -> "Обычный VPN"
        "Всегда спец. профиль" -> "Специальный профиль"
        else -> if (networkLabel == "Wi-Fi") "Обычный VPN" else "Специальный профиль"
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Background, surface = Panel, primary = Accent, onBackground = Color.White, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 22.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, null, tint = Accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Text("AutoVPN", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text("⚙", color = Muted, fontSize = 22.sp)
            }
            Spacer(Modifier.height(34.dp))
            Text("ТВОЁ СОЕДИНЕНИЕ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(224.dp).border(1.dp, Accent.copy(alpha = .32f), CircleShape).padding(13.dp).border(1.dp, Accent.copy(alpha = .18f), CircleShape).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Bolt, null, tint = Accent, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("VPN", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("НЕ ПОДКЛЮЧЁН", fontSize = 10.sp, color = Muted, letterSpacing = 1.5.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Интерфейс готовится — VPN-движок ещё не подключён", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(25.dp))
            InfoPanel("СЕТЬ", networkLabel, "ПРОФИЛЬ", selectedProfile)
            Spacer(Modifier.height(18.dp))
            Text("РЕЖИМ РАБОТЫ", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(9.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Автоматически", "Всегда обычный VPN", "Всегда спец. профиль").forEach { item ->
                    Row(Modifier.fillMaxWidth().background(if (mode == item) Accent.copy(alpha = .12f) else Panel, RoundedCornerShape(14.dp)).border(1.dp, if (mode == item) Accent.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(14.dp)).clickable { mode = item }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item, color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        RadioButton(selected = mode == item, onClick = { mode = item }, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                    }
                }
            }
            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).clickable { showServers = !showServers }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, null, tint = Accent)
                Spacer(Modifier.width(10.dp))
                Text("Выбор сервера", color = Color.White, modifier = Modifier.weight(1f))
                Text(if (showServers) "Скрыть" else "Открыть", color = Accent, fontSize = 12.sp)
            }
            if (showServers) {
                Spacer(Modifier.height(8.dp))
                Text("Список серверов подключим вместе с загрузчиком подписок и проверкой узлов.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("ПРОТОТИП ИНТЕРФЕЙСА • ВЕРСИЯ 0.1.0", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun InfoPanel(leftTitle: String, leftValue: String, rightTitle: String, rightValue: String) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(leftTitle, color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(5.dp))
            Text(leftValue, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(rightTitle, color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(5.dp))
            Text(rightValue, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
