// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.utils

import com.intellij.openapi.project.Project
import com.intellij.serviceContainer.AlreadyDisposedException
import kotlinx.coroutines.delay
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.codewhispererruntime.model.AccessDeniedException
import software.amazon.awssdk.services.codewhispererruntime.model.CodeWhispererRuntimeException
import software.amazon.awssdk.services.codewhispererruntime.model.GetTransformationResponse
import software.amazon.awssdk.services.codewhispererruntime.model.InternalServerException
import software.amazon.awssdk.services.codewhispererruntime.model.ThrottlingException
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationJob
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationPlan
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationProgressUpdate
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStatus
import software.amazon.awssdk.services.codewhispererruntime.model.ValidationException
import software.amazon.awssdk.services.ssooidc.model.InvalidGrantException
import software.aws.toolkits.core.utils.WaiterUnrecoverableException
import software.aws.toolkits.core.utils.Waiters.waitUntil
import software.aws.toolkits.jetbrains.services.codemodernizer.CodeTransformTelemetryManager
import software.aws.toolkits.jetbrains.services.codemodernizer.client.GumbyClient
import software.aws.toolkits.jetbrains.services.codemodernizer.constants.BILLING_RATE
import software.aws.toolkits.jetbrains.services.codemodernizer.model.JobId
import software.aws.toolkits.resources.message
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class PollingResult(
    val succeeded: Boolean,
    val jobDetails: TransformationJob?,
    val state: TransformationStatus,
    val transformationPlan: TransformationPlan?,
)

/**
 * Wrapper around [waitUntil] that polls the API DescribeMigrationJob to check the migration job status.
 */
suspend fun JobId.pollTransformationStatusAndPlan(
    succeedOn: Set<TransformationStatus>,
    failOn: Set<TransformationStatus>,
    clientAdaptor: GumbyClient,
    initialSleepDurationMillis: Long,
    sleepDurationMillis: Long,
    isDisposed: AtomicBoolean,
    project: Project,
    maxDuration: Duration = Duration.ofSeconds(604800),
    onStateChange: (previousStatus: TransformationStatus?, currentStatus: TransformationStatus, transformationPlan: TransformationPlan?) -> Unit,
): PollingResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    var state = TransformationStatus.UNKNOWN_TO_SDK_VERSION
    var transformationResponse: GetTransformationResponse? = null
    var transformationPlan: TransformationPlan? = null
    var didSleepOnce = false
    val maxRefreshes = 10
    var numRefreshes = 0

    // We refresh token at the start of polling, but for some long jobs that runs for 30 minutes plus, tokens may need to be
    // refreshed again when AccessDeniedException or InvalidGrantException are caught.
    refreshToken(project)

    try {
        waitUntil(
            succeedOn = { result -> result in succeedOn },
            failOn = { result -> result in failOn },
            maxDuration = maxDuration,
            exceptionsToStopOn = setOf(
                InternalServerException::class,
                ValidationException::class,
                AwsServiceException::class,
                CodeWhispererRuntimeException::class,
                RuntimeException::class,
            ),
            exceptionsToIgnore = setOf(ThrottlingException::class)
        ) {
            try {
                if (!didSleepOnce) {
                    delay(initialSleepDurationMillis)
                    didSleepOnce = true
                }
                if (isDisposed.get()) throw AlreadyDisposedException("The invoker is disposed.")
                transformationResponse = clientAdaptor.getCodeModernizationJob(this.id)
                val newStatus = transformationResponse?.transformationJob()?.status() ?: throw RuntimeException("Unable to get job status")
                var newPlan: TransformationPlan? = null
                if (newStatus in STATES_WHERE_PLAN_EXIST) {
                    delay(sleepDurationMillis)
                    newPlan = clientAdaptor.getCodeModernizationPlan(this).transformationPlan()
                }
                if (newStatus != state) {
                    telemetry.jobStatusChanged(this, newStatus.toString(), state.toString())
                }
                if (newPlan != transformationPlan) {
                    telemetry.jobStatusChanged(this, "PLAN_UPDATED", state.toString())
                }
                if (newStatus !in failOn && (newStatus != state || newPlan != transformationPlan)) {
                    transformationPlan = newPlan
                    onStateChange(state, newStatus, transformationPlan)
                }
                state = newStatus
                numRefreshes = 0
                return@waitUntil state
            } catch (e: AccessDeniedException) {
                if (numRefreshes++ > maxRefreshes) throw e
                refreshToken(project)
                return@waitUntil state
            } catch (e: InvalidGrantException) {
                if (numRefreshes++ > maxRefreshes) throw e
                refreshToken(project)
                return@waitUntil state
            } finally {
                delay(sleepDurationMillis)
            }
        }
    } catch (e: Exception) {
        // Still call onStateChange to update the UI
        onStateChange(state, TransformationStatus.FAILED, transformationPlan)
        when (e) {
            is WaiterUnrecoverableException, is AccessDeniedException, is InvalidGrantException -> {
                return PollingResult(false, transformationResponse?.transformationJob(), state, transformationPlan)
            }
            else -> throw e
        }
    }
    return PollingResult(true, transformationResponse?.transformationJob(), state, transformationPlan)
}

// "name" holds the ID of the corresponding plan step (where table will go) and "description" holds the plan data
fun getTableMapping(stepZeroProgressUpdates: List<TransformationProgressUpdate>) = stepZeroProgressUpdates.associate {
    it.name() to it.description()
}

fun getBillingText(linesOfCode: Int): String {
    val estimatedCost = String.format(Locale.US, "%.2f", linesOfCode.times(BILLING_RATE))
    return message("codemodernizer.migration_plan.header.billing_text", linesOfCode, BILLING_RATE, estimatedCost)
}
