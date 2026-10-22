# CONTEXT - person-service (русская версия)

Модуль 2. Общие правила, порты и как запускается платформа: [`CONTEXT.ru.md`](../CONTEXT.ru.md).

---

## 1. Именование: `person-service` или `users-service`

Задание модуля 1 называет доменный сервис `person-service`, и репозиторий
следует ему: Gradle-модуль, контракт, публикуемый артефакт `person-client`,
схема БД `person` и свойства `individuals.person-service.*` — всё под этим
именем. Описание платформы в §1 `../CONTEXT.ru.md` называет тот же сервис **Users Service**.

Считаем это одним сервисом под двумя именами, пока курс не скажет иначе. Если
следующий модуль потребует второе имя, переименование затронет:
`settings.gradle.kts`, каталог модуля,
`person-service/openapi/person-service.yaml`, координаты публикуемого артефакта,
`PersonsApi`, схему Flyway, compose-сервис `person-postgres` и
`PersonServiceProperties`. Изменение механическое, но не маленькое — поэтому
спекулятивно не делается.

---

## 2. Прогресс — модуль 2 (person-service)

Сценарий — практика модуля 2, шаг за шагом, в порядке её «шагов реализации».

### Согласованные отклонения от ТЗ модуля 2

| ТЗ | У нас | Согласовано |
|---|---|---|
| `/api/v1/users`, корень — user, он содержит individual | `/api/v1/individuals`, корень — **физлицо**, оно содержит своего user, user — адрес; DTO `CreateIndividualRequest`, `UpdateIndividualRequest`, `IndividualResponse` | с преподавателем на ревью, 2026-10-11: корень — физлицо (user — учётная запись, которую держит физлицо, а позже и юрлицо), API и DTO строятся вокруг него |
| `DELETE /api/v1/users/{id}` удаляет весь агрегат | `DELETE /api/v1/individuals/{id}` — мягкое удаление: физлицо получает `ARCHIVED` и `archived_at`, строки остаются, чтение его больше не находит, email остаётся занятым | с преподавателем, 2026-10-11 |
| компенсация регистрации через этот `DELETE` | отдельный `DELETE /api/v1/individuals/compensate-registration/{id}`: полное удаление физлица, user и адреса, вызывает только individuals-api | преподаватель, 2026-10-11: «Нужен и ты должен его добавить», «Через DELETE не стоит — нужна логика именно компенсации» |
| DDL: `passport_number`, `phone_number`, связи допускают NULL; `users.secret_key` | NOT NULL на всех частях агрегата, `secret_key` убран, `status` ограничен `NEW`, `VERIFIED`, `ARCHIVED` | наше решение по требованию самого ТЗ «исправьте обнаруженные разрывы»: агрегат рождается целиком |

Примеры запросов из ТЗ — как их принимает наш API:

| ТЗ | У нас |
|---|---|
| `POST /api/v1/users` `{email, firstName, lastName, address, individual: {passportNumber, phoneNumber}}` | `POST /api/v1/individuals` `{passportNumber, phoneNumber, user: {email, firstName, lastName, address}}` |
| `GET /api/v1/users/by-email?email=…` | `GET /api/v1/individuals/by-email?email=…` |
| `PATCH /api/v1/users/{id}` `{firstName, lastName, address: {city}}` | `PATCH /api/v1/individuals/{id}` `{user: {firstName, lastName, address: {city}}}` |
| `DELETE /api/v1/users/{id}` | `DELETE /api/v1/individuals/{id}` (архивировать) или `DELETE /api/v1/individuals/compensate-registration/{id}` (удалить) |

После мягкого удаления email остаётся занятым: повторная регистрация отвечает
`409`. API его никогда не освобождает — это осознанное действие саппорта.

### Критерии приёмки — модуль 2, как согласовано

