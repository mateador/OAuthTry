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

## Two kinds of client

| | `messaging-client` (confidential) | `spa-client` (public) |
|---|---|---|
| Can keep a secret? | yes: `secret`, sent with HTTP Basic at the token endpoint | no: only `client_id` is sent |
| Grant types | `authorization_code`, `refresh_token`, `client_credentials` | `authorization_code` |
| Scopes | `openid`, `profile`, `weather.read` | `openid`, `weather.read` |
| Redirect URI | `http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc` | `http://127.0.0.1:3000/callback` |
| PKCE | **optional** (verified if sent) | **required**, `S256` only |

**User (form login):** `user` / `password`. Everything is in memory and for demo only. No consent screen is shown (`requireAuthorizationConsent=false`): after login the server redirects straight back with a code.

The RSA signing key is generated at startup, so tokens issued before a restart become invalid.

### Why PKCE matters most for the public client

A confidential client proves who it is with its secret when it exchanges the code, so a stolen code is useless to an attacker. A public client (single-page or mobile app) has no secret, so anyone who intercepts the code could redeem it. PKCE closes that gap: the client sends a hash of a random `code_verifier` (the `code_challenge`) with the authorization request, then reveals the verifier at the token endpoint. Only the party that started the flow can do that.

## What each scope does

| Scope | Effect |
|-------|--------|
| `weather.read` | Required by `/weather` (`SCOPE_weather.read`). The only scope the resource endpoint checks. |
| `openid` | Turns the request into OpenID Connect: an `id_token` is returned and `/userinfo` can be called. Only meaningful in the authorization code flow (a user must be present). |
| `profile` | Lets `/userinfo` return profile claims. With this in-memory user the response only contains `sub`, because no extra claims are mapped. |

A token only carries scopes that were requested **and** registered for the client. Requesting an unregistered scope fails with `invalid_scope`.

## Try the flows

Use `localhost` consistently in the browser, because the login session cookie is per host. Run the shell snippets in the same terminal so variables such as `$ACCESS_TOKEN` and `$CODE_VERIFIER` persist.

### Confidential client: `messaging-client`

#### Flow 1: Client credentials (no user, machine to machine)

- **What it is:** the client authenticates with its own `client_id` and secret and receives an access token for itself. No user and no browser are involved.
- **When it is used:** backend-to-backend calls, such as a scheduled job or another microservice calling `/weather`.
- **Why it matters here:** it is the simplest grant and shows the core of OAuth 2.0 (token issuing and scope checks) without user interaction. It also shows that a token can represent an application instead of a person (`sub` is the client id).

```bash
TOKEN=$(curl -s -u messaging-client:secret \
  -d "grant_type=client_credentials" \
  -d "scope=weather.read" \
  http://localhost:8080/oauth2/token | tee /dev/stderr | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
```

The response has `access_token`, `token_type`, `expires_in` and `scope`, but **no** `refresh_token` and **no** `id_token` (no user is involved).

```bash
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/weather
```

Expected: `200` with `{"forecast":"Sunny, 25C for the next 12 hours"}`.

Scope and credential experiments:

```bash
# Registered scope, but not the one /weather needs -> token is issued, /weather answers 403
PROFILE_TOKEN=$(curl -s -u messaging-client:secret -d "grant_type=client_credentials" -d "scope=profile" \
  http://localhost:8080/oauth2/token | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
curl -i -H "Authorization: Bearer $PROFILE_TOKEN" http://localhost:8080/weather      # 403

# Unregistered scope -> invalid_scope
curl -s -u messaging-client:secret -d "grant_type=client_credentials" -d "scope=admin" \
  http://localhost:8080/oauth2/token

# Bad credentials -> invalid_client (401)
curl -i -u messaging-client:wrong -d "grant_type=client_credentials" http://localhost:8080/oauth2/token

# No token / garbage token -> 401
curl -i http://localhost:8080/weather
curl -i -H "Authorization: Bearer abc" http://localhost:8080/weather
```

#### Flow 2a: Authorization code, no PKCE

- **What it is:** the user logs in at the authorization server, which redirects back to the client with a short-lived, single-use `code`. The client then swaps that code, plus its secret, for tokens at the token endpoint.
- **When it is used:** server-side web applications that act on behalf of a user and can keep a secret.
- **Why it matters here:** this is the main OAuth 2.0 flow for user-facing apps. The user's password never reaches the client, and the tokens never pass through the browser, only the code does. It is also the flow that yields the `id_token` (OpenID Connect) and the `refresh_token`.

