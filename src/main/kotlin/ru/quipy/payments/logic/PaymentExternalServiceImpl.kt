package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private val client = OkHttpClient.Builder().callTimeout(Duration.ofMillis(requestAverageProcessingTime.toMillis() + 100)).build()

    private val processingTimes = LinkedList<Long>()
    private val processingTimesMaxSize = rateLimitPerSec*2
    private val mutex = ReentrantLock()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")
        val transactionId = UUID.randomUUID()
        logger.info("[$accountName] Submit for $paymentId , txId: $transactionId, amount: $amount")

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        val request = Request.Builder().run {
            url("http://localhost:1234/external/process?" +
                    "serviceName=${serviceName}&" +
                    "accountName=${accountName}&" +
                    "transactionId=$transactionId&" +
                    "paymentId=$paymentId&" +
                    "amount=$amount&" +
                    "timeout=${Duration.ofMillis(requestAverageProcessingTime.toMillis() - 50)}"
            )
            post(emptyBody)
        }.build()

        if (isOverDeadline(deadline)) {
            logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
            }
            return
        }

        try {
            semaphore.acquire()

            if (isOverDeadline(deadline)) {
                logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
                }
                return
            }

            rateLimiter.tickBlocking()

            if (isOverDeadline(deadline)) {
                logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
                }
                return
            }

            processPaymentReqSync(request, transactionId, paymentId,amount, paymentStartedAt, deadline)

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

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    private fun processPaymentReqSync(
            request: Request,
            transactionId: UUID,
            paymentId: UUID,
            amount: Int,
            paymentStartedAt: Long,
            deadline: Long,
            attemptNum: Int = 0
    ) {
        if (!isNextAttemptRational(attemptNum, amount)) {
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request's next attempt isn't rational.")
            }
            return
        }

        attemptNum.inc()

        val avgPT = avgPT()

        if (isOverDeadline(deadline, avgPT)) {
            logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
            }
            return
        }

        val startTime = now()

        try {
            client.newCall(request).execute().use { response ->
                val body = try {
                    mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                }

                addProcessingTime(now() - startTime)

                logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                paymentESService.update(paymentId) {
                    it.logProcessing(body.result, now(), transactionId, reason = body.message)
                }

                if ((!body.result || RETRYABLE_HTTP_CODES.contains(response.code)) && !isOverDeadline(deadline)) {
                    Thread.sleep(max(requestAverageProcessingTime.toMillis() - 50 - (now()-startTime) + 150, 25))
                    waitUntilNextAllowedAttempt(startTime)
                    processPaymentReqSync(request, transactionId, paymentId,amount, paymentStartedAt, deadline, attemptNum)
                }
            }
        } catch (e: Exception) {
            if (!isOverDeadline(deadline, avgPT)) {
                Thread.sleep(max(requestAverageProcessingTime.toMillis() - 50 - (now()-startTime) + 150, 25))
                waitUntilNextAllowedAttempt(startTime)
                processPaymentReqSync(request, transactionId, paymentId,amount, paymentStartedAt, deadline, attemptNum)
            } else {
                throw e
            }
        }
    }

    private fun waitUntilNextAllowedAttempt(startTime: Long) {
        rateLimiter.tickBlocking()
        val delta = now() - startTime
        val timeToWait = max(1000 - delta, 5)

        Thread.sleep(timeToWait)
    }

    private fun isNextAttemptRational(attemptNum: Int, amount: Int): Boolean {
        if (properties.price >= amount) return false
        if (attemptNum >= floor(5000.0 / properties.averageProcessingTime.toMillis())) return false

        val totalCostIfFail = (attemptNum + 1) * properties.price
        val expectedProfit = amount - totalCostIfFail

        return expectedProfit >= 0
    }

    private fun addProcessingTime(duration: Long) {
        mutex.withLock {
            processingTimes.add(duration)
            while (processingTimes.size > processingTimesMaxSize) {
                processingTimes.removeFirst()
            }
        }
    }

    private fun calcPT(quantile: Double): Long {
        mutex.withLock {
            if (processingTimes.size < processingTimesMaxSize) {
                return properties.averageProcessingTime.toMillis()
            }
            val sorted = processingTimes.sorted()
            val size = sorted.size
            val quantileIndex = (size - 1) * quantile // индекс
            val lowerIndex = quantileIndex.toInt() // коругленный индекс
            val fraction = quantileIndex - lowerIndex // остаток

            return if (lowerIndex >= size - 1) {
                sorted.last()
            } else {
                (sorted[lowerIndex] + (sorted[lowerIndex + 1] - sorted[lowerIndex]) * fraction).toLong() // линейная интерполяция
            }
        }
    }

    private fun avgPT(): Duration {
        val quantileValue = calcPT(0.9)
        return Duration.ofMillis(quantileValue)
    }

    private fun isOverDeadline(deadline: Long, avgPT: Duration = avgPT()): Boolean {
        return deadline - now() < avgPT.toMillis()
    }
}

public fun now() = System.currentTimeMillis()