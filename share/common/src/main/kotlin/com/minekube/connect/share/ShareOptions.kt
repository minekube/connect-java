package com.minekube.connect.share

data class ShareOptions(
    val gameMode: ShareGameMode,
    val allowCheats: Boolean,
    val maxGuests: Int = 8,
) {
    init {
        require(maxGuests in MIN_GUESTS..MAX_GUESTS) {
            "Share capacity must be between $MIN_GUESTS and $MAX_GUESTS"
        }
    }

    companion object {
        const val MIN_GUESTS = 1
        const val MAX_GUESTS = 16
    }
}

enum class ShareGameMode {
    SURVIVAL,
    CREATIVE,
    ADVENTURE,
    SPECTATOR,
}