| # | Сценарий | Ожидание |
|---|---|---|
| 1 | создание со всеми обязательными полями | `201`; физлицо, user и адрес в одной транзакции; статус `NEW` |
| 2 | создание с уже занятым email, в любом регистре | `409 EMAIL_ALREADY_EXISTS`, ничего не сохранено |
| 3 | создание с отсутствующим или пустым полем (включая `email: ""`) или неизвестной страной | `400`, каждое поле названо в `details`, ничего не сохранено |
| 4 | чтение по id, по email в другом регистре | `200`, агрегат целиком |
| 5 | чтение неизвестного id или архивного физлица | `404 INDIVIDUAL_NOT_FOUND` |
| 6 | PATCH части полей физлица, его user и адреса | `200`; меняется только присланное, в одной транзакции и одной ревизии аудита |
| 7 | два одновременных PATCH одного физлица | один `200`, второй `409 CONCURRENT_MODIFICATION` |
| 8 | `DELETE /{id}` | `204`; строки остаются с `ARCHIVED` и `archived_at`; дальше GET, by-email, PATCH и DELETE отвечают `404` |
| 9 | повторная регистрация того же email после `DELETE /{id}` | `409` — email остаётся занятым |
| 10 | `DELETE /compensate-registration/{id}` | `204`; ни одной строки физлица, user и адреса не осталось; тот же email снова регистрируется с `201` |
| 11 | регистрация через individuals-api, когда упал шаг Keycloak | физлицо удалено компенсацией, клиент получает исходную ошибку, email свободен |
| 12 | `/me` после смены фамилии в person-service | новая фамилия — профиль читается из person-service |
| 13 | создание, изменение, архивация, компенсация | каждое оставляет ревизию Envers с флагами `*_mod` на изменённых полях |
| 14 | любая ошибка | тело RFC 9457 (`application/problem+json`) с `timestamp`, `error`, `traceId`, `details`; заголовки, которых требует Spring, сохраняются (`Allow` у `405`) |

### Решения

