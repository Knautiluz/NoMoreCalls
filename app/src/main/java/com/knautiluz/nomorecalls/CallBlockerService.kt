package com.knautiluz.nomorecalls

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

class CallBlockerService : CallScreeningService() {

    companion object {
        const val PREFS_NAME = "CallGuardPrefs"
        const val KEY_ALLOWED_NUMBERS = "allowedNumbers"
        const val KEY_IS_BLOCKING_ENABLED = "isBlockingEnabled"
        const val KEY_BLOCKED_CALLS_HISTORY = "blockedCallsHistory"
        const val KEY_PERSISTENT_CALLS_ATTEMPTS = "persistentCallsAttempts"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: return
        val normalizedNumber = ContactHelper.normalizeNumber(number)
        val contactName = ContactHelper.getContactName(this, normalizedNumber) ?: "Desconhecido"
        val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val allowedNumbers = sharedPrefs.getStringSet(KEY_ALLOWED_NUMBERS, emptySet()) ?: emptySet()
        val isBlockingEnabled = sharedPrefs.getBoolean(KEY_IS_BLOCKING_ENABLED, true)
        val persistentCallsAttempts = sharedPrefs.getInt(KEY_PERSISTENT_CALLS_ATTEMPTS, 3)

        if (!isBlockingEnabled) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        if (allowedNumbers.contains(normalizedNumber)) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val history = getBlockedCallsHistory()
        val tenMinutesAgo = System.currentTimeMillis() - 10 * 60 * 1000
        val recentTries = history.count { it.number == normalizedNumber && it.time > tenMinutesAgo } + 1
        val isAllowedByPersistence = persistentCallsAttempts > 0 && recentTries >= persistentCallsAttempts

        if (isAllowedByPersistence) {
            respondToCall(callDetails, CallResponse.Builder().build())
        } else {
            val response = CallResponse.Builder().apply {
                setDisallowCall(true)
                setRejectCall(true)
                setSkipCallLog(false)
                setSkipNotification(true)
            }.build()
            respondToCall(callDetails, response)
        }
        pushBlockedNumber(normalizedNumber, contactName, isAllowedByPersistence, recentTries)
    }

    private fun getBlockedCallsHistory(): MutableList<BlockedCall> {
        val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()
        val json = sharedPrefs.getString(KEY_BLOCKED_CALLS_HISTORY, null)
        val type = object : TypeToken<MutableList<BlockedCall>>() {}.type
        return if (json != null) {
            try {
                gson.fromJson(json, type)
            } catch (e: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
    }

    private fun pushBlockedNumber(number: String, name: String, isAllowed: Boolean, tries: Int) {
        try {
            val history = getBlockedCallsHistory()
            history.add(0, BlockedCall(number = number, name = name, time = System.currentTimeMillis(), tries = tries, isAllowedByPersistence = isAllowed))

            if (history.size > 50) history.removeAt(history.size - 1)

            val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val gson = Gson()
            sharedPrefs.edit { putString(KEY_BLOCKED_CALLS_HISTORY, gson.toJson(history)) }
        } catch (e: Exception) {
            Log.e("No More Calls", "Error in history: ${e.message}")
        }
    }
}
