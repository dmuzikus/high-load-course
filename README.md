# Test-Case №2

## Запуск теста
```http request
POST http://127.0.0.1:1234/test/run
Content-Type: application/json

{
  "ratePerSecond": 2,
  "testCount": 100,
  "processingTimeMillis": 60000
}
```

## Параметры сервиса оплаты (аккаунта)

* Payment accounts list:
    + PaymentAccountProperties
        - serviceName=onlineStore
        - accountName=acc-5
        - parallelRequests=5
        - rateLimitPerSec=3
        - price=30
        - averageProcessingTime=PT4.9S

## Задача

У нас есть `Shop`, клиенты которого хотят создать и оплатить заказ. Оплата происходит через внешний сервис оплаты,
для которого мы должны соблюдать лимиты (у каждого аккаунта свои). Также у него реализован `back pressure`:
при нарушении `rate limit` или `window limit` запрос будет отклонен, а деньги за обращение к нему все равно будут списаны.

## Решенные задачи

* `rate limit` внешнего сервиса оплаты соблюдается благодаря `SlidingWindowRateLimiter`

## Решение

В данном кейсе нужно было учесть другой лимит внешней системы оплаты - `window limit`. Как это сделать?
Для решения воспользовались примитивом синхронизации `Semaphore` из `java.util.concurrent`, который позволяет выполниться свою критическу секцию не более чем N потокам.
Важно отметить, что нужно не забыть 'отпустить' захваченные ресурсы, иначе мы попадем в ситуацию `Deadlock`. <br />
В итоге взяли `OngoingWindow` из `common/utils`.

## Полученные результаты

### Прибыль >= 93.3%
![](/doc/images/metrics_1.png)
### Успешных тестов кратно больше (0 фейлов/ошибок)
![](/doc/images/metrics_2.png)
### Отсутствует превышение лимитов (*_limit_breached)
![](/doc/images/metrics_3.png)