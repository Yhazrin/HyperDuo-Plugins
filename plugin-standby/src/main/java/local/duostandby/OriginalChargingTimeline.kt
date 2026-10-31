package local.duostandby

import kotlin.math.*

/** Independent analytical reconstruction, not CoreAnimation or Apple source.
 * Evidence: charging-followup/binary/original-charging-parameters.json (24A94425).
 * Zero initial velocity is an explicit experiment assumption: original constructor did not set it.
 * Seconds are physical time; duration clips evaluation, it does not normalize the spring.
 * Missing GPU/material operations remain represented as tracks, never substituted with new effects.
 */
object OriginalChargingTimeline {
    const val DISAPPEARANCE_NOTIFICATION_SECONDS = 6.2
    const val PRESENTATION_END_SECONDS = 9.8
    enum class Phase { PRESENTATION, DISMISSAL }
    data class Edge(val parent: String, val child: String)
    data class Inputs(
        val batteryPercent: Double,
        val initialCenterY: Double = 50.0,
        val grabberCenterY: Double = 54.0,
        val adoptedScale: Double = 1.0,
        val adoptedOpacity: Double = 1.0,
        val adoptedGrabberOpacity: Double = 0.0
    )
    data class Track(
        val animationKey: String, val layer: String, val keyPath: String,
        val duration: Double, val delay: Double, val stiffness: Double,
        val mass: Double, val damping: Double,
        val from: String, val to: String, val callAddress: String,
        val phase: Phase = Phase.PRESENTATION,
        val fillMode: String = "kCAFillModeForwards",
        val removedOnCompletion: Boolean = false
    ) {
        val end: Double get() = delay + duration
        /** null means no contribution (forwards fill does not fill backwards). */
        fun sample(seconds: Double, inputs: Inputs): Double? {
            if (!seconds.isFinite() || seconds < delay) return null
            val a = resolve(from, inputs)
            val b = resolve(to, inputs)
            val local = (seconds - delay).coerceIn(0.0, duration)
            return b + (a - b) * displacement(local, stiffness, mass, damping)
        }
    }
    // Ordered original tree; the label is a sibling of ringContainer, not inside ringLayer.
    val hierarchy: List<Edge> = listOf(
        Edge("self", "chargingContainerView"),
        Edge("self", "grabberView"),
        Edge("chargingContainerView.layer", "magSafeLayer"),
        Edge("magSafeLayer", "chargeRingLayer"),
        Edge("magSafeLayer", "chargingBoltGlyph"),
        Edge("chargeRingLayer", "ringContainerLayer"),
        Edge("chargeRingLayer", "chargePercentLabel.layer"),
        Edge("ringContainerLayer", "ringLayer"),
        Edge("ringLayer", "platterView.layer"),
        Edge("ringLayer", "trackFillRingLayer"),
        Edge("ringLayer", "batteryLevelRing")
    )
    val tracks: List<Track> = listOf(
        Track("levelRingStrokeEndAnimation", "batteryLevelRing", "strokeEnd", 2.1, 0.1236, 300.0, 2.0, 50.0, "0", "powerPercent / 100", "0x2799c"),
        Track("magSafeScaleAnimation", "magSafeLayer", "transform.scale.xy", 1.1993, 0.25, 200.0, 2.0, 25.0, "0.8", "1", "0x279fc"),
        Track("chargingBoltFadeInAnimation", "chargingBoltGlyph", "opacity", 0.8, 0.25, 300.0, 2.0, 50.0, "0", "1", "0x27a40"),
        Track("chargingBoltScaleAnimation", "chargingBoltGlyph", "transform.scale.xy", 1.1993, 0.0, 200.0, 2.0, 25.0, "0", "0.4", "0x27a7c"),
        Track("magSafeFadeInAnimation", "magSafeLayer", "opacity", 0.8, 0.25, 300.0, 2.0, 50.0, "0", "1", "0x27ab0"),
        Track("chargingBoltBlurInAnimation", "chargingBoltGlyph", "filters.gaussianBlur.inputRadius", 0.8, 0.0, 300.0, 2.0, 50.0, "50", "0", "0x27af0"),
        Track("chargingBoltBlurOutAnimation", "chargingBoltGlyph", "filters.gaussianBlur.inputRadius", 0.8, 3.0, 300.0, 2.0, 50.0, "0", "20", "0x27b28"),
        Track("chargingBoltScaleOutAnimation", "chargingBoltGlyph", "transform.scale.xy", 1.1993, 3.0, 200.0, 2.0, 25.0, "0.4", "0", "0x27b5c"),
        Track("chargingBoltFadeOutAnimation", "chargingBoltGlyph", "opacity", 0.907, 3.0, 120.0, 1.0, 17.0, "1", "0", "0x27b98"),
        Track("containerRingScaleInAnimation", "ringContainerLayer", "transform.scale.xy", 1.1877, 3.2800000000000002, 100.0, 0.8, 10.0, "1", "1.25", "0x27bdc"),
        Track("ringScaleOutAnimation", "ringLayer", "transform.scale.xy", 1.1877, 3.0, 100.0, 0.8, 10.0, "1", "0.8", "0x27c14"),
        Track("chargingLabelFadeInAnimation", "chargePercentLabel.layer", "opacity", 1.0, 3.1, 200.0, 2.0, 100.0, "0", "1", "0x27c50"),
        Track("chargingLabelBlurInAnimation", "chargePercentLabel.layer", "filters.gaussianBlur.inputRadius", 0.8, 3.1, 300.0, 2.0, 50.0, "40", "0", "0x27c8c"),
        Track("chargingLabelScaleInAnimation", "chargePercentLabel.layer", "transform.scale.xy", 1.1993, 3.1, 200.0, 2.0, 25.0, "0.8", "1", "0x27cc0"),
        Track("magSafeMoveUpAnimation", "magSafeLayer", "position.y", 1.277, 6.33, 300.0, 3.0, 35.0, "initialRingCenterPosition.y", "initialRingCenterPosition.y - 200", "0x27d90"),
        Track("shadowFadeOutAnimation", "magSafeLayer", "shadownOpacity", 0.8, 6.2, 300.0, 2.0, 50.0, "0.5", "0", "0x27de0"),
        Track("dissappearBlurAnimation", "magSafeLayer", "filters.gaussianBlur.inputRadius", 1.277, 6.3035000000000005, 300.0, 3.0, 35.0, "0", "50", "0x27e1c"),
        Track("dissappearScaleOutAnimation", "magSafeLayer", "transform.scale.xy", 0.9392, 6.23778, 300.0, 3.0, 50.0, "1", "0.5", "0x27e60"),
        Track("chargingLabelDissappearScaleOutAnimation", "chargePercentLabel.layer", "transform.scale.xy", 0.9392, 6.2, 300.0, 3.0, 50.0, "1", "0", "0x27e98"),
        Track("grabberMoveUpAnimation", "grabberView.layer", "position.y", 2.2328, 6.33, 100.0, 3.0, 20.0, "grabberLayer.position.y read via floatValue", "15", "0x27ef0"),
        Track("grabberFadeInAnimation", "grabberView.layer", "opacity", 1.0, 6.4023, 200.0, 2.0, 100.0, "0", "1", "0x27f34"),
        Track("grabberFadeOutAnimation", "grabberView.layer", "opacity", 1.6, 8.2, 300.0, 8.0, 100.0, "1", "0", "0x27f70"),
        Track("dismissalScaleOutAnimation", "magSafeLayer", "transform.scale.xy", 0.6, 0.0, 493.48, 2.0, 62.8319, "adopted current scale", "adopted current scale * 0.8", "0x26930", Phase.DISMISSAL),
        Track("dismissalFadeOutAnimation", "magSafeLayer", "opacity", 0.3, 0.0, 1973.92, 2.0, 128.488, "adopted current opacity", "0", "0x269a8", Phase.DISMISSAL),
        Track("dismissalGrabberFadeOutAnimation", "grabberView.layer", "opacity", 0.3, 0.0, 1973.92, 2.0, 128.488, "adopted current grabber opacity", "0", "0x269f4", Phase.DISMISSAL)
    )
    private val byProperty = tracks.groupBy { Triple(it.phase, it.layer, it.keyPath) }

    /** Later attached eligible track replaces the prior same-property contribution.
     * Composition arbitration is an experiment model, not a runtime CA equivalence claim. */
    fun value(layer: String, keyPath: String, seconds: Double, inputs: Inputs,
              model: Double, phase: Phase = Phase.PRESENTATION): Double {
        var result = model
        byProperty[Triple(phase, layer, keyPath)]?.forEach { track ->
            track.sample(seconds, inputs)?.let { result = it }
        }
        return result
    }
    fun isPlaying(seconds: Double, phase: Phase = Phase.PRESENTATION): Boolean =
        seconds.isFinite() && seconds >= 0.0 && seconds <
            if (phase == Phase.PRESENTATION) PRESENTATION_END_SECONDS else 0.6

    private fun resolve(value: String, input: Inputs): Double = when (value) {
        "powerPercent / 100" -> input.batteryPercent.coerceIn(0.0, 100.0) / 100.0
        "initialRingCenterPosition.y" -> input.initialCenterY
        "initialRingCenterPosition.y - 200" -> input.initialCenterY - 200.0
        "grabberLayer.position.y read via floatValue" -> input.grabberCenterY.toFloat().toDouble()
        "adopted current scale" -> input.adoptedScale
        "adopted current scale * 0.8" -> input.adoptedScale * 0.8
        "adopted current opacity" -> input.adoptedOpacity
        "adopted current grabber opacity" -> input.adoptedGrabberOpacity
        else -> value.toDouble()
    }

    /** Homogeneous displacement x(t)/x(0), x'(0)=0; covers all damping regimes. */
    fun displacement(seconds: Double, stiffness: Double, mass: Double, damping: Double): Double {
        require(stiffness > 0.0 && mass > 0.0 && damping >= 0.0)
        val t = seconds.coerceAtLeast(0.0)
        val alpha = damping / (2.0 * mass)
        val omegaSquared = stiffness / mass
        val discriminant = alpha * alpha - omegaSquared
        return when {
            abs(discriminant) < 1e-10 -> exp(-alpha * t) * (1.0 + alpha * t)
            discriminant < 0.0 -> {
                val omega = sqrt(-discriminant)
                exp(-alpha * t) * (cos(omega * t) + alpha / omega * sin(omega * t))
            }
            else -> {
                val root = sqrt(discriminant)
                val r1 = -alpha + root
                val r2 = -alpha - root
                (-r2 * exp(r1 * t) + r1 * exp(r2 * t)) / (r1 - r2)
            }
        }
    }
}
