package app.voidmaps.car

import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Lane as CarLane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import app.voidmaps.data.LaneDir
import app.voidmaps.data.ManeuverKind
import app.voidmaps.nav.NavState
import java.util.TimeZone

/**
 * Turn-by-turn data for the car's own displays: Android Auto passes the [Trip] on to the
 * instrument cluster and the head-up display of cars that support it.
 */
object CarTrip {
    fun build(s: NavState): Trip {
        val b = Trip.Builder()
        if (s.rerouting) return b.setLoading(true).build()
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val step = if (s.turnAround) {
            Step.Builder("Bitte wenden").setManeuver(maneuver(ManeuverKind.UTURN, null)).build()
        } else {
            val m = s.route.maneuvers.getOrNull(s.next)
            if (m != null) {
                val cue = m.street ?: m.text.trimEnd('.').ifBlank { s.destination.name }
                val sb = Step.Builder(cue).setManeuver(maneuver(m.kind, m.exit))
                m.street?.let { sb.setRoad(it) }
                s.lanes?.let { section ->
                    runCatching {
                        section.lanes.forEach { lane ->
                            val lb = CarLane.Builder()
                            lane.directions.forEach { d ->
                                lb.addDirection(LaneDirection.create(laneShape(d), lane.recommended && (lane.follow == null || d == lane.follow)))
                            }
                            sb.addLane(lb.build())
                        }
                        sb.setLanesImage(CarIcon.Builder(IconCompat.createWithBitmap(CarIcons.lanes(section, ::laneKind))).build())
                    }
                }
                sb.build()
            } else {
                null
            }
        }
        if (step != null) {
            val toNext = s.route.remainingTime(s.snap?.along ?: 0.0) -
                s.route.remainingTime(s.route.maneuverAlong.getOrElse(s.next) { s.route.distance })
            b.addStep(
                step,
                TravelEstimate.Builder(distance(if (s.turnAround) 0.0 else s.distanceToNext), DateTimeWithZone.create(now + (toNext * 1000).toLong(), tz))
                    .setRemainingTimeSeconds(toNext.toLong().coerceAtLeast(0))
                    .build(),
            )
        }
        b.addDestination(
            Destination.Builder().setName(s.destination.name).setAddress(s.destination.detail).build(),
            TravelEstimate.Builder(distance(s.remainingDistance), DateTimeWithZone.create(s.eta, tz))
                .setRemainingTimeSeconds(s.remainingTime.toLong().coerceAtLeast(0))
                .build(),
        )
        s.route.maneuvers.getOrNull(s.next)?.street?.let { b.setCurrentRoad(it) }
        return b.build()
    }

    /**
     * Display distance already rounded to what the unit shows. Unrounded values (e.g. 36.27 km) were
     * shown by some cars without the decimal separator – "3627 km" instead of "36 km".
     */
    fun distance(m: Double): Distance = when {
        m < 1000 -> Distance.create(((m / 10).toInt() * 10).toDouble().coerceAtLeast(0.0), Distance.UNIT_METERS)
        m < 10_000 -> Distance.create(Math.round(m / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(Math.round(m / 1000).toDouble(), Distance.UNIT_KILOMETERS)
    }

    private fun maneuver(kind: ManeuverKind, exit: Int?): Maneuver {
        val type = when (kind) {
            ManeuverKind.DEPART -> Maneuver.TYPE_DEPART
            ManeuverKind.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ManeuverKind.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            ManeuverKind.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            ManeuverKind.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            ManeuverKind.UTURN -> Maneuver.TYPE_U_TURN_LEFT
            ManeuverKind.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            ManeuverKind.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            ManeuverKind.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            ManeuverKind.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            ManeuverKind.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
            ManeuverKind.EXIT_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            ManeuverKind.EXIT_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            ManeuverKind.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            ManeuverKind.ROUNDABOUT -> if (exit != null) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            ManeuverKind.FERRY -> Maneuver.TYPE_FERRY_BOAT
            ManeuverKind.ARRIVE -> Maneuver.TYPE_DESTINATION
        }
        val mb = Maneuver.Builder(type)
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(CarIcons.maneuver(kind))).build())
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) mb.setRoundaboutExitNumber(exit ?: 1)
        return mb.build()
    }

    private fun laneShape(d: LaneDir): Int = when (d) {
        LaneDir.STRAIGHT -> LaneDirection.SHAPE_STRAIGHT
        LaneDir.SLIGHT_RIGHT -> LaneDirection.SHAPE_SLIGHT_RIGHT
        LaneDir.RIGHT -> LaneDirection.SHAPE_NORMAL_RIGHT
        LaneDir.SHARP_RIGHT -> LaneDirection.SHAPE_SHARP_RIGHT
        LaneDir.SLIGHT_LEFT -> LaneDirection.SHAPE_SLIGHT_LEFT
        LaneDir.LEFT -> LaneDirection.SHAPE_NORMAL_LEFT
        LaneDir.SHARP_LEFT -> LaneDirection.SHAPE_SHARP_LEFT
        LaneDir.UTURN -> LaneDirection.SHAPE_U_TURN_LEFT
    }

    private fun laneKind(d: LaneDir): ManeuverKind = when (d) {
        LaneDir.STRAIGHT -> ManeuverKind.STRAIGHT
        LaneDir.SLIGHT_RIGHT -> ManeuverKind.SLIGHT_RIGHT
        LaneDir.RIGHT -> ManeuverKind.RIGHT
        LaneDir.SHARP_RIGHT -> ManeuverKind.SHARP_RIGHT
        LaneDir.SLIGHT_LEFT -> ManeuverKind.SLIGHT_LEFT
        LaneDir.LEFT -> ManeuverKind.LEFT
        LaneDir.SHARP_LEFT -> ManeuverKind.SHARP_LEFT
        LaneDir.UTURN -> ManeuverKind.UTURN
    }
}
