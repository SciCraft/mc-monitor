package net.scicraft.mc.monitor

import com.sun.tools.attach.VirtualMachine
import com.sun.tools.attach.VirtualMachineDescriptor
import com.sun.tools.attach.spi.AttachProvider
import io.prometheus.client.Collector
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.GaugeMetricFamily
import io.prometheus.client.exporter.HTTPServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.*
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import javax.management.ObjectName
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import kotlin.io.path.Path
import kotlin.io.path.name

val BASE_PATH: Path = Path.of(System.getProperty("user.home"))

fun main() {
    CollectorRegistry.defaultRegistry.register(McMonitorCollector)
    val server = HTTPServer.Builder().withHostname("::1").withPort(9200).build()
    println("Listening on port ${server.port}")
}

object McMonitorCollector : Collector() {
    override fun collect(): List<MetricFamilySamples> {
        val gaugeOnline = GaugeMetricFamily("net_minecraft_server_online", "Is the server online?", listOf("server"))
        val gaugeUptime = GaugeMetricFamily("net_minecraft_server_uptime", "Uptime of the server [s]", listOf("server"))
        val gaugeWorldSize = GaugeMetricFamily("net_minecraft_server_world_size", "Size of the world [bytes]", listOf("server"))
        val gaugeMsptAvg = GaugeMetricFamily("net_minecraft_server_mspt_avg", "Average MSPT [ms]", listOf("server"))
        val gaugeMsptMax = GaugeMetricFamily("net_minecraft_server_mspt_max", "Maximum MSPT [ms]", listOf("server"))
        val gaugeHeapUsed = GaugeMetricFamily("net_minecraft_server_heap_used", "Heap memory usage of the Minecraft server [bytes]", listOf("server"))
        val gaugeHeapMax = GaugeMetricFamily("net_minecraft_server_heap_max", "Maximum heap memory of the Minecraft server [bytes]", listOf("server"))
        val gaugeNonHeapUsed = GaugeMetricFamily("net_minecraft_server_non_heap_used", "Non-heap memory usage of the Minecraft server [bytes]", listOf("server"))
        val gaugePlayers = GaugeMetricFamily("net_minecraft_server_players", "Number of players on the server", listOf("server"))
        val gaugePlayersMax = GaugeMetricFamily("net_minecraft_server_players_max", "Maximum number of players on the server", listOf("server"))
        val gaugeProtocolVersion = GaugeMetricFamily("net_minecraft_server_protocol_version", "Protocol version", listOf("server"))
        val gaugePing = GaugeMetricFamily("net_minecraft_server_ping", "Ping of the server [ms]", listOf("server"))

        val statuses = queryServers()
        for ((server, status) in statuses) {
            gaugeOnline.addMetric(listOf(server), if (status is Online) 1.0 else 0.0)
            gaugeWorldSize.addMetric(listOf(server), status.data.worldSize.toDouble())
            val maxPlayers = status.data.properties.getProperty("max-players", "20").toInt()
            if (status is Online) {
                gaugeUptime.addMetric(listOf(server), status.startTime.until(Instant.now(), ChronoUnit.MILLIS).toDouble() / 1e3)
                if (status.jmxData != null) {
                    if (status.jmxData.msptAvg != null) gaugeMsptAvg.addMetric(listOf(server), status.jmxData.msptAvg)
                    if (status.jmxData.msptMax != null) gaugeMsptMax.addMetric(listOf(server), status.jmxData.msptMax)
                    gaugeHeapUsed.addMetric(listOf(server), status.jmxData.heapUsed.toDouble())
                    gaugeHeapMax.addMetric(listOf(server), status.jmxData.heapMax.toDouble())
                    gaugeNonHeapUsed.addMetric(listOf(server), status.jmxData.nonHeapUsed.toDouble())
                }
                if (status.pingData != null) {
                    gaugePlayers.addMetric(listOf(server), status.pingData.players.toDouble())
                    gaugePlayersMax.addMetric(listOf(server), status.pingData.playersMax.toDouble())
                    gaugeProtocolVersion.addMetric(listOf(server), status.pingData.protocolVersion.toDouble())
                    gaugePing.addMetric(listOf(server), status.pingData.pingTime.toNanos() / 1e6)
                }
            } else if (status is Offline) {
                gaugePlayersMax.addMetric(listOf(server), maxPlayers.toDouble())
            }
        }

        return listOf(
            gaugeOnline,
            gaugeUptime,
            gaugeWorldSize,
            gaugeMsptAvg,
            gaugeMsptMax,
            gaugeHeapUsed,
            gaugeHeapMax,
            gaugeNonHeapUsed,
            gaugePlayers,
            gaugePlayersMax,
            gaugeProtocolVersion,
            gaugePing,
        )
    }
}

fun getOfflineStatus(dir: Path): Offline? {
    val serverPropsPath = dir.resolve("server.properties")
    if (!Files.exists(serverPropsPath)) return null
    val props = Properties()
    Files.newInputStream(serverPropsPath).use { props.load(it) }
    val worldDir = dir.resolve(props.getProperty("level-name", "world"))
    val worldSize = getDiskUsage(worldDir)
    return Offline(ServerData(dir, props, worldSize))
}

data class OnlineDescriptor(val pid: Int, val cwd: Path, val vm: VirtualMachineDescriptor?)

