package com.blackcloudgroup.binaural.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.MindfulnessSessionRecord
import java.time.Instant

class HealthConnectManager(private val context: Context) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun writeMindfulnessSession(startTime: Instant, endTime: Instant, title: String) {
        try {
            val record = MindfulnessSessionRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION,
                notes = title
            )
            healthConnectClient.insertRecords(listOf(record))
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to write mindfulness session to Health Connect", e)
        }
    }
}
