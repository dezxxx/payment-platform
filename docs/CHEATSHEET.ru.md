# Шпаргалка

Одна страница со всеми адресами, портами, паролями и командами проекта — чтобы
ничего из этого больше не искать ни в задании курса, ни в `CONTEXT.ru.md`.
Значения берутся из `.env` (копия `.env.example`); здесь записано только, что
это за значения и зачем они.

> Английская версия — основная: [`CHEATSHEET.md`](CHEATSHEET.md). Если тексты
> разошлись — прав английский. Разбор классов — в другой шпаргалке,
> [`CLASSES.ru.md`](CLASSES.ru.md).

---

## 1. Порты

Порт хоста — это то, что набираешь в браузере или в `curl`. Порт контейнера —
то, что сервис слушает внутри сети compose; именно он используется в
`application-docker.yml` и `prometheus.yml`, никогда не порт хоста.

| Сервис | Хост | Контейнер | Открыть | Что это |
|---|---|---|---|---|
| keycloak | 8080 | 8080 | http://localhost:8080 | сервер аутентификации, админ-консоль |
| individuals-api | 8081 | 8081 | http://localhost:8081 | наш сервис |
| person-service | 8082 | 8082 | — | модуль 2, ещё не запускается |
| nexus | 8083 | 8081 | http://localhost:8083 | приватный Maven-репозиторий платформы |
| keycloak-postgres | 5433 | 5432 | — | база Keycloak |
| person-postgres | 5434 | 5432 | — | база person-service (пока только миграции) |
| prometheus | 9090 | 9090 | http://localhost:9090 | метрики, скребёт `individuals-api:8081` |
| tempo | 3200 | 3200 | http://localhost:3200 | хранилище трейсов, Grafana ходит сюда |
| tempo OTLP | 4318 | 4318 | — | сюда приложение пушит трейсы |
| loki | 3100 | 3100 | http://localhost:3100 | хранилище логов, читается через Grafana |
| alloy | 12345 | 12345 | http://localhost:12345 | читает логи контейнеров и отдаёт в Loki; его интерфейс показывает конвейер |
| grafana | 3000 | 3000 | http://localhost:3000 | дашборды |

Почему у Nexus 8083: по умолчанию Nexus сидит на 8081, но задание закрепляет
8081 за individuals-api — значит переезжает Nexus. URL живут в
`gradle.properties`.

## 2. Учётные данные

Всё ниже — локальные значения по умолчанию из `.env.example`. Настоящие живут в
`.env`, который никогда не коммитится.

| Где | Пользователь | Пароль |
|---|---|---|
| админ-консоль Keycloak (realm master) | `admin` | `admin` |
| Grafana | `admin` | `admin` |
| Nexus | `admin` | `admin123` |
| keycloak-postgres | `keycloak` | `keycloak` (база `keycloak`) |
| person-postgres | `person` | `person` (база `person`) |
| клиент Keycloak `individuals-api` | client secret | `KEYCLOAK_CLIENT_SECRET` |

Realm: `payment-platform`. Client id: `individuals-api` (confidential, service
account включён, direct access grants включён, standard flow выключен).

## 3. Наши эндпоинты

База: `http://localhost:8081`

| Метод | Путь | Авторизация | Успех | Ошибки |
|---|---|---|---|---|
| POST | `/api/v1/auth/registration` | нет | 201 токены | 400 валидация, 409 почта занята, 503 зависимость |
| POST | `/api/v1/auth/login` | нет | 200 токены | 400 валидация, 401 неверные данные, 503 |
| POST | `/api/v1/auth/refresh-token` | нет | 200 токены | 400 валидация, 401 `REFRESH_TOKEN_INVALID`, 503 |
| GET | `/api/v1/auth/me` | `Bearer <access>` | 200 пользователь | 401 токена нет или он невалиден |

Инфраструктурные, все открытые:

| Путь | Что |
|---|---|
| `/actuator/health` | пробы liveness/readiness |
| `/actuator/info` | информация о сборке |
| `/actuator/prometheus` | метрики, скребутся раз в 15 секунд |
| `/swagger-ui.html` | UI контракта |
| `/v3/api-docs` | контракт в JSON |

Источник правды по контракту — `individuals-api/openapi/individuals-api.yaml`.
Всё остальное (`AuthApi`, шесть DTO) генерируется из него.

## 4. Коды HTTP, которые здесь используются

Только те, которыми этот сервис умеет отвечать. Каждый из них — константа в
`ErrorCode`, кроме 200/201: это успехи.

