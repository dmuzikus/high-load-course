package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.*
import org.HdrHistogram.Histogram
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.NonBlockingOngoingWindow
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.*

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
        private val properties: PaymentAccountProperties,
        private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = 15000 // если больше, то у коллеги Error падают из-за scope-а

    private val rateLimiter = SlidingWindowRateLimiter(rateLimitPerSec.toLong(), Duration.ofSeconds(1))
    private val semaphore = NonBlockingOngoingWindow(parallelRequests)

    private var avgPT: Duration = Duration.ofMillis(requestAverageProcessingTime.toMillis() * 3)
    private var histHighestTrackableValue = requestAverageProcessingTime.toMillis() * 3
    private val hist = Histogram(requestAverageProcessingTime.toMillis(), histHighestTrackableValue, 2)

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(requestAverageProcessingTime).build()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(parallelRequests) + SupervisorJob())

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        scope.launch {
            logger.warn("[$accountName] Submitting payment request for payment $paymentId")
            val transactionId = UUID.randomUUID()
            logger.info("[$accountName] Submit for $paymentId , txId: $transactionId, amount: $amount")

            // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
            // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
            paymentESService.update(paymentId) {
                it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
            }

            if (isOverDeadline(deadline) || !isNextAttemptRational(amount)) {
                logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Request not rational.")
                }
                return@launch
            }

            val timeout = max(deadline - now() - avgPT.toMillis(), 0)

            try {
                withTimeout(timeout) {
                    while (semaphore.putIntoWindow() is NonBlockingOngoingWindow.WindowResponse.Fail) {
                        delay(10)
                    }

                    while (!rateLimiter.tick()) {
                        if (isOverDeadline(deadline)) {
                            logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
                            }
                            return@withTimeout
                        }
                        delay(10)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
                }
                return@launch
            }

            try {
                val hedgedJob = CoroutineScope(Dispatchers.IO).launch hedged@{

                    delay(avgPT.toMillis())
                    while (!rateLimiter.tick()) {
                        if (isOverDeadline(deadline)) {
                            logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
                            }
                            return@hedged
                        }
                        delay(10)
                    }
                    processPaymentReqAsync(transactionId, paymentId, amount)
                }

                processPaymentReqAsync(transactionId, paymentId, amount, hedgedJob)
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
                semaphore.releaseWindow()
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    private fun processPaymentReqAsync(
            transactionId: UUID,
            paymentId: UUID,
            amount: Int,
            hedgedJob: Job? = null
    ): CompletableFuture<Void>? {

        val startTime = now()

        val request = HttpRequest.newBuilder()
                .uri(URI("http://localhost:1234/external/process?" +
                        "serviceName=${serviceName}&" +
                        "accountName=${accountName}&" +
                        "transactionId=$transactionId&" +
                        "paymentId=$paymentId&" +
                        "amount=$amount&" +
                        "timeout=$avgPT")
                )
                .version(HttpClient.Version.HTTP_2)
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(avgPT)
                .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAcceptAsync { response ->
                    val body = try {
                        mapper.readValue(response.body(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.statusCode()}")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(),false, e.message)
                    }

                    hist.recordValue(now() - startTime)

                    avgPT = Duration.ofMillis(minOf(hist.getValueAtPercentile(90.0), histHighestTrackableValue))

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                    // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                    // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, reason = body.message)
                    }
                    if (hedgedJob !== null && hedgedJob.isActive) {
                        hedgedJob.cancel()
                    }
                }.orTimeout(avgPT.toMillis(), TimeUnit.MILLISECONDS)
    }


    private fun isNextAttemptRational(amount: Int): Boolean {
        if (properties.price >= amount) return false

        val expectedProfit = amount - properties.price

        return expectedProfit >= 0
    }

    private fun isOverDeadline(deadline: Long, avgProcessingTime: Duration = avgPT): Boolean {
        return deadline - now() < avgProcessingTime.toMillis()
    }
}

public fun now() = System.currentTimeMillis()