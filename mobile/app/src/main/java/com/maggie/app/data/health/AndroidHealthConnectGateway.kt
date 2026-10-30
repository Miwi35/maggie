package com.maggie.app.data.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/** [HealthConnectGateway] on the real client. The client is created on first use: on a phone without Health Connect it cannot be. */
class AndroidHealthConnectGateway(private val context: Context) : HealthConnectGateway {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override fun availability(): HealthConnectAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NEEDS_UPDATE
        else -> HealthConnectAvailability.NOT_INSTALLED
    }

    override suspend fun grantedPermissions(): Set<String> =
        if (availability() == HealthConnectAvailability.AVAILABLE) client.permissionController.getGrantedPermissions() else emptySet()

    override fun requestPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    override suspend fun readExerciseSessions(since: Instant, until: Instant): List<HcExerciseSession> =
        readAll(ExerciseSessionRecord::class, since, until).map {
            HcExerciseSession(
                id = it.metadata.id,
                clientRecordId = it.metadata.clientRecordId,
                exerciseType = it.exerciseType,
                title = it.title,
                start = it.startTime,
                startOffset = it.startZoneOffset,
                end = it.endTime,
            )
        }

    override suspend fun readWeights(since: Instant, until: Instant): List<HcWeight> =
        readAll(WeightRecord::class, since, until).map { HcWeight(it.metadata.id, it.time, it.zoneOffset, it.weight.inKilograms) }

    override suspend fun readBodyFat(since: Instant, until: Instant): List<HcBodyFat> =
        readAll(BodyFatRecord::class, since, until).map { HcBodyFat(it.metadata.id, it.time, it.zoneOffset, it.percentage.value) }

    // Local date-times and a one-day slicer: Health Connect cuts at each local midnight, DST days included.
    override suspend fun aggregateDays(from: LocalDate, to: LocalDate, zone: ZoneId): List<HcDayActivity> {
        val request = AggregateGroupByPeriodRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
            timeRangeSlicer = Period.ofDays(1),
        )
        return client.aggregateGroupByPeriod(request).map { group ->
            HcDayActivity(
                date = group.startTime.toLocalDate(),
                steps = group.result[StepsRecord.COUNT_TOTAL] ?: 0L,
                activeKcal = group.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            )
        }
    }

    override suspend fun insertSession(session: HcSessionToWrite): String {
        val record = ExerciseSessionRecord(
            startTime = session.start,
            startZoneOffset = session.startOffset,
            endTime = session.end,
            endZoneOffset = session.endOffset,
            // The client id makes a second insert of the same workout replace the first, not add a twin.
            metadata = Metadata.manualEntry(clientRecordId = session.clientRecordId, clientRecordVersion = 1L),
            exerciseType = session.exerciseType,
            title = session.title,
        )
        return client.insertRecords(listOf(record)).recordIdsList.first()
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, since: Instant, until: Instant): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.between(since, until), pageToken = pageToken),
            )
            records += response.records
            pageToken = response.pageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return records
    }
}
