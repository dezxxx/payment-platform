# Прогон модуля 1

Сценарий, по которому модуль показывают целиком: от регистрации до того, что
о ней написали метрики, логи и трейсы. Рассчитан на 15 минут и на то, что всё
делаешь руками, а не смотришь чужой скриншот.

Каждая команда здесь выполнялась вживую. Если что-то отвечает не так, как
написано, — это расхождение, а не «у автора по-другому».

Английская версия — основная: [`DEMO.md`](DEMO.md). Адреса, пароли и порты —
в [`CHEATSHEET.ru.md`](CHEATSHEET.ru.md).

---

## 0. Подготовка

**В PowerShell всегда `curl.exe`, а не `curl`.** Голый `curl` там — псевдоним
для `Invoke-WebRequest`, он не понимает `-X`, `-H`, `-d` и на 4xx кидает
исключение вместо того, чтобы напечатать тело. А тело — это ровно то, что надо
увидеть.

### Почему нужна заглушка

Регистрация — главный сценарий модуля, и её первый шаг зовёт **person-service**,
которого в модуле 1 нет: от него есть только контракт и миграции. Без него
регистрация честно отвечает **503 (Service Unavailable)** и на этом всё
заканчивается: ни успешных метрик, ни строки «Registered» в логах, ни трейса
через всю цепочку.

Поэтому на время прогона его место занимает заглушка. Она отвечает ровно на ту
одну операцию, которую описывает `person-service/openapi/person-service.yaml`,
и **не является частью сдачи** — это инструмент показа.

### Включить заглушку

1. В `.env` раскомментировать строку:

   ```
   PERSON_SERVICE_URL=http://host.docker.internal:8082
   ```

   Приложение работает в Docker, а заглушка — на хосте; `host.docker.internal`
   это то, как контейнер видит твою машину. Приложение оставляем в контейнере
   намеренно: иначе Alloy не соберёт его логи и Loki показывать будет нечего.

2. В **отдельном** терминале запустить и не закрывать:

   ```bash
   node docs/demo/person-service-stub.js
   ```

   Она печатает каждый свой вызов — по ней видно, что наш сервис действительно
   к ней сходил.

---

## 1. Поднять стек

```bash
docker compose up -d --build
docker compose ps
```

Десять сервисов. Keycloak на холодную импортирует realm и первые полминуты
отвечает отказом — это нормально и это часть картины: приложение переживает
недоступность зависимости, а не падает вместе с ней.

Дождаться готовности:

```bash
curl.exe -s http://localhost:8081/actuator/health
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8080/realms/payment-platform/.well-known/openid-configuration
```

Первое отвечает `{"status":"UP"}`, второе — `200`.

> **Про `Up` и `(healthy)` в `docker compose ps`.** `Up` значит «процесс
> запущен», `(healthy)` — «проверка готовности прошла». База стартует за
> секунду, но ещё несколько секунд не принимает подключения: для Docker она уже
> `Up`, для Keycloak — ещё нет. Поэтому у баз в `docker-compose.yml` стоит
> `healthcheck`, а у Keycloak — `depends_on: condition: service_healthy`.

---

## 2. Регистрация

```bash
curl.exe -X POST http://localhost:8081/api/v1/auth/registration ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"demo1@example.com\",\"password\":\"Str0ngPass!\",\"confirmPassword\":\"Str0ngPass!\",\"firstName\":\"Ivan\",\"lastName\":\"Ivanov\"}"
```

