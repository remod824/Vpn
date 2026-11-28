package app.autovpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import android.util.Base64
import java.util.Locale

data class ServerNode(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val link: String,
    val country: String,
    val pingMs: Long = -1L,
    val isWorking: Boolean = false,
    val isFavorite: Boolean = false
) {

}

data class RefreshSummary(
    val total: Int,
    val checked: Int,
    val reachable: Int
)

object ServerCatalog {
    private const val PREFS = "autovpn_catalog"
    private const val KEY_NODES = "nodes_json"
    private const val KEY_UPDATED = "updated_at"
    private const val KEY_SELECTED = "selected_node"
    private const val MAX_NODES = 1000
    private const val MAX_SOURCE_BYTES = 6 * 1024 * 1024
    private const val SOURCES_URL =
        "https://raw.githubusercontent.com/remod824/Vpn/main/sources.txt"

    private val linkPattern = Regex(
        """(?i)(?:vless|vmess|trojan|ss|hysteria2|hy2|tuic|anytls|socks5h?|socks4)://[^\s"'<>]+"""
    )

    fun load(context: Context): List<ServerNode> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NODES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        ServerNode(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            protocol = item.optString("protocol"),
                            host = item.optString("host"),
                            port = item.optInt("port"),
                            link = item.optString("link"),
                            country = item.optString("country", "Неизвестно"),
                            pingMs = item.optLong("pingMs", -1L),
                            isWorking = item.optBoolean("isWorking", false),
                            isFavorite = item.optBoolean("isFavorite", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun lastUpdated(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED, 0L)

    fun selected(context: Context): ServerNode? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null) ?: return null
        return load(context).firstOrNull { it.id == id }
    }