| Решение | Значение |
|---|---|
| Веб-стек | Spring MVC поверх JPA — блокирующий, в отличие от individuals-api |
| Маппинг DTO ↔ сущность | руками, без MapStruct |
| Корень агрегата | **физлицо (individual)**. User — учётная запись (email, имя, адрес), которую держит физлицо, а позже и юрлицо; никогда не наоборот. `IndividualEntity` владеет `user` (`individuals.user_id`, cascade ALL), `UserEntity` о физлице не знает. `/api/v1/users` из ТЗ с user наверху переделан после ревью |
| Имена DTO | у сгенерированных Java-классов суффикс `Dto` (`modelNameSuffix`): `IndividualResponseDto` рядом с `IndividualEntity` — в коде сразу видно, что есть что. Схемы: `CreateIndividualRequest`, `UpdateIndividualRequest`, `IndividualResponse`, `ProblemResponse`; user и address — вложенные объекты внутри них |
| API | `/api/v1/individuals`: POST → 201, GET `/{id}`, GET `/by-email?email=`, PATCH `/{id}`, DELETE `/{id}` → 204 (мягкое), DELETE `/compensate-registration/{id}` → 204 (полное), обслуживает `IndividualRestControllerV1`. Тело: физлицо сверху (`passportNumber`, `phoneNumber`, `status`…), внутри его `user`, внутри user — `address` |
| Создание | `passportNumber`, `phoneNumber`, `user`, его `address` и `countryAlpha3` **обязательны**: агрегат рождается целиком (физлицо, user, адрес в одной транзакции), а PATCH меняет только то, что уже есть. Неизвестный код страны → 400 |
| Идентификатор | `individuals.id` (UUID) — доменный идентификатор: его возвращает API и принимает в `/{id}`; individuals-api кладёт его в Keycloak как `user_uid`. person-service о Keycloak ничего не знает |
| Email | уникален без учёта регистра (индекс `lower(email)`); через PATCH не меняется |
| Ошибки | RFC 9457 (`type`, `title`, `status`, `detail`, `instance`, `application/problem+json`) плюс общие для курса `timestamp`, `error`, `traceId`, `details` как доп. поля — одно тело устраивает оба задания. `type` = `https://example.org/problems/<код-в-kebab-case>`, как в примере ТЗ, `title` — название HTTP-статуса, `detail` — текст из `ErrorCode`. Один `GlobalExceptionHandler` наследует `ResponseEntityExceptionHandler`, поэтому ошибки самого Spring (кривой JSON, 405, 415, `@Valid`) получают то же тело. Тексты ошибок всегда по-английски (`spring.web.locale: en`, фиксированный resolver) |
| Миграции | по одной на шаг: схема → таблицы → индексы и ограничения → страны; аудитные таблицы Envers — V005 |
| Удаление | две операции. `DELETE /{id}` — мягкое удаление: физлицо получает `ARCHIVED` и `archived_at`, user и адрес не меняются, а все чтения (`/{id}`, `/by-email`, PATCH, DELETE) находят только активных. `DELETE /compensate-registration/{id}` — полное удаление агрегата, его вызывает только individuals-api, когда в регистрации упала часть Keycloak: данные несостоявшейся регистрации не храним. Email архивного физлица остаётся занятым |
| Схема | DDL из ТЗ ужесточён под агрегат: `individuals.user_id`, `passport_number`, `phone_number`, `users.address_id`, `addresses.country_id` — NOT NULL; у физлица есть `created`/`updated`, как у user и адреса; `users.secret_key` удалён (не использовался, а person-service не хранит секретов других систем). `status` — `NEW`, `VERIFIED` или `ARCHIVED` (enum `IndividualStatus`, CHECK в базе, enum в контракте); создание ставит `NEW`, мягкое удаление — `ARCHIVED`; `VERIFIED` — основа для будущей верификации |
| Индексы | там, где они нужны запросу или внешнему ключу, и никогда дважды: UNIQUE уже сам является индексом |
| Аудит | Hibernate Envers пишет историю в свою схему `person_history` (`users_history`, `addresses_history`, `individuals_history`, `revinfo`), Spring Data Envers её читает (`RevisionRepository` у User и Individual). Флаги изменённых полей у всех трёх аудируемых сущностей — ТЗ требует минимум User и Individual, а PATCH чаще всего меняет адрес. Страны не аудируются: их заполняет миграция, через API они не меняются. Удалённый пользователь сохраняет данные в последней ревизии (`store_data_at_delete`) |
| Сущности | `IndividualEntity` — корень агрегата: владеет user, user владеет адресом (`@OneToOne`, cascade ALL), поэтому весь агрегат сохраняется и удаляется через физлицо; все связи LAZY |
| Время | в сущностях `Instant` — точка во времени, одинаковая, где бы ни работал сервер; ложится в обычные колонки `TIMESTAMP` из DDL (`preferred_instant_jdbc_type`) и хранится в UTC (`hibernate.jdbc.time_zone`); в JSON — с `Z`. От `LocalDateTime` отказались: `@CreationTimestamp` заполнял его местным временем сервера |
| Локальный порт | 8082 в compose; 8092 при запуске из IDE (`SERVER_PORT=8092 ./gradlew bootRun`), потому что 8082 занят compose |
| История Flyway | `person.flyway_schema_history`, рядом с таблицами (`spring.flyway.default-schema: person`). Если оставить её на `search_path`, она «переезжает»: пользователь БД тоже зовётся `person`, поэтому `"$user"` до V001 указывал на `public`, а после — на `person`, и второй старт падал |
| Репозитории | четыре, по одному на сущность, как у преподавателя. Email ищется через `lower(email) = lower(:email)`, а не встроенным `…IgnoreCase` (тот делает `upper()` и не может использовать `uk_users_email_lower`) |
| Исключения | одно `PersonException` с `ErrorCode` (статус + текст), тот же образец, что `ApiException` в модуле 1. `VALIDATION_ERROR` / `MALFORMED_REQUEST` / `COUNTRY_NOT_FOUND` 400, `INDIVIDUAL_NOT_FOUND` 404, `EMAIL_ALREADY_EXISTS` / `CONCURRENT_MODIFICATION` 409, от фреймворка `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR` 500. Второе создание, проскочившее проверку email, останавливает `uk_users_email_lower`, и ответ всё равно 409 |
| Транзакции | `IndividualService` — `@Transactional(readOnly = true)`; создание, изменение и удаление открывают пишущую транзакцию. Ответ собирается внутри транзакции: ленивые связи грузятся там, и ничего ленивого из сервиса не выходит |
| Клиент | `person-service-client` заменяет `person-client`; individuals-api переходит на него и получает компенсацию регистрации — `DELETE /compensate-registration/{id}`, если упала часть Keycloak |
| Эволюция контракта | модуль 2 ещё не публикует события в Kafka, но следующие модули понесут те же данные событиями Avro со схемами в Confluent Schema Registry, а он отклоняет несовместимую схему. OpenAPI-контракт уже сейчас следует тем же правилам: поля только добавляются, и только необязательные, со значением по умолчанию; опубликованное поле никогда не переименовывается, не удаляется и не меняет тип; ломающее изменение — новая версия (`/api/v2/...`), а не правка v1. События переиспользуют имена и форматы REST: id физлица как `user_uid`, время в UTC ISO-8601, `status` строкой (`NEW`, `VERIFIED`, `ARCHIVED`) |

