package app.drawbridge.dpc.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.apps.PackageWatcher
import app.drawbridge.dpc.ui.MainActivity
import app.drawbridge.dpc.update.UpdateWorker
import app.drawbridge.dpc.vpn.dns.DnsFilter
import app.drawbridge.dpc.vpn.dns.DnsMessage
import app.drawbridge.dpc.vpn.dns.EncryptedDnsClient
import app.drawbridge.dpc.vpn.net.IpPacket
import app.drawbridge.policy.model.DnsPolicy
import app.drawbridge.policy.PolicyManager
import app.drawbridge.policy.work.PolicyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The device-wide filter: an always-on [VpnService] that intercepts DNS only.
 *
 * Only the configured resolver addresses are routed into the tunnel, so this is
 * not a full packet capture — ordinary traffic goes straight out as before and
 * there is no userspace TCP/IP stack to maintain. Everything a browser or an
 * app's embedded WebView loads still has to resolve a name first, and that
 * lookup comes through here.
 *
 * Its own package is excluded from the tunnel so that policy fetches and safe
 * search lookups cannot deadlock against the filter they are configuring.
 */
class DnsFilterService : VpnService() {

    private lateinit var policy: PolicyManager
    private lateinit var filter: DnsFilter

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var forwarders: ExecutorService? = null
    private var packageWatcher: PackageWatcher? = null

    @Volatile
    private var running = false

    /** Maps the fake resolver addresses handed to the system onto real upstreams. */
    private var upstreamByFakeAddress: Map<InetAddress, InetAddress> = emptyMap()

    /**
     * Set when the policy configures an encrypted upstream. Queries then go over
     * DNS-over-TLS and only fall back to [upstreamByFakeAddress] if that fails.
     *
     * This traffic never enters the tunnel: the service excludes its own package
     * from the VPN, so the tunnel's routes — including the blackhole routes for
     * other apps' encrypted DNS — do not apply to it.
     */
    private var encryptedClient: EncryptedDnsClient? = null

    /** The `dns` block the current tunnel was built from, to detect changes. */
    private var activeDns: DnsPolicy? = null

    /** Outlives individual tunnels; cancelled only when the service is destroyed. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        policy = DrawbridgeApplication.policy(this)
        filter = DnsFilter(policy)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFilter()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startFilter()

        // START_STICKY so an OEM task killer that gets past the always-on VPN
        // lockdown still results in the service coming back.
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopFilter()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopFilter()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startFilter() {
        if (running) return

        if (!openTunnel()) {
            stopSelf()
            return
        }

        // The always-on service is the most reliable process in the app, so it is
        // also where package watching and opportunistic policy refresh live.
        packageWatcher = PackageWatcher(this).also { it.start() }
        PolicyWorker.refreshNow(this)

        // Also retry any missing required app. The install normally happens once,
        // at provisioning — but if the device had no network then, waiting a full
        // day for the periodic job would leave it with no browser at all.
        // No-ops when everything is already installed.
        UpdateWorker.runNow(this)

        watchForDnsPolicyChanges()
    }

    /**
     * Rebuilds the tunnel when the policy's `dns` block changes.
     *
     * The resolver addresses and the encrypted upstream are baked into the
     * tunnel when it is established, so without this a change to either would
     * sit inert until the next reboot — a policy update that looks applied but
     * is not is worse than one that visibly failed.
     *
     * Blocklist changes need none of this: they are read per query.
     */
    private fun watchForDnsPolicyChanges() {
        serviceScope.launch {
            policy.policy
                .map { it.dns }
                .distinctUntilChanged()
                .drop(1) // The value the tunnel was just built from.
                .collect { dns ->
                    if (!running || dns == activeDns) return@collect
                    Log.i(TAG, "DNS policy changed; re-establishing the tunnel")
                    closeTunnel()
                    if (!openTunnel()) {
                        // Always-on VPN will restart the service, which is a
                        // cleaner recovery than limping on with no tunnel.
                        Log.e(TAG, "Could not re-establish the tunnel; stopping")
                        stopSelf()
                    }
                }
        }
    }

    private fun openTunnel(): Boolean {
        val descriptor = establishTunnel() ?: run {
            Log.e(TAG, "Could not establish the VPN tunnel")
            return false
        }

        tunnel = descriptor
        running = true
        isRunning = true

        forwarders = Executors.newFixedThreadPool(FORWARDER_THREADS)
        worker = Thread({ runTunnelLoop(descriptor) }, "drawbridge-dns").apply { start() }
        return true
    }

