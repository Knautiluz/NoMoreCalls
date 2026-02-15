package com.knautiluz.nomorecalls

data class Contact(
    val id: String,
    val name: String,
    val number: String,
    var isAllowed: Boolean = false
)