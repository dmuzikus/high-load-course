# Test-Case №1

## Запуск теста
```http request
POST http://127.0.0.1:1234/test/run
Content-Type: application/json

{
  "ratePerSecond": 11,
  "testCount": 1200
}
```

## Лимиты сервиса оплаты (аккаунта)

* Payment accounts list:
  + PaymentAccountProperties
    - serviceName=onlineStore
    - accountName=acc-3
    - parallelRequests=30
    - rateLimitPerSec=10
    - price=30
    - averageProcessingTime=PT1S

## Задача

У нас есть `Shop`, клиенты которого хотят создать и оплатить заказ. Оплата происходит через внешний сервис оплаты,
для которого мы должны соблюдать лимиты (аккаунта сервиса оплаты). Также у него реализован `back pressure`:
при нарушении `rate limit` или `window limit` запрос будет отклонен, а деньги за обращение к сервису все равно будут списаны.

## Решение

Нужно ограничить `rate` во внешний сервис оплаты для соблюдения его лимитов. Воспользуемся готовыми `rateLimiters` из `common/utils`.
Поскольку нагрузка может быть распределена неравномерно (пиковые значения на стыке двух окон), то было принято решение воспользоваться `SlidingWindowRateLimiter` (скользящее окно), которое решает данную проблему
в отличие от `FixedWindowRateLimiter` (фиксированное окно)

## Исправления

Также было замечено `condition race` и неверные знаки:

### Было
```kotlin
override fun tick(): Boolean {
  if (sum.get() > rate) { // Тут должен быть нестрогий знак
      return false
  } else {
      if (sum.get() <= rate) { // 10 <= 10 ? -> sum=11  | Неверное поведение
          queue.add(Measure(1, System.currentTimeMillis()))
          sum.incrementAndGet()
          return true
      } else return false
  }
}
```

### Стало
```kotlin
override fun tick(): Boolean {
  mutex.withLock { // Поток захватывает общий ресурс и блокирует его для других
    return if (sum.get() < rate) {
      queue.add(Measure(1, System.currentTimeMillis()))
      sum.incrementAndGet()
      true
    } else false
  }
}
```

## Полученные результаты

### Прибыль >= 93.3%
![](/doc/images/metrics_1.png)
### Успешных тестов кратно больше (0 фейлов/ошибок)
![](/doc/images/metrics_2.png)
### Отсутствует превышение лимитов (*_limit_breached)
![](/doc/images/metrics_3.png)