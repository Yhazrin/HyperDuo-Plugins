package local.duostandby

/** 24A94425 CalendarPoster: LazyState callbacks d8ac0 / d9c7c / d9d24,
 * read by c26f4..c2768 and rendered by MonthPressFeedback b9354.
 * This is the recovered state/endpoint contract, NOT a touch recognizer or a
 * reimplementation of SwiftUI's spring/projection engine. Host initial state
 * and original interaction regions must be supplied, never inferred here.
 */
class OriginalMonthPressFeedback(initialTiltEdge: Int?, initialDimmed: Boolean) {
    data class Spring(val response: Double, val dampingFraction: Double, val blendDuration: Double)
    data class Snapshot(
        val tiltEdge: Int?, val dimmed: Boolean, val angleRadians: Double,
        val anchor: String, val axisX: Double, val axisY: Double, val axisZ: Double,
        val anchorZ: Double, val perspective: Double, val blackOverlayOpacity: Double
    )
    data class Change(val state: Snapshot, val tiltSpring: Spring?, val dimmingSpring: Spring?)

    companion object {
        // Original SwiftUI.Edge no-payload enum order, not Android edge flags.
        const val TOP = 0
        const val LEADING = 1
        const val BOTTOM = 2
        const val TRAILING = 3
        val TILT_SPRING = Spring(0.6, 0.62, 0.0) // b929c
        val RETURN_SPRING = Spring(0.4, 0.62, 0.0) // b92dc
        val DIMMING_SPRING = Spring(0.8, 1.0, 0.0) // b9320

        /** Original _Rotation3DEffect.animatableData getter 1c72b1b1c.
         * Field order: radians, axis x/y/z, anchor x/y, anchorZ, perspective.
         * All but anchorZ are multiplied by 128 BEFORE vector spring integration.
         * Omitting this scale changes the original settling decision substantially.
         */
        @JvmStatic
        fun rotationAnimatableData(fields: DoubleArray): DoubleArray {
            require(fields.size == 8 && fields.all { it.isFinite() })
            return DoubleArray(8) { if (it == 6) fields[it] else fields[it] * 128.0 }
        }

        /** Original setter 1c72b1b54; do not clamp the interpolated anchor. */
        @JvmStatic
        fun rotationFromAnimatableData(data: DoubleArray): DoubleArray {
            require(data.size == 8 && data.all { it.isFinite() })
            return DoubleArray(8) { if (it == 6) data[it] else data[it] * (1.0 / 128.0) }
        }

        /** Original scalar fallback 1c779b918..b970.
         * Only after activeDelay is absent and no custom settling callback returns true.
         * target/offset are animation-space values, NOT absolute view properties.
         * A zero target deliberately skips the relative-position check in the original.
         */
        @JvmStatic
        fun scalarFallbackSettled(target: Double, offset: Double, velocity: Double, force: Double): Boolean {
            require(listOf(target, offset, velocity, force).all { it.isFinite() })
            if (maxOf(velocity * velocity, force * force) > 0.0036) return false
            val relative = target * 0.01
            val threshold = relative * relative
            val remaining = target - offset
            return threshold <= 0.0 || remaining * remaining <= threshold
        }

        /** Generic counterpart 1c779caec..cc40, for the rotation's eight-component
         * AnimatablePair. Inputs MUST be encoded deltas in the same animation space.
         * Angle and anchor settle together, not independently. This is only the
         * fallback predicate, not a replacement for SwiftUI's environment callback.
         */
        @JvmStatic
        fun rotationFallbackSettled(target: DoubleArray, offset: DoubleArray,
                                    velocity: DoubleArray, force: DoubleArray): Boolean {
            for (v in arrayOf(target, offset, velocity, force)) {
                require(v.size == 8 && v.all { it.isFinite() })
            }
            if (maxOf(rotationMagnitudeSquared(velocity), rotationMagnitudeSquared(force)) > 0.0036) return false
            val threshold = rotationMagnitudeSquared(DoubleArray(8) { target[it] * 0.01 })
            return threshold <= 0.0 || rotationMagnitudeSquared(DoubleArray(8) { target[it] - offset[it] }) <= threshold
        }

        private fun rotationMagnitudeSquared(v: DoubleArray): Double {
            // Preserve the nesting of the exported original AnimatablePair type.
            fun square(i: Int) = v[i] * v[i]
            return square(0) + (square(1) + (square(2) + (square(3) +
                ((square(4) + square(5)) + (square(6) + square(7))))))
        }

        /** SwiftUICore 24A94425 effectValue 1c72b1a98 -> 1c72b16e0.
         * Calendar's fixed axis=(0,1,0), anchorZ=0, perspective=.4.
         * Row-major Android Matrix coefficients; divide projected x/y by w.
         * anchorX remains continuous because original animatableData includes anchor.
         */
        @JvmStatic
        fun projection(width: Double, height: Double, angleRadians: Double, anchorX: Double): DoubleArray {
            require(width > 0.0 && height > 0.0)
            require(listOf(width, height, angleRadians, anchorX).all { it.isFinite() })
            val ax = width * anchorX
            val ay = height * 0.5 // UnitPoint.leading/trailing share y=.5
            val cosine = kotlin.math.cos(angleRadians)
            val perspectiveTerm = 0.4 / maxOf(width, height) * kotlin.math.sin(angleRadians)
            return doubleArrayOf(
                cosine + ax * perspectiveTerm, 0.0, ax * (1.0 - cosine) - ax * ax * perspectiveTerm,
                ay * perspectiveTerm, 1.0, -ax * ay * perspectiveTerm,
                perspectiveTerm, 0.0, 1.0 - ax * perspectiveTerm
            )
        }
    }