| Код | Имя | Значит | Чья вина | Наша константа |
|---|---|---|---|---|
| 200 | OK | вызов сработал | — | login, refresh-token, me |
| 201 | Created | сработал и создал новое | — | registration |
| 400 | Bad Request | сам запрос неправильный — кривая почта, нет поля, битый JSON | вызывающего | `VALIDATION_ERROR` |
| 401 | Unauthorized | «я не знаю, кто ты» | вызывающего | `INVALID_CREDENTIALS` на неверный пароль, `AUTHENTICATION_REQUIRED` когда токена нет, `REFRESH_TOKEN_INVALID` на истёкший refresh |
| 403 | Forbidden | «знаю, кто ты, и тебе нельзя» — токен валиден, роли не хватает | вызывающего | `ACCESS_DENIED` |
| 404 | Not Found | нет такого пути или такого пользователя | вызывающего | `NOT_FOUND` |
| 405 | Method Not Allowed | путь есть, а такого HTTP-метода у него нет | вызывающего | `METHOD_NOT_ALLOWED` |
| 409 | Conflict | уже существует — почта занята | вызывающего | `USER_ALREADY_EXISTS` |
| 415 | Unsupported Media Type | не тот Content-Type, например `text/plain` вместо `application/json` | вызывающего | `UNSUPPORTED_MEDIA_TYPE` |
| 500 | Internal Server Error | сломались мы — баг, неверный client secret | наша | `INTERNAL_ERROR` |
| 503 | Service Unavailable | лежит зависимость — Keycloak недоступен | наша | `DEPENDENCY_UNAVAILABLE`, `REGISTRATION_INCONSISTENT` |

Семейства, если кода нет в таблице:

- **2xx** сработало
- **3xx** иди в другое место (мы такими не отвечаем никогда)
- **4xx** неправ вызывающий — тот же запрос без изменений упадёт снова
- **5xx** неправы мы — тот же запрос может сработать позже

Ровно поэтому 401 — это не 400, а 503 — не 500: разница говорит вызывающему,
бессмысленно повторять запрос или наоборот стоит.

Пара, которую путают чаще всего: **401 — «ты кто?», 403 — «это не для тебя»**.

## 5. Эндпоинты Keycloak, которые мы вызываем

`REALM` = `payment-platform`.

| Зачем | Вызов |
|---|---|
| токен service account | `POST /realms/REALM/protocol/openid-connect/token` — `grant_type=client_credentials` |
| логин пользователя | `POST /realms/REALM/protocol/openid-connect/token` — `grant_type=password` |
| обновление токена | `POST /realms/REALM/protocol/openid-connect/token` — `grant_type=refresh_token` |
| создать пользователя | `POST /admin/realms/REALM/users` — тело несёт `user_uid`, **без пароля**; отвечает 201 с пустым телом, новый id только в заголовке `Location` |
| поставить пароль | `PUT /admin/realms/REALM/users/{id}/reset-password` — `temporary: false`, иначе следующий логин упадёт с 400 `invalid_grant` |
| удалить пользователя | `DELETE /admin/realms/REALM/users/{id}` — только компенсация, когда пароль поставить не удалось |
| прочитать пользователя | `GET /admin/realms/REALM/users/{id}` — читаем одно поле, `createdTimestamp` |
| JWKS / issuer | `GET /realms/REALM/.well-known/openid-configuration` |

Как ошибки Keycloak ложатся на наши: `400 invalid_grant` «Invalid user
credentials» → наш 401; `400 invalid_grant` «Account is not fully set up» →
тоже 401, и означает, что пароль поставили временным; `409` «User exists with
same email» → наш 409.

## 6. Команды

```bash
# весь стек
docker compose up -d
docker compose ps
docker compose logs -f keycloak
docker compose down            # тома оставить
docker compose down -v         # тома снести - realm переимпортируется при следующем up

# только то, что нужно individuals-api при запуске из IDE
docker compose up -d keycloak

# запустить приложение из терминала
./gradlew :individuals-api:bootRun

# собрать и протестировать
./gradlew :individuals-api:compileJava
./gradlew build                              # всё, включая интеграционные

# тесты по отдельности - они разведены по пакетам, а не по именам
./gradlew :individuals-api:test              # только unit, секунды, без Docker
./gradlew :individuals-api:integrationTest   # Keycloak и PostgreSQL в контейнерах, минуты

# один класс
./gradlew :individuals-api:integrationTest --tests '*KeycloakRegistrationIT*'

# покрытие - критерий приёмки 11
./gradlew :individuals-api:jacocoTestReport   # отчёт, открыть build/reports/jacoco/test/html/index.html
./gradlew :individuals-api:jacocoTestCoverageVerification   # порог 80% на пакете service


# Postman: ÐºÐ¾Ð»Ð»ÐµÐºÑÐ¸Ñ ÑÐµÐ»Ð¸ÐºÐ¾Ð¼, Ð¸Ð· ÑÐµÑÐ¼Ð¸Ð½Ð°Ð»Ð°
npx newman run postman/individuals-api.postman_collection.json

# Nexus: публикация клиента, который потом резолвят другие модули
NEXUS_USERNAME=admin NEXUS_PASSWORD=admin123 ./gradlew :person-client:publish

# то же самое в PowerShell - там переменные задаются иначе
$env:NEXUS_USERNAME="admin"; $env:NEXUS_PASSWORD="admin123"; ./gradlew :person-client:publish
```

