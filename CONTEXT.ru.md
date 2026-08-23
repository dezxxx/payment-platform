# CONTEXT (русская версия)

Контекст проекта для людей и для LLM. Прочитать до того, как что-то трогать.

> Это перевод `CONTEXT.md`. Английская версия — основная: когда что-то меняется,
> правятся **оба** файла. Если тексты разошлись — прав английский.

---

## 1. Что это за репозиторий

`payment-platform` — монорепозиторий платёжной платформы.
Расположение на диске: `D:\IDEA_projects\payment-platform`.

Модуль 1 сдаёт **individuals-api**: внешний входной слой, который оркестрирует
регистрацию и аутентификацию пользователей. Сам он не хранит ничего.

| Компонент | Источник правды для |
|---|---|
| `person-service` | доменного пользователя, адреса, данных физлица |
| Keycloak | учётной записи, токенов, ролей, атрибутов аутентификации |
| `individuals-api` | ничего — только координирует двух выше |

Корень назван по платформе, а не по одному сервису, намеренно:
`transaction-service`, `payment-service`, `webhook-collector-service` и
`notification-service` приедут сюда в следующих модулях.


### Вся платформа, для будущих модулей

Записано с описания полной системы из курса, чтобы следующие модули от неё не
разъехались. Сегодня существуют только первые две строки.

| Сервис | Тип | Владеет | С кем говорит |
|---|---|---|---|
| **Individuals API** | оркестратор | ничем, базы нет | Keycloak (Admin + Token API), все доменные сервисы |
| **Keycloak** | внешний IAM | учётными данными, ролями, выдачей и проверкой JWT | — |
| **Users Service** | доменный | бизнес-профилем: имя, страна, адрес | вызывается только через Individuals API |
| **Wallets Service** | доменный | кошельками и балансами, внутренними переводами | потребитель Kafka |
| **Payments Service** | доменный | транзакциями пополнения, перевода, вывода | Currency Service, Fake Payment Provider, Kafka |
| **Currency Service** | утилитарный | курсами валют от внешнего провайдера | вызывается Payments Service |
| **Webhook Service** | интеграционный | входящими вебхуками от провайдера | публикует в Kafka, сам ничего не обрабатывает |
| **Notification Service** | инфраструктурный | уведомлениями пользователя + REST для их чтения | потребитель Kafka |
| **Fake Payment Provider** | внешняя заглушка | имитирует платёжный шлюз, отвечает вебхуком | — |

Поведение, которое описание фиксирует, и которое следующие модули не имеют
права придумать иначе:

- **Балансы меняются только по событиям Kafka** (`transaction.completed`,
  `transaction.failed`). Wallets Service никогда не правит баланс по REST-вызову.
- **`/init` ничего не пишет в базу.** Платёжная транзакция сохраняется только
  после успешного `/confirm`.
- **Эндпоинт вебхука защищён общим секретом**, проверяется на каждом вызове.
- **Webhook Service только публикует** `payment.status.updated`; что это значит,
  решают потребители.
- Notification Service реагирует на события вида `user.registered` и
  `transaction.completed`.

Два стиля интеграции сосуществуют намеренно: синхронный REST там, где ответ
нужен сейчас, Kafka там, где работа может завершиться позже. Компенсация
существует из-за первого стиля — см. «Политика компенсации» ниже.

#### Именование: `person-service` или `users-service`

Задание модуля 1 называет доменный сервис `person-service`, и репозиторий
следует ему: Gradle-модуль, контракт, публикуемый артефакт `person-client`,
схема БД `person` и свойства `individuals.person-service.*` — всё под этим
именем. Описание платформы выше называет тот же сервис **Users Service**.

Считаем это одним сервисом под двумя именами, пока курс не скажет иначе. Если
следующий модуль потребует второе имя, переименование затронет:
`settings.gradle.kts`, каталог модуля,
`person-service/openapi/person-service.yaml`, координаты публикуемого артефакта,
`PersonsApi`, схему Flyway, compose-сервис `person-postgres` и
`PersonServiceProperties`. Изменение механическое, но не маленькое — поэтому
спекулятивно не делается.

---

## 2. Зафиксированные технические решения

Это решено. Не менять без явного решения.

| Решение | Значение | Где живёт |
|---|---|---|
| Корневой проект Gradle | `payment-platform` | `settings.gradle.kts` |
| Базовый пакет | `com.dezxxx.individuals` | — |
| Maven group | `com.dezxxx` | `gradle.properties` |
| Java | 25 (Gradle toolchain, игнорирует `JAVA_HOME`) | `gradle.properties` |
| Spring Boot | 4.1.0 | `gradle/libs.versions.toml` |
| Gradle | 9.5.1 через Wrapper | `gradle/wrapper/` |
| Lombok | да — 1.18.46 | корневой `build.gradle.kts` |
| OpenAPI Generator | 7.14.0 | `gradle/libs.versions.toml` |
| Инструмент сборки | только Gradle Kotlin DSL | — |
| Образ Keycloak | 26.7.2 | `.env` |
| Testcontainers | 2.0.5, из Boot BOM | id артефактов отличаются от 1.x — см. раздел 9 |

`JAVA_HOME` на этой машине указывает на JDK 21. Это нормально и «чинить» не
надо — Gradle toolchain сам подтягивает JDK 25. JDK 25 установлена в
`C:\Users\Dez\.jdks\openjdk-25.0.1`.

### Политика версий

**Ни одна версия никогда не пишется прямо в build-скрипт.** Их держат три слоя:

