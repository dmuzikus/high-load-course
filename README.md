# Test-Case №5 Special

## Запуск теста
```http request
POST http://127.0.0.1:1234/test/run
Content-Type: application/json

{
  "ratePerSecond": 5,
  "testCount": 800,
  "processingTimeMillis": 5000
}
```

## Параметры сервиса оплаты (аккаунта)

* Payment accounts list:
    + PaymentAccountProperties
        - serviceName=onlineStore
        - accountName=acc-16
        - parallelRequests=5
        - rateLimitPerSec=30
        - price=30
        - averageProcessingTime=PT0.8S

## Задача

У нас есть `Shop`, клиенты которого хотят создать и оплатить заказ. Оплата происходит через внешний сервис оплаты,
для которого мы должны соблюдать лимиты (у каждого аккаунта свои). Также у него реализован `back pressure`:
при нарушении `rate limit` или `window limit` запрос будет отклонен, а деньги за обращение к нему все равно будут списаны.

## Решенные задачи

* `rate limit` внешнего сервиса оплаты соблюдается благодаря `SlidingWindowRateLimiter`
* `window limit` внешнего сервиса оплаты соблюдается благодаря `Semaphore` / `OngoingWindow`
* `deadline` запроса учитывается
* `retry` повторные попытки оплаты заказа

## Решение

Входящая и исходящая нагрузка:
* `Submit Rate` - 5
* `Processing Speed` - 30
    + `Real Rate` -> 1 / 0.8 * 5 = 6.25

При клиентском таймауте сервер продолжает выполнение запроса, а значит мы потенциально можем нарушить `window rate`. На помощь приходит `QueryParam` 'timeout' - с его помощью
мы сообщим серверу таймаут (время, через которое нужно прекратить выполнение запроса)

### Bombardier /externalsys/controller/ExternalSystemController
```kotlin
@PostMapping("/process")
suspend fun process(
    @RequestParam serviceName: String,
    @RequestParam accountName: String,
    @RequestParam transactionId: String,
    @RequestParam paymentId: String,
    @RequestParam amount: Int,
    @RequestParam timeout: Duration?, // интересующий нас query параметр
)
```

Клиентский же повысим до 3-х сигм:

### High-load-course /payments/logic/PaymentExternalServiceImpl.kt

```kotlin
private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofMillis(requestAverageProcessingTime.toMillis() * 3))
        .build()
```

## Полученные результаты

### Прибыль < 93%
Приемлемое значение с учетом затрат на повторные попытки и с учетом фейлов (недополучили прибыль)
![](/doc/images/metrics_1.png)
### Успешных тестов кратно больше фейлов/ошибок
![](/doc/images/metrics_2.png)
### Отсутствует превышение лимитов (*_limit_breached)
![](/doc/images/metrics_3.png)