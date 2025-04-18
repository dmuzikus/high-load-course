# Test-Case №7

## Запуск теста
```http request
POST http://127.0.0.1:1234/test/run
Content-Type: application/json

{
    "ratePerSecond": 1000,
    "testCount": 200000,
    "processingTimeMillis": 50000
}
```

## Параметры сервиса оплаты (аккаунта)

* Payment accounts list:
    + PaymentAccountProperties
        - serviceName=onlineStore
        - accountName=acc-12
        - parallelRequests=20000
        - rateLimitPerSec=1100
        - price=30
        - averageProcessingTime=PT10S

## Задача

У нас есть `Shop`, клиенты которого хотят создать и оплатить заказ. Оплата происходит через внешний сервис оплаты,
для которого мы должны соблюдать лимиты (у каждого аккаунта свои). Также у него реализован `back pressure`:
при нарушении `rate limit` или `window limit` запрос будет отклонен, а деньги за обращение к нему все равно будут списаны.

## Решенные задачи

* `rate limit` внешнего сервиса оплаты соблюдается благодаря `SlidingWindowRateLimiter`
* `window limit` внешнего сервиса оплаты соблюдается благодаря `Semaphore` / `OngoingWindow`
* `deadline` запроса учитывается
* `retry` повторные попытки оплаты заказа
* `timeout` на сервере
* `averageProcessingTime` вычисляется динамически (90 персентиль)

## Решение

Входящая и исходящая нагрузка:
* `Submit Rate` - 1000
* `Processing Speed` - 1100
    + `Real Rate` -> 1 / 10 * 20_000 = 2_000

Для достижения пропускной способности в 1_000 RPS нам потребуется минимум
```
1_000 / ( 1 / 10 ) = 10_000
```

потоков.

Это слишком много, использовать тяжеловесный ThreadPool нерационально. Поэтому попробовали `CoroutineScope`<br />
Заменили Http клиента на `java.net.http.HttpClient`, т.к. `okhttp3` не поддерживает `http2`.<br />
Синхронные вызовы клиента заменили на асинхронные, в связи с этим вместо `retry` реализовали `hedged-requests`.<br />
Вместо `tickBlocking`:
```kotlin
while (!rateLimiter.tick()) {
    if (isOverDeadline(deadline)) {
        logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId")
        paymentESService.update(paymentId) {
            it.logProcessing(false, now(), transactionId, reason = "Request deadline.")
        }
        return@launch
    }
    delay(10)
}
```
Вместо `semaphore.acquire()`:
```kotlin
while (semaphore.putIntoWindow() is NonBlockingOngoingWindow.WindowResponse.Fail) {
    delay(10)
}
```
Изменили `maxConcurrentStreams` у `jetty`:

```kotlin
@Bean
fun jettyServerCustomizer(): JettyServletWebServerFactory {
    val jettyServletWebServerFactory = JettyServletWebServerFactory()

    val c = JettyServerCustomizer {
        (it.connectors[0].getConnectionFactory("h2c") as HTTP2CServerConnectionFactory).maxConcurrentStreams = 1_000_000
    }

    jettyServletWebServerFactory.serverCustomizers.add(c)
    return jettyServletWebServerFactory
}
```

`PaymentTransactionsSubscriber` теперь обрабатывает `PaymentProcessedEvent`- ы пачками.

## Полученные результаты

#### Мой ПК

RAM 16 GB, 11th Gen Intel(R) Core(TM) i5-11400F @ 2.60GHz   2.59 GHz

![](/doc/images/my_bad_pc.png)

##### Прибыль < 93%
![](/doc/images/my_metric_1.png)
##### Успешных тестов крайне мало
![](/doc/images/my_metric_2.png)
##### Отсутствует превышение лимитов (*_limit_breached)
![](/doc/images/my_metric_3.png)

#### Компьютер коллеги

RAM 32 GB, AMD Ryzen 9 7900X OEM

##### Прибыль < 93%
![](/doc/images/colleague_metric_1.jpg)
##### Успешных тестов > 50%
![](/doc/images/colleague_metric_2.jpg)
##### Отсутствует превышение лимитов (*_limit_breached)
![](/doc/images/colleague_metric_3.jpg)