package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import org.HdrHistogram.Histogram
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.Http3TimeoutInterceptor
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
        private val properties: PaymentAccountProperties,
        private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()

        val RETRYABLE_HTTP_CODES = setOf(429, 500, 502, 503, 504)
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimiter = SlidingWindowRateLimiter(rateLimitPerSec.toLong(), Duration.ofSeconds(1))
    private val semaphore = Semaphore(parallelRequests, true)

    private var avgPT: Duration = Duration.ofMillis(requestAverageProcessingTime.toMillis() * 3)
    private var histHighestTrackableValue = requestAverageProcessingTime.toMillis() * 3
    private val hist = Histogram(requestAverageProcessingTime.toMillis(), histHighestTrackableValue, 2)

    private val client = OkHttpClient.Builder()
            .addInterceptor( Http3TimeoutInterceptor { avgPT.toMillis() } )
            .build()

    private val pool = Executors.newFixedThreadPool(parallelRequests)

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")
        val transactionId = UUID.randomUUID()
        logger.info("[$accountName] Submit for $paymentId , txId: $transactionId, amount: $amount")

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        if (isOverDeadline(deadline)) {
            logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
            }
            return
        }

        pool.submit {
            try {
                semaphore.acquire()

                val attempt = AtomicInteger(0)

                while (isNextAttemptRational(attempt.incrementAndGet(), amount)) {
                    val processPaymentReqSyncResponse = processPaymentReqSync(transactionId, paymentId, amount, deadline, attempt)

                    if (processPaymentReqSyncResponse.response || !processPaymentReqSyncResponse.retry) {
                        return@submit
                    }
                }
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Request next attempt is not rational.")
                }
                return@submit
            } catch (e: Exception) {
                when (e) {
                    is SocketTimeoutException -> {
                        logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                        }
                    }

                    else -> {
                        logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = e.message)
                        }
                    }
                }
            } finally {
                semaphore.release()
            }
        }

    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    data class ProcessPaymentReqSyncResponse(
            var retry: Boolean,
            var response: Boolean
    )

    private fun processPaymentReqSync(
            transactionId: UUID,
            paymentId: UUID,
            amount: Int,
            deadline: Long,
            attempt: AtomicInteger
    ): ProcessPaymentReqSyncResponse {
        val processPaymentReqSyncResponse = ProcessPaymentReqSyncResponse(retry = false, response = false)

        if (isOverDeadline(deadline)) {
            logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
            }
            return processPaymentReqSyncResponse
        }

        val startTime = now()

        try {
            rateLimiter.tickBlocking()

            if (isOverDeadline(deadline)) {
                logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
                }
                return processPaymentReqSyncResponse
            }

            val request = Request.Builder().run {
                url("http://localhost:1234/external/process?" +
                        "serviceName=${serviceName}&" +
                        "accountName=${accountName}&" +
                        "transactionId=$transactionId&" +
                        "paymentId=$paymentId&" +
                        "amount=$amount&" +
                        "timeout=$avgPT"
                )
                post(emptyBody)
            }.build()

            client.newCall(request).execute().use { response ->
                val body = try {
                    mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                }

                processPaymentReqSyncResponse.response = body.result

                hist.recordValue(now() - startTime)

                avgPT = Duration.ofMillis(minOf(hist.getValueAtPercentile(90.0), histHighestTrackableValue))

                logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                paymentESService.update(paymentId) {
                    it.logProcessing(body.result, now(), transactionId, reason = body.message)
                }

                processPaymentReqSyncResponse.retry = (!body.result || RETRYABLE_HTTP_CODES.contains(response.code)) && !isOverDeadline(deadline)
            }
        } catch (_: Exception) {}

        if (!processPaymentReqSyncResponse.response) {
            waitUntilNextAllowedAttempt(startTime, attempt.get())
        }

        return processPaymentReqSyncResponse
    }

    private fun waitUntilNextAllowedAttempt(startTime: Long, attempt: Int) {
        val delta = now() - startTime
        val timeToWait = max(1000 - delta, (100 * attempt).toLong())

        Thread.sleep(timeToWait)
    }

    private fun isNextAttemptRational(nextAttemptNum: Int, amount: Int): Boolean {
        if (properties.price >= amount) return false
        if (nextAttemptNum >= 4) return false

        val totalCostIfFail = nextAttemptNum * properties.price
        val expectedProfit = amount - totalCostIfFail

        return expectedProfit >= 0
    }

    private fun isOverDeadline(deadline: Long): Boolean {
        return deadline - now() < avgPT.toMillis()
    }
}

public fun now() = System.currentTimeMillis()