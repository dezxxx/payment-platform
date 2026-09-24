# realm-export.json

The declaration of the `payment-platform` realm. Keycloak reads it **once**,
on a first start against an empty database:

```yaml
# docker-compose.yml
command: start-dev --import-realm
volumes:
  - ./individuals-api/src/main/resources/realm/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
```

A realm that already exists is left alone, so editing this file has no effect
on a running instance. See [Changing something](#changing-something).

JSON carries no comments, which is why this file is described here instead.

## What is in it, block by block

| Block | Lines | Declares |
|---|---|---|
| realm settings | 2-12 | name, login rules, token lifespans |
| `roles` | 13-20 | the platform role `USER` |
| `clients` | 21-57 | the `individuals-api` client, its secret, its `user_uid` mapper |
| `users` | 58-72 | the service account and its Admin API roles |
| `components` | 73-87 | the user profile: which attributes an account may hold |

### Realm settings

| Key | Value | Why |
|---|---|---|
| `registrationEmailAsUsername` | `true` | the contract has no username field, so the address serves as one |
| `duplicateEmailsAllowed` | `false` | what makes a second registration answer **409** |
| `registrationAllowed` | `false` | accounts are created through our API, never through Keycloak's own page |
| `verifyEmail` | `false` | nothing verifies an address in module 1 |
| `accessTokenLifespan` | `300` | access token, 5 minutes |
| `ssoSessionIdleTimeout` | `1800` | refresh token, 30 minutes of inactivity |
| `ssoSessionMaxLifespan` | `36000` | 10 hours, whatever the activity |

### `roles`

`USER` is declared here and granted nowhere: no import setting hands out a
realm role. `KeycloakAdminGateway.assignPlatformRole` grants it as the third
step of registration.

### `clients`

| Key | Value | Why |
|---|---|---|
| `publicClient` | `false` | a confidential client: it proves itself with a secret |
| `secret` | `change-me` | **placeholder** - see [The secret](#the-secret) |
| `serviceAccountsEnabled` | `true` | makes Keycloak create `service-account-individuals-api` |
| `directAccessGrantsEnabled` | `true` | enables `grant_type=password`, which `/login` is built on |
| `standardFlowEnabled` | `false` | no browser redirect flow: this client is called by a backend |
| `implicitFlowEnabled` | `false` | a flow the standard itself discourages |

#### The `user_uid` mapper

```json
"user.attribute": "user_uid",   // take it from the account attribute
"claim.name":     "user_uid",   // put it in the token under this name
"multivalued":    "false"       // one value, not an array
```

`oidc-usermodel-attribute-mapper` copies an account attribute into a token.
`multivalued: false` matters: attributes are stored as lists, and the claim has
to be a plain string for `KeycloakClaims` to read it.

The mapper belongs to the client, not to the realm, so `user_uid` only travels
in tokens issued to this application.

Console path: **Clients -> individuals-api -> Client scopes ->
individuals-api-dedicated**.

### `users`

One service account, with the four Admin API roles the gateway needs:

| Role | Used by |
|---|---|
| `manage-users` | `createUser`, `setPassword`, `assignPlatformRole`, `deleteUser` |
| `view-users`, `query-users` | `findRegisteredAt`, behind `/me` |
| `view-realm` | reading the `USER` role by name before granting it |

Nothing beyond that: a leaked secret must not buy the whole realm.

### `components` - the user profile

Keycloak 24+ stores no attribute that the user profile does not declare. Line
82 is one long escaped string; unescaped, our own attribute reads:

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

- `max: 36` - the length of a UUID
- `permissions: admin` - the account cannot edit its own `user_uid`
- `group` - keeps it apart from the name fields in the console

Without this block `KeycloakUserRequest` would send the attribute and Keycloak
would silently drop it, leaving every token without the claim.

## Facts repeated outside this file

Nothing enforces these pairs. They drift silently.

| Fact | Here | Also in |
|---|---|---|
| attribute name `user_uid` | `user.attribute` | `KeycloakUserRequest.USER_UID_ATTRIBUTE` |
| claim name `user_uid` | `claim.name` | `KeycloakClaims` |
| realm name | `realm` | `application.yml`: `individuals.keycloak.realm`, `issuer-uri` |
| client id | `clientId` | `application.yml`: `individuals.keycloak.client-id` |
| client secret | `secret` (placeholder) | `.env`: `KEYCLOAK_CLIENT_SECRET` |
| role name `USER` | `roles.realm[].name` | `KeycloakAdminGateway.PLATFORM_ROLE` |
| email as username | `registrationEmailAsUsername` | `KeycloakUserRequest`: `username = email` |
| password grant | `directAccessGrantsEnabled` | `KeycloakOidcGateway`: `grant_type=password` |
| attribute declaration | `components` | `KeycloakUserRequest`: `attributes` |

A mismatch in the first two is the quiet one: the account is still created, the
token is still valid, and `/me` answers **500** because the claim never arrived.

## The secret

`"secret": "change-me"` is a placeholder, so the file stays committable. The
real value lives in `.env` as `KEYCLOAK_CLIENT_SECRET`, which is gitignored,
and `.env.example` carries the same placeholder.

Whatever Keycloak holds is what counts: if the secret was rotated in the
console, `.env` has to carry the new value.

## Changing something

The import runs only against an empty Keycloak database, so:

```bash
# 1. edit this file, and the code that repeats the same fact
# 2. recreate the realm - DELETES every account in Keycloak
docker compose down -v
docker compose up -d
```

To keep the existing accounts, change it in the console instead and edit this
file to match, so the next clean start produces the same realm.

The integration tests import this same file into a Keycloak container, which is
what keeps it from drifting away from the code.
