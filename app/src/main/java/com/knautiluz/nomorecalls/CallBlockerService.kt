package com.knautiluz.nomorecalls

import android.content.Context
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

class CallBlockerService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: return
        val sharedPrefs = applicationContext.getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE)
        val isBlockingEnabled = sharedPrefs.getBoolean("isBlockingEnabled", true)

        val responseBuilder = CallResponse.Builder()

        if (!isBlockingEnabled) {
            respondToCall(callDetails, responseBuilder.build())
            return
        }

        val isNativeContact = ContactHelper.isNumberInContacts(this, phoneNumber)
        val allowedNumbers = sharedPrefs.getStringSet("allowedNumbers", emptySet()) ?: emptySet()

        // Normalização: remove tudo que não é número para comparar
        val normalizedIncoming = phoneNumber.replace(Regex("[^0-9]"), "")
        val isExplicitlyAllowed = allowedNumbers.any {
            val norm = it.replace(Regex("[^0-9]"), "")
            norm.isNotEmpty() && (normalizedIncoming.endsWith(norm) || norm.endsWith(normalizedIncoming))
        }

        // Lógica: Bloqueia se não for contato OU se for contato mas não estiver marcado na lista
        if (!isNativeContact || !isExplicitlyAllowed) {
            responseBuilder.apply {
                setDisallowCall(true)
                setRejectCall(true)
                setSkipCallLog(false)
                setSkipNotification(true)
            }
            saveToHistory(phoneNumber)
            Log.d("CallBlocker", "Bloqueado: $phoneNumber")
        }

        respondToCall(callDetails, responseBuilder.build())
    }

    private fun saveToHistory(number: String) {
        try {
            val sharedPrefs = applicationContext.getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE)
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