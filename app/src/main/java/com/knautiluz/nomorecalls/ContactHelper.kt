package com.knautiluz.nomorecalls

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactHelper {

    fun normalizeNumber(num: String): String {
        var clean = num.replace(Regex("[^0-9]"), "")

        if (clean.startsWith("55") && clean.length > 10) {
            clean = clean.substring(2)
        }

        if (clean.startsWith("0")) {
            clean = clean.substring(1)
        }

        return clean
    }

    fun getContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null

        val normalizedNumber = normalizeNumber(phoneNumber)

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalizedNumber)
        )

        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }
}