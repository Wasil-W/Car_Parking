package dev.wasil.permit.parking

import dev.wasil.permit.data.store.normalizePlate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The cars this app knows about, and which one this phone drives.
 *
 * This replaced `enum class MyCar { WASIL, WALID }`. The rename is the whole
 * change — see `docs/USER-MODEL.md`, "What 'user' turns out to mean in this
 * app": the app has no users. It has **cars** and **phones**, bound by
 * Bluetooth, and `MyCar.WASIL` was already a car with a name on it rather than
 * a person. So the job was never "add users", it was "stop hard-coding two car
 * names".
 *
 * Nothing on screen changes. Every two-car behaviour — the single hand-over
 * button, the two-arc mark, the blue/terracotta pairing — is now selected on
 * [Roster.size] rather than on an enum. Those are not decoration: with two cars
 * and one permit the permit can only move to one place, which is why one button
 * is right. They are the **arity-two rendering** of a general app.
 */
@JvmInline
@Serializable(with = VehicleIdSerializer::class)
value class VehicleId(val value: String)

/**
 * Reads an id back, and lowercases it on the way in.
 *
 * That single `lowercase()` is the whole park-log migration. Records written
 * before this refactor stored a `MyCar`, which serialised as `"WASIL"` and
 * `"WALID"`; ids are the lowercase form of exactly those strings, because that
 * is what `MyCar.key()` already produced for the shared room. Without this, a
 * history built up over months would keep its rows but lose every "Permit ·
 * Wasil" badge to an id that matches no car — quietly wrong, which is the one
 * thing a log must not be.
 */
object VehicleIdSerializer : KSerializer<VehicleId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.wasil.permit.parking.VehicleId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: VehicleId) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): VehicleId =
        VehicleId(decoder.decodeString().lowercase())
}

/**
 * One car.
 *
 * [id] is stable and deliberately **not** the plate: plates change on a permit
 * (Amsterdam lets you change one from the council's own portal) and the car
 * does not. It is also the key both phones write their shared state under, so
 * it has to be something two phones derive identically from one permit account
 * without talking to each other — see [slotIdFor].
 *
 * [name] is display only. It is seeded to "Wasil"/"Walid" because those are the
 * two cars this app has, and because a stored name that nothing can edit yet is
 * still better than a name compiled into thirty files.
 *
 * **No Bluetooth field, deliberately**, though `USER-MODEL.md` sketches one.
 * The MAC is a fact about *this phone* and the stereo it rides with: the other
 * brother's phone pairs with his own car and neither phone ever learns the
 * other's device. Hanging it on a roster entry that both phones rebuild from
 * one shared permit account would imply it travels, and a rebuild triggered by
 * a plate change would silently drop the pairing that makes detection work at
 * all. It stays on [ParkStateStore.carMac], device-local, where it already is.
 * Moving it is dimension 2(b) of `USER-MODEL.md` — one phone driving several
 * cars — which is not being built.
 */
@Serializable
data class Vehicle(
    val id: VehicleId,
    /** Normalised, as the permit site wants it. Blank until an account has said. */
    val plate: String = "",
    val name: String,
)

/**
 * Which cars exist. Never empty.
 *
 * Non-empty by construction because the exhaustiveness a `when (car)` used to
 * give away for free is genuinely lost here, and `USER-MODEL.md` asks for it
 * back in the one form still available: a type that cannot be empty, and one
 * function that resolves an id or fails loudly rather than `firstOrNull`
 * scattered through the app.
 */