    /** Tears down the tunnel but leaves the service, its scope and the watcher alive. */
    private fun closeTunnel() {
        running = false
        isRunning = false

        worker?.interrupt()
        worker = null

        forwarders?.shutdownNow()
        forwarders = null

        runCatching { tunnel?.close() }
        tunnel = null

        encryptedClient?.close()
        encryptedClient = null
    }

    private fun stopFilter() {
        closeTunnel()

        packageWatcher?.stop()
        packageWatcher = null

        serviceScope.coroutineContext.cancelChildren()
    }

    private fun establishTunnel(): ParcelFileDescriptor? {
        activeDns = policy.policy.value.dns
        val configured = policy.policy.value.dns.upstreams
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            .ifEmpty {
                Log.w(TAG, "No usable upstream resolvers in policy; falling back to defaults")
                DEFAULT_UPSTREAMS.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            }

        if (configured.isEmpty()) return null

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setBlocking(true)
            .setMtu(MTU)
            .addAddress(TUNNEL_IPV4, TUNNEL_IPV4_PREFIX)
            .addAddress(TUNNEL_IPV6, TUNNEL_IPV6_PREFIX)

        // Every resolver is presented to the system under an address inside the
        // tunnel's own subnet. Only those addresses are routed here, so nothing
        // but DNS ever crosses this interface.
        val mapping = mutableMapOf<InetAddress, InetAddress>()
        var ipv4Index = 2
        var ipv6Index = 2

        for (upstream in configured) {
            val fake = when (upstream) {
                is Inet4Address -> InetAddress.getByName("$TUNNEL_IPV4_PREFIX_STRING${ipv4Index++}")
                is Inet6Address -> InetAddress.getByName("$TUNNEL_IPV6_PREFIX_STRING${ipv6Index++}")
                else -> continue
            }
            mapping[fake] = upstream
            builder.addDnsServer(fake)
            builder.addRoute(fake, if (fake is Inet6Address) 128 else 32)
        }

        if (mapping.isEmpty()) return null
        upstreamByFakeAddress = mapping

        encryptedClient = policy.policy.value.dns.encryptedUpstream?.let { upstream ->
            runCatching { EncryptedDnsClient(upstream) }
                .onSuccess { Log.i(TAG, "Forwarding over DNS-over-TLS to ${it.host}") }
                .onFailure { Log.e(TAG, "Ignoring unusable encrypted upstream '$upstream'", it) }
                .getOrNull()
        }

        // Known DoH/DoQ endpoints reachable by hardcoded IP are routed into the
        // tunnel and then dropped, since nothing here handles non-DNS traffic.
        // Endpoints reached by name are already covered by the blocklist.
        if (policy.policy.value.dns.blockEncryptedDns) {
            for (address in ENCRYPTED_DNS_BLACKHOLE) {
                runCatching {
                    val parsed = InetAddress.getByName(address)
                    builder.addRoute(parsed, if (parsed is Inet6Address) 128 else 32)
                }.onFailure { Log.w(TAG, "Could not blackhole $address", it) }
            }
        }

        // **A VPN is metered until it says otherwise, and this one has no
        // business saying so.** `VpnService.Builder` defaults `setMetered` to
        // true, so from the moment the filter comes up every app behind it sees
        // a metered connection — on Wi-Fi, on ethernet, on anything. Apps that
        // are careful with somebody's data allowance then behave as if the phone
        // were on a mobile plan: AntennaPod says "Your VPN makes it seem like
        // your connection is metered" and asks before each download, and the
        // same logic sits in podcast, backup, photo-sync and update clients
        // everywhere. Reported on 2026-08-30 by the owner, on Wi-Fi.
        //
        // False rather than a computed value: it means *inherit from the
        // underlying network*, which is the honest answer for a tunnel that
        // carries nothing but DNS. On mobile data the phone still reports
        // metered, because it still is — this stops the filter lying, it does
        // not make it lie the other way.
        //
        // API 29, and the minimum here is 28. On 28 the tunnel stays metered and
        // there is no call to make: `setMetered` does not exist, and neither
        // does the platform's own notion of a VPN inheriting meteredness.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Log.e(TAG, "Could not exclude drawbridge from the tunnel", it) }

        excludePackagesTheTunnelBreaks(builder, policy.policy.value.dns.excludedPackages)

        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        return runCatching { builder.establish() }
            .onFailure { Log.e(TAG, "establish() failed", it) }
            .getOrNull()
    }