### Сделано

- [x] Сборка: Spring MVC, JPA, Flyway, Envers, actuator, OTLP; версии плагинов в
      собственном `build.gradle.kts`, без общего каталога
- [x] Контракт переписан по заданию и проходит валидацию
- [x] Серверные интерфейсы генерируются в `build/generated/openapi`
      (`interfaceOnly` + `delegatePattern` + `useSpringBoot4`, `@Nullable` из JSpecify)
- [x] C4-диаграммы внутри модуля, `person-service/diagrams-c4/`: контейнеры, компоненты и
      последовательность (создание, архивация, компенсация), EN + RU, рядом с каждой PNG
- [x] `person-service-client` генерируется своей задачей (`spring-http-interface`,
      реактивный — для WebFlux в individuals-api) в свой source set и свой jar;
      в `person-service.jar` из него ничего не попадает
- [x] Миграции V001–V004 по DDL из задания, с отличиями:
      `countries.name` — `VARCHAR(64)`, а не 32 (семь названий ISO длиннее);
      ограничения уникальности и внешние ключи — в V003 рядом с индексами, а не
      в таблицах; у `addresses.country_id` появился индекс; `users.address_id`
      — UNIQUE: двое могут жить по одному адресу, но у каждого своя строка
      адреса, и переезд одного никогда не переселяет другого; индексы
      `idx_individuals_user_id` и `idx_users_address_id` из задания убраны как
      дубли этих UNIQUE
- [x] JPA-сущности `UserEntity`, `AddressEntity`, `IndividualEntity`,
      `CountryEntity` и минимальный `application.yml` (`ddl-auto: validate`,
      UTC, `open-in-view: false`). Проверено на compose-базе: Flyway применяет
      V001–V004, Hibernate принимает все сущности
- [x] Три маппера руками, по одному на сущность, как в проекте преподавателя:
      `AddressMapper`, `IndividualMapper` и `UserMapper` для агрегата, который
      делегирует двум другим. Сущность ↔ сгенерированный DTO и ничего больше —
      ни запросов в базу, ни бизнес-решений. Страну ищет сервис и передаёт
      готовой; в PATCH `null` значит «не трогать». Перевод времени в UTC, нужный
      обоим мапперам, лежит в `util/DateTimeUtil`
- [x] `IndividualService` с шестью операциями, четыре репозитория, `PersonException`
      + `ErrorCode`. Создание проверяет email, ищет страну (сначала alpha3, потом
      alpha2), ставит статус `NEW` и `filled`; изменение делает `flush`, чтобы в
      ответе уже были новая версия и время. Стартует на compose-базе: Spring Data
      принимает все запросы репозиториев
- [x] Envers: V005 создаёт `person_history` с тремя таблицами истории и
      `revinfo` (своя `RevisionInfoEntity`); `@Audited(withModifiedFlag = true)` у
      User, Address, Individual; флаги `*_mod` названы по колонкам. Проверка схемы
      при старте проходит на compose-базе; через API создание, PATCH и удаление
      оставляют ревизии 0, 1 и 2
- [x] `IndividualRestControllerV1` реализует сгенерированный `IndividualsApi`: пять методов в одну
      строку, 201 / 200 / 204, без логики. Swagger UI на `/swagger-ui.html` показывает
      сам файл контракта (springdoc, так же, как в individuals-api)
- [x] Слой ошибок (шаг 7): `ProblemResponse` в контракте несёт поля курса,
      `GlobalExceptionHandler` отвечает на любую ошибку в формате RFC 9457. Проверено
      вживую: 400 / 404 / 405 / 409 / 415, а из 12 параллельных PATCH проигравшие
      оптимистическую блокировку получают 409 `CONCURRENT_MODIFICATION`