Ответ — **201 (Created)** и пара токенов:

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<jwt>",
  "expiresIn": 300,
  "tokenType": "Bearer",
  "userUid": "85e3d59e-e480-4b29-96a1-2a75d482c955"
}
```

**Что здесь стоит сказать вслух.** `userUid` выдал person-service, а не мы. Он
же уехал в Keycloak как атрибут аккаунта и оттуда попал в токен через маппер
realm. Один идентификатор, три места, ноль расхождений — это и есть то, ради
чего модуль написан. В окне заглушки при этом видно `-> 201 85e3d59e…`.

Отдельного логина не потребовалось: регистрация логинит пользователя сама,
поэтому клиенту не нужен второй запрос.

### Повторная регистрация того же адреса

```bash
# та же команда ещё раз
```

**409 (Conflict)**, `USER_ALREADY_EXISTS`. Важно **где** это произошло:
person-service отказал **первым**, до Keycloak, поэтому откатывать нечего и
клиент получает точный ответ, а не нашу внутреннюю ошибку. Ровно поэтому
person-service стоит первым шагом сценария.

---

## 3. Вход, обновление токена, «кто я»

```bash
curl.exe -X POST http://localhost:8081/api/v1/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"demo1@example.com\",\"password\":\"Str0ngPass!\"}"
```

Та же форма ответа. Скопировать `accessToken` и спросить, кто мы:

```bash
curl.exe http://localhost:8081/api/v1/auth/me -H "Authorization: Bearer <accessToken>"
```

```json
{
  "userUid": "85e3d59e-…",
  "keycloakUserId": "a073ad90-…",
  "email": "demo1@example.com",
  "firstName": "Ivan",
  "lastName": "Ivanov",
  "emailVerified": false,
  "roles": ["USER"],
  "registeredAt": "2026-09-20T09:47:26.123Z"
}
```

**Два идентификатора рядом — не дублирование.** `userUid` это платформа,
`keycloakUserId` это `sub` токена, техническая связка. Бизнес-слой пользуется
только первым.

**`roles: ["USER"]`** — роль выдаёт наш сервис при регистрации, Keycloak сам её
не назначает. Служебные роли Keycloak (`offline_access`, `uma_authorization`) из
ответа убраны: они говорят, что аккаунту можно внутри Keycloak, а клиент
спрашивает про бизнес.

**`registeredAt`** — единственное поле, которого нет ни в одном claim. Ради него
`/me` делает один вызов к Admin API Keycloak; остальные семь полей достаются из
уже проверенного токена и не стоят ничего.

Обновление токена:

```bash
curl.exe -X POST http://localhost:8081/api/v1/auth/refresh-token ^
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"<refreshToken>\"}"
```

---

## 4. Отказы — все в одной форме

```bash
# пароль и подтверждение не совпали -> 400
curl.exe -X POST http://localhost:8081/api/v1/auth/registration ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"x@y.io\",\"password\":\"Str0ngPass!\",\"confirmPassword\":\"Other1!\",\"firstName\":\"I\",\"lastName\":\"I\"}"

# неверный пароль -> 401 INVALID_CREDENTIALS
curl.exe -X POST http://localhost:8081/api/v1/auth/login ^
  -H "Content-Type: application/json" -d "{\"email\":\"demo1@example.com\",\"password\":\"Wrong1!\"}"

# без токена -> 401 AUTHENTICATION_REQUIRED
curl.exe http://localhost:8081/api/v1/auth/me