Open in a browser and log in as `user` / `password`:

```
http://localhost:8080/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20profile%20weather.read&redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc&state=xyz123&nonce=n-0S6_WzA2Mj
```

The `state` value comes back unchanged; a real client checks it to defend against CSRF. The `nonce` is embedded in the `id_token` to prevent replay.

No app serves the redirect URI, so the browser shows an error page. That is expected: copy `code` from the address bar. It is single use and expires in about 5 minutes. Exchange it:

```bash
curl -s -u messaging-client:secret \
  -d "grant_type=authorization_code" \
  -d "code=<code>" \
  -d "redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc" \
  http://localhost:8080/oauth2/token
```

The response has `access_token`, `refresh_token` and `id_token`. Save them:

```bash
ACCESS_TOKEN=<access_token>
REFRESH_TOKEN=<refresh_token>
```

Inspect the claims (`sub` is `user`, `scope` lists the granted scopes); the same works for the `id_token`, which also holds your `nonce`:

```bash
echo "$ACCESS_TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null; echo
```

Use the token:

```bash
curl -i -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/weather       # 200
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/userinfo       # {"sub":"user"}
```

Experiments:

- Reuse the same code: `invalid_grant`.
- Request only `scope=profile`, then call `/weather` with that token: `403`.
- Use a different `redirect_uri` in the token request: `invalid_grant`.

#### Flow 2b: Authorization code with optional PKCE

- **What it is:** Flow 2a plus a `code_challenge` (a hash of a random `code_verifier`) in the authorization request, and the `code_verifier` in the token request.
- **When it is used:** whenever a client can send it. Current OAuth guidance recommends PKCE even for confidential clients.
- **Why it matters here:** it is defence in depth. Even if a code is intercepted, it cannot be redeemed without the verifier. Keeping it optional for this client lets you compare 2a and 2b side by side.

Same as Flow 2a, with a challenge added. PKCE is not required here, but when it is sent the server enforces it.

```bash
CODE_VERIFIER=$(openssl rand -base64 48 | tr -d '=+/' | cut -c1-64)
CODE_CHALLENGE=$(printf %s "$CODE_VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
echo "$CODE_CHALLENGE"
```

Append `&code_challenge=<CODE_CHALLENGE>&code_challenge_method=S256` to the Flow 2a authorize URL, then exchange the code with `-d "code_verifier=$CODE_VERIFIER"` added to the token request.

Experiments (repeat the authorize step each time, since a code is single use):

- Send a **wrong** `code_verifier`: `invalid_grant`.
- Send **no** `code_verifier` although a challenge was used: `invalid_grant`.
- `code_challenge_method=plain`: rejected, only `S256` is accepted.

#### Flow 3: Refresh token

- **What it is:** the client trades a `refresh_token` for a new access token, without asking the user to log in again.
- **When it is used:** when an access token expires during a long user session.
- **Why it matters here:** it lets access tokens stay short-lived, which limits the damage if one leaks, without forcing frequent logins. It also shows scope down-scoping and revocation, since refresh tokens are the long-lived credential that must be protected and revocable.

Needs the `REFRESH_TOKEN` from Flow 2a or 2b.

```bash
curl -s -u messaging-client:secret \
  -d "grant_type=refresh_token" \
  -d "refresh_token=$REFRESH_TOKEN" \
  http://localhost:8080/oauth2/token
```

You get a fresh `access_token` without the user logging in again. Spring reuses the same refresh token by default (no rotation).

Narrow the scope on refresh (a subset of what was originally granted, never more):

```bash
curl -s -u messaging-client:secret -d "grant_type=refresh_token" -d "refresh_token=$REFRESH_TOKEN" \
  -d "scope=profile" http://localhost:8080/oauth2/token                              # works, narrower token
curl -s -u messaging-client:secret -d "grant_type=refresh_token" -d "refresh_token=$REFRESH_TOKEN" \
  -d "scope=admin" http://localhost:8080/oauth2/token                                # invalid_scope
```

### Public client: `spa-client`

#### Flow 4: Authorization code + PKCE (required)

