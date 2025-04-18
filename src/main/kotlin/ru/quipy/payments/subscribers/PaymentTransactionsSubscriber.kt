package ru.quipy.payments.subscribers

import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.api.PaymentProcessedEvent
import ru.quipy.streams.AggregateSubscriptionsManager
import ru.quipy.streams.annotation.RetryConf
import ru.quipy.streams.annotation.RetryFailedStrategy
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.ConcurrentLinkedQueue
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class PaymentTransactionsSubscriber {

    companion object {
        private const val batchSize: Int = 1_000
    }

    val paymentLog: ConcurrentHashMap<UUID, ConcurrentLinkedQueue<PaymentLogRecord>> = ConcurrentHashMap()
    private val queue = LinkedBlockingQueue<PaymentLogRecord>(20_000)

    private val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    @Autowired
    lateinit var subscriptionsManager: AggregateSubscriptionsManager

    @PostConstruct
    fun init() {
        subscriptionsManager.createSubscriber(
            PaymentAggregate::class,
            "payments:payment-processings-subscriber",
            retryConf = RetryConf(1, RetryFailedStrategy.SKIP_EVENT)
        ) {
            `when`(PaymentProcessedEvent::class) { event ->
                val logRecord = PaymentLogRecord(
                    event.processedAt,
                    status = if (event.success) PaymentStatus.SUCCESS else PaymentStatus.FAILED,
                    event.amount,
                    event.paymentId
                )

                processNewLogRecord(logRecord)
            }
        }
    }

    class PaymentLogRecord(
        val timestamp: Long,
        val status: PaymentStatus,
        val amount: Int,
        val transactionId: UUID,
    )

    @PreDestroy
    fun shutdown() {
        if (queue.isNotEmpty()) {
            processBatchLog(queue.size)
        }
    }

    fun processNewLogRecord(logRecord: PaymentLogRecord) {
        queue.put(logRecord)

        scope.launch {
            if (queue.size >= batchSize) {
                processBatchLog(batchSize)
            }
        }
    }

    fun processBatchLog(batchSize: Int) {
        try {
            val batch = ArrayList<PaymentLogRecord>()
            queue.drainTo(batch, batchSize)

            batch.groupBy { it.transactionId }.forEach { (txId, records) ->
                paymentLog
                        .computeIfAbsent(txId) { ConcurrentLinkedQueue() }
                        .addAll(records)
            }
        } catch (_: Exception) {}
    }

    enum class PaymentStatus {
        FAILED,
        SUCCESS
    }
}