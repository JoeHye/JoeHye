// Mindfulness session records are still marked experimental in connect-client 1.1.0.
@file:OptIn(ExperimentalMindfulnessSessionApi::class)

package com.blackcloudgroup.binaural.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Records finished sessions as Health Connect mindfulness sessions.
 *
 * Every entry point reports its outcome explicitly ([Availability], [WriteResult]); nothing here
 * throws for expected conditions (not installed, permission revoked, provider errors).
 */
class HealthConnectManager(context: Context) {

    private val appContext = context.applicationContext

    sealed interface Availability {
        object Available : Availability
        /** Android 13 and lower without the Health Connect app installed. */
        object NotInstalled : Availability
        object UpdateRequired : Availability
        /** Health Connect is present but too old to store mindfulness sessions. */
        object MindfulnessUnsupported : Availability
    }

    sealed interface WriteResult {
        object Written : WriteResult
        data class Skipped(val reason: String) : WriteResult
        data class Failed(val message: String, val cause: Throwable? = null) : WriteResult
    }

    val requiredPermissions: Set<String> =
        setOf(HealthPermission.getWritePermission(MindfulnessSessionRecord::class))

    fun availability(): Availability {
        return when (val status = HealthConnectClient.getSdkStatus(appContext, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_UNAVAILABLE -> Availability.NotInstalled
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UpdateRequired
            HealthConnectClient.SDK_AVAILABLE -> {
                val feature = client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)
                if (feature == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                    Availability.Available
                } else {
                    Availability.MindfulnessUnsupported
                }
            }
            else -> {
                Log.w(TAG, "Unknown Health Connect SDK status $status; treating as not installed")
                Availability.NotInstalled
            }
        }
    }

    /** Play Store page for installing/updating the Health Connect app (Android 13 and lower). */
    fun providerInstallIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
    ).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun hasRequiredPermissions(): Boolean = try {
        client().permissionController.getGrantedPermissions().containsAll(requiredPermissions)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read granted Health Connect permissions", e)
        false
    }

    /**
     * @param clientRecordId stable per session so a retried write updates instead of duplicating.
     */
    suspend fun writeMindfulnessSession(
        startTime: Instant,
        endTime: Instant,
        title: String,
        clientRecordId: String
    ): WriteResult {
        if (availability() != Availability.Available) {
            return WriteResult.Skipped("Health Connect mindfulness sessions are not available on this device")
        }
        if (!hasRequiredPermissions()) {
            return WriteResult.Skipped("Health Connect write permission is not granted")
        }
        val zone = ZoneId.systemDefault().rules
        return try {
            val record = MindfulnessSessionRecord(
                startTime = startTime,
                startZoneOffset = zone.getOffset(startTime),
                endTime = endTime,
                endZoneOffset = zone.getOffset(endTime),
                mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MUSIC,
                title = title,
                notes = null,
                metadata = Metadata.activelyRecorded(
                    clientRecordId = clientRecordId,
                    clientRecordVersion = 1L,
                    device = Device(type = Device.TYPE_PHONE)
                )
            )
            client().insertRecords(listOf(record))
            Log.i(TAG, "Logged '$title' (${Duration.between(startTime, endTime).toMinutes()} min) to Health Connect")
            WriteResult.Written
        } catch (e: SecurityException) {
            Log.e(TAG, "Health Connect rejected the write: permission missing or revoked", e)
            WriteResult.Failed("Permission was revoked", e)
        } catch (e: Exception) {
            // RemoteException/IOException from the provider, or IllegalArgumentException from record validation.
            Log.e(TAG, "Failed to write mindfulness session to Health Connect", e)
            WriteResult.Failed(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(appContext, PROVIDER_PACKAGE)

    companion object {
        private const val TAG = "HealthConnectManager"
        private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        /** Sessions shorter than this aren't worth a Health Connect entry (e.g. a quick test tap). */
        val MIN_LOGGED_SESSION: Duration = Duration.ofMinutes(1)

        /**
         * Writes outlive the service: a session can end right as the service is destroyed
         * (activity unbound, stopSelf), and a service-scoped job would be cancelled mid-write.
         */
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
