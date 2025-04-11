# Test-Case №4

## Запуск теста
```http request
POST http://127.0.0.1:1234/test/run
Content-Type: application/json

{
  "ratePerSecond": 7,
  "testCount": 800,
  "processingTimeMillis": 3500
}
```

## Параметры сервиса оплаты (аккаунта)

* Payment accounts list:
    + PaymentAccountProperties
        - serviceName=onlineStore
        - accountName=acc-8
        - parallelRequests=50
        - rateLimitPerSec=10
        - price=30
        - averageProcessingTime=PT0.7S

## Задача

У нас есть `Shop`, клиенты которого хотят создать и оплатить заказ. Оплата происходит через внешний сервис оплаты,
для которого мы должны соблюдать лимиты (у каждого аккаунта свои). Также у него реализован `back pressure`:
при нарушении `rate limit` или `window limit` запрос будет отклонен, а деньги за обращение к нему все равно будут списаны.

## Решенные задачи

* `rate limit` внешнего сервиса оплаты соблюдается благодаря `SlidingWindowRateLimiter`
* `window limit` внешнего сервиса оплаты соблюдается благодаря `Semaphore` / `OngoingWindow`
* `deadline` запроса учитывается

## Решение

Входящая и исходящая нагрузка:
* `Submit Rate` - 7
* `Processing Speed` - 10
  + `Real Rate` -> 1 / 0.7 * 50 = 71.42

Основной задачей этого кейса являлось добавление повторных попыток оплаты заказа, если это рационально:
* Есть время на следующую попытку
* Код ответа предыдущей попытки говорит о том, что следующая попытка вполне может стать успешной:
  * 429
  * 500
  * 502
  * 503
  * 504

```kotlin
if (attemptNum > floor((deadline - paymentStartedAt - 1).toDouble() / (properties.averageProcessingTime.toMillis()) - 1).toLong()) {
    return
}
```

Также стоит добавить, что перед каждой новой попыткой оплаты одного и того же заказа нужно проверять `rate limit`

## Полученные результаты

### Прибыль < 93%
Приемлемое значение с учетом затрат на повторные попытки и с учетом фейлов (недополучили прибыль)
![](/doc/images/metrics_1.png)
### Успешных тестов кратно больше фейлов/ошибок
![](/doc/images/metrics_2.png)
### Отсутствует превышение лимитов (*_limit_breached)
![](/doc/images/metrics_3.png)