    /**
     * Leaves the policy's [DnsPolicy.excludedPackages] outside the tunnel.
     *
     * These are apps an always-on VPN stops working, and the one the list ships
     * with is Android Auto. Wireless projection refuses to start while a VPN is
     * present and puts *"error 21, are you using a VPN?"* on the car's screen
     * every time the phone comes into range — a wired connection is unaffected,
     * which is the tell that this is about the VPN's presence rather than about
     * anything crossing it. Nothing does cross it: only DNS is routed here.
     *
     * **Excluding an app is what makes the VPN invisible to it**, not merely
     * unused by it. A disallowed app's default network is the underlying Wi-Fi
     * or mobile one, so `NET_CAPABILITY_NOT_VPN` is set for it and a check
     * through `ConnectivityManager` finds no VPN — which is why "split
     * tunnelling" is the fix every VPN offers for this error, and why it works
     * whether Android Auto is blocked by the tunnel or merely offended by it.
     * The residual risk is an app that looks for a `tun` interface instead,
     * which is process-independent and would see one anyway; if that is what
     * Android Auto does, no exclusion here can help it.
     *
     * A missing package is the ordinary case, not an error: the list is written
     * once for every phone, and most phones will not have all of it.
     */
    private fun excludePackagesTheTunnelBreaks(builder: Builder, packages: List<String>) {
        for (excluded in packages) {
            runCatching { builder.addDisallowedApplication(excluded) }
                .onSuccess { Log.i(TAG, "Outside the tunnel, and so unfiltered: $excluded") }
                .onFailure { Log.i(TAG, "Not installed, nothing to exclude: $excluded") }
        }
    }

    private fun runTunnelLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val read = input.read(buffer)
                if (read <= 0) continue

                val datagram = IpPacket.parseUdp(buffer, read) ?: continue
                if (datagram.destinationPort != DNS_PORT) continue

                val upstream = upstreamByFakeAddress[datagram.destination] ?: continue
                val request = datagram.copy()

