package app.autovpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.OsConstants
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.content.ContextCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

class AutoVpnVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    companion object {
        const val ACTION_STATUS = "app.autovpn.VPN_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONFIG = "config"
        const val ACTION_START = "app.autovpn.START_VPN"
        const val ACTION_STOP = "app.autovpn.STOP_VPN"
        private const val CHANNEL_ID = "autovpn_vpn"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context, config: String) {
            val intent = Intent(context, AutoVpnVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AutoVpnVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var commandServer: CommandServer? = null
    private var tunDescriptor: android.os.ParcelFileDescriptor? = null
    private val stopping = AtomicBoolean(false)
    private var interfaceListener: InterfaceUpdateListener? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivity by lazy { getSystemService<ConnectivityManager>()!! }
    private val notificationManager by lazy { getSystemService<NotificationManager>()!! }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AutoVPN", "Подготовка VPN…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    publishStatus("Ошибка: пустая конфигурация")
                    stopSelf()
                } else {
                    startTunnel(config)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: String) {
        Thread {
            try {
                stopping.set(false)
                publishStatus("Запуск VPN-ядра…")
                val setup = SetupOptions().apply {
                    basePath = filesDir.absolutePath
                    workingPath = filesDir.absolutePath
                    tempPath = cacheDir.absolutePath
                    logMaxLines = 1000
                    debug = false
                }
                Libbox.setup(setup)
                val server = CommandServer(this, this)
                commandServer = server
                server.start()
                server.startOrReloadService(config, OverrideOptions())
                publishStatus("VPN подключён")
                updateNotification("AutoVPN", "Защищённое соединение активно")
            } catch (error: Exception) {
                publishStatus("Ошибка подключения: ${error.message ?: error.javaClass.simpleName}")
                stopTunnel()
            }
        }.start()
    }

    private fun stopTunnel() {
        if (!stopping.compareAndSet(false, true)) return
        publishStatus("Отключение…")
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        publishStatus("Отключено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        runCatching { tunDescriptor?.close() }
        super.onDestroy()
    }

    private fun publishStatus(value: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, value)
        )
        updateNotification("AutoVPN", value)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AutoVPN VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(title: String, body: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(title: String, body: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, body))
    }

    // CommandServerHandler
    override fun serviceStop() {
        stopTunnel()
    }

    override fun serviceReload() {
        // Config reload is triggered by the app when the selected node changes.
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String?) {
        android.util.Log.d("AutoVPN-libbox", message.orEmpty())
    }

    override fun connectSSHAgent(): Int = -1

    // PlatformInterface
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) throw IllegalStateException("Не удалось исключить сокет ядра из VPN")
    }

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) {
            throw IllegalStateException("Не выдано системное разрешение VPN")
        }
        val builder = Builder()
            .setSession("AutoVPN")
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        val ipv4 = options.inet4Address
        while (ipv4.hasNext()) {
            val prefix = ipv4.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }
        val ipv6 = options.inet6Address
        while (ipv6.hasNext()) {
            val prefix = ipv6.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }
        val dns = options.dnsServerAddress
        while (dns.hasNext()) {
            builder.addDnsServer(dns.next())
        }
        if (options.autoRoute) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }
        val pfd = builder.establish()
            ?: throw IllegalStateException("Android не смог создать VPN-интерфейс")
        tunDescriptor?.close()
        tunDescriptor = pfd
        return pfd.fd
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): ConnectionOwner {
        return runCatching {
            Libbox.findConnectionOwner(
                ipProtocol,
                sourceAddress.orEmpty(),
                sourcePort,
                destinationAddress.orEmpty(),
                destinationPort
            )
        }.getOrElse { ConnectionOwner() }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        interfaceListener = listener
        if (defaultNetworkCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    reportDefaultInterface(network)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    reportDefaultInterface(network)
                }

                override fun onLost(network: Network) {
                    reportDefaultInterface(connectivity.activeNetwork)
                }
            }
            defaultNetworkCallback = callback
            runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        }
        reportDefaultInterface(connectivity.activeNetwork)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (interfaceListener === listener) {
            interfaceListener = null
            defaultNetworkCallback?.let { callback ->
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
            defaultNetworkCallback = null
        }
    }

    private fun reportDefaultInterface(network: Network?) {
        val listener = interfaceListener ?: return
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val props = connectivity.getLinkProperties(network) ?: return
        val name = props.interfaceName ?: return
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val caps = connectivity.getNetworkCapabilities(network)
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        listener.updateDefaultInterface(name, index, false, metered)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val values = mutableListOf<LibboxNetworkInterface>()
        val javaInterfaces = runCatching {
            JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        for (network in connectivity.allNetworks) {
            val properties = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val name = properties.interfaceName ?: continue
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: continue

            val item = LibboxNetworkInterface().apply {
                this.name = name
                index = javaInterface.index
                dnsServer = StringArray(properties.dnsServers.mapNotNull { it.hostAddress }.iterator())
                dnsSearchDomain = StringArray(
                    properties.domains.orEmpty()
                        .split(Regex("""[,\\s]+"""))
                        .filter { it.isNotBlank() }
                        .iterator()
                )
                gateway = StringArray(
                    properties.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway?.hostAddress }
                        .iterator()
                )
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LibboxNetworkInterface.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LibboxNetworkInterface.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LibboxNetworkInterface.InterfaceTypeEthernet
                    else -> LibboxNetworkInterface.InterfaceTypeOther
                }
                mtu = runCatching { javaInterface.mtu }.getOrDefault(0)
                addresses = StringArray(
                    javaInterface.interfaceAddresses.mapNotNull { address ->
                        address.address?.hostAddress?.let { "$it/${address.networkPrefixLength}" }
                    }.iterator()
                )
                flags = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                } else {
                    0
                }
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            values.add(item)
        }

        return object : NetworkInterfaceIterator {
            private val iterator = values.iterator()
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): LibboxNetworkInterface = iterator.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() {
        throw UnsupportedOperationException("Shell is unavailable")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = throw UnsupportedOperationException("Shell is unavailable")

    override fun readSystemSSHHostKey(): String = ""
    override fun lookupSFTPServer(): String = ""
    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("Bridge is unavailable")
    override fun lookupUser(username: String?): PlatformUser = PlatformUser().apply {
        this.username = username.orEmpty()
        uid = android.os.Process.myUid()
        gid = android.os.Process.myUid()
        homeDir = filesDir.absolutePath
    }

    override fun registerMyInterface(name: String?) = Unit

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    // PlatformInterface notification callbacks
    override fun sendNotification(notification: Notification) {
        updateNotification(notification.title, notification.body)
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        notificationManager.cancel(typeID)
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