| Файл | Держит |
|---|---|
| `gradle/libs.versions.toml` | все библиотеки, плагины и теги контейнеров сборки |
| `gradle.properties` | координаты, toolchain, URL Nexus |
| `.env` | теги образов, порты и учётные данные для docker-compose |

---

## 3. Архитектурные правила

0. **Реактивный стек — Spring WebFlux, не Spring MVC.** Требование задания.
   Netty вместо Tomcat, `Mono`/`Flux` вместо обычных типов возврата,
   `WebClient` вместо `RestClient`, `SecurityWebFilterChain` вместо
   `SecurityFilterChain`. Ничто в сервисе не имеет права блокировать event loop:
   никаких `.block()`, никакого блокирующего JDBC, никакого блокирующего
   HTTP-клиента. Оба OpenAPI-модуля генерируются с `reactive = true`, так что
   сами контракты типизированы через `Mono`, и блокирующая реализация просто не
   скомпилируется против них.

1. **Сначала контракт, всегда.** Спецификация OpenAPI пишется до кода. Руками
   не пишется ничего, что может выдать генератор.
   - `individuals-api/openapi/individuals-api.yaml` — из него генерируются
     серверные интерфейсы; контроллеры их *реализуют*, поэтому код не может
     разъехаться с контрактом.
   - `person-service/openapi/person-service.yaml` — из него `person-client`
     генерирует DTO и `@HttpExchange`-клиенты.

2. **Никаких рукописных DTO.** Каждый DTO выходит из генератора.

3. **Никаких зависимостей `project(":...")` между модулями.** Межмодульные
   контракты ездят артефактами, опубликованными в Nexus, по Maven-координатам.
   Модули `include`-ятся только чтобы один wrapper собирал их все.

4. **Транспорт = Spring HTTP Service Clients + WebClient.** Ни Feign, ни
   `RestClient`. `person-client` генерируется с
   `library = spring-http-interface`, прокси строятся через `WebClientAdapter`,
   так что каждый вызов возвращает `Mono`. Задание называет `RestClient`;
   почему адаптер отличается, а стиль — нет, см. таблицу отклонений.

5. **Каждая сборка валидирует контракты** — `openApiValidate` идёт перед
   `openApiGenerate`, и `check` от него зависит. Сломанный контракт валит
   сборку, а не рантайм.

### Слои individuals-api

Обязательная цепочка, только в одну сторону:

```
controller -> service -> gateway -> внешняя система
```

| Слой | Держит | Никогда не делает |
|---|---|---|
| `controller` | принимает и возвращает DTO, реализует сгенерированный `AuthApi` | бизнес-логику; прямые вызовы Keycloak или person-service |
| `service` | оркестрацию сценариев регистрации и входа | HTTP, JSON, типы Keycloak или person-client |
| `gateway` | все исходящие вызовы, один шлюз на внешнюю систему | доменные решения |
| `validation`, `error`, `config`, `metrics` | правила валидации, маппинг ошибок, обвязку безопасности, метрики | всё, что принадлежит другому слою |

Три шлюза, без исключений:

- `PersonServiceGateway` — оборачивает сгенерированный `PersonsApi`
- `KeycloakAdminGateway` — создание аккаунта, пароль, атрибуты
- `KeycloakOidcGateway` — token endpoint: логин и обновление

**Идентификаторы.** Бизнес-слой знает только доменный `user_uid`. Keycloak-овский
`sub` хранится как техническая связка (`keycloak_user_id`) и никогда не
становится главным идентификатором платформы. Ничему выше слоя шлюзов не
разрешено адресовать пользователя по нему.

**Почему шлюзы, а не репозитории.** individuals-api не владеет данными, значит
репозитория у него нет. Шлюз занимает его место в цепочке слоёв: интерфейс
снаружи, реализация за ним, внедряется в сервис через конструктор ссылочным
типом. Правило «Service и Controller не получают выдуманных интерфейсов»
продолжает действовать.

Паттерны в деле: `PersonServiceGateway` — **Adapter**, переводит типы
сгенерированного клиента в доменные, чтобы сгенерированный код не протекал
наверх. `KeycloakAdminGateway` — **Facade**, прячет несколько вызовов Admin API
за понятными методами.

### Политика компенсации

Порядок — сначала доменный пользователь, потом аккаунт в Keycloak —
гарантирует, что аккаунт Keycloak никогда не останется без доменного
пользователя. Обратный случай реален: если шаги Keycloak упали, доменный
пользователь в person-service уже создан.

Когда это происходит, сервис обязан:

- записать структурированную ошибку,
- пометить инцидент как несогласованную регистрацию,
- вернуть клиенту ошибку,
- никогда не скрывать частичный сбой.

Модуль 1 использует упрощённую форму: логирование плюс явный код ошибки
(`REGISTRATION_INCONSISTENT`). Настоящие компенсирующие транзакции — дальше по
курсу. Ничто здесь не делает молчаливых повторов и не притворяется, что
регистрация удалась.

### Наблюдаемость

**Метрики.** Actuator плюс Prometheus. По умолчанию наружу отдаётся только
`health`, поэтому эндпоинт Prometheus включается явно:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "health,info,prometheus"
  endpoint:
    health:
      probes:
        enabled: true