@JvmInline
value class Roster private constructor(val vehicles: List<Vehicle>) {

    val size: Int get() = vehicles.size

    operator fun get(index: Int): Vehicle = vehicles[index]

    fun byId(id: VehicleId?): Vehicle? = vehicles.firstOrNull { it.id == id }

    /** The vehicle this id names, or a loud failure. Never a silent wrong car. */
    fun require(id: VehicleId?): Vehicle =
        byId(id) ?: error("no vehicle $id in roster ${vehicles.map { it.id.value }}")

    /**
     * The other car, when there is exactly one other car.
     *
     * Null at any other arity, and that is the point: "hand it over" has no
     * answer with three cars, so the caller is forced to notice rather than
     * being handed an arbitrary one.
     */
    fun other(id: VehicleId?): Vehicle? {
        if (size != PAIR) return null
        val mine = byId(id) ?: return null
        return vehicles.first { it.id != mine.id }
    }

    /**
     * Which identity colour this car gets, or null for none.
     *
     * Identity by hue is a **two-body affordance**. Two identity colours exist
     * because there are two identities and one contested resource; the palette
     * already spends green on `fine`, rust on `alert`, two neutrals on zones, a
     * grey-blue on tariff boundaries and a teal on the walk route, so there is
     * no third safe hue — and even if there were, nobody remembers that the
     * third car is the teal one. Past two, identity is carried by plate and
     * name in neutrals, which is what a null here selects.
     */
    fun identitySlotOf(id: VehicleId?): Int? {
        if (size > PAIR) return null
        return vehicles.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    /** Which car the permit is sitting on, matched by plate rather than by label text. */
    fun holderFor(activeVrn: String?): Vehicle? {
        val vrn = activeVrn?.takeIf { it.isNotBlank() } ?: return null
        return vehicles.firstOrNull { it.plate.isNotBlank() && it.plate == vrn }
    }

    /** How many cars actually have a plate on them — i.e. how many the permit can reach. */
    val platedCount: Int get() = vehicles.count { it.plate.isNotBlank() }

    companion object {
        /** The arity every two-car behaviour in this app is the correct rendering of. */
        const val PAIR = 2

        /**
         * The roster an install starts with, and the one an install with no
         * permit account keeps.
         *
         * The two names are **defaults**, not a model of two people. They are
         * here rather than compiled into `MainScreen`, `SetupFlow`,
         * `ParkNotifications` and `HandoffColors`, which is the whole of what
         * "stop hard-coding two car names" asks for at this size.
         */
        val SEED: Roster get() = Roster(seedVehicles)

        fun of(vehicles: List<Vehicle>): Roster {
            val unique = vehicles.distinctBy { it.id }
            return Roster(if (unique.isEmpty()) seedVehicles else unique)
        }

        private val seedVehicles = listOf(
            Vehicle(slotIdFor(0), name = slotNameFor(0, "")),
            Vehicle(slotIdFor(1), name = slotNameFor(1, "")),
        )
    }
}

/**
 * The id of the nth slot.
 *
 * The first two are the strings the old `MyCar.key()` produced, and that is not
 * nostalgia: they are the Firebase node keys every existing pairing is already
 * written under, and both brothers' permit state lives at
 * `/rooms/<hash>/wasil` and `/rooms/<hash>/walid`. Changing them would orphan
 * both phones' state at once, which is exactly the migration this refactor is
 * not allowed to break. They are opaque wire keys; [Vehicle.name] is what a
 * person reads.
 */
fun slotIdFor(index: Int): VehicleId = when (index) {
    0 -> VehicleId("wasil")
    1 -> VehicleId("walid")
    else -> VehicleId("car${index + 1}")
}

/** The inverse of [slotIdFor]. Unknown ids sort last rather than throwing. */
fun slotIndexOf(id: VehicleId): Int = when (id.value) {
    "wasil" -> 0
    "walid" -> 1
    else -> id.value.removePrefix("car").toIntOrNull()?.minus(1) ?: Int.MAX_VALUE
}

/** The seeded display name for a slot. Past the pair, a car is its plate. */
fun slotNameFor(index: Int, plate: String): String = when (index) {
    0 -> "Wasil"
    1 -> "Walid"
    else -> plate.ifBlank { "Car ${index + 1}" }
}

/**
 * The roster the permit account implies, folded onto the one already stored.
 *
 * The account is the roster's real source — `PermitRepository.plates()` has
 * been reading it since v0.1 — so this is where setup's plate picker lands
 * rather than in a second hand-typed path.
 *
 * Two properties it has to have, and both are about two phones agreeing without
 * talking to each other:
 *
 * - **A plate keeps the slot it already had.** Otherwise re-saving the permit
 *   could move a car from slot 0 to slot 1, which swaps its identity colour on
 *   one phone and silently redirects its Firebase node.
 * - **New plates are assigned in sorted order, never in "mine first" order.**
 *   Both phones read the same account and must land on the same slots; ordering
 *   by whose car it is would give Wasil's phone one roster and Walid's the
 *   mirror image, and every shared write would go to the wrong node.
 *
 * An empty list leaves [existing] alone: an account that answered with nothing
 * is a failure to read, not a statement that the cars are gone.
 */
fun rosterFrom(plates: List<String>, existing: Roster = Roster.SEED): Roster {
    val clean = plates.map(::normalizePlate).filter { it.isNotBlank() }.distinct()
    if (clean.isEmpty()) return existing

    val known = existing.vehicles.filter { it.plate.isNotBlank() }.associateBy { it.plate }
    val taken = clean.mapNotNull { known[it]?.id }.toMutableSet()
    var probe = 0
    fun claimFreeSlot(): Int {
        while (slotIdFor(probe) in taken) probe++
        taken += slotIdFor(probe)
        return probe
    }

    val assigned = clean.sorted().map { plate ->
        known[plate] ?: claimFreeSlot().let { slot ->
            Vehicle(slotIdFor(slot), plate, slotNameFor(slot, plate))
        }
    }
    // Identity order is slot order, so slot 0 is slot 0 on both phones.
    return Roster.of(assigned.sortedBy { slotIndexOf(it.id) })
}

/**
 * The roster an install from before this refactor implies.
 *
 * Order is taken verbatim from the two stored plates rather than sorted,
 * because the existing Firebase nodes and the existing identity colours are
 * already laid out that way and a migration that reshuffles them is a migration
 * that loses the pairing.
 */
fun legacyRoster(wasilPlate: String, walidPlate: String): Roster = Roster.of(
    listOf(
        Vehicle(slotIdFor(0), normalizePlate(wasilPlate), slotNameFor(0, wasilPlate)),
        Vehicle(slotIdFor(1), normalizePlate(walidPlate), slotNameFor(1, walidPlate)),
    ),
)