    fun select(context: Context, node: ServerNode?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELECTED, node?.id).apply()
    }

    fun setFavorite(context: Context, id: String, favorite: Boolean): List<ServerNode> {
        val updated = load(context).map {
            if (it.id == id) it.copy(isFavorite = favorite) else it
        }
        save(context, updated)
        return updated
    }

    suspend fun refresh(context: Context): RefreshSummary = withContext(Dispatchers.IO) {
        val oldById = load(context).associateBy { it.id }
        val sourceText = download(SOURCES_URL)
        val sourceUrls = sourceText.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()
            .take(20)
            .toList()
            .ifEmpty {
                // Keep refresh working even if GitHub serves a stale/empty sources.txt.
                listOf("https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt")
            }

        val imported = coroutineScope {
            sourceUrls.map { source ->
                async(Dispatchers.IO) {
                    runCatching { extractLinks(download(source)) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }

        val parsed = imported.asSequence()
            .distinct()
            .mapNotNull(::parseNode)
            .distinctBy { it.id }
            .take(MAX_NODES)
            .map { node ->
                val previous = oldById[node.id]
                node.copy(
                    isFavorite = previous?.isFavorite ?: false,
                    pingMs = previous?.pingMs ?: -1L,
                    isWorking = previous?.isWorking ?: false
                )
            }
            .toList()

        val checkedNodes = coroutineScope {
            val head = parsed.take(60).map { node ->
                async(Dispatchers.IO) {
                    val ping = probe(node.host, node.port)
                    node.copy(pingMs = ping, isWorking = ping >= 0L)
                }
            }.awaitAll()
            head + parsed.drop(60)
        }

        val ordered = checkedNodes.sortedWith(
            compareBy<ServerNode> { if (it.pingMs >= 0L) 0 else 1 }
                .thenBy { if (it.pingMs >= 0L) it.pingMs else Long.MAX_VALUE }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
        save(context, ordered)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()

        RefreshSummary(
            total = ordered.size,
            checked = minOf(ordered.size, 60),
            reachable = ordered.count { it.isWorking }
        )
    }

    private fun save(context: Context, nodes: List<ServerNode>) {
        val array = JSONArray()
        nodes.take(MAX_NODES).forEach { node ->
            array.put(
                JSONObject()
                    .put("id", node.id)
                    .put("name", node.name)
                    .put("protocol", node.protocol)
                    .put("host", node.host)
                    .put("port", node.port)
                    .put("link", node.link)
                    .put("country", node.country)
                    .put("pingMs", node.pingMs)
                    .put("isWorking", node.isWorking)
                    .put("isFavorite", node.isFavorite)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NODES, array.toString())
            .apply()
    }

    private fun download(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "AutoVPN-Android/0.2")
        connection.setRequestProperty("Accept", "text/plain,*/*")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code для $address" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SOURCE_BYTES) { "Подписка слишком большая: $address" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(StandardCharsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun extractLinks(raw: String): List<String> {
        val compact = raw.trim()
        val decoded = if (!compact.contains("://") && compact.length > 48) {
            decodeBase64(compact)?.takeIf { it.contains("://") }
        } else null
        val text = decoded ?: raw
        return linkPattern.findAll(text)
            .map { it.value.trimEnd(',', ';', ')', ']') }
            .distinct()
            .toList()
    }

    private fun parseNode(link: String): ServerNode? {
        val scheme = link.substringBefore("://").lowercase(Locale.ROOT)
        if (scheme == "vmess") return parseVmess(link)
        if (scheme == "ss") return parseShadowsocks(link)

        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val host = uri.host?.trim('[', ']')?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        if (port !in 1..65535) return null
        val name = uri.rawFragment?.let(::decodePart)?.takeIf { it.isNotBlank() }
            ?: "${scheme.uppercase(Locale.ROOT)} • $host"
        return ServerNode(
            id = sha256(link),
            name = name.take(90),
            protocol = scheme.uppercase(Locale.ROOT),
            host = host,
            port = port,
            link = link,
            country = guessCountry(name)
        )
    }

    private fun parseVmess(link: String): ServerNode? {
        val encoded = link.substringAfter("://").substringBefore('#').trim()
        val jsonText = decodeBase64(encoded) ?: return null
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val host = json.optString("add").takeIf { it.isNotBlank() } ?: return null
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port")
        if (port !in 1..65535) return null
        val name = json.optString("ps").ifBlank { "VMESS • $host" }
        return ServerNode(
            id = sha256(link),
            name = name.take(90),
            protocol = "VMESS",
            host = host,
            port = port,
            link = link,
            country = guessCountry(name)
        )
    }

    private fun parseShadowsocks(link: String): ServerNode? {
        var authority = link.substringAfter("://").substringBefore('#').substringBefore('?')
        if (!authority.contains('@')) {
            authority = decodeBase64(authority) ?: return null
        } else {
            val user = authority.substringBefore('@')
            if (!user.contains(':')) {
                val decodedUser = decodeBase64(user)
                if (decodedUser?.contains(':') == true) {
                    authority = decodedUser + "@" + authority.substringAfter('@')
                }
            }
        }
        val endpoint = authority.substringAfterLast('@')
        val host: String
        val port: Int
        if (endpoint.startsWith("[")) {
            val closing = endpoint.indexOf(']')
            if (closing < 0) return null
            host = endpoint.substring(1, closing)
            port = endpoint.substring(closing + 1).removePrefix(":").toIntOrNull() ?: return null
        } else {
            host = endpoint.substringBeforeLast(':', "")
            port = endpoint.substringAfterLast(':').toIntOrNull() ?: return null
        }
        if (host.isBlank() || port !in 1..65535) return null
        val name = link.substringAfter('#', "").takeIf { it.isNotBlank() }?.let(::decodePart)
            ?: "SS • $host"
        return ServerNode(
            id = sha256(link),
            name = name.take(90),
            protocol = "SS",
            host = host,
            port = port,
            link = link,
            country = guessCountry(name)
        )
    }

    private fun decodeBase64(value: String): String? = runCatching {
        val clean = value.replace("-", "+").replace("_", "/")
            .filterNot { it.isWhitespace() }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        String(Base64.decode(padded, Base64.DEFAULT or Base64.URL_SAFE), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun decodePart(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun countryFlag(country: String): String = when (country) {
        "Netherlands" -> "🇳🇱"
        "Germany" -> "🇩🇪"
        "France" -> "🇫🇷"
        "Finland" -> "🇫🇮"
        "United States" -> "🇺🇸"
        "United Kingdom" -> "🇬🇧"
        "Russia" -> "🇷🇺"
        "Singapore" -> "🇸🇬"
        "Japan" -> "🇯🇵"
        "Turkey" -> "🇹🇷"
        "Poland" -> "🇵🇱"
        "Canada" -> "🇨🇦"
        "Sweden" -> "🇸🇪"
        "Norway" -> "🇳🇴"
        "Switzerland" -> "🇨🇭"
        "Austria" -> "🇦🇹"
        "Belgium" -> "🇧🇪"
        "Spain" -> "🇪🇸"
        "Italy" -> "🇮🇹"
        "Czechia" -> "🇨🇿"
        "Romania" -> "🇷🇴"
        "Ukraine" -> "🇺🇦"
        "Hong Kong" -> "🇭🇰"
        "South Korea" -> "🇰🇷"
        "India" -> "🇮🇳"
        "Australia" -> "🇦🇺"
        "Brazil" -> "🇧🇷"
        else -> "🌐"
    }

    private fun guessCountry(name: String): String {
        val value = name.lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
        val countries = listOf(
            listOf("netherlands", "holland", "nederland", "нидерланд", "голланд", "\\bnl\\b") to "Netherlands",
            listOf("germany", "deutschland", "германи", "германия", "\\bde\\b") to "Germany",
            listOf("finland", "финлянд", "\\bfi\\b") to "Finland",
            listOf("france", "франци", "франция", "\\bfr\\b") to "France",
            listOf("united states", "usa", "america", "сша", "\\bus\\b") to "United States",
            listOf("united kingdom", "england", "britain", "великобрит", "\\bgb\\b", "\\buk\\b") to "United Kingdom",
            listOf("russia", "росси", "россия", "\\bru\\b") to "Russia",
            listOf("singapore", "сингапур", "\\bsg\\b") to "Singapore",
            listOf("japan", "япони", "\\bjp\\b") to "Japan",
            listOf("turkey", "türkiye", "турци", "турция", "\\btr\\b") to "Turkey",
            listOf("poland", "польш", "\\bpl\\b") to "Poland",
            listOf("canada", "канада", "\\bca\\b") to "Canada",
            listOf("sweden", "швеци", "\\bse\\b") to "Sweden",
            listOf("norway", "норвеги", "\\bno\\b") to "Norway",
            listOf("switzerland", "швейцар", "\\bch\\b") to "Switzerland",
            listOf("austria", "австри", "\\bat\\b") to "Austria",
            listOf("belgium", "бельги", "\\bbe\\b") to "Belgium",
            listOf("spain", "испания", "\\bes\\b") to "Spain",
            listOf("italy", "итал", "\\bit\\b") to "Italy",
            listOf("czech", "чехи", "\\bcz\\b") to "Czechia",
            listOf("romania", "румыни", "\\bro\\b") to "Romania",
            listOf("ukraine", "украин", "\\bua\\b") to "Ukraine",
            listOf("hong kong", "гонконг", "\\bhk\\b") to "Hong Kong",
            listOf("south korea", "korea", "корея", "\\bkr\\b") to "South Korea",
            listOf("india", "индия", "\\bin\\b") to "India",
            listOf("australia", "австрали", "\\bau\\b") to "Australia",
            listOf("brazil", "бразили", "\\bbr\\b") to "Brazil"
        )
        return countries.firstOrNull { (keys, _) ->
            keys.any { key ->
                if (key.startsWith("\\b")) Regex(key).containsMatchIn(value) else value.contains(key)
            }
        }?.second ?: "Unknown"
    }
    private fun probe(host: String, port: Int): Long {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 1_500)
            }
            (System.nanoTime() - started) / 1_000_000L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