```

`prometheus.yml` обязан указывать на `metrics_path: /actuator/prometheus`.

Обязательные метрики приложения:

| Метрика | Тип | Смысл |
|---|---|---|
| `auth.registration` | counter | попытки регистрации |
| `auth.registration.success` | counter | успешные регистрации |
| `auth.registration.failure` | counter | неуспешные регистрации |
| `auth.login` | counter | входы |
| `auth.login.failure` | counter | неуспешные входы |
| `auth.refresh` | counter | обновления токена |
| `external.keycloak.requests` | timer | длительность вызова Keycloak |
| `external.person_service.requests` | timer | длительность вызова person-service |

Названы в точечной конвенции Micrometer, **без** суффикса `_total`, который
показан в таблице задания. Prometheus сам дописывает `_total` счётчикам, поэтому
метрика, зарегистрированная как `auth_registration_total`, соберётся как
`auth_registration_total_total`. Задание перечисляет имена **после** сбора; код
обязан регистрировать точечные.

**Трассировка.** Micrometer Tracing поверх OpenTelemetry, экспорт по OTLP:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://tempo:4318/v1/traces
```

Пространство имён `management.opentelemetry.tracing.export.otlp.*` — это Boot 4;
в Boot 3 было `management.otlp.tracing.*`. Контекст трейса передаётся по сети
только тогда, когда HTTP-клиент собран из автоконфигурированных билдеров Spring
— ещё одна причина, почему шлюзы не имеют права строить свой собственный.

**Логи.** JSON в stdout, каждая запись несёт: имя сервиса, `traceId`, `spanId`,
путь запроса, HTTP-метод, статус ответа, бизнес-код ошибки и доменный `user_uid`,
когда он известен.

### Realm Keycloak

`realm/realm-export.json` обязан определять:

- отдельный realm для курса
- confidential client для individuals-api
- service account на этом клиенте для Admin REST API
- маппер, кладущий `user_uid` в токен, либо согласованный способ получить его
  через `/me`
- роли, нужные для чтения и управления пользователями (`view-users`,
  `manage-users` из `realm-management`)
- `upConfig`, объявляющий `user_uid` атрибутом профиля пользователя — см. ниже

Секрет клиента — это учётные данные: он живёт в `.env`, никогда в экспорте,
закоммиченном в git.

#### Почему `upConfig` не опционален

Начиная с Keycloak 24 декларативный профиль пользователя включён всегда, а
неуправляемые атрибуты **выключены** по умолчанию. Атрибут, который профиль не
объявил, отбрасывается молча: `POST /admin/realms/{realm}/users` всё равно
отвечает `201`, аккаунт создаётся, а атрибута просто нет. Ни ошибки, ни
предупреждения. Протокол-мапперу после этого нечего маппить, и claim в токене
никогда не появляется.

Ровно это здесь и случилось, и было невидимо, пока токен не раскодировали.
Лечится объявлением `user_uid` в `upConfig`. Объявление `upConfig` заменяет весь
профиль по умолчанию, поэтому `username`, `email`, `firstName` и `lastName`
приходится перечислять заново с их исходными валидациями.

Правка профиля пользователя требует `manage-realm`, которой у нашего
сервис-аккаунта намеренно **нет** — это конфигурация, а не работа в рантайме,
поэтому её место в `realm-export.json` и больше нигде.

#### Проверено на живом Keycloak 26.7.2

Проверено сквозняком после `down -v` и свежего импорта:

| Проверка | Результат |
|---|---|
| `client_credentials` на `individuals-api` | токен выдан — клиент confidential, секрет совпадает с `.env`, сервис-аккаунт включён |
| `GET /admin/realms/{realm}/users` с этим токеном | `200` — `view-users` действительно выдана |
| создание пользователя с `credentials` + `attributes` одним вызовом | `201`, аккаунт полный |
| password grant этим пользователем | токены выданы, `user_uid` присутствует как claim |
| то же, но `"temporary": true` | `400 invalid_grant`, `Account is not fully set up` |
| создание второго пользователя с существующим email | `409`, `User exists with same email` |
| password grant с неверным паролем | `400 invalid_grant`, `Invalid user credentials` |

Два следствия для кода шлюзов:

- Keycloak отвечает на неверный пароль **400**, а не 401. `KeycloakOidcGateway`
  переводит: наш контракт говорит 401, и клиент не должен видеть здесь 400.
- `400 invalid_grant` покрывает и неверный пароль, и недонастроенный аккаунт.
  Код статуса сам по себе их не различает — только `error_description`.
  Описание логируем, возвращаем 401.

### Отклонения от задания курса и почему

| Задание | Здесь | Причина |
|---|---|---|
| `generatorName("java")` | `"spring"` + `library("spring-http-interface")` | то же задание требует Spring HTTP Service Clients, а не generic-клиент на Java |
| `$buildDir` | `layout.buildDirectory` | `$buildDir` удалён в Gradle 9 — сниппет из задания здесь не компилируется |
| спека лежит в `common/` | спека лежит в модуле, которому принадлежит | соответствует таблице артефактов: `person-service/openapi/person-service.yaml` |
| Spring Boot 4.0.6 | 4.1.0 | 4.1.0 вышла в GA после того, как написали задание |
| `proselyte` / `com.example` | `dezxxx` / `com.dezxxx` | плейсхолдеры задания |
| трейсы Tempo в `/tmp/tempo/traces` | `/var/tempo`, с явным путём wal | туда docker-compose монтирует именованный том; под `/tmp` трейсы умирают вместе с контейнером, а том остаётся пустым |
| `RestClient` как стандартный HTTP-клиент (и `Feign/OpenAPI` на диаграмме платформы) | Spring HTTP Service Clients поверх `WebClientAdapter` | **Стиль**, который требует задание — аннотированные `@HttpExchange`-интерфейсы за сгенерированными прокси — соблюдён точно; `person-client` генерируется из OpenAPI-документа с `library = spring-http-interface`. Отличается только адаптер. `RestClient` синхронный, а `RestClientAdapter` вообще не умеет возвращать `Mono`, так что переход означал бы перегенерацию клиента с `reactive = false` и обёртку каждого вызова в `Mono.fromCallable(...).subscribeOn(boundedElastic())`. Голый вызов припарковал бы поток event loop Netty, обслуживающий сотни соединений. C4-диаграмма того же задания подписывает этот сервис **Spring Boot WebFlux**, и два требования тянут в разные стороны; реактивный адаптер — тот, при котором WebFlux остаётся осмысленным |
| том postgres в `/var/lib/postgresql/data` | `/var/lib/postgresql` | PostgreSQL 18 сдвинул каталог данных на уровень ниже и отказывается стартовать, когда примонтирован старый путь — контейнер выходит с кодом 1 |