Первый запуск Nexus не мгновенный, и **пока не принято лицензионное соглашение,
он отдаёт 403 на любое обращение к содержимому репозиториев**. Принять можно в
браузере на http://localhost:8083 (логин `admin` / `admin123`) или через REST.
Состояние лежит в томе `nexus-data` и переживает и `docker compose down`, и
смену версии образа.

Интеграционные поднимают собственный Keycloak, и на холодную это небыстро —
импортируется весь realm. Если параллельно работает `docker compose`, старт
может и не уложиться в таймаут: на время прогона стек проще погасить
(`docker compose stop`).

Проверки на дым. **В PowerShell всегда писать `curl.exe`, никогда `curl`** —
голый `curl` там алиас для `Invoke-WebRequest`, у которого нет флага `-i` и
который на 4xx кидает исключение вместо того, чтобы напечатать тело ответа. А
тело — это ровно то, что здесь надо увидеть.

```bash
# приложение поднялось
curl -i http://localhost:8081/actuator/health

# без токена -> должно быть 401 с НАШИМ json (timestamp, path, status, error, message, traceId)
curl -i http://localhost:8081/api/v1/auth/me

# keycloak поднялся и realm импортировался
curl -s http://localhost:8080/realms/payment-platform/.well-known/openid-configuration | head -c 300

# залогинить пользователя напрямую в keycloak (в обход нашего api)
curl -s -X POST http://localhost:8080/realms/payment-platform/protocol/openid-connect/token \
  -d grant_type=password -d client_id=individuals-api \
  -d client_secret=CHANGE_ME -d username=EMAIL -d password=PASSWORD
```

Раскодировать access token без единого инструмента (PowerShell):

```powershell
$t = "PASTE.TOKEN.HERE".Split(".")[1]
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($t.PadRight($t.Length + (4 - $t.Length % 4) % 4, "=")))
```

## 7. Одно значение в двух файлах

Меняешь одну сторону — вторая молча ломается. Полная таблица в `CONTEXT.ru.md`,
раздел «Согласованность между файлами», пары самого realm-файла — в
`individuals-api/src/main/resources/realm/README.ru.md`; те, что кусают чаще
всего:

| Значение | Живёт в | Должно совпадать с |
|---|---|---|
| порт приложения | `application.yml` `server.port` | compose `8081:8081`, target в `prometheus.yml` |
| имя realm | `.env` `KEYCLOAK_REALM` | `realm-export.json` `realm` |
| client id | `.env` `KEYCLOAK_CLIENT_ID` | `realm-export.json` `clientId` |
| client secret | `.env` `KEYCLOAK_CLIENT_SECRET` | `realm-export.json` `secret` |
| эндпоинт tempo | `application-docker.yml` `tempo:4318/v1/traces` | OTLP-приёмник в `tempo.yml` |

---

## 8. Сокращения

Каждое из них хоть раз встречается в коде, логах или конфигах проекта.

| Сокращение | Полностью | Что это здесь |
|---|---|---|
| **MDC** | Mapped Diagnostic Context | Карта «ключ-значение», привязанная к потоку. Logback подмешивает её в каждую запись лога — оттуда `traceId` и `spanId`. Включено через `spring.reactor.context-propagation: auto` |
| **OTLP** | OpenTelemetry Protocol | Протокол отправки трейсов в Tempo. Порт 4318, путь `/v1/traces`. **Не** OLTP — это другое |
| **OTel** | OpenTelemetry | Стандарт и библиотеки для трейсов и метрик |
| **OIDC** | OpenID Connect | Слой аутентификации поверх OAuth 2.0. «Стандартная» сторона Keycloak — `KeycloakOidcGateway` |
| **JWT** | JSON Web Token | Токен из трёх частей через точку: заголовок, полезная нагрузка, подпись |
| **JWKS** | JSON Web Key Set | Публичные ключи realm. По ним `ReactiveJwtDecoder` проверяет подпись JWT |
| **ECS** | Elastic Common Schema | Формат JSON-логов. Отсюда поля `@timestamp`, `log.level`, `service.name` |
| **PromQL** | Prometheus Query Language | Язык запросов Prometheus: `rate(auth_login_total[5m])` |
| **UT / IT** | Unit Test / Integration Test | Коды тест-кейсов из ТЗ: `UT-REG-001`, `IT-KC-001` |
| **JDBC** | Java Database Connectivity | Стандартный API Java к базам. Через него Flyway ходит в PostgreSQL |
| **BOM** | Bill Of Materials | Список версий, которым управляет Spring Boot вместо нас |
| **DTO** | Data Transfer Object | Объект для передачи данных между слоями |
| **DDL** | Data Definition Language | Часть SQL, создающая таблицы — наши миграции Flyway |
| **CI** | Continuous Integration | Сборка и тесты на сервере, не на твоей машине |