# опечатка в пути -> тоже 401 AUTHENTICATION_REQUIRED
curl.exe http://localhost:8081/api/v1/auth/nothing-here
```

Все четыре отвечают одинаковой формой — `timestamp`, `path`, `status`, `error`,
`message`, `traceId`, `details`.

**Два последних дают один статус, но разные коды**, и это специально: на
запросе без токена никакого пароля не читали, поэтому говорить «неверный пароль»
нельзя — читатель пойдёт искать проблему не там.

**Несуществующий путь отвечает 401, а не 404, тоже специально**: сказать
неаутентифицированному клиенту «такого пути нет» значит выдать карту API любому,
кто спросит.

---

## 5. Метрики: что всё это записало

```bash
curl.exe -s http://localhost:8081/actuator/prometheus | findstr /B "auth_ external_"
```

После прогона выше:

```
auth_registration_total           2.0     ← две попытки, дошедшие до сервиса
auth_registration_success_total   1.0
auth_registration_failure_total   1.0     ← повтор адреса
auth_login_total                  2.0
auth_login_failure_total          1.0
auth_refresh_total                1.0
external_keycloak_requests_seconds_count        …
external_person_service_requests_seconds_count  …
```

**Главное, что стоит заметить.** Запросов на регистрацию было **три** (ещё один
с несовпавшим паролем), а счётчик показывает **два**. Тот, что упал с **400**,
до сервиса не дошёл — его завернула валидация в контроллере. Поэтому счётчик
меряет здоровье оркестрации, а не качество ввода пользователей. Стоял бы он в
контроллере — показывал бы 33% отказов на исправно работающем сервисе.

### В Prometheus

http://localhost:9090/graph — вкладка **Graph**, вставить:

```promql
rate(auth_registration_total[5m])
rate(auth_registration_success_total[5m]) / rate(auth_registration_total[5m])
rate(external_keycloak_requests_seconds_sum[5m]) / rate(external_keycloak_requests_seconds_count[5m])
```

Спрашивают всегда `rate`, а не значение: счётчик после рестарта обнуляется, и
`rate` это распознаёт, а голое значение нарисует обрыв в минус.

### В Grafana

http://localhost:3000, логин `admin` / `admin` (предложит сменить — **Skip**).
**Dashboards → individuals-api**. Период справа вверху поставить **Last 15
minutes**, иначе всплеск потеряется.

Четыре панели про наши метрики:

| Панель | Что показывает |
|---|---|
| Registration outcome | попытки / успехи / отказы |
| Logins and refreshes | входы, отказы входа, обновления токена |
| Outbound calls | время вызова Keycloak против person-service |
| Since the application started | те же счётчики цифрами |

Последняя нужна именно для показа руками: после десятка запросов `rate(...[5m])`
размазывает всплеск почти в ноль, а stat покажет честные числа.

---

## 6. Логи: Loki

**Grafana → Explore** (компас слева) → источник **Loki** → вкладка **Code**.

> Если в трёх инструментах путаешься — открой `docs/observability.puml`.
> Там одной картинкой: кто на что отвечает и в какую сторону едет.

| Запрос | Что покажет |
|---|---|
| `{service="individuals-api"}` | всё, что писал сервис |
| `{service="individuals-api"} \|= "Registered"` | строку об успешной регистрации |
| `{service="individuals-api", level=~"ERROR\|WARN"}` | только проблемы |
| `{service="individuals-api"} \|= "409"` | конкретный отказ |

Раскрыть любую строку — видно поля:

```json
{
  "@timestamp": "…",
  "log": { "level": "INFO", "logger": "com.dezxxx.individuals.service.RegistrationService" },
  "service": { "name": "individuals-api" },
  "message": "Registered 85e3d59e-e480-4b29-96a1-2a75d482c955",
  "traceId": "75d91d98fd18a312a2462909ee9d215b",
  "spanId": "…",
  "http.method": "POST",
  "http.path": "/api/v1/auth/registration",
  "http.status": "201",
  "user_uid": "85e3d59e-…"
}
```

**Это восемь полей, которых требует модуль**, и все они здесь: имя сервиса,
`traceId`, `spanId`, путь, метод, статус, код бизнес-ошибки (на отказах) и
доменный `user_uid`.

Стоит сказать, чего это стоило: MDC (Mapped Diagnostic Context — карта, которую
Logback подмешивает в каждую запись) привязан к потоку, а реактивная цепочка
прыгает по потокам на каждом внешнем вызове. Поля едут в Reactor Context и
копируются в MDC на каждом сигнале; включает это `spring.reactor.context-propagation: auto`.

Сами логи собирает **Alloy**: читает stdout контейнеров и отдаёт в Loki. Поэтому
приложение и должно работать в Docker — запущенное из IDE оно в Loki не попадёт.

---

## 7. Трейсы: Tempo

**Grafana → Explore** → источник **Tempo** → вкладка **TraceQL**:

```
{name="http post /api/v1/auth/registration"}
```

Открыть самый длинный трейс. Видно всю цепочку:

```
6192 ms  http post /api/v1/auth/registration
   7 ms    security filterchain before
6179 ms    secured request
 311 ms      http post   ← person-service
2694 ms      http post   ← Keycloak, токен service account
 896 ms      http post   ← создание пользователя
 495 ms      http put    ← пароль
  66 ms      http get    ← чтение роли USER
  35 ms      http post   ← назначение роли
 818 ms      http post   ← вход нового пользователя
