# Authorization

A Spring Boot 3.3 OAuth 2.0 / OpenID Connect example. A single application acts as both:

- an **Authorization Server** (issues JWT access tokens), and
- a **Resource Server** that protects the `/weather` endpoint.

## Prerequisites

- Java 21
- Maven 3.9+ (or use your IDE, e.g. IntelliJ IDEA)

## Running the application

From the project root:

```bash
mvn spring-boot:run
```

Or build a jar and run it:

```bash
mvn clean package
java -jar target/authorization-0.0.1-SNAPSHOT.jar
```

The server starts on `http://localhost:8080`.

In IntelliJ you can also run `AuthorizationApplication` directly.

## Configuration (in-memory, for demo only)

| Item | Value |
|------|-------|
| User (form login) | `user` / `password` |
| Client ID | `messaging-client` |
| Client secret | `secret` |
| Grant types | `authorization_code`, `refresh_token`, `client_credentials` |
| Scopes | `openid`, `profile`, `weather.read` |
| Redirect URI | `http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc` |

The RSA signing key is generated at startup, so tokens issued before a restart become invalid.

## Trying it out

### 1. Get a token (client credentials)

```bash
curl -s -u messaging-client:secret \
  -d "grant_type=client_credentials" \
  -d "scope=weather.read" \
  http://localhost:8080/oauth2/token
```

The response contains an `access_token`.

### 2. Call the protected endpoint

```bash
curl -H "Authorization: Bearer <access_token>" http://localhost:8080/weather
```

Expected response:

```json
{"forecast":"Sunny, 25C for the next 12 hours"}
```

Without a token (or without the `weather.read` scope) the request is rejected with `401`/`403`.

### Authorization code flow (optional)

Open this URL in a browser, log in as `user` / `password`, and approve the scopes:

```
http://localhost:8080/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20weather.read&redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc
```

The browser is redirected to the redirect URI with a `code` parameter. Exchange it for tokens:

```bash
curl -s -u messaging-client:secret \
  -d "grant_type=authorization_code" \
  -d "code=<code>" \
  -d "redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc" \
  http://localhost:8080/oauth2/token
```

## Useful endpoints

| Endpoint | Purpose |
|----------|---------|
| `/.well-known/openid-configuration` | OIDC discovery document |
| `/oauth2/authorize` | Authorization endpoint |
| `/oauth2/token` | Token endpoint |
| `/oauth2/jwks` | Public signing keys |
| `/weather` | Protected resource (requires `SCOPE_weather.read`) |