---

## 4. Сценарий регистрации (главный сценарий)

Восемь шагов, в этом порядке. Порядок **и есть** проектное решение, а не деталь
реализации: person-service идёт первым, потому что он выдаёт идентичность, и
его отказ не оставляет ничего, что надо было бы откатывать.

1. `individuals-api` принимает запрос регистрации
2. проверяет формат и совпадение пароля с подтверждением
3. `POST /api/v1/persons/registration` — person-service создаёт доменного
   пользователя
4. он отвечает `user_uid` — идентификатором, которым будет пользоваться вся
   платформа
5. получает токен сервис-аккаунта (`client_credentials`)
6. `POST /admin/realms/{realm}/users` — создаёт аккаунт, **без пароля**, неся
   `user_uid` пользовательским атрибутом
7. `PUT /admin/realms/{realm}/users/{id}/reset-password` — ставит пароль
8. логинится через `/realms/{realm}/protocol/openid-connect/token` (`password`)
   и возвращает access token, refresh token, время жизни и `user_uid`

Тело шага 6 — обрати внимание, чего в нём *нет*:

```json
{
  "username": "user@dezxxx.com",
  "email": "user@dezxxx.com",
  "firstName": "Ivan",
  "lastName": "Ivanov",
  "enabled": true,
  "emailVerified": false,
  "attributes": { "user_uid": ["6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33"] }
}
```

Аккаунт создаётся с **201** и пустым телом; новый id существует только в
заголовке `Location`, и вытащить его оттуда — работа `KeycloakAdminGateway`.

Тело шага 7:

```json
{ "type": "password", "value": "Str0ngP@ssw0rd", "temporary": false }
```

`temporary: false` обязателен. Временный пароль заставляет Keycloak повесить
required action `UPDATE_PASSWORD`, и шаг 8 после этого падает с
`400 invalid_grant: Account is not fully set up` — уже после того, как всё
остальное выглядело успешным.

**Keycloak умеет принять пароль прямо в шаге 6**, массивом `credentials[]`, что
сэкономило бы один поход и сделало создание аккаунта атомарным. Задание явно
называет эндпоинт `reset-password`, поэтому реализована форма из двух вызовов;
плата за это — разрыв, описанный ниже.

### Где может сломаться и что видит клиент

| Где | Клиент видит | Что уже создано |
|---|---|---|
| `@Valid` | **400** `VALIDATION_ERROR`, по строке в `details[]` на каждое кривое поле | ничего |
| person-service `409` | **409** `USER_ALREADY_EXISTS` | ничего |
| person-service `5xx` | **503** `DEPENDENCY_UNAVAILABLE` | ничего |
| person-service `400` | **500** `INTERNAL_ERROR` — их валидация и наша разошлись, это наш баг | ничего |
| *— выдан `user_uid`; дальше любой сбой разводит две системы —* | | |
| создание в Keycloak | **500** `REGISTRATION_INCONSISTENT` | доменный пользователь |
| reset-password в Keycloak | **500** `REGISTRATION_INCONSISTENT` | доменный пользователь; недоделанный аккаунт удалён |
| логин | **503** `DEPENDENCY_UNAVAILABLE` | всё — это **не** расхождение |

Последняя строка — самая тонкая. Если упал только логин, обе системы держат
одного и того же пользователя и аккаунт работает; мы просто не смогли отдать
токены, и клиент войдёт обычным способом.

**Компенсация.** Сбой на шаге 7 оставляет аккаунт, в который невозможно войти,
при этом его адрес занят — повторная регистрация отвечала бы **409** навсегда.
`RegistrationService` удаляет этот аккаунт, что освобождает адрес. Доменный
пользователь **не** откатывается — person-service не предоставляет удаления —
поэтому расхождение логируется с `user_uid` и сообщается как
`REGISTRATION_INCONSISTENT`. Это признанный предел модуля 1, и задание его
разрешает: логирование плюс явный код ошибки.

---

## 5. Внешний API

| Метод | Путь | Авторизация |
|---|---|---|
| POST | `/v1/auth/registration` | нет |
| POST | `/v1/auth/login` | нет |
| POST | `/v1/auth/refresh-token` | нет |
| GET | `/v1/auth/me` | Bearer |

### Что делает каждый эндпоинт

**`POST /registration`** — создаёт доменного пользователя, затем аккаунт в
Keycloak, затем логинится за клиента, чтобы тому не пришлось слать второй
запрос. Отвечает `201` с `TokenResponse`. Дубль email — это `409`, и поднять
его может любая из сторон: `person-service` может уже держать этот адрес, а
Keycloak отвечает `User exists with same email` своим `409`. Оба наружу
превращаются в один и тот же `409` — клиенту не важно, какое хранилище
возразило.