fun getRunningServers(): Set<OnlineDescriptor> {
    val provider = AttachProvider.providers()[0]
    val servers = mutableSetOf<OnlineDescriptor>()
    val vms = VirtualMachine.list().associateBy { it.id().toInt() }
    Files.list(Path("/proc")).use {
        for (path in it) {
            val pid = path.name.toIntOrNull() ?: continue
            if (Files.getOwner(path).name != "minecraft") continue
            try {
                if (!Files.readSymbolicLink(path.resolve("exe")).name.startsWith("java")) continue
                val cwd = Files.readSymbolicLink(path.resolve("cwd"))
                if (!Files.exists(cwd.resolve("server.properties"))) continue
                servers.add(OnlineDescriptor(pid, cwd, vms[pid] ?: VirtualMachineDescriptor(provider, pid.toString())))
            } catch (_: AccessDeniedException) {}
        }
    }
    return servers
}

fun queryServers(): Map<String, ServerStatus> {
    val statuses = sortedMapOf<Path, ServerStatus>()
    Files.list(BASE_PATH).use { dirStream ->
        for (dir in dirStream) {
            statuses[dir] = getOfflineStatus(dir) ?: continue
        }
    }
    for ((pid, cwd, vm) in getRunningServers()) {
        if (cwd !in statuses) statuses[cwd] = getOfflineStatus(cwd) ?: continue
        val data = statuses[cwd]!!.data
        val jmxData = vm?.let {
            try {
                getOnlineServerStatus(vm)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        val startTime = Files.getLastModifiedTime(Path.of("/proc/$pid")).toInstant()
        val port = data.properties.getProperty("server-port", "25565").toInt()
        val pingData = try {
            pingServer(InetSocketAddress("localhost", port))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        statuses[cwd] = Online(data, pid, startTime, jmxData, pingData)
    }
    return statuses.mapKeys { it.key.fileName.toString() }
}

fun getDiskUsage(path: Path): Long {
    if (!Files.exists(path)) return 0
    var size = 0L
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            size += attrs.size()
            return FileVisitResult.CONTINUE
        }
    })
    return size
}

inline fun <R> VirtualMachine.use(block: (VirtualMachine) -> R): R {
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        when (exception) {
            null -> detach()
            else -> try {
                detach()
            } catch (closeException: Throwable) {
                exception.addSuppressed(closeException)
            }
        }
    }
}

fun getOnlineServerStatus(descr: VirtualMachineDescriptor) = VirtualMachine.attach(descr).use { vm ->
    JMXConnectorFactory.connect(JMXServiceURL(vm.startLocalManagementAgent())).use {
        val mbeanConnection = it.mBeanServerConnection
        val tickTimes = if ("net.minecraft.server" in mbeanConnection.domains) {
            mbeanConnection.getAttribute(ObjectName("net.minecraft.server:type=Server"), "tickTimes") as LongArray
        } else {
            LongArray(0)
        }
        val memoryBean = ManagementFactory.newPlatformMXBeanProxy(mbeanConnection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean::class.java)
        val avgMspt = if (tickTimes.isEmpty()) null else tickTimes.sum() / (1e6 * tickTimes.size)
        val maxMspt = tickTimes.maxOrNull()?.div(1e6)
        JMXData(avgMspt, maxMspt, memoryBean.heapMemoryUsage.used, memoryBean.heapMemoryUsage.max, memoryBean.nonHeapMemoryUsage.used)
    }
}

val REQUEST_PACKET = byteArrayOf(
    6, // length
    0, // id=handshake
    0, // version=0
    0, // address=''
    0, 0, // port=0
    1, // next_state=status
    1, // length
    0, // id=status_request
)

val PING_PACKET = byteArrayOf(
    9, // length
    1, // id=ping
    0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), // payload=0x012345678abcdef
)

fun DataInputStream.readVarInt(): Int {
    var value = 0
    var shift = 0
    while (true) {
        val b = readByte().toInt()
        value = value or ((b and 0x7f) shl shift)
        if (b and 0x80 == 0) {
            return value
        }
        shift += 7
    }
}

fun pingServer(addr: InetSocketAddress) = Socket().use { socket ->
    socket.connect(addr, 1000)
    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    val din = DataInputStream(BufferedInputStream(socket.getInputStream()))
    out.write(REQUEST_PACKET)
    out.flush()
    val json = run {
        din.readVarInt()
        val id = din.readVarInt()
        if (id != 0) throw IOException("Unexpected packet id $id")
        val jsonSize = din.readVarInt()
        Json.parseToJsonElement(din.readNBytes(jsonSize).decodeToString()).jsonObject
    }
    out.write(PING_PACKET)
    out.flush()
    val pingSent = System.nanoTime()
    val pingTime = run {
        din.readVarInt()
        val pongReceived = System.nanoTime()
        val id = din.readVarInt()
        if (id != 1) throw IOException("Unexpected packet id $id")
        val payload = din.readLong()
        if (payload != 0x0123456789abcdef) throw IOException("Unexpected payload 0x${payload.toString(16)}")
        pongReceived - pingSent
    }
    PingData(
        Duration.of(pingTime, ChronoUnit.NANOS),
        json["version"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        json["version"]!!.jsonObject["protocol"]!!.jsonPrimitive.int,
        json["players"]!!.jsonObject["online"]!!.jsonPrimitive.int,
        json["players"]!!.jsonObject["max"]!!.jsonPrimitive.int,
    )
}

interface ServerStatus {
    val data: ServerData
}
data class Online(override val data: ServerData, val pid: Int, val startTime: Instant, val jmxData: JMXData?, val pingData: PingData?) :
    ServerStatus
data class Offline(override val data: ServerData) : ServerStatus
data class ServerData(val cwd: Path, val properties: Properties, val worldSize: Long)
data class JMXData(val msptAvg: Double?, val msptMax: Double?, val heapUsed: Long, val heapMax: Long, val nonHeapUsed: Long)
data class PingData(val pingTime: Duration, val version: String, val protocolVersion: Int, val players: Int, val playersMax: Int)