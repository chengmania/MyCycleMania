package com.kc3smw.cyclemania

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.Profile
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.routing.ev.VehicleSpeed
import com.graphhopper.util.Instruction
import com.graphhopper.util.Parameters
import com.graphhopper.util.shapes.GHPoint
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.Locale

data class TurnInstruction(
    val sign: Int,
    val streetName: String,
    val distanceMeters: Double,
    val lat: Double,
    val lon: Double
)

class NavigationManager(private val context: Context) {

    private var hopper: GraphHopper? = null
    var instructions: List<TurnInstruction> = emptyList()
        private set
    private var currentInstructionIndex = 0
    private var lastAnnouncedBucket = -1
    private var lastAnnouncedInstructionIndex = -1
    var routePoints: List<GeoPoint> = emptyList()
        private set
    var routeDistanceMeters: Double = 0.0
        private set
    var routeTimeSeconds: Double = 0.0
        private set

    @Volatile var isInitializingNow: Boolean = false
        private set

    private data class RouteResult(
        val instructions: List<TurnInstruction>,
        val routePoints: List<GeoPoint>,
        val distanceMeters: Double,
        val timeSeconds: Double
    )

    fun isInitialized() = hopper != null
    fun hasPbfFile(): Boolean = findPbfFile() != null

    private val initMutex = Mutex()

    suspend fun initialize(): Boolean = initMutex.withLock {
        if (hopper != null) return@withLock true
        isInitializingNow = true
        val result = withContext(Dispatchers.IO) {
            val pbfFile = findPbfFile() ?: return@withContext false
            val graphDir = File(context.getExternalFilesDir("routing"), "graph_cache")
            File(graphDir, "gh.lock").delete()
            if (tryGraphHopperInit(pbfFile, graphDir)) return@withContext true
            // First attempt failed — cache may be corrupt from a previous interrupted import.
            // Clear it and retry once so the user doesn't have to re-download the PBF.
            Log.w("NavigationManager", "Retrying after clearing graph cache")
            graphDir.deleteRecursively()
            tryGraphHopperInit(pbfFile, graphDir)
        }
        isInitializingNow = false
        result
    }

    private fun tryGraphHopperInit(pbfFile: File, graphDir: File): Boolean {
        return try {
            // CustomProfile/CustomModel use Janino runtime bytecode compilation which
            // Android ART rejects. Use standard profiles + a custom WeightingFactory
            // (BikeQuietWeighting) that reads road_class encoded values at routing time.
            val fastest = Profile("bike_fastest").setVehicle("bike").setWeighting("fastest")
            val quiet   = Profile("bike_quiet").setVehicle("bike").setWeighting("bike_quiet")
            val config = GraphHopperConfig()
            config.setProfiles(listOf(fastest, quiet))
            config.putObject("datareader.file", pbfFile.absolutePath)
            config.putObject("graph.location", graphDir.absolutePath)
            config.putObject("graph.dataaccess", "MMAP")
            config.putObject("import.osm.ignored_highways", "")
            Log.i("NavigationManager", "Starting GraphHopper init from ${pbfFile.name} into ${graphDir.absolutePath}")
            val gh = CycleManiaGraphHopper()
            gh.init(config)
            Log.i("NavigationManager", "gh.init done, calling importOrLoad")
            gh.importOrLoad()
            Log.i("NavigationManager", "importOrLoad complete — routing ready")
            hopper = gh
            true
        } catch (t: Throwable) {
            Log.e("NavigationManager", "GraphHopper init failed", t)
            false
        }
    }

    // Maps the routing_mode preference to a registered profile name.
    // "fastest"          → bike_fastest (standard GH fastest weighting for bike)
    // "balanced"/"safest"→ bike_quiet   (BikeQuietWeighting — penalises major roads)
    private fun profileForMode(): String = when (routingMode()) {
        "balanced", "safest" -> "bike_quiet"
        else -> "bike_fastest"
    }

    // GraphHopper subclass that plugs in BikeQuietWeighting without Janino.
    // createWeighting() is final in GH 7.0, but createWeightingFactory() is protected.
    private class CycleManiaGraphHopper : GraphHopper() {
        override fun createWeightingFactory(): WeightingFactory {
            val base = super.createWeightingFactory()
            return WeightingFactory { profile, hints, disableTurnCosts ->
                if (profile.weighting == "bike_quiet") {
                    val em = getEncodingManager()
                    BikeQuietWeighting(
                        em.getBooleanEncodedValue(VehicleAccess.key("bike")),
                        em.getDecimalEncodedValue(VehicleSpeed.key("bike")),
                        em.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
                    )
                } else {
                    base.createWeighting(profile, hints, disableTurnCosts)
                }
            }
        }
    }

    // Route computation runs on IO; state is assigned on the calling (main) thread
    // so checkAndAnnounce never races with a concurrent route update.
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Boolean {
        val result = withContext(Dispatchers.IO) {
            val gh = hopper ?: return@withContext null
            try {
                val req = GHRequest(fromLat, fromLon, toLat, toLon)
                    .setProfile(profileForMode())
                    .setLocale(Locale.getDefault())
                req.hints.putObject(Parameters.Routing.INSTRUCTIONS, true)
                val rsp: GHResponse = gh.route(req)
                if (rsp.hasErrors()) {
                    Log.e("NavigationManager", "Route errors: ${rsp.errors}")
                    return@withContext null
                }
                val path = rsp.best
                RouteResult(
                    instructions = path.instructions.map { instr ->
                        val pts = instr.points
                        val lat = if (pts.size() > 0) pts.getLat(0) else 0.0
                        val lon = if (pts.size() > 0) pts.getLon(0) else 0.0
                        TurnInstruction(
                            sign = instr.sign,
                            streetName = instr.name ?: "",
                            distanceMeters = instr.distance,
                            lat = lat,
                            lon = lon
                        )
                    },
                    routePoints = path.points.map { GeoPoint(it.lat, it.lon) },
                    distanceMeters = path.distance,
                    timeSeconds = path.time / 1000.0
                )
            } catch (t: Throwable) {
                Log.e("NavigationManager", "Route failed", t)
                null
            }
        } ?: return false
        applyRouteResult(result)
        return true
    }

    fun checkAndAnnounce(currentLat: Double, currentLon: Double, tts: TtsManager) {
        // Snapshot both fields so a concurrent route update can't cause an
        // index-out-of-bounds between the size check and the list access.
        val instrs = instructions
        val idx = currentInstructionIndex
        if (instrs.isEmpty()) return
        if (idx >= instrs.size) return   // was size-1: FINISH instruction was never reached
        val next = instrs[idx]
        val distToTurn = RideRecorder.haversineMeters(
            currentLat, currentLon, next.lat, next.lon
        )
        val bucket = when {
            distToTurn < 20 -> 0
            distToTurn < 60 -> 1
            distToTurn < 220 -> 2
            else -> return
        }
        if (bucket == lastAnnouncedBucket && idx == lastAnnouncedInstructionIndex) return

        if (bucket == 0) {
            announce(tts, next, 0.0)
            currentInstructionIndex++
            lastAnnouncedBucket = -1
            lastAnnouncedInstructionIndex = -1
        } else {
            announce(tts, next, distToTurn)
            lastAnnouncedBucket = bucket
            lastAnnouncedInstructionIndex = idx
        }
    }

    private fun announce(tts: TtsManager, instr: TurnInstruction, dist: Double) {
        val direction = when (instr.sign) {
            Instruction.TURN_LEFT, Instruction.TURN_SHARP_LEFT, Instruction.TURN_SLIGHT_LEFT -> "turn left"
            Instruction.TURN_RIGHT, Instruction.TURN_SHARP_RIGHT, Instruction.TURN_SLIGHT_RIGHT -> "turn right"
            Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT -> "make a U-turn"
            Instruction.CONTINUE_ON_STREET -> return
            Instruction.FINISH -> { tts.speak("You have arrived at your destination."); return }
            else -> return
        }
        val street = if (instr.streetName.isNotBlank()) " onto ${instr.streetName}" else ""
        val text = if (dist < 20) "$direction$street" else "In ${dist.toInt()} meters, $direction$street"
        tts.speak(text)
    }

    suspend fun routeWaypoints(waypoints: List<GeoPoint>): Boolean {
        if (waypoints.size < 2) return false
        val result = withContext(Dispatchers.IO) {
            val gh = hopper ?: return@withContext null
            try {
                val ghPoints = waypoints.map { GHPoint(it.latitude, it.longitude) }
                val req = GHRequest(ghPoints)
                    .setProfile(profileForMode())
                    .setLocale(Locale.getDefault())
                req.hints.putObject(Parameters.Routing.INSTRUCTIONS, true)
                val rsp: GHResponse = gh.route(req)
                if (rsp.hasErrors()) {
                    Log.e("NavigationManager", "Waypoint route errors: ${rsp.errors}")
                    return@withContext null
                }
                val path = rsp.best
                RouteResult(
                    instructions = path.instructions.map { instr ->
                        val pts = instr.points
                        val lat = if (pts.size() > 0) pts.getLat(0) else 0.0
                        val lon = if (pts.size() > 0) pts.getLon(0) else 0.0
                        TurnInstruction(
                            sign = instr.sign,
                            streetName = instr.name ?: "",
                            distanceMeters = instr.distance,
                            lat = lat,
                            lon = lon
                        )
                    },
                    routePoints = path.points.map { GeoPoint(it.lat, it.lon) },
                    distanceMeters = path.distance,
                    timeSeconds = path.time / 1000.0
                )
            } catch (e: Exception) {
                Log.e("NavigationManager", "Multi-waypoint route failed", e)
                null
            }
        } ?: return false
        applyRouteResult(result)
        return true
    }

    private fun applyRouteResult(result: RouteResult) {
        routeDistanceMeters = result.distanceMeters
        routeTimeSeconds = result.timeSeconds
        routePoints = result.routePoints
        instructions = result.instructions
        currentInstructionIndex = 0
        lastAnnouncedBucket = -1
        lastAnnouncedInstructionIndex = -1
    }

    fun clearRoute() {
        instructions = emptyList()
        routePoints = emptyList()
        routeDistanceMeters = 0.0
        routeTimeSeconds = 0.0
        currentInstructionIndex = 0
        lastAnnouncedBucket = -1
        lastAnnouncedInstructionIndex = -1
    }

    private fun routingMode(): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString("routing_mode", "fastest") ?: "fastest"

    private fun findPbfFile(): File? {
        val dir = context.getExternalFilesDir("routing") ?: return null
        return dir.listFiles()?.firstOrNull { it.extension.lowercase() == "pbf" }
    }

    // Run a short silent route to trigger JIT compilation so all future routes are instant.
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val gh = hopper ?: return@withContext
        try {
            // Lansdale → North Wales, ~2 miles apart in Montgomery County PA
            val req = GHRequest(40.2415, -75.2835, 40.2115, -75.2718).setProfile("bike_fastest")
            gh.route(req)
            Log.i("NavigationManager", "Routing warm-up complete")
        } catch (t: Throwable) {
            Log.w("NavigationManager", "Warm-up route failed (non-fatal)", t)
        }
    }

    fun shutdown() {
        hopper?.close()
        hopper = null
    }
}
