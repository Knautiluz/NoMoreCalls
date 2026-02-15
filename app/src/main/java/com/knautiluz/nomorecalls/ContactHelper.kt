package com.knautiluz.nomorecalls

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactHelper {
    fun isNumberInContacts(context: Context, phoneNumber: String?): Boolean {
        if (phoneNumber.isNullOrEmpty()) return false

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use {
                it.count > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}