**`POST /login`** — ни одна наша база не задействована. Email и пароль уходят
прямо в Keycloak как `grant_type=password`; при успехе `200` с `TokenResponse`.
Неверные учётные данные приходят от Keycloak как `400 invalid_grant` и
переводятся в `401`.

**`POST /refresh-token`** — тонкая обёртка над `grant_type=refresh_token`.
Keycloak обычно ротирует и refresh-токен, так что оба токена в ответе свежие.
`200` при успехе; истёкший или отозванный токен — `401`.

**`GET /auth/me`** — защищённый. Spring Security проверяет JWT против JWKS
реалма, затем сервис читает из него `sub` и вызывает
`GET /admin/realms/{realm}/users/{id}` через `KeycloakAdminGateway`.

Вызов в Keycloak сделан намеренно, а не от лени разбирать токен. JWT — это
снимок на момент выдачи, и он остаётся валидным весь свой срок жизни, поэтому
по одному токену нельзя понять, не отключили ли аккаунт минуту назад; Admin API
может. Плюс он несёт поля, которых в токене нет — например, время создания.
Если аккаунта нет, `/me` отвечает `404`; если плох сам токен — `401`.

Роли в `CurrentUserResponse` читаются из `realm_access` токена, а не вторым
вызовом Admin API — они уже там и стоят ноль.

`registeredAt` объявлен в `CurrentUserResponse` и берётся из Keycloak-овского
`createdTimestamp`, который приходит в миллисекундах от эпохи. Перевод в UTC
делает `KeycloakAdminGateway`, его метод называется `findRegisteredAt` и
возвращает `Mono<OffsetDateTime>` — ни один слой выше шлюза не видит голое
число и не держит в руках payload Keycloak. Запись, в которую разбирается
ответ, — `KeycloakUserRepresentation`: package-private, одно поле, имя взято у
самого Keycloak, чтобы его можно было найти в их документации.

### Карта портов

Порты хоста, зафиксированы заданием курса. Значения живут в `.env`, никогда в
`docker-compose.yml`.

| Сервис | Хост | Контейнер |
|---|---|---|
| keycloak | 8080 | 8080 |
| individuals-api | 8081 | 8081 |
| keycloak-postgres | 5433 | 5432 |
| person-postgres | 5434 | 5432 |
| prometheus | 9090 | 9090 |
| tempo | 3200, 4318 | 3200, 4318 |
| loki | 3100 | 3100 |
| alloy | — | — |
| grafana | 3000 | 3000 |

`person-service` получает 8082 в модуле 2 — не из задания, выбрано потому, что
8081 занят. Записи `servers` в OpenAPI следуют этой таблице.

**Nexus работает на 8083, а не на своём дефолтном 8081.** Задание закрепляет
8081 за individuals-api, так что двигаться приходится Nexus; URL живут в
`gradle.properties`. Пока Nexus не поднят, этот недоступный репозиторий — ещё и
причина, почему IntelliJ пишет `Sources were not downloaded for ...`: обычная
сборка находит jar в Maven Central и до третьего репозитория не доходит, а IDE
спрашивает исходники у каждого репозитория и сообщает о неудаче всего поиска.

---

## 6. Структура модулей

```
payment-platform/
├── settings.gradle.kts        включает модули, резолв Nexus + mavenLocal
├── build.gradle.kts           общие соглашения (toolchain, Lombok, тесты)
├── gradle.properties          координаты, toolchain, Nexus
├── gradle/libs.versions.toml  все версии
├── docker-compose.yml
├── .env                       теги образов, порты, секреты (не в git)
├── infra/                     провижининг prometheus, tempo, grafana
├── postman/
├── docs/                      диаграммы PlantUML (компоненты, деплой,
│                              последовательность регистрации, слои) и
│                              CHEATSHEET.md — порты, пароли, команды
│                              CLASSES.md — по строке на класс
├── person-client/             сгенерированные DTO + HTTP-клиенты -> Nexus
├── individuals-api/           оркестратор
└── person-service/            только контракт + миграции Flyway (модуль 2)
```

---

## 7. Порядок сборки

`person-client` обязан существовать артефактом до того, как `individuals-api`
начнёт резолвиться. Пока Nexus не поднят, `mavenLocal()` это покрывает:

```bash
./gradlew :person-client:publishToMavenLocal
./gradlew build
```

---

## 8. Прогресс — модуль 1

### Критерии приёмки

Модуль принимается только когда каждая строка ниже верна. Это критерии задания,
дословно по смыслу.

| # | Критерий |
|---|---|
| 1 | монорепозиторий содержит individuals-api и person-service |
| 2 | контракты OpenAPI существуют и проходят `openApiValidate` |
| 3 | регистрация работает через оркестрационный сценарий |
| 4 | `user_uid` записан в Keycloak как пользовательский атрибут |
| 5 | `/login`, `/refresh-token` и `/me` работают |
| 6 | HTTP-клиент с его DTO опубликован в Nexus |
| 7 | `docker compose up --build` поднимает инфраструктуру и приложение |
| 8 | `/actuator/health` и `/actuator/prometheus` доступны |
| 9 | трейсы видны в Grafana/Tempo |
| 10 | существуют unit- и интеграционные тесты |
| 11 | главный сценарий покрыт на 80% и выше по ключевым сервисам |
| 12 | написаны README.md и CONTEXT.md |

Критерию 11 нужен инструмент покрытия, а в сборке его пока нет — JaCoCo в
списке ниже.

### Обязательные тест-кейсы

**Unit** — оркестрация, валидация, маппинг ошибок, конфликты, разбор ответов
внешних систем.

