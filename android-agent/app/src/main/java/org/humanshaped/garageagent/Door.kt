package org.humanshaped.garageagent

enum class DoorPhase(val hap: Int?) {
    OPEN(0), CLOSED(1), OPENING(2), CLOSING(3), STOPPED(4), UNKNOWN(null);

    val isMoving get() = this == OPENING || this == CLOSING
}

object DoorText {
    /** Maps myQ's status line ("Closed for 23 hours", "Opening for 2 seconds") to a phase. */
    fun parse(text: String?): DoorPhase {
        val t = text?.trim()?.lowercase() ?: return DoorPhase.UNKNOWN
        return when {
            t.startsWith("opening") -> DoorPhase.OPENING
            t.startsWith("closing") -> DoorPhase.CLOSING
            t.startsWith("closed") -> DoorPhase.CLOSED
            t.startsWith("open") -> DoorPhase.OPEN
            t.startsWith("stopped") -> DoorPhase.STOPPED
            else -> DoorPhase.UNKNOWN
        }
    }
}

/** What the agent knows about the door, and when it learned it. */
data class DoorSnapshot(
    val phase: DoorPhase,
    val rawText: String?,
    val seenAt: Long,
    val fault: String? = null,
)