---

## 9. Коды тест-кейсов

ТЗ называет каждый обязательный тест кодом из трёх частей. Тот же код стоит в
`@DisplayName` теста, поэтому в отчёте Gradle и в IDE он виден рядом с
результатом — сверять с таблицей ТЗ можно не гадая.

```
IT  -  KC  -  001
│      │      └── порядковый номер внутри группы
│      └───────── область: что именно проверяем
└──────────────── тип теста
```

**Тип:**

| | Полностью | Что значит |
|---|---|---|
| **UT** | Unit Test, модульный тест | Один класс в изоляции, зависимости замоканы. Быстрый, без контейнеров. Пакет `com.dezxxx.individuals.unit` |
| **IT** | Integration Test, интеграционный тест | Несколько частей вместе, настоящий HTTP и настоящие контейнеры. Медленный. Пакет `com.dezxxx.individuals.integration` |

Пакет решает, какая задача Gradle тест запустит: `test` фильтрует по `unit.*`,
`integrationTest` — по `integration.*`. Тест вне обоих пакетов не запускается
вообще и молчит об этом.

**Область:**

| | Полностью | Класс |
|---|---|---|
| **REG** | Registration, регистрация | `RegistrationServiceTest`, `PasswordsMatchValidatorTest` |
| **LOG** | Login, вход | `AuthenticationServiceTest` |
| **REF** | Refresh, обновление токена | `AuthenticationServiceTest` |
| **ME** | эндпоинт `/me` | `AuthenticationServiceTest` |
| **KC** | Keycloak | `KeycloakRegistrationIT` |
| **OBS** | Observability, наблюдаемость | `ObservabilityIT` |
| **DB** | Database, база данных | `PersonSchemaMigrationIT` |

**Все пятнадцать кодов:**

| Код | Читается как | Сценарий |
|---|---|---|
| UT-REG-001 | модульный, регистрация, №1 | валидный запрос: сначала person-service, потом Keycloak, потом токены |
| UT-REG-002 | модульный, регистрация, №2 | пароль и подтверждение не совпали: 400, Keycloak не вызывается |
| UT-REG-003 | модульный, регистрация, №3 | person-service сообщает о конфликте почты: 409, Keycloak не вызывается |
| UT-REG-004 | модульный, регистрация, №4 | доменный пользователь создан, Keycloak недоступен: 502/503, сбой зафиксирован |
| UT-LOG-001 | модульный, логин, №1 | успешный вход: вернулись access и refresh токены |
| UT-LOG-002 | модульный, логин, №2 | неверный пароль: 401 |
| UT-REF-001 | модульный, refresh, №1 | успешное обновление: вернулся новый access токен |
| UT-ME-001 | модульный, `/me`, №1 | валидный bearer-токен: вернулся текущий пользователь |
| IT-KC-001 | интеграционный, Keycloak, №1 | регистрация против реального контейнера: пользователь появился в realm |
| IT-KC-002 | интеграционный, Keycloak, №2 | после регистрации: атрибут `user_uid` найден в Keycloak |
| IT-KC-003 | интеграционный, Keycloak, №3 | `/login` против реального token endpoint: вернулся настоящий JWT |
| IT-OBS-001 | интеграционный, наблюдаемость, №1 | `/actuator/prometheus` читается |
| IT-OBS-002 | интеграционный, наблюдаемость, №2 | после запроса трейс виден в Tempo/Grafana |
| IT-OBS-003 | интеграционный, наблюдаемость, №3 | записи логов несут `traceId` и `spanId` |
| IT-DB-001 | интеграционный, база данных, №1 | миграции person-service против PostgreSQL: Flyway отработал |

Что из этого уже написано — в `CONTEXT.ru.md`, раздел 8. Здесь только расшифровка.