| Код | Сценарий | Ожидается |
|---|---|---|
| UT-REG-001 | валидный запрос регистрации | сначала вызван person-service, потом Keycloak, потом возвращены токены |
| UT-REG-002 | пароль и подтверждение различаются | 400, Keycloak не вызывается вообще |
| UT-REG-003 | person-service сообщает конфликт email | 409, Keycloak не вызывается вообще |
| UT-REG-004 | доменный пользователь создан, Keycloak недоступен | 502/503, частичный сбой зафиксирован |
| UT-LOG-001 | успешный вход | возвращены access и refresh токены |
| UT-LOG-002 | неверный пароль | 401 |
| UT-REF-001 | успешное обновление токена | возвращён новый access token |
| UT-ME-001 | валидный bearer-токен | возвращён текущий пользователь |

**Интеграционные** — контейнеры, настоящий HTTP к Keycloak, токены, actuator,
трассировка.

| Код | Сценарий | Ожидается |
|---|---|---|
| IT-KC-001 | регистрация против настоящего контейнера Keycloak | пользователь появляется в реалме |
| IT-KC-002 | после регистрации | атрибут `user_uid` находится в Keycloak |
| IT-KC-003 | `/login` против настоящего token endpoint | приходит настоящий JWT |
| IT-OBS-001 | `/actuator/prometheus` | метрики Prometheus читаются |
| IT-OBS-002 | после запроса | трейс виден в Tempo/Grafana |
| IT-OBS-003 | записи логов | несут `traceId` и `spanId` |
| IT-DB-001 | миграции person-service против PostgreSQL | Flyway отрабатывает |

**Где эти классы обязаны лежать.** `individuals-api/build.gradle.kts` фильтрует
`test` по `com.dezxxx.individuals.unit.*`, а `integrationTest` по
`com.dezxxx.individuals.integration.*`. Тест, положенный куда-то ещё, не
попадает ни в одну задачу и падает молча — тем, что вообще не запускается.

### Сделано

- [x] Корень монорепозитория `payment-platform`, git на `main`
- [x] Скелет каталогов для всех модулей
- [x] `.gitignore`
- [x] `gradle/libs.versions.toml` — все версии вынесены
- [x] `gradle.properties` — координаты, toolchain, Nexus
- [x] `settings.gradle.kts` — модули, Nexus + mavenLocal, заглушки под
      следующие модули курса
- [x] Корневой `build.gradle.kts` — toolchain 25, Lombok, соглашения по тестам
- [x] Gradle Wrapper 9.5.1
- [x] `person-client/build.gradle.kts` — генерация + публикация в Nexus
- [x] `individuals-api/build.gradle.kts` — Boot-приложение, contract-first,
      раздельные задачи unit / integration
- [x] `person-service/build.gradle.kts` — только валидация контракта
- [x] `individuals-api/openapi/individuals-api.yaml` — 4 эндпоинта, общая модель
      ошибок, примеры тел на каждом внешнем методе
- [x] `person-service/openapi/person-service.yaml` — 3 замороженные операции,
      схемы по DDL, та же общая модель ошибок
- [x] Flyway `V001__init_person_schema.sql`
- [x] Flyway `V002__seed_countries.sql` — все 249 записей ISO 3166-1
- [x] `.env` + `.env.example` + `docker-compose.yml` — образы закреплены, порты
      по таблице задания
- [x] **Первая настоящая `./gradlew build` — зелёная.** См. раздел 9.
- [x] `application.yml` / `application-docker.yml` / `logback-spring.xml`
- [x] `realm/realm-export.json` — realm, confidential client, service account,
      маппер `user_uid`, `upConfig`, роли realm-management
- [x] `individuals-api/Dockerfile` (ни разу не собирался)
- [x] `infra/` — провижининг prometheus, tempo, loki, alloy, grafana
- [x] `error/` — 7 классов, обе дороги (advice и обработчики безопасности)
- [x] `config/` — безопасность, HTTP-клиенты, свойства Keycloak и person-service
- [x] `gateway/` — все три шлюза, которых требует задание, плюс два переводчика
      ошибок и `GatewayErrors`
- [x] `RegistrationService` — сценарий из восьми шагов с компенсацией
- [x] `AuthenticationService` — login, refresh, `/me`
- [x] `README.md` — первая версия: что это, четыре эндпоинта, как запустить и
      честная таблица состояния
- [x] `docs/CHEATSHEET.ru.md` — у эксплуатационной шпаргалки наконец появился
      русский двойник
- [x] `docs/CLASSES.md` + `docs/CLASSES.ru.md` — каждый класс, его
      единственная работа и правило, по которому выбирается пакет для нового

### Дальше, в этом порядке

- [ ] `validation` — правило confirmPassword
- [ ] `AuthController implements AuthApi` — пока его нет, четыре эндпоинта
      объявлены в контракте, но их никто не обслуживает
- [ ] `AuthMetrics` — восемь метрик из раздела «Наблюдаемость»
- [ ] Коллекция Postman
- [ ] `README.md` — дозаполнить таблицу состояния, когда эндпоинты заработают
- [ ] Unit-тесты, затем интеграционные на Testcontainers
- [ ] JaCoCo — критерий приёмки 11 требует числа покрытия, а сборка его пока не
      умеет
- [ ] Публикация `person-client` в Nexus — критерий приёмки 6; пока отработан
      только `publishToMavenLocal`

### Рабочие договорённости

