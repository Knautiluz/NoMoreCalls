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
        val isBlockingEnabled = sharedPrefs.getBoolean("isBlockingEnabled", true)

        val responseBuilder = CallResponse.Builder()

        if (!isBlockingEnabled) {
            respondToCall(callDetails, responseBuilder.build())
            return
        }

        val isNativeContact = ContactHelper.isNumberInContacts(this, number)
        val allowedNumbers = sharedPrefs.getStringSet("allowedNumbers", emptySet()) ?: emptySet()

        // Normalização: remove tudo que não é número para comparar
        val normalizedIncoming = number.replace(Regex("[^0-9]"), "")
        val isExplicitlyAllowed = allowedNumbers.any {
            val norm = it.replace(Regex("[^0-9]"), "")
            norm.isNotEmpty() && (normalizedIncoming.endsWith(norm) || norm.endsWith(normalizedIncoming))
        }

        // Rejeita todas as ligações de contatos que não estiverem liberados de ligar.
        if (!isNativeContact || !isExplicitlyAllowed) {
            responseBuilder.apply {
                setDisallowCall(true)
                setRejectCall(true)
                setSkipCallLog(false)
                setSkipNotification(true)
            }
            pushBlockedNumber(number)
            Log.d("CallBlocker", "Bloqueado: $number")
        }

        respondToCall(callDetails, responseBuilder.build())
    }

    private fun pushBlockedNumber(number: String) {
        try {
            val sharedPrefs = applicationContext.getSharedPreferences("CallGuardPrefs",
                MODE_PRIVATE
            )
            val gson = Gson()
            val json = sharedPrefs.getString("blockedCallsHistory", null)
            val type = object : TypeToken<MutableList<BlockedCall>>() {}.type

            val history: MutableList<BlockedCall> = if (json != null) {
                gson.fromJson(json, type)
            } else {
                mutableListOf()
            }

            history.add(0, BlockedCall(number = number, time = System.currentTimeMillis()))
            if (history.size > 50) history.removeAt(history.size - 1)

            sharedPrefs.edit { putString("blockedCallsHistory", gson.toJson(history)) }
        } catch (e: Exception) {
            Log.e("CallBlockerService", "Erro history: ${e.message}")
        }
    }
}