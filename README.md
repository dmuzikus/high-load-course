# Test-Case №5

## Запуск теста
```http request
POST http://127.0.0.1:1234/test/run
Content-Type: application/json

{
  "ratePerSecond": 5,
  "testCount": 1000,
  "processingTimeMillis": 6000
}
```

## Параметры сервиса оплаты (аккаунта)

* Payment accounts list:
    + PaymentAccountProperties
        - serviceName=onlineStore
        - accountName=acc-7
        - parallelRequests=50
        - rateLimitPerSec=8
        - price=30
        - averageProcessingTime=PT1.2S

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
* `Processing Speed` - 8
    + `Real Rate` -> 1 / 1.2 * 50 = 41.6

Поскольку `averageProcessingTime` показывает лишь примерное среднее значение, то очевидно, что запрос может выполниться как быстрее, так и дольше.
И для случая 'дольше' нам необходимо установить `timeout`. Ведь если запрос надолго заблокирует поток, то это потенциальная потеря прибыли от запросов,
которые мы можем не успеть обработать, и также это 'урезает' `Processing Speed` . <br />
Для `OkHttpClient` установим `timeout` равный `requestAverageProcessingTime + 100`. Откуда еще 100? Берем в расчет `latency * 2` из `3way-handsnake`. 
`50` для обращения к серверу, еще `50` для ответа о готовности, а последние `50` на отправку сообщения условно 'включим' в `requestAverageProcessingTime`. <br />
Для вычисления фактического среднего времени выполнения запроса будем накапливать историю и брать 90 персентиль. <br />
Также улучшили проверку рациональности следующей попытки выполнения запроса `isNextAttemptRational` - теперь вычисляется, принесет ли хоть какую то прибыль следующий запрос (не работаем себе в убыток)

```kotlin
private fun isNextAttemptRational(attemptNum: Int, amount: Int): Boolean {
    if (properties.price >= amount) return false // Если цена запроса выше, чем прибыль целого заказа
    if (attemptNum >= floor(6000.0 / properties.averageProcessingTime.toMillis())) return false  // Если кол-во возможных попыток истекло

    val totalCostIfFail = (attemptNum + 1) * properties.price
    val expectedProfit = amount - totalCostIfFail

    return expectedProfit >= 0 // Проверяем, что получим профит, если сделаем еще одну попытку
}
```

## Полученные результаты

### Прибыль < 93%
Приемлемое значение с учетом затрат на повторные попытки (недополучили прибыль)
![](/doc/images/metrics_1.png)
### Успешных тестов кратно больше фейлов/ошибок
![](/doc/images/metrics_2.png)
### Отсутствует превышение лимитов (*_limit_breached)
![](/doc/images/metrics_3.png)