- Никаких commit и push без явного одобрения, каждый раз.
- Автор коммита всегда `Sergey Zatulsky <web7tudio@gmail.com>`.
- Сообщения коммитов на английском, `type: short description`.
- Общение по-русски, код и комментарии на английском.
- `CONTEXT.md` и `CONTEXT.ru.md` правятся вместе; английский — основной.

---

## 9. Первый прогон сборки — чего он стоил

`./gradlew build` не запускалась ни разу до 2026-08-19. Версии сверялись с
Maven Central руками, но по-настоящему ничего не резолвилось. Всплыло семь
дефектов; все исправлены, сборка зелёная от `clean`.

| # | Симптом | Корневая причина | Исправление |
|---|---|---|---|
| 1 | Wrapper не смог скачать дистрибутив, `Connect timed out` через 10 с | `services.gradle.org` редиректит на ассеты GitHub-релиза; `networkTimeout=10000` с `retries=0` сдавался на первом медленном коннекте | `networkTimeout=120000`, `retries=3`, `retryBackOffMs=2000` |
| 2 | `Could not resolve com.dezxxx:person-client` → `Username must not be null!` | блок `credentials { }` был объявлен безусловно; Gradle считает null-креды жёсткой ошибкой, а не недоступным репозиторием, и рушит весь резолв | креды подставляются, только когда заданы и `NEXUS_USERNAME`, **и** `NEXUS_PASSWORD` — в `settings.gradle.kts` и в publish-репозитории `person-client` |
| 3 | `Configuration cache state could not be cached` | следствие #2 — нерезолвимый classpath нельзя сериализовать | ушло вместе с #2 |
| 4 | `sourcesJar` использовал выход `openApiGenerate`, не объявив зависимость | сгенерированный каталог добавлялся в `sourceSets` голым путём, поэтому о нём знал только вручную связанный `compileJava` | srcDir теперь привязан к провайдеру задачи, и зависимость наследует каждый потребитель |
| 5 | `TemplateNotFoundException: pom-sb3.mustache` | генератор пытался выдать Maven `pom.xml` как supporting file; библиотека `spring-http-interface` такого шаблона не поставляет | `globalProperties = { apis, models }` — только API и модели, никаких supporting files |
| 6 | `package org.springframework.format.annotation does not exist` | сгенерированные модели аннотируют date-time свойства через `@DateTimeFormat`, который живёт в `spring-context`, а не в `spring-web` | `spring-context` добавлен в каталог и в `person-client` |
| 7 | `Could not find org.testcontainers:junit-jupiter:` (пустая версия) | Boot 4.1 тянет **Testcontainers 2.0.5**, а 2.x переименовала все артефакты модулей | `testcontainers-junit-jupiter`, `testcontainers-postgresql` |

Ещё две вещи, которые прогон закрыл:

- `val integrationTest by tasks.registering(...)` объявлен устаревшим с Gradle
  9.6 и не пережил бы Gradle 10. Переписано как
  `tasks.register<Test>("integrationTest")`. Сборка теперь не сообщает ни одного
  deprecation.
- `bootJar` падал с *«Main class name has not been configured»* — ожидаемо,
  класса приложения ещё не было. Добавлен `IndividualsApiApplication`.

### Проверено, а не предположено

- Gradle 9.5.1 прогоняет всю сборку; 9.7.1 тоже проверен и работает, но
  придержан из-за IDE — см. «Почему Gradle 9.5.1» ниже.
- Spring Boot 4.1.0, OpenAPI Generator 7.14.0, Lombok 1.18.46 резолвятся.
- `person-client` генерирует `PersonsApi` с `@HttpExchange` — Spring HTTP
  Service Clients, без Feign, как требует правило 4.
- `individuals-api` генерирует `AuthApi` только интерфейсом.
- Все три спеки проходят `openApiValidate`.
- `clean build` занимает ~6 с и производит `individuals-api.jar`.

### Известный шум, не дефекты

- ~100 предупреждений deprecation из сгенерированных исходников: OpenAPI
  Generator 7.14.0 выдаёт `org.springframework.lang.@Nullable`, устаревший в
  Spring 7. Безвредно; глушить стоит на сгенерированном source set, а не в
  генераторе.
- `Ignoring complex example on request body` — генератор не переносит блоки с
  несколькими примерами в код. Примеры остаются в контракте, где их и хочет
  видеть задание.

### Соответствие контракта заданию

`ErrorResponse` теперь совпадает с моделью, которую задание фиксирует на весь
курс: `required` — это `[timestamp, path, status, error, message, traceId]`, а
`details` — **массив строк**. Более ранний объект `ErrorDetail` нёс больше
структуры, но эту модель разделят пять сервисов — побеждает единообразие.
Информация о поле выживает в виде `"confirmPassword: must match password"`.

`UserResponse` переименован в `CurrentUserResponse`, чтобы совпасть со списком
обязательных схем из задания.

### Почему Gradle 9.5.1, а не последний

Wrapper изначально был закреплён на 9.7.1 — текущем релизе Gradle. Сборка на
нём работает. IntelliJ IDEA 2025.2.6 — нет: её Kotlin-плагин грузит *шаблоны*
скриптов из дистрибутива Gradle, но никогда не строит модель для конкретного
скрипта, поэтому каждый `build.gradle.kts` в редакторе полностью не резолвится —
вплоть до `mapOf` и `to` из стандартной библиотеки Kotlin — а `./gradlew build`
остаётся зелёной.

Отпечаток, который это опознал: **Java**-модель синхронизировалась правильно
(JDK 25 найдена, импорты Spring резолвятся, ноль ошибок в `.java`), при том что
**каждый** `.kts` был красный. Дефект сборки не может сломать стандартную
библиотеку Kotlin в одном типе файлов и только в нём.

