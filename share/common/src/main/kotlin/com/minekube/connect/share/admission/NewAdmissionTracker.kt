package com.minekube.connect.share.admission

import java.util.UUID

class NewAdmissionTracker {
    private var currentIds: Set<UUID> = emptySet()

    fun update(pending: List<PendingAdmission>): List<PendingAdmission> {
        val newRequests = pending.filterNot {
            it.requestId in currentIds
        }
        currentIds = pending.mapTo(mutableSetOf()) {
            it.requestId
        }
        return newRequests
    }
}