- **What it is:** the authorization code flow for a client with no secret. PKCE is mandatory and replaces the secret as the proof that the client redeeming the code is the one that started the flow.
- **When it is used:** single-page apps and mobile or desktop apps, which cannot keep a secret.
- **Why it matters here:** it is the recommended flow for public clients (the old implicit flow is deprecated). It demonstrates the problem PKCE solves: without a secret, a stolen code would be enough to get tokens.

Generate the verifier and challenge as in Flow 2b, then open (substituting the challenge):

```
http://localhost:8080/oauth2/authorize?response_type=code&client_id=spa-client&scope=openid%20weather.read&redirect_uri=http://127.0.0.1:3000/callback&state=abc&code_challenge=<CODE_CHALLENGE>&code_challenge_method=S256
```

Log in. The browser then fails to load `127.0.0.1:3000` because nothing listens there; copy `code` from the address bar. The token request has **no client secret**: the client identifies itself with `client_id` in the body, and PKCE is the only proof.

```bash
curl -s \
  -d "grant_type=authorization_code" \
  -d "client_id=spa-client" \
  -d "code=<code>" \
  -d "code_verifier=$CODE_VERIFIER" \
  -d "redirect_uri=http://127.0.0.1:3000/callback" \
  http://localhost:8080/oauth2/token
```

The response has `access_token` and `id_token` but **no** `refresh_token` (that grant is not enabled for this client). Call `/weather` with the access token as before.

Experiments:

```bash
# Wrong verifier -> invalid_grant (authorize again first: the code is single use)
curl -s -d "grant_type=authorization_code" -d "client_id=spa-client" -d "code=<code>" \
  -d "code_verifier=wrongwrongwrongwrongwrongwrongwrongwrongwrongwrong" \
  -d "redirect_uri=http://127.0.0.1:3000/callback" http://localhost:8080/oauth2/token

# client_credentials is not allowed for this client
curl -s -d "grant_type=client_credentials" -d "client_id=spa-client" http://localhost:8080/oauth2/token
```

In the browser:

- **No `code_challenge`** in the authorize URL: redirected back with `error=invalid_request`. This is the difference from `messaging-client`.
- **`code_challenge_method=plain`**: rejected.
- **`scope=profile`** (not registered for `spa-client`): redirected back with `error=invalid_scope`.
- **`redirect_uri=http://evil.example/cb`**: the server shows an error page and does **not** redirect. This is why redirect URIs are registered: a code is never sent to an unregistered address.

### Token endpoints beyond issuing

- **What they are:** introspection reports whether a token is active and what it contains. Revocation invalidates a token before it expires.
- **When they are used:** introspection by resource servers that do not validate JWTs locally, revocation on logout or when a token is compromised.
- **Why they matter here:** they show the limits of self-contained JWTs. This app's resource server never calls back, so a revoked access token can still work until it expires.

Introspect (is the token active, and what does it contain?), authenticated as the confidential client:

```bash
curl -s -u messaging-client:secret -d "token=$ACCESS_TOKEN" http://localhost:8080/oauth2/introspect
```

Revoke the refresh token, then try to use it:

```bash
curl -i -u messaging-client:secret -d "token=$REFRESH_TOKEN" -d "token_type_hint=refresh_token" http://localhost:8080/oauth2/revoke
curl -s -u messaging-client:secret -d "grant_type=refresh_token" -d "refresh_token=$REFRESH_TOKEN" http://localhost:8080/oauth2/token   # invalid_grant
```

A revoked *access token* may still be accepted by `/weather` until it expires: the resource server validates the JWT signature locally and never asks the authorization server. This is the usual trade-off of self-contained JWTs.

Restart experiment: stop and restart the app, then call `/weather` with a token from before the restart. It returns `401`, because the signing key is regenerated at startup.

## Useful endpoints

| Endpoint | Purpose |
|----------|---------|
| `/.well-known/openid-configuration` | OIDC discovery document |
| `/oauth2/authorize` | Authorization endpoint |
| `/oauth2/token` | Token endpoint |
| `/oauth2/introspect` | Token introspection |
| `/oauth2/revoke` | Token revocation |
| `/oauth2/jwks` | Public signing keys |
| `/userinfo` | OIDC user info (needs a token with `openid`) |
| `/weather` | Protected resource (requires `SCOPE_weather.read`) |
