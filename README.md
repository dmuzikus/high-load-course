# Test-Case №5 Special

## Запуск теста
```http request
POST http://127.0.0.1:1234/test/run
Content-Type: application/json

{
  "ratePerSecond": 100,
  "testCount": 5000,
  "processingTimeMillis": 20000
}
```

## Параметры сервиса оплаты (аккаунта)

* Payment accounts list:
    + PaymentAccountProperties
        - serviceName=onlineStore
        - accountName=acc-9
        - parallelRequests=50
        - rateLimitPerSec=120
        - price=30
        - averageProcessingTime=PT0.5S

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

## Решение

Входящая и исходящая нагрузка:
* `Submit Rate` - 100
* `Processing Speed` - 120
    + `Real Rate` -> 1 / 0.5 * 50 = 100

Для достижения пропускной способности в 100 RPS нам потребуется:
```
100 / (1 / 0.5) = 50
```
потоков.

Поскольку нам нужно ровно 50 потоков ( из-за `parallelRequests`), то воспользовались `Executors.newFixedThreadPool`. <br />

Также:
* `Http3TimeoutInterceptor` - интерцептор `OkHttpClient` для динамически вычисленного таймаута (см. ниже)
* `averageProcessingTime` теперь вычисляется динамически (90 персентель) и используется в качестве `timeout` (для внешней системы оплаты) и для клиентского таймаута
    + Расчет производится с использованием `org.HdrHistogram.Histogram`
* Кол-во повторных оплат ограничено 3 попытками



## Полученные результаты

### Прибыль >= 93%
![](/doc/images/metrics_1.png)
### Успешных тестов кратно больше фейлов/ошибок
![](/doc/images/metrics_2.png)
### Отсутствует превышение лимитов (*_limit_breached)
![](/doc/images/metrics_3.png)