package com.knautiluz.nomorecalls

data class BlockedCall(
    val name: String,
    val number: String,
    val time: Long,
    var tries: Int = 1,
    val isAllowedByPersistence: Boolean = false
)