- [x] Postman-коллекция `postman/person-service.postman_collection.json`: 22
      запроса, 106 проверок — пять операций, каждая ошибка со своим телом RFC 9457,
      удаление и то, что они оставили в `/actuator/prometheus`; повторно запускается
      на той же базе
- [x] Наблюдаемость, сторона сервиса: открыты `health`, `info`, `prometheus`,
      `metrics`; JSON-логи ECS с `traceId`, `spanId`, `service.name` и `user_uid`;
      трейсы по OTLP в Tempo, каждый метод `IndividualService` — спан (`individualService.create`…)
      под HTTP-спаном и таймер `individual_operations_seconds{operation}`. Проверено с
      локальным Tempo: `spanId` строки лога — это спан `individualService.create` того же трейса.
      `user_uid` — ещё и атрибут спана `individualService.*`, Tempo находит запросы физлица
      (`{span.user_uid="..."}`); метрикой-меткой — никогда: ряд на каждого пользователя
      утопит Prometheus. Имя `user_uid`, а не `user.id` из OpenTelemetry, чтобы одно имя
      находило пользователя в individuals-api, токене Keycloak и person-service

- [x] Docker-образ и compose: `person-service/Dockerfile` собирает jar внутри образа
      (контекст — папка `person-service`, свой id кэша Gradle, чтобы собираться рядом с
      individuals-api); compose запускает настоящий сервис на 8082 только после того,
      как `person-postgres` стал healthy, healthcheck на `/actuator/health` (bash
      `/dev/tcp` — в JRE-образе нет curl). Prometheus собирает `person-service:8082`,
      Alloy хранит `user_uid` как structured metadata, в Grafana дашборд
      `person-service`. Проверено на чистом стенде: обе Postman-коллекции зелёные,
      строка из Loki открывает свой трейс в Tempo
- [x] Тесты person-service, given / when / then: 39 unit (IndividualService, мапперы,
      GlobalExceptionHandler, DateTimeUtil, UserUidLogFilter) и 19 интеграционных на
      настоящем PostgreSQL (Testcontainers): все сценарии из ТЗ — создание, повтор
      email -> 409, чтение по id и по email, один PATCH физлица, user и адреса, откат
      частичного изменения, удаление без единой оставшейся строки, ревизии Envers и
      флаги изменений, health и метрики. Покрытие 97% строк, сборка падает ниже 80%
      (`./gradlew check`)
- [x] `person-service-client` публикует в Nexus сам person-service (`make publish`):
      jar клиента и pom со списком его зависимостей
- [x] individuals-api на новом клиенте, из Nexus по координатам:
      - регистрация принимает адрес и individual и передаёт их в
        `POST /api/v1/individuals`; неизвестный код страны — 400 `VALIDATION_ERROR` с
        указанием адреса
      - компенсация: если часть в Keycloak упала, физлицо удаляется
        (`DELETE /api/v1/individuals/compensate-registration/{id}`), а вызывающий получает исходную ошибку;
        `REGISTRATION_INCONSISTENT` — только если и удаление упало. Проверено вживую:
        Keycloak остановлен -> 503, строки не осталось, в истории Envers создание и
        удаление
      - `KeycloakTokenResponseDto`, `KeycloakUserRequestDto`
      - ждёт healthy person-service и healthy Keycloak и имеет свой healthcheck; заглушка WireMock и
        старый модуль `person-client` удалены
- [x] Оба сервиса отвечают по-английски независимо от `Accept-Language` клиента и
      языка сервера (`spring.web.locale: en`, фиксированный resolver): API отдаёт
      стабильные тексты и коды, переводит интерфейс
- [x] Трейсы идут через Alloy, агента: оба сервиса отправляют OTLP на
      `alloy:4318`, Alloy собирает их в пачки и передаёт в Tempo — как ТЗ и
      предпочитает вместо прямого экспорта. Одна регистрация — один трейс через оба
      сервиса (`registration` -> `http post /api/v1/individuals` -> `individualService.create`)
- [x] Проверено на чистом стенде: обе Postman-коллекции зелёные (individuals-api
      20 запросов / 52 проверки, person-service 22 / 106), обе сборки со всеми
      тестами зелёные

### Дальше

- [ ] Ваше полное ручное тестирование приложения
- [ ] Решить, какие коммиты и в какие ветки, затем push и PR
      `module-2-person-service` -> `main`