    private var tiltEdge = initialTiltEdge
    private var dimmed = initialDimmed
    init { require(initialTiltEdge == null || initialTiltEdge in TOP..TRAILING) }

    fun snapshot(): Snapshot = Snapshot(
        tiltEdge, dimmed,
        when (tiltEdge) { null -> 0.0; TRAILING -> 0.12; else -> -0.12 },
        if (tiltEdge == LEADING) "trailing" else "leading",
        0.0, 1.0, 0.0, 0.0, 0.4, if (dimmed) 0.1 else 0.0
    )

    /** d8ac0: press selects captured edge; release writes nil unconditionally.
     * No invented touch-owner arbitration, debounce or release-as-tap behavior.
     */
    fun onZonePressChanged(edge: Int, pressed: Boolean): Change {
        require(edge in TOP..TRAILING)
        val before = snapshot()
        tiltEdge = if (pressed) edge else null
        dimmed = pressed
        return change(before)
    }

    /** d9c7c: dedicated leading callback; distinct from a general screen press. */
    fun onLeadingPressChanged(pressed: Boolean): Change = onZonePressChanged(LEADING, pressed)

    /** d9d24: writes ONLY __isPressed. Must not silently reset tiltEdge. */
    fun onDimmedPressChanged(pressed: Boolean): Change {
        val before = snapshot()
        dimmed = pressed
        return change(before)
    }

    private fun change(before: Snapshot): Change {
        val after = snapshot()
        return Change(after,
            if (before.angleRadians == after.angleRadians) null
            else if (after.angleRadians == 0.0) RETURN_SPRING else TILT_SPRING,
            if (before.dimmed == after.dimmed) null else DIMMING_SPRING)
    }

    /** PosterKit 24A94425 PassivePressGestureRecognizer (0e80..169c).
     * Coordinates MUST already be converted to the recognizer's local point space.
     * The host supplies enabled/eligible region and UIKit-equivalent reset timing.
     * This returns raw recognizer events; SwiftUI onChange scheduling is not modeled.
     * Never consume another recognizer's gesture or turn release into a tap here.
     */
    class PassiveRecognizer(private val hasDragCallback: Boolean) {
        data class Event(val pressChanged: Boolean?, val dragDetected: Boolean, val failed: Boolean)
        private var trackedTouch: Int? = null
        private var startX = 0.0
        private var startY = 0.0
        private var didReportDrag = false
        private var failed = false

        /** incomingTouchCount is touchesBegan's changed set, NOT total pointer count. */
        fun touchesBegan(incomingTouchCount: Int, touchId: Int, localX: Double, localY: Double): Event {
            if (failed) return event()
            if (trackedTouch != null || incomingTouchCount != 1) {
                failed = true
                return event()
            }
            trackedTouch = touchId
            startX = localX
            startY = localY
            return event(press = true)
        }

        /** 1210..1454: optional one-shot drag notification, NOT press cancellation.
         * max(abs(dx), abs(dy)) >= 10; diagonal Euclidean slop would be incorrect.
         * The pr_interactable(onPressChanged:) initializer supplies no drag callback.
         */
        fun touchesMoved(touchId: Int, localX: Double, localY: Double): Event {
            if (failed || !hasDragCallback || didReportDrag || trackedTouch != touchId) return event()
            if (maxOf(kotlin.math.abs(localX - startX), kotlin.math.abs(localY - startY)) < 10.0) return event()
            didReportDrag = true
            return event(drag = true)
        }

        /** 1518: both end/cancel set failed; callback(false) belongs to reset. */
        fun touchesEndedOrCancelled(): Event {
            failed = true
            return event()
        }

        /** 1604: clear tracked touch/drag; report false only if a touch was tracked. */
        fun reset(): Event {
            val hadTouch = trackedTouch != null
            trackedTouch = null
            didReportDrag = false
            failed = false
            return event(press = if (hadTouch) false else null)
        }

        private fun event(press: Boolean? = null, drag: Boolean = false) = Event(press, drag, failed)
    }

    /** Calendar's zero-delay, zero-blend channels, on an explicit host clock.
     * AnimatorState.combine adds the new model delta to the old interval;
     * update presents (model + springOffset) - accumulatedInterval.
     * No invented absolute-coordinate restart or duration cutoff. The Android
     * adapter uses the recovered fallback; custom SwiftUI finishing definitions,
     * graph scheduling and animation forks are not reproduced by this class.
     */
    class LiveMotion(initial: Snapshot) {
        data class Frame(val angle: Double, val anchorX: Double, val dim: Double, val running: Boolean)
        private fun rotation(s: Snapshot) = rotationAnimatableData(doubleArrayOf(
            s.angleRadians, s.axisX, s.axisY, s.axisZ,
            if (s.anchor == "trailing") 1.0 else 0.0, 0.5, s.anchorZ, s.perspective))
        private val tilt = Channel(rotation(initial), true)
        private val dim = Channel(doubleArrayOf(initial.blackOverlayOpacity), false)

        fun apply(change: Change, now: Double) {
            change.tiltSpring?.let { tilt.retarget(rotation(change.state), it, now) }
            change.dimmingSpring?.let { dim.retarget(doubleArrayOf(change.state.blackOverlayOpacity), it, now) }
        }
        fun frame(now: Double): Frame {
            val fields = rotationFromAnimatableData(tilt.sample(now))
            val opacity = dim.sample(now)[0]
            return Frame(fields[0], fields[4], opacity, tilt.running || dim.running)
        }

        private class Channel(initial: DoubleArray, private val rotation: Boolean) {
            private var model = initial.copyOf()
            private var interval = DoubleArray(initial.size)
            private var states = Array(initial.size) { doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
            private var solvers = Array(initial.size) { FluidIntegrator(0.0, 0.0, 0.0, 0.0) }
            private var spring = DIMMING_SPRING
            private var epoch = 0.0
            var running = false; private set
            fun retarget(next: DoubleArray, nextSpring: Spring, now: Double) {
                require(now.isFinite() && nextSpring.blendDuration == 0.0)
                if (model.contentEquals(next)) return
                if (running) {
                    val elapsed = now - epoch
                    // shouldMerge reads velocity before previous.animate and retains
                    // the pre-call force in its local state copy (d1fc..d36c).
                    val previous = states.map { it.copyOf() }
                    advance(elapsed)
                    val finished = settled()
                    states = Array(model.size) { j -> doubleArrayOf(
                        if (finished) interval[j] else states[j][0],
                        previous[j][1], previous[j][2], elapsed) }
                    solvers = Array(model.size) { j -> states[j].let { FluidIntegrator(it[0], it[1], it[2], it[3]) } }
                    for (j in interval.indices) interval[j] += next[j] - model[j]
                } else {
                    epoch = now
                    interval = DoubleArray(model.size) { next[it] - model[it] }
                    states = Array(model.size) { doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
                    solvers = Array(model.size) { FluidIntegrator(0.0, 0.0, 0.0, 0.0) }
                }
                model = next.copyOf(); spring = nextSpring; running = true
            }
            private fun advance(elapsed: Double) {
                states = Array(model.size) { j -> solvers[j].advanceZeroBlend(
                    interval[j], spring.response, spring.dampingFraction, elapsed) }
            }
            private fun settled(): Boolean = if (rotation) rotationFallbackSettled(interval,
                DoubleArray(8) { states[it][0] }, DoubleArray(8) { states[it][1] }, DoubleArray(8) { states[it][2] })
                else scalarFallbackSettled(interval[0], states[0][0], states[0][1], states[0][2])
            fun sample(now: Double): DoubleArray {
                if (!running) return model.copyOf()
                advance(now - epoch)
                if (settled()) { running = false; return model.copyOf() }
                return DoubleArray(model.size) { (model[it] + states[it][0]) - interval[it] }
            }
        }
    }

    /** FluidSpringAnimation's scalar zero-blend/zero-delay numerical core.
     * Original 1c779b600..b75c: stiffness cap + 300Hz velocity-Verlet update.
     * Initial position/velocity/force/time are explicit. This is NOT SwiftUI's
     * animation transaction, shouldMerge, delay or settling-completion policy.
     * In particular response is NOT a duration and no final-value snap occurs.
     */
    class FluidIntegrator(position: Double, velocity: Double, acceleration: Double, time: Double) {
        private var position = position
        private var velocity = velocity
        private var acceleration = acceleration
        private var time = time
        private var lastRequest = time
        init { require(listOf(position, velocity, acceleration, time).all { it.isFinite() }) }

        fun advanceZeroBlend(target: Double, response: Double, dampingFraction: Double, now: Double): DoubleArray {
            require(listOf(target, response, dampingFraction, now).all { it.isFinite() })
            require(response > 0.0 && dampingFraction >= 0.0 && now >= lastRequest)
            lastRequest = now
            val frequency = (2.0 * Math.PI) / response
            val stiffness = minOf(frequency * frequency, Double.fromBits(0x40e5f8ffffffffffL))
            val damping = -dampingFraction * (2.0 * kotlin.math.sqrt(stiffness))
            // Original long-gap branch b6c0: >1 second advances only the last1/60.
            if (now - time > 1.0) time = now - 1.0 / 60.0
            val halfStep = Double.fromBits(0x3f5b4e81b4e81b4fL)
            val step = Double.fromBits(0x3f6b4e81b4e81b4fL)
            while (time < now) {
                velocity += acceleration * halfStep
                position += velocity * step
                acceleration = damping * velocity + stiffness * (target - position)
                velocity += acceleration * halfStep
                time += step
            }
            return doubleArrayOf(position, velocity, acceleration, time)
        }
    }
}
