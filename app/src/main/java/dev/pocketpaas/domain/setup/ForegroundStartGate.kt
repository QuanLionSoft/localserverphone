package dev.pocketpaas.domain.setup

/**
 * Keeps a user-requested start tied to the foreground screen that requested it.
 *
 * The activity owns this gate and calls every method on its main thread. A permission result may
 * arrive before the activity resumes, so the result only marks its matching ticket ready. The
 * activity must explicitly call [takeReady] to dispatch a start, and [onStop] discards the request.
 */
class ForegroundStartGate {
    private var resumed = false
    private var nextTicket = 0L
    private var pendingTicket: Long? = null
    private var ready = false

    fun onResume() { resumed = true }

    fun onPause() { resumed = false }

    fun onStop() {
        resumed = false
        cancel()
    }

    /** Creates a new ticket only for an explicit action on a resumed screen. */
    fun request(): Long? {
        if (!resumed) return null
        val ticket = ++nextTicket
        pendingTicket = ticket
        ready = false
        return ticket
    }

    /** Records completion of the permission interaction without dispatching a start. */
    fun permissionResult(ticket: Long) {
        if (pendingTicket == ticket) ready = true
    }

    /** Consumes a ready request once, only while the owning screen is resumed. */
    fun takeReady(): Boolean {
        if (!resumed || pendingTicket == null || !ready) return false
        cancel()
        return true
    }

    fun cancel() {
        pendingTicket = null
        ready = false
    }
}
