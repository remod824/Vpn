package app.autovpn

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object SingBoxConfigBuilder {
    fun build(node: ServerNode): String {
        val outbound = when (node.protocol.uppercase(Locale.ROOT)) {
            "VLESS" -> buildVless(node)
            "VMESS" -> buildVmess(node)
            "TROJAN" -> buildTrojan(node)
            "SS" -> buildShadowsocks(node)
            "HYSTERIA2", "HY2" -> buildHysteria2(node)
            "TUIC" -> buildTuic(node)
            "ANYTLS" -> buildAnyTls(node)
            "SOCKS5", "SOCKS5H", "SOCKS4" -> buildSocks(node)
            else -> throw IllegalArgumentException("Протокол ${node.protocol} пока не поддерживается VPN-ядром")
        }.put("tag", "proxy")

        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "tun0")
            .put("address", org.json.JSONArray().put("172.19.0.1/30"))
            .put("mtu", 1400)
            .put("auto_route", true)
            .put("strict_route", true)

        val dnsServer = JSONObject()
            .put("type", "udp")
            .put("tag", "remote-dns")
            .put("server", "1.1.1.1")
            .put("server_port", 53)
            .put("detour", "proxy")

        return JSONObject()
            .put("log", JSONObject().put("level", "warn").put("timestamp", true))
            .put(
                "dns",
                JSONObject()
                    .put("servers", org.json.JSONArray().put(dnsServer))
                    .put("final", "remote-dns")
            )
            .put("inbounds", org.json.JSONArray().put(tun))
            .put(
                "outbounds",
                org.json.JSONArray()
                    .put(outbound)
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
                    .put(JSONObject().put("type", "block").put("tag", "block"))
            )
            .put("route", JSONObject().put("auto_detect_interface", true).put("final", "proxy"))
            .toString()
    }

    private fun buildVless(node: ServerNode): JSONObject {
        val uri = URI(node.link)
        val params = query(uri)
        val uuid = decode(uri.rawUserInfo.orEmpty()).substringBefore(':')
        require(uuid.isNotBlank()) { "В ссылке VLESS отсутствует UUID" }
        val result = serverObject("vless", node)
            .put("uuid", uuid)
        params["flow"]?.takeIf { it == "xtls-rprx-vision" }?.let { result.put("flow", it) }
        addTransport(result, params)
        addTls(result, node.host, params, params["security"] == "reality")
        return result
    }

    private fun buildVmess(node: ServerNode): JSONObject {
        val encoded = node.link.substringAfter("://").substringBefore('#')
        val json = JSONObject(decodeBase64(encoded) ?: throw IllegalArgumentException("Не удалось декодировать VMess"))
        val host = json.optString("add", node.host).ifBlank { node.host }
        val port = json.optString("port").toIntOrNull() ?: node.port
        val result = JSONObject()
            .put("type", "vmess")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", json.optString("id"))
            .put("alter_id", json.optInt("aid", 0))
            .put("security", json.optString("scy").ifBlank { "auto" })
        val params = linkedMapOf<String, String>()
        val net = json.optString("net").ifBlank { "tcp" }
        params["type"] = net
        params["path"] = json.optString("path")
        params["host"] = json.optString("host")
        addTransport(result, params)
        if (json.optString("tls") == "tls") {
            val tlsParams = linkedMapOf<String, String>()
            tlsParams["sni"] = json.optString("sni").ifBlank { json.optString("host").ifBlank { host } }
            addTls(result, host, tlsParams, false)
        }
        require(json.optString("id").isNotBlank()) { "В ссылке VMess отсутствует UUID" }
        return result
    }

    private fun buildTrojan(node: ServerNode): JSONObject {
        val uri = URI(node.link)
        val params = query(uri)
        val password = decode(uri.rawUserInfo.orEmpty()).substringAfter(':', decode(uri.rawUserInfo.orEmpty()))
        require(password.isNotBlank()) { "В ссылке Trojan отсутствует пароль" }
        val result = serverObject("trojan", node).put("password", password)
        addTransport(result, params)
        addTls(result, node.host, params, false, defaultEnabled = true)
        return result
    }

    private fun buildShadowsocks(node: ServerNode): JSONObject {
        var authority = node.link.substringAfter("://").substringBefore('#').substringBefore('?')
        if (!authority.contains('@')) authority = decodeBase64(authority)
            ?: throw IllegalArgumentException("Не удалось декодировать Shadowsocks")
        val userPart = authority.substringBefore('@')
        val endpoint = authority.substringAfterLast('@')
        val credentials = decodeBase64(userPart)?.takeIf { it.contains(':') } ?: decode(userPart)
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        require(method.isNotBlank() && password.isNotBlank()) { "Некорректные данные Shadowsocks" }
        val result = serverObject("shadowsocks", node)
            .put("method", method)
            .put("password", password)
        val host = endpoint.substringBeforeLast(':', "")
        val port = endpoint.substringAfterLast(':').toIntOrNull()
        if (host.isNotBlank() && port != null) {
            result.put("server", host.trim('[', ']'))
            result.put("server_port", port)
        }
        return result
    }

    private fun buildHysteria2(node: ServerNode): JSONObject {
        val uri = URI(node.link)
        val params = query(uri)
        val userInfo = decode(uri.rawUserInfo.orEmpty())
        val password = userInfo.substringAfter(':', userInfo)
        require(password.isNotBlank()) { "В ссылке Hysteria2 отсутствует пароль" }
        val result = serverObject("hysteria2", node).put("password", password)
        addTls(result, node.host, params, false, defaultEnabled = true)
        params["obfs"]?.takeIf { it.isNotBlank() }?.let { obfs ->
            result.put("obfs", JSONObject().put("type", obfs).put("password", params["obfs-password"].orEmpty()))
        }
        return result
    }

    private fun buildTuic(node: ServerNode): JSONObject {
        val uri = URI(node.link)
        val userInfo = decode(uri.rawUserInfo.orEmpty())
        val parts = userInfo.split(':', limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "В ссылке TUIC требуются UUID и пароль"
        }
        val params = query(uri)
        val result = serverObject("tuic", node)
            .put("uuid", parts[0])
            .put("password", parts[1])
            .put("congestion_control", params["congestion_control"] ?: "cubic")
            .put("udp_relay_mode", params["udp_relay_mode"] ?: "native")
        addTls(result, node.host, params, false, defaultEnabled = true)
        return result
    }

    private fun buildAnyTls(node: ServerNode): JSONObject {
        val uri = URI(node.link)
        val password = decode(uri.rawUserInfo.orEmpty())
        require(password.isNotBlank()) { "В ссылке AnyTLS отсутствует пароль" }
        val result = serverObject("anytls", node).put("password", password)
        addTls(result, node.host, query(uri), false, defaultEnabled = true)
        return result
    }

    private fun buildSocks(node: ServerNode): JSONObject {
        val uri = URI(node.link)
        val userInfo = decode(uri.rawUserInfo.orEmpty())
        val parts = userInfo.split(':', limit = 2)
        val result = serverObject("socks", node)
            .put("version", if (node.protocol.uppercase(Locale.ROOT) == "SOCKS4") "4" else "5")
        if (parts.size == 2) {
            result.put("username", parts[0])
            result.put("password", parts[1])
        }
        return result
    }

    private fun serverObject(type: String, node: ServerNode): JSONObject =
        JSONObject()
            .put("type", type)
            .put("server", node.host)
            .put("server_port", node.port)

    private fun addTls(
        target: JSONObject,
        defaultHost: String,
        params: Map<String, String>,
        reality: Boolean,
        defaultEnabled: Boolean = false
    ) {
        val enabled = defaultEnabled || params["security"] == "tls" || reality ||
            params["tls"] == "1" || params["tls"] == "true"
        if (!enabled) return
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", params["sni"]?.takeIf { it.isNotBlank() }
                ?: params["servername"]?.takeIf { it.isNotBlank() }
                ?: defaultHost)
        if (params["fp"].orEmpty().isNotBlank()) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", params["fp"]))
        }
        if (reality) {
            val realityOptions = JSONObject().put("enabled", true)
            params["pbk"]?.takeIf { it.isNotBlank() }?.let { realityOptions.put("public_key", it) }
            params["sid"]?.takeIf { it.isNotBlank() }?.let { realityOptions.put("short_id", it) }
            tls.put("reality", realityOptions)
        }
        target.put("tls", tls)
    }

    private fun addTransport(target: JSONObject, params: Map<String, String>) {
        val type = params["type"] ?: params["network"] ?: "tcp"
        if (type == "tcp" || type.isBlank()) return
        val transport = JSONObject().put("type", type)
        when (type) {
            "ws" -> {
                params["path"]?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                params["host"]?.takeIf { it.isNotBlank() }?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
            }
            "grpc" -> params["serviceName"]?.takeIf { it.isNotBlank() }?.let {
                transport.put("service_name", it)
            }
            "http", "h2" -> {
                params["host"]?.takeIf { it.isNotBlank() }?.let {
                    transport.put("host", org.json.JSONArray().put(it))
                }
                params["path"]?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                if (type == "h2") transport.put("type", "http")
            }
        }
        target.put("transport", transport)
    }

    private fun query(uri: URI): Map<String, String> {
        val result = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.forEach { part ->
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            result[key] = value
        }
        return result
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64(value: String): String? = runCatching {
        val clean = value.filterNot { it.isWhitespace() }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        String(Base64.decode(padded, Base64.DEFAULT or Base64.URL_SAFE), StandardCharsets.UTF_8)
    }.getOrNull()
}
