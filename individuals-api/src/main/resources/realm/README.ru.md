# realm-export.json

Заявка на создание realm'а `payment-platform`. Keycloak читает её **один раз**,
при первом старте с пустой базой:

```yaml
# docker-compose.yml
command: start-dev --import-realm
volumes:
  - ./individuals-api/src/main/resources/realm/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
```

Существующий realm он не трогает, поэтому правка файла на работающем Keycloak
ничего не меняет. См. [Как что-то поменять](#как-что-то-поменять).

Комментариев в JSON нет — поэтому файл описан здесь.

## Что внутри, по блокам

| Блок | Строки | Что объявляет |
|---|---|---|
| настройки realm | 2-12 | имя, правила входа, сроки токенов |
| `roles` | 13-20 | роль платформы `USER` |
| `clients` | 21-57 | клиент `individuals-api`, его секрет, маппер `user_uid` |
| `users` | 58-72 | служебный пользователь и его права в Admin API |
| `components` | 73-87 | профиль пользователя: какие атрибуты может хранить учётка |

### Настройки realm

| Ключ | Значение | Почему |
|---|---|---|
| `registrationEmailAsUsername` | `true` | в контракте нет поля «логин», поэтому им служит почта |
| `duplicateEmailsAllowed` | `false` | это и даёт **409** на повторную регистрацию |
| `registrationAllowed` | `false` | учётки создаёт наш API, а не страница Keycloak |
| `verifyEmail` | `false` | в модуле 1 почту никто не проверяет |
| `accessTokenLifespan` | `300` | access-токен, 5 минут |
| `ssoSessionIdleTimeout` | `1800` | refresh-токен, 30 минут простоя |
| `ssoSessionMaxLifespan` | `36000` | 10 часов, при любой активности |

### `roles`

`USER` здесь только объявлена и никому не выдаётся: ни одна настройка импорта
не раздаёт роли realm'а. Выдаёт её `KeycloakAdminGateway.assignPlatformRole`,
третьим шагом регистрации.

### `clients`

| Ключ | Значение | Почему |
|---|---|---|
| `publicClient` | `false` | конфиденциальный клиент: доказывает себя секретом |
| `secret` | `change-me` | **заглушка** — см. [Секрет](#секрет) |
| `serviceAccountsEnabled` | `true` | Keycloak создаёт `service-account-individuals-api` |
| `directAccessGrantsEnabled` | `true` | включает `grant_type=password`, на котором построен `/login` |
| `standardFlowEnabled` | `false` | вход с перенаправлениями в браузере не нужен: клиента вызывает бэкенд |
| `implicitFlowEnabled` | `false` | поток, от которого отговаривает сам стандарт |

#### Маппер `user_uid`

```json
"user.attribute": "user_uid",   // откуда взять: атрибут учётки
"claim.name":     "user_uid",   // куда положить: поле в токене
"multivalued":    "false"       // одно значение, не массив
```

`oidc-usermodel-attribute-mapper` копирует атрибут учётки в токен.
`multivalued: false` важен: атрибуты хранятся списком, а в токене должна быть
обычная строка, иначе `KeycloakClaims` её не прочитает.

Маппер принадлежит клиенту, а не realm'у, поэтому `user_uid` попадает только
в токены этого приложения.

Путь в консоли: **Clients → individuals-api → Client scopes →
individuals-api-dedicated**.

### `users`

Один служебный пользователь с четырьмя правами в Admin API:

| Право | Кем используется |
|---|---|
| `manage-users` | `createUser`, `setPassword`, `assignPlatformRole`, `deleteUser` |
| `view-users`, `query-users` | `findRegisteredAt`, за которым стоит `/me` |
| `view-realm` | прочитать роль `USER` по имени перед выдачей |

Больше ничего: утёкший секрет не должен покупать весь realm.

### `components` — профиль пользователя

Keycloak 24+ не сохраняет атрибут, который не объявлен в профиле пользователя.
Строка 82 — одна длинная экранированная строка; если распутать, наш атрибут
выглядит так:

```json
{
  "name": "user_uid",
  "displayName": "Domain user uid",
  "validations": { "length": { "max": 36 } },
  "permissions": { "view": ["admin"], "edit": ["admin"] },
  "multivalued": false,
  "group": "user-metadata"
}
```

- `max: 36` — длина UUID
- `permissions: admin` — сам пользователь свой `user_uid` не меняет
- `group` — в консоли лежит отдельно от имени и фамилии

Без этого блока `KeycloakUserRequest` отправил бы атрибут, Keycloak молча бы
его отбросил, и claim'а не было бы ни в одном токене.

## Факты, повторённые вне этого файла

Совпадение этих пар ничем не проверяется. Расходятся молча.

| Факт | Здесь | Ещё в |
|---|---|---|
| имя атрибута `user_uid` | `user.attribute` | `KeycloakUserRequest.USER_UID_ATTRIBUTE` |
| имя claim'а `user_uid` | `claim.name` | `KeycloakClaims` |
| имя realm | `realm` | `application.yml`: `individuals.keycloak.realm`, `issuer-uri` |
| id клиента | `clientId` | `application.yml`: `individuals.keycloak.client-id` |
| секрет клиента | `secret` (заглушка) | `.env`: `KEYCLOAK_CLIENT_SECRET` |
| имя роли `USER` | `roles.realm[].name` | `KeycloakAdminGateway.PLATFORM_ROLE` |
| почта как логин | `registrationEmailAsUsername` | `KeycloakUserRequest`: `username = email` |
| вход по паролю | `directAccessGrantsEnabled` | `KeycloakOidcGateway`: `grant_type=password` |
| объявление атрибута | `components` | `KeycloakUserRequest`: `attributes` |

Первые две пары — самые тихие: учётка создастся, токен будет действителен, а
`/me` ответит **500**, потому что claim не доехал.

## Секрет

`"secret": "change-me"` — заглушка, чтобы файл можно было держать в git.
Настоящее значение лежит в `.env`, в `KEYCLOAK_CLIENT_SECRET`, который в git не
попадает; в `.env.example` стоит та же заглушка.

Считается то, что хранит Keycloak: если секрет перевыпускали в консоли, в
`.env` должно лежать новое значение.

## Как что-то поменять

Импорт работает только с пустой базой Keycloak, поэтому:

```bash
# 1. правишь этот файл и код, где повторён тот же факт
# 2. пересоздаёшь realm — УДАЛЯЕТ все учётки в Keycloak
docker compose down -v
docker compose up -d
```

Чтобы сохранить учётки, меняй в консоли и приводи этот файл в то же состояние,
чтобы следующий чистый запуск дал такой же realm.

Интеграционные тесты импортируют этот же файл в контейнер Keycloak — это и не
даёт ему разойтись с кодом.