                forwarders?.execute { handleQuery(request, upstream, output) }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Tunnel loop stopped", e)
        }
    }

    private fun handleQuery(
        request: IpPacket.UdpDatagram,
        upstream: InetAddress,
        output: FileOutputStream,
    ) {
        val query = request.payload
        val question = DnsMessage.parseQuestion(query)

        val response = if (question == null) {
            // Not something we can reason about; let the upstream decide.
            forward(query, upstream)
        } else {
            when (val decision = filter.decide(question)) {
                DnsFilter.Decision.Forward -> forward(query, upstream)

                // The encrypted upstream's own name, which cannot be resolved
                // through the encrypted channel it identifies.
                DnsFilter.Decision.Bootstrap -> forwardOverUdp(query, upstream)

                DnsFilter.Decision.Block -> DnsMessage.buildResponse(
                    query,
                    question,
                    rcode = DnsMessage.RCODE_NAME_ERROR,
                )

                DnsFilter.Decision.Empty -> DnsMessage.buildResponse(query, question)

                is DnsFilter.Decision.Redirect -> redirect(query, question, decision.target, upstream)
            }
        } ?: return

        val reply = IpPacket.buildUdpReply(request, response) ?: return
        synchronized(output) {
            runCatching { output.write(reply) }
                .onFailure { if (running) Log.e(TAG, "Could not write a DNS reply", it) }
        }
    }

    /**
     * Sends a query upstream: encrypted when an upstream is configured,
     * otherwise over plain UDP.
     *
     * An encrypted-transport failure falls back to UDP rather than failing the lookup. Losing the
     * encrypted hop is worse than nothing, but a device that cannot resolve
     * anything is worse still — and the fallback resolver is itself a filtering
     * one, so the failure mode is a narrower filter, not an open one.
     */
    private fun forward(query: ByteArray, upstream: InetAddress): ByteArray? {
        encryptedClient?.let { client ->
            client.query(query)?.let { return it }
            Log.w(TAG, "Falling back to plain DNS for this query")
        }
        return forwardOverUdp(query, upstream)
    }

    private fun forwardOverUdp(query: ByteArray, upstream: InetAddress): ByteArray? = try {
        DatagramSocket().use { socket ->
            // Excluding our own package from the tunnel already keeps this socket
            // off the interface; protect() makes that independent of whether the
            // exclusion could be applied.
            protect(socket)
            socket.soTimeout = UPSTREAM_TIMEOUT_MILLIS
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(upstream, DNS_PORT)))

            val buffer = ByteArray(MAX_DNS_RESPONSE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            buffer.copyOf(packet.length)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Upstream $upstream did not answer", e)
        null
    }

    /**
     * Answers with the addresses of [target] under the queried name — how safe
     * search is enforced. The CNAME is included alongside the addresses so that
     * clients which inspect the chain see a coherent answer.
     */
    private fun redirect(
        query: ByteArray,
        question: DnsMessage.Question,
        target: String,
        upstream: InetAddress,
    ): ByteArray? {
        val addresses = resolveTarget(target, upstream, question.type)
        if (addresses.isEmpty()) return forward(query, upstream)

        val answers = buildList {
            add(DnsMessage.Answer.canonicalName(question.name, target, REDIRECT_TTL_SECONDS))
            addresses.forEach { add(DnsMessage.Answer.address(target, it, REDIRECT_TTL_SECONDS)) }
        }
        return DnsMessage.buildResponse(query, question, answers = answers)
    }

    private fun resolveTarget(
        target: String,
        upstream: InetAddress,
        questionType: Int,
    ): List<InetAddress> {
        redirectCache[target]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAt) {
                return cached.addresses.filterByQuestionType(questionType)
            }
        }

        // Resolved through the platform resolver rather than by hand-building a
        // second query: this process is outside the tunnel, so the lookup does
        // not re-enter the filter.
        val resolved = runCatching { InetAddress.getAllByName(target).toList() }
            .onFailure { Log.w(TAG, "Could not resolve safe-search target $target", it) }
            .getOrDefault(emptyList())

        if (resolved.isNotEmpty()) {
            redirectCache[target] = CachedAddresses(
                addresses = resolved,
                expiresAt = System.currentTimeMillis() +
                    TimeUnit.SECONDS.toMillis(REDIRECT_TTL_SECONDS.toLong()),
            )
        }
        return resolved.filterByQuestionType(questionType)
    }

    private fun List<InetAddress>.filterByQuestionType(questionType: Int): List<InetAddress> =
        filter { address ->
            when (questionType) {
                DnsMessage.TYPE_A -> address is Inet4Address
                DnsMessage.TYPE_AAAA -> address is Inet6Address
                else -> true
            }
        }

    private data class CachedAddresses(val addresses: List<InetAddress>, val expiresAt: Long)

    private val redirectCache = java.util.concurrent.ConcurrentHashMap<String, CachedAddresses>()

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.filter_channel_name),
                    // LOW so the persistent notification is quiet, but it cannot be
                    // hidden entirely: Android requires a visible notification for
                    // an active VPN, which is a good thing here.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.filter_notification_title))
            .setContentText(getString(R.string.filter_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    companion object {
        private const val TAG = "DnsFilterService"

        const val ACTION_STOP = "app.drawbridge.dpc.STOP_FILTER"

        private const val CHANNEL_ID = "drawbridge-filter"
        private const val NOTIFICATION_ID = 1

        private const val MTU = 1500
        private const val DNS_PORT = 53
        private const val MAX_DNS_RESPONSE = 4096
        private const val UPSTREAM_TIMEOUT_MILLIS = 5_000
        private const val FORWARDER_THREADS = 8
        private const val REDIRECT_TTL_SECONDS = 300

        private const val TUNNEL_IPV4 = "10.111.222.1"
        private const val TUNNEL_IPV4_PREFIX = 24
        private const val TUNNEL_IPV4_PREFIX_STRING = "10.111.222."
        private const val TUNNEL_IPV6 = "fd00:d8ba:d8ba::1"
        private const val TUNNEL_IPV6_PREFIX = 120
        private const val TUNNEL_IPV6_PREFIX_STRING = "fd00:d8ba:d8ba::"

        private val DEFAULT_UPSTREAMS = listOf("185.228.168.168", "185.228.169.168")

        /**
         * Well-known encrypted-DNS endpoints that apps reach by hardcoded IP,
         * where a domain blocklist cannot help.
         */
        private val ENCRYPTED_DNS_BLACKHOLE = listOf(
            "8.8.8.8", "8.8.4.4", // Google
            "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.1.1.3", // Cloudflare
            "9.9.9.9", "149.112.112.112", // Quad9
            "94.140.14.14", "94.140.15.15", // AdGuard
            "208.67.222.222", "208.67.220.220", // OpenDNS
            "76.76.2.0", "76.76.10.0", // Control D
            "45.90.28.0", "45.90.30.0", // NextDNS
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2620:fe::fe", "2620:fe::9",
        )

        /** Reflects the live tunnel, for the status screen. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Starts the filter if VPN consent already exists.
         *
         * As Device Owner the consent dialog is skipped entirely — [prepare]
         * returns null because always-on VPN is configured by policy. On an
         * unprovisioned device the caller has to show the returned intent first.
         */
        fun requestStart(context: Context): Intent? {
            val consent = prepare(context)
            if (consent != null) return consent
            context.startForegroundService(Intent(context, DnsFilterService::class.java))
            return null
        }

        fun requestStop(context: Context) {
            context.startService(
                Intent(context, DnsFilterService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