Отброшено по пути, оба неверны:

- сами build-скрипты — `./gradlew projects` отрабатывает, а это требует, чтобы
  Gradle сперва скомпилировал каждый build-скрипт;
- `org.gradle.configuration-cache` — отключение ничего не изменило, а лог IDE
  показывал, что определения скриптов грузятся нормально. Он снова включён.

Подтверждено откатом wrapper на 9.5.1 и пересинхронизацией: редактор очистился
сразу, без единого изменения в build-файлах.

Вернуться, когда IDEA выпустит поддержку Gradle 9.7. Переход обратно — это одна
строка в `gradle/wrapper/gradle-wrapper.properties`, ничто в сборке от версии
не зависит.

---

## 10. Карта конфигурации

Те же четыре вида существуют как PlantUML в `docs/` — `component.puml`,
`deployment.puml`, `registration-sequence.puml` и `layers.puml` — для плагина
IDE. Mermaid ниже — копия, которая рендерится на GitHub без плагина. Меняется
одно — меняй и другое.

Файлы здесь работают в два разных момента времени и по большей части не знают
друг о друге. Связывает их горстка значений, которые обязаны совпадать — и это
ровно те места, что ломаются молча.

### Время сборки — контейнеров ещё не существует

```mermaid
flowchart TB
    SG["settings.gradle.kts<br/>какие модули есть"]
    GP["gradle.properties<br/>координаты, toolchain, Nexus"]
    LV["libs.versions.toml<br/>все версии"]
    PSY["person-service.yaml"]
    IAY["individuals-api.yaml"]
    PCJ["person-client.jar<br/>DTO плюс HttpExchange"]
    APIJ["individuals-api.jar"]

    SG --> APIJ
    GP --> APIJ
    LV --> APIJ
    PSY -->|openApiGenerate| PCJ
    IAY -->|openApiGenerate| APIJ
    PCJ -->|координаты Maven<br/>через mavenLocal или Nexus| APIJ
```

Спеки OpenAPI живут только здесь. В рантайме их никто не читает — код из них
уже сгенерирован.

### Время работы — кто что читает

```mermaid
flowchart TB
    ENV[".env"]
    DC["docker-compose.yml"]
    RE["realm-export.json"]
    PY["prometheus.yml"]
    TY["tempo.yml"]
    AY["application.yml"]
    ADY["application-docker.yml"]
    LB["logback-spring.xml"]

    ENV -->|подстановка| DC
    DC -->|монтирование| RE
    DC -->|монтирование| PY
    DC -->|монтирование| TY
    DC -->|блок environment| APP["процесс individuals-api"]
    AY --> APP
    ADY -->|переопределяет адреса| APP
    LB --> APP
    RE -->|import-realm при старте| KC["Keycloak"]
    PY --> PROM["Prometheus"]
    TY --> TEMPO["Tempo"]
```

`.env` подставляется в сам compose-файл. В контейнер он **не** попадает — туда
попадает только блок `environment:`. Именно поэтому individuals-api перечисляет
`KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID` и `KEYCLOAK_CLIENT_SECRET` явно.

### Время работы — кто с кем говорит

```mermaid
flowchart LR
    CL(["клиент"]) -->|8081| API["individuals-api"]
    API -->|8080| KC["keycloak"]
    API -.->|8082 — модуль 2| PS["person-service"]
    KC -->|5432| KCDB[("keycloak-postgres")]
    PSDB[("person-postgres")]
    API -->|4318 OTLP push| TEMPO["tempo"]
    PROM["prometheus"] -->|scrape 8081| API
    GRAF["grafana"] --> PROM
    GRAF --> TEMPO
```

Две противоположные механики, и их легко перепутать. **Prometheus тянет** — он
сам ходит к приложению по расписанию, поэтому адрес приложения живёт в
`prometheus.yml`. **Приложение толкает** трейсы, поэтому адрес Tempo живёт в
`application.yml`. По одной стрелке в каждую сторону.

### Значения, которые обязаны совпадать

Каждая строка — место, где несовпадение ломает что-то, не подняв ошибки нигде.

| Связка | Одна сторона | Другая сторона | Что обязано совпасть |
|---|---|---|---|
| сбор метрик | цель `individuals-api:8081` в `prometheus.yml` | имя compose-сервиса плюс `server.port` | имя и порт |
| путь метрик | `metrics_path` в `prometheus.yml` | `exposure.include` в `application.yml` | prometheus отдаётся наружу |
| трейсы | `tempo:4318/v1/traces` в `application-docker.yml` | OTLP http receiver в `tempo.yml` | хост, порт, путь |
| хранилище Tempo | `/var/tempo/...` в `tempo.yml` | `tempo-data:/var/tempo` в compose | путь |
| имя realm | `KEYCLOAK_REALM` в `.env` | `realm` в `realm-export.json` | значение |
| client id | `KEYCLOAK_CLIENT_ID` в `.env` | `clientId` в `realm-export.json` | значение |
| client secret | `KEYCLOAK_CLIENT_SECRET` в `.env` | `secret` в `realm-export.json` | значение |
| порт приложения | `server.port` в `application.yml` | `8081:8081` в compose | правая часть |

Как это кусается: меняешь `server.port` на 8080 и забываешь `prometheus.yml`.
Приложение стартует, `/actuator/prometheus` отвечает, а Prometheus тихо скрейпит
отказ в соединении. Ни одной ошибки в логе приложения — просто пустой график в
Grafana.