```

Один запрос клиента — **семь** обращений к внешним системам. Без трейса это не
видно ниоткуда.

### Связка лога и трейса

Взять `traceId` из любой строки в Loki и вставить в **то же поле TraceQL** —
голый идентификатор там работает, Grafana его распознаёт и открывает именно
этот трейс. Отдельной вкладки для этого нет и не нужно.

В самой строке лога поле `TraceID` к тому же кликабельно — переход настроен в
провижнинге источников, так что из лога в трейс можно попасть вообще не
копируя ничего.

Это и есть корреляция, которой требует задание: от одной строки лога до полной
картины запроса в два клика.

---

## 8. Nexus

http://localhost:8083, логин `admin` / `admin123`.
**Browse → maven-snapshots → com/dezxxx/person-client**.

Там лежит опубликованный артефакт: jar, sources, pom, контрольные суммы.

Смысл: `individuals-api` не зависит от `person-client` через `project(":…")`. Он
резолвит его **по координатам Maven**, ровно как резолвил бы артефакт чужой
команды. Именно это держит модули независимыми, и через этот же Nexus пойдут
клиенты всех следующих сервисов курса.

Проверить, что резолв действительно идёт оттуда, можно жёстко: удалить артефакт
из `~/.m2` и из кэша Gradle и собрать заново — сборка пройдёт, потому что взять
его больше неоткуда.

---

## 9. Тесты и покрытие

```bash
./gradlew build
```

Проходит всё: генерация из контрактов, 36 модульных тестов, 15 интеграционных,
отчёт JaCoCo и проверка порога покрытия.

```bash
./gradlew :individuals-api:jacocoTestReport
# открыть individuals-api/build/reports/jacoco/test/html/index.html
```

`com.dezxxx.individuals.service` — **100%** при пороге 80%, которого требует
критерий приёмки 11. Порог проверяет сама сборка: упадёт ниже — упадёт `build`.

Интеграционные поднимают **настоящий** Keycloak и **настоящий** PostgreSQL в
контейнерах. Realm импортируется из того же `realm-export.json`, что монтирует
docker-compose, а секрет клиента тест читает из этого же файла — поэтому
разъехаться они не могут.

### Коллекция Postman

Postman → **Import** → `postman/individuals-api.postman_collection.json` →
**Run**. Десять запросов, токены переносятся между ними сами. Из терминала:

```bash
npx newman run postman/individuals-api.postman_collection.json
```

---

## 10. «А где это лежит?»

Половина вопросов на сдаче будет такой. Карта: что спросили → куда открывать.
В IDEA файл по имени открывается через **Ctrl+Shift+N**, дерево искать не надо.

### Контракты и описание API

| Спросят | Файл |
|---|---|
| где описан ваш API | `individuals-api/openapi/individuals-api.yaml` |
| где контракт person-service | `person-service/openapi/person-service.yaml` |
| где сгенерированный интерфейс | `individuals-api/build/generated/openapi/…/api/AuthApi.java` — не в git, создаётся сборкой |
| где посмотреть API вживую | http://localhost:8081/swagger-ui.html |

### Настройки

| Спросят | Файл |
|---|---|
| где настройки приложения | `individuals-api/src/main/resources/application.yml` |
| где адреса внутри Docker | `individuals-api/src/main/resources/application-docker.yml` |
| где порты, пароли, версии образов | `.env` в корне — **не в git**; шаблон рядом, `.env.example` |
| где client secret Keycloak | `.env`, ключ `KEYCLOAK_CLIENT_SECRET`, и больше нигде |
| где настроен realm Keycloak | `individuals-api/src/main/resources/realm/realm-export.json` |
| где маппер, кладущий `user_uid` в токен | там же, внутри клиента `individuals-api`, блок `protocolMappers` |
| где версии библиотек | `gradle/libs.versions.toml` — в build-скриптах версий нет вообще |

### Код

| Спросят | Файл |
|---|---|
| где сценарий регистрации | `…/service/RegistrationService.java` |
| где логин, refresh и `/me` | `…/service/AuthenticationService.java` |
| где эндпоинты | `…/rest/AuthController.java` |
| где вызовы Keycloak | `…/gateway/keycloak/oidc/` и `…/gateway/keycloak/admin/` |
| где вызов person-service | `…/gateway/person/PersonServiceGateway.java` |
| где все коды ошибок | `…/error/ErrorCode.java` — один enum, в нём код, статус и сообщение |
| где проверка «пароли совпали» | `…/validation/PasswordsMatch.java` |
| где имена метрик | `…/metrics/AuthMetrics.java` — все восемь в одном файле |
| где поля логов | `…/logging/RequestLogFilter.java` |
| где настроена security | `…/config/SecurityConfig.java` |

Полный путь у всех — `individuals-api/src/main/java/com/dezxxx/individuals/`.
По строке на каждый класс — в [`CLASSES.ru.md`](CLASSES.ru.md).

### Инфраструктура

| Спросят | Файл |
|---|---|
| где описан стек | `docker-compose.yml` |
| где Prometheus узнаёт, что скрести | `infra/prometheus/prometheus.yml` |
| где Tempo принимает трейсы | `infra/tempo/tempo.yml` |
| где логи собираются в Loki | `infra/alloy/config.alloy` |
| где источники данных Grafana | `infra/grafana/provisioning/datasources/datasources.yml` |
| где дашборд | `infra/grafana/dashboards/individuals-api.json` |
| где миграции | `person-service/src/main/resources/db/migration/` |
| где публикация в Nexus | `person-client/build.gradle.kts`, блок `publishing` |

### Тесты

| Спросят | Файл |
|---|---|
| где модульные | `individuals-api/src/test/java/…/unit/` |
| где интеграционные | `individuals-api/src/test/java/…/integration/` |
| где тест с реальным Keycloak | `…/integration/KeycloakRegistrationIT.java` |
| где проверка миграций | `…/integration/PersonSchemaMigrationIT.java` |
| где настроен порог покрытия | `individuals-api/build.gradle.kts`, `jacocoTestCoverageVerification` |
| где отчёт о покрытии | `individuals-api/build/reports/jacoco/test/html/index.html` |

### Если забыл

Три документа отвечают почти на всё:

| Вопрос про | Файл |
|---|---|
| «что это за класс» | [`CLASSES.ru.md`](CLASSES.ru.md) |
| «какой порт, пароль, команда» | [`CHEATSHEET.ru.md`](CHEATSHEET.ru.md) |
| «почему так сделано» | [`CONTEXT.ru.md`](../CONTEXT.ru.md) |

Сказать «сейчас посмотрю в CONTEXT» — нормальный ответ. Проект на то и документирован.

## 11. Что скорее всего спросят

**«Почему individuals-api ничего не хранит?»**
Потому что он оркестратор. Правда о доменном пользователе принадлежит
person-service, правда об учётной записи — Keycloak. Своя база означала бы
третью копию, которую никто не синхронизирует.

**«Что будет, если Keycloak упадёт посреди регистрации?»**
person-service уже записал пользователя, откатить его нечем — его контракт не
даёт удаления. Аккаунт без пароля удаляется компенсацией, а клиент получает
**503** и отдельный код `REGISTRATION_INCONSISTENT`, по которому это находится в
логах. Это признанный предел модуля 1, и он записан в `CONTEXT.md`, а не
замазан.

**«Почему `/me` ходит в Keycloak, а не читает токен?»**
Семь полей из восьми читаются из токена и стоят ноль. Восьмое, `registeredAt`,
не лежит ни в одном claim. Плюс токен — это снимок на момент выдачи: он не
знает, что аккаунт отключили минуту назад, а Admin API знает.

**«Почему WebClient, а задание называет RestClient?»**
Задание требует **HTTP Service Clients** — аннотированные интерфейсы, за
которыми прокси. Это у нас есть, и интерфейс не написан руками, а сгенерирован
из контракта. `RestClient` — синхронный движок под этим прокси; в WebFlux движок
называется `WebClient`, иначе блокировали бы event loop. Паттерн тот же,
реализация под стек.

**«Покажи, что тесты что-то ловят»**
`RegistrationServiceTest` нашёл настоящий дефект: `then(login(...))` вычисляет
аргумент при сборке цепочки, поэтому шлюз вызывался до установки пароля и даже
на провалившихся регистрациях. Лечится `Mono.defer`. Это записано в `CONTEXT.md`
§8.

---

## 12. Убрать за собой

```bash
# Ctrl+C в окне заглушки
docker compose down          # тома оставить
docker compose down -v       # снести и данные: realm импортируется заново
```

В `.env` обратно закомментировать `PERSON_SERVICE_URL` — иначе приложение будет
искать person-service на хосте и после того, как в модуле 2 появится настоящий.
