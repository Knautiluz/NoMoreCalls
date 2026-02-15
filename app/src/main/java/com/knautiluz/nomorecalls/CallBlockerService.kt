package com.knautiluz.nomorecalls

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

class CallBlockerService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: return
        val sharedPrefs = applicationContext.getSharedPreferences("CallGuardPrefs", MODE_PRIVATE)

        val allowedNumbers = sharedPrefs.getStringSet("allowedNumbers", emptySet()) ?: emptySet()
        val isBlockingEnabled = sharedPrefs.getBoolean("isBlockingEnabled", true)

        if (!isBlockingEnabled) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val incomingClean = normalizeNumber(number)

        val isAllowed = allowedNumbers.any {
            normalizeNumber(it) == incomingClean
        }

        if (isAllowed) {
            respondToCall(callDetails, CallResponse.Builder().build())
        } else {
            val response = CallResponse.Builder().apply {
                setDisallowCall(true)
                setRejectCall(true)
                setSkipCallLog(false)
                setSkipNotification(true)
            }.build()

            respondToCall(callDetails, response)

            val contactName = ContactHelper.getContactName(this, number) ?: "Desconhecido"
            pushBlockedNumber(number, contactName)
        }
    }

    private fun normalizeNumber(num: String): String {
        var clean = num.replace(Regex("[^0-9]"), "")

        if (clean.startsWith("55") && clean.length > 10) {
            clean = clean.substring(2)
        }

        if (clean.startsWith("0")) {
            clean = clean.substring(1)
        }

        return clean
    }

    private fun pushBlockedNumber(number: String, name: String) {
        try {
            val sharedPrefs = applicationContext.getSharedPreferences("CallGuardPrefs", MODE_PRIVATE)
            val gson = Gson()
            val json = sharedPrefs.getString("blockedCallsHistory", null)
            val type = object : TypeToken<MutableList<BlockedCall>>() {}.type

            val history: MutableList<BlockedCall> = if (json != null) {
                gson.fromJson(json, type)
            } else {
                mutableListOf()
            }

            history.add(0, BlockedCall(number = number, name = name, time = System.currentTimeMillis()))
            if (history.size > 50) history.removeAt(history.size - 1)

            sharedPrefs.edit(commit = true) { putString("blockedCallsHistory", gson.toJson(history)) }
        } catch (e: Exception) {
            Log.e("CallBlockerService", "Erro history: ${e.message}")
        }
    }
}