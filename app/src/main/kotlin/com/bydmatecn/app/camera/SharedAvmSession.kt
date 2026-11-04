package com.bydmatecn.app.camera

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One AVM handle shared by every in-process camera consumer. */
data class AvmSurfaceBinding(val index: Int, val surface: Surface)

/** Source parameters are shared by every Surface attached to one vendor AVM handle. */
data class AvmSourceConfig(val width: Int, val height: Int, val fps: Int) {
    init {
        require(width > 0 && height > 0 && fps > 0)
    }
}

enum class AvmConsumerPriority { BACKGROUND, INTERACTIVE }

/**
 * Process-wide AVM subscription registry.
 *
 * The vendor stack exposes one handle but accepts several preview surfaces. Consumers register
 * their surfaces here instead of opening competing [AvmCameraProbe] instances. A changed set is
 * rebuilt under one lock. Warm attachment is attempted before any existing binding is removed, so
 * a rejected additional Surface cannot blank consumers that are already receiving frames. Consumers
 * that cannot attach can still consume [AvmFrameShare] without taking ownership away from others.
 */
@Singleton
class SharedAvmSession @Inject constructor() {
    private data class Request(
        val bindings: List<AvmSurfaceBinding>,
        val priority: AvmConsumerPriority,
        val sourceConfig: AvmSourceConfig?,
    )

    private val probe = AvmCameraProbe()
    private val requests = linkedMapOf<String, Request>()
    private var activeRequests = emptyMap<String, Request>()
    private val activeOwners: Set<String> get() = activeRequests.keys

    val log: StateFlow<List<String>> get() = probe.log
    val cameraId: Int get() = probe.cameraId
    val lastDiscoverAt: Long get() = probe.lastDiscoverAt
    val discoverJournal: List<String> get() = probe.discoverJournal

    @Synchronized
    fun discover(allowedTags: Set<String>? = null): Boolean = probe.discover(allowedTags)

    /**
     * Source profile consumers must use for their output buffers.
     *
     * Once a producer is running its dimensions are authoritative: creating an ImageReader with a
     * newly discovered/default size while the electronic mirror is already producing another size
     * yields an accepted Surface that never receives usable frames on some firmware.
     */
    @Synchronized
    fun preferredSourceConfig(): AvmSourceConfig =
        probe.activeSourceConfig ?: probe.discoveredSourceConfig

    @Synchronized
    fun attach(
        owner: String,
        bindings: List<AvmSurfaceBinding>,
        priority: AvmConsumerPriority,
        sourceConfig: AvmSourceConfig? = null,
    ): Boolean {
        require(bindings.isNotEmpty())
        val request = Request(bindings.toList(), priority, sourceConfig)
        val active = activeRequests[owner]
        if (active.sameBindings(request) && probe.isOpen) {
            // A previous warm update may have left a deferred desired request in [requests].
            // Re-selecting the live binding must also discard that stale desired Surface.
            requests[owner] = request
            return true
        }
        requests[owner] = request
        if (rebuild("attach $owner")) return owner in activeOwners

        // A failed warm add is rolled back inside AvmCameraProbe. Keep the known-good bindings and
        // retain this registration for a later retry; never close/suspend another consumer merely
        // to make room for the new one.
        probe.append(
            "shared AVM attach deferred owner=$owner; registered=${requests.keys}; " +
                "active=$activeOwners; config=${probe.activeSourceConfig}",
        )
        return false
    }

    @Synchronized
    fun detach(owner: String, reason: String) {
        if (requests.remove(owner) == null) return
        if (owner !in activeOwners && probe.isOpen && requests.keys == activeOwners) return
        rebuild("detach $owner: $reason")
    }

    @Synchronized
    fun isActive(owner: String): Boolean = owner in activeOwners && probe.isOpen

    @Synchronized
    fun hasRegistration(owner: String): Boolean = owner in requests

    @Synchronized
    fun isBindingActive(owner: String, binding: AvmSurfaceBinding): Boolean =
        probe.isOpen && activeRequests[owner]?.bindings.orEmpty().any { it.sameSurface(binding) }


    /** Compact state for file logs and real-vehicle diagnostics; contains no Surface identities. */
    @Synchronized
    fun diagnosticSnapshot(): String {
        val registered = requests.mapValues { (_, request) ->
            request.bindings.map { it.index }
        }
        val active = activeRequests.mapValues { (_, request) ->
            request.bindings.map { it.index }
        }
        return "camera=$cameraId open=${probe.isOpen} config=${probe.activeSourceConfig} " +
            "registered=$registered active=$active"
    }

    private fun rebuild(reason: String): Boolean {
        if (requests.isEmpty()) {
            probe.close(reason)
            activeRequests = emptyMap()
            return true
        }
        return openRequests(requests, reason)
    }

    private fun openRequests(selected: Map<String, Request>, reason: String): Boolean {
        if (probe.cameraId < 0 && !probe.discover()) return false
        val sourceConfig = resolveSourceConfig(selected)
        val activeConfig = probe.activeSourceConfig
        if (probe.isOpen && activeConfig != null &&
            activeConfig.width == sourceConfig.width && activeConfig.height == sourceConfig.height
        ) {
            if (activeConfig.fps != sourceConfig.fps && !probe.updateActiveFps(sourceConfig.fps)) {
                // Some firmware accepts additional preview surfaces but refuses a warm FPS change.
                // Sharing the existing producer is still preferable to rejecting or reopening it.
                probe.append(
                    "shared AVM keeps current fps=${activeConfig.fps}; requested=${sourceConfig.fps}; " +
                        "continue warm surface update",
                )
            }
            if (updateWarmBindings(selected)) return true
            // updateWarmBindings rolls back failed additions. Do not close a known-good producer.
            probe.append("shared AVM warm binding update rejected; keep active=$activeOwners")
            return false
        }

        probe.close("shared AVM rebuild: $reason")
        activeRequests = emptyMap()
        // Bind background first and interactive last. addPreviewSurface preserves every binding;
        // setPreviewSurface is used only as a one-surface compatibility fallback by the probe.
        val bindings = orderedBindings(selected)
        val opened = probe.openWarmBindings(bindings, sourceConfig)
        if (opened) activeRequests = selected.toMap()
        return opened
    }

    /**
     * Changes a warm source without blanking surfaces that remain registered.
     *
     * Attach first and detach second. If a replacement Surface is rejected, the old Surface is
     * therefore still live and [openRequests] can rebuild from a known-good handle. Bindings are
     * compared by Surface identity: two owners may deliberately share one physical output, and
     * detaching either owner must not remove an output still referenced by the other.
     */
    private fun updateWarmBindings(selected: Map<String, Request>): Boolean {
        val current = uniqueBindings(activeRequests)
        val target = uniqueBindings(selected)
        val added = target.filterNot { candidate -> current.any { it.sameSurface(candidate) } }
        val removed = current.filterNot { candidate -> target.any { it.sameSurface(candidate) } }

        if (!probe.updateBindings(added, removed)) return false
        activeRequests = selected.toMap()
        return true
    }

    private fun orderedBindings(selected: Map<String, Request>): List<AvmSurfaceBinding> =
        uniqueBindings(selected)

    /**
     * One AVM producer has one source size, while all consumers can request their own output Surface.
     * Keep an already-running size stable (avoids blanking the mirror) and use the highest requested
     * FPS, matching the vendor registry behaviour. Before first open, the highest-priority explicit
     * size wins; conflicting lower-priority sizes inherit it.
     */
    private fun resolveSourceConfig(selected: Map<String, Request>): AvmSourceConfig {
        val explicit = selected.values.mapNotNull { it.sourceConfig }
        val preferred = probe.activeSourceConfig ?: selected.values
            .sortedByDescending { it.priority.ordinal }
            .firstNotNullOfOrNull { it.sourceConfig } ?: probe.discoveredSourceConfig
        val sizes = explicit.map { it.width to it.height }.distinct()
        if (sizes.size > 1) {
            probe.append(
                "shared AVM source sizes conflict=${sizes.joinToString()}; " +
                    "reuse=${preferred.width}x${preferred.height}",
            )
        }
        val fps = (explicit.map { it.fps } + preferred.fps).maxOrNull()!!.coerceIn(1, 60)
        return AvmSourceConfig(preferred.width, preferred.height, fps)
    }

    private fun uniqueBindings(selected: Map<String, Request>): List<AvmSurfaceBinding> {
        val result = mutableListOf<AvmSurfaceBinding>()
        selected.values.sortedBy { it.priority.ordinal }.forEach { request ->
            request.bindings.forEach { binding ->
                if (result.none { it.sameSurface(binding) }) result += binding
            }
        }
        return result
    }

    private fun AvmSurfaceBinding.sameSurface(other: AvmSurfaceBinding): Boolean =
        index == other.index && surface === other.surface

    private fun Request?.sameBindings(other: Request): Boolean {
        if (this == null || priority != other.priority || sourceConfig != other.sourceConfig ||
            bindings.size != other.bindings.size
        ) return false
        return bindings.indices.all {
            bindings[it].index == other.bindings[it].index &&
                bindings[it].surface === other.bindings[it].surface
        }
    }
}
