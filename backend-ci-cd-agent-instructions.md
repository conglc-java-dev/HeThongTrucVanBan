# Backend CI/CD — Agent Implementation Instructions

## Goal

Implement a complete CI/CD foundation for the existing Backend project.

Environment:

- Source repository: GitHub
- Container registry: GitHub Container Registry (GHCR)
- Production server:
  - Host: `115.146.126.49`
  - SSH port: `2228`
  - SSH user: `trucvb`
  - Docker: already installed
  - Backend project: NOT deployed yet
- Frontend is owned by another team.
- Backend and Frontend may eventually share the same physical server.
- This task is for the Backend deployment boundary.

Do not ask for or hard-code the user's SSH private key, server password, database password, JWT secret, or other credentials.

---

# Part 1 — Inspect the Backend first

Before changing anything:

1. Inspect the repository.
2. Determine:
   - Spring Boot version.
   - Java version.
   - Maven or Gradle.
   - Application port.
   - Database.
   - Redis usage.
   - Other infrastructure dependencies.
   - Existing Unit Tests.
   - Existing Integration Tests.
   - Existing Dockerfile.
   - Existing Docker Compose.
   - Existing application configuration.
3. Do not overwrite working configuration blindly.
4. Reuse existing conventions where possible.

The final Compose file must contain the services actually required by the Backend.

At minimum, if the project uses them:

```text
nginx
backend
postgres
redis
```

Add other services only when the Backend actually requires them.

---

# Part 2 — CI Workflow

Create:

```text
.github/workflows/ci.yml
```

## Trigger

The main quality gate is Pull Request → `main`.

```yaml
on:
  pull_request:
    branches:
      - main
```

Do not unnecessarily create multiple CI runs.

## Runner

Use:

```yaml
runs-on: ubuntu-latest
```

Conceptually:

```text
GitHub
  ↓
Temporary GitHub-hosted Runner
  ↓
Checkout
  ↓
Setup JDK
  ↓
Install dependencies
  ↓
Compile
  ↓
Unit Test
  ↓
Integration Test (if applicable)
  ↓
PASS / FAIL
```

The runner is temporary and is NOT the production server.

## CI steps

The workflow must:

1. Checkout source code.
2. Setup the correct Java version.
3. Cache Maven/Gradle dependencies where appropriate.
4. Compile the project.
5. Run Unit Tests.
6. Run existing Integration Tests if they exist.
7. Fail the workflow when compilation/tests fail.

Use the project's actual build system.

For Maven, a typical command is:

```bash
./mvnw clean verify
```

For Gradle:

```bash
./gradlew clean check
```

Do not blindly use these commands if the repository uses a different lifecycle.

---

# Part 3 — Unit Test

CI must automatically execute the existing Unit Tests.

Expected:

```text
PR
 ↓
CI
 ↓
Unit Test
 ↓
PASS → continue
FAIL → workflow failed
```

The failed check and logs must be visible in GitHub Pull Requests.

---

# Part 4 — Integration Test

Inspect the existing project.

If Integration Tests already exist:

- Execute them in CI.
- Ensure required infrastructure is available.

If they do not exist:

- Do not invent a huge integration-test suite.
- Do not create one test for every API merely for coverage.
- Prepare the project for future integration testing if useful.

The principle is:

```text
Unit Test
→ isolated logic

Integration Test
→ multiple real components working together
```

Example:

```text
HTTP
 ↓
Controller
 ↓
Service
 ↓
JPA/Hibernate
 ↓
PostgreSQL
```

Integration tests are automated and can run on the CI runner.

---

# Part 5 — Dockerfile

Create or improve:

```text
Dockerfile
```

Requirements:

- Production-appropriate Java runtime.
- Prefer multi-stage build if building inside Docker.
- Do not hard-code secrets.
- Run as non-root where practical.
- Use environment variables/external configuration for runtime configuration.

Important distinction:

```text
CI
→ compile + test

CD
→ build Docker image
→ push Docker image
→ deploy
```

The Docker image should be built for deployment only after the required CI quality gate has passed.

---

# Part 6 — Docker Compose

Create:

```text
docker-compose.yml
```

The Compose stack must represent the Backend deployment boundary.

Example:

```text
nginx
backend
postgres
redis
```

Add only dependencies actually required by the application.

Architecture:

```text
Internet
   │
   │ :80 / :443
   ▼
 Nginx
   │
   │ reverse proxy
   ▼
Backend :8080
   │
   ├── PostgreSQL
   │
   └── Redis
```

Do not expose PostgreSQL or Redis publicly unless there is an explicit requirement.

Prefer:

```yaml
backend:
  expose:
    - "8080"
```

rather than unnecessarily publishing the Backend port to the Internet.

---

# Part 7 — Nginx

Create:

```text
nginx/
└── nginx.conf
```

Nginx must reverse proxy requests to the Backend.

Example concept:

```text
Client
  ↓
Nginx
  ↓
backend:8080
```

Preserve standard proxy headers:

```text
Host
X-Real-IP
X-Forwarded-For
X-Forwarded-Proto
```

Do not create Frontend routing because Frontend is owned by another team.

Important:

If Frontend and Backend eventually share the same server, there must be only one clear owner of public ports `80/443`.

Do not create two containers both trying to bind the same public ports.

---

# Part 8 — Environment Variables

Create:

```text
.env.example
```

Document required variables without real secrets.

Typical examples:

```text
POSTGRES_HOST
POSTGRES_PORT
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD

REDIS_HOST
REDIS_PORT

JWT_SECRET
```

Use the actual variables required by the inspected application.

Never commit production secrets.

Add `.env` to `.gitignore` if it is not already there.

---

# Part 9 — GHCR

Use GitHub Container Registry.

The image should follow a pattern such as:

```text
ghcr.io/<github-owner>/<repository>
```

Use an immutable tag based on Git commit SHA.

Example:

```text
ghcr.io/example/backend:a81f3c2
```

Do not rely only on:

```text
latest
```

Reason:

```text
commit
 ↓
image
 ↓
exact deployment version
```

This makes rollback and debugging easier.

---

# Part 10 — CD Workflow

Create:

```text
.github/workflows/cd.yml
```

## Trigger

CD must run after successful merge/push to `main`.

Use:

```yaml
on:
  push:
    branches:
      - main
```

The intended flow is:

```text
PR
 ↓
CI
 ↓
PASS
 ↓
Leader Review
 ↓
Merge → main
 ↓
CD
```

---

# Part 11 — CD Flow

CD must implement:

```text
main
 ↓
Checkout
 ↓
Build Docker Image
 ↓
Tag with Git SHA
 ↓
Login to GHCR
 ↓
Push image to GHCR
 ↓
SSH into production server
 ↓
Pull image
 ↓
Deploy Backend
 ↓
Health Check
 ↓
Success / Failure
```

Do not build and push the production image before the PR quality gate has passed.

---

# Part 12 — GitHub Secrets

Do not put credentials directly in `cd.yml`.

Use GitHub repository/environment secrets.

Expected secrets:

```text
SERVER_HOST
SERVER_PORT
SERVER_USER
SERVER_SSH_KEY
GHCR_TOKEN
```

Known server values:

```text
SERVER_HOST=115.146.126.49
SERVER_PORT=2228
SERVER_USER=trucvb
```

The SSH private key must be stored only as a GitHub Secret.

Do not print secrets in logs.

For GHCR authentication, prefer GitHub's built-in:

```text
GITHUB_TOKEN
```

when its permissions are sufficient.

Configure:

```yaml
permissions:
  contents: read
  packages: write
```

Avoid creating an unnecessary long-lived PAT.

---

# Part 13 — Production Server Initial Setup

The server currently has Docker but no Backend deployment.

The agent must provide a clear server bootstrap procedure.

Do not assume the repository already exists on the server.

Recommended deployment directory:

```text
/opt/backend
```

The user must run commands on the server.

SSH:

```bash
ssh -p 2228 trucvb@115.146.126.49
```

Then:

```bash
sudo mkdir -p /opt/backend
sudo chown -R $USER:$USER /opt/backend
cd /opt/backend
```

The deployment directory should eventually contain the production Compose configuration and Nginx configuration.

Do NOT require cloning the Git repository onto the production server if the CD pipeline is designed to deploy Docker images from GHCR.

The server should consume the image from GHCR rather than building the application from source.

---

# Part 14 — Production Server Docker Authentication

The production server must be able to pull the private GHCR image.

Prefer using a GitHub token with only the required package read permission.

Do not put the token into the repository.

The login operation should be performed securely and should not expose the token in shell history or logs.

Conceptually:

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u <github-user> --password-stdin
```

The actual secret must come from a secure mechanism.

If the CD workflow performs the remote login through SSH, pass the credential securely.

Do not write:

```bash
docker login ghcr.io -u user -p actual-password
```

into a committed file.

---

# Part 15 — Production Compose Image Strategy

The production server's Compose should reference the GHCR image, not build the application from source.

Example concept:

```yaml
services:
  backend:
    image: ghcr.io/<owner>/<repo>:<tag>
```

The exact mechanism for injecting the immutable image tag must be designed carefully.

Preferred:

```text
IMAGE_TAG=<commit-sha>
```

then:

```yaml
image: ghcr.io/<owner>/<repo>:${IMAGE_TAG}
```

CD can update the deployment to the new tag before:

```bash
docker compose pull backend
docker compose up -d backend
```

Do not use `docker compose build` on the production server.

Production should consume the already-built image from GHCR.

---

# Part 16 — Deployment Must Not Restart Unrelated Services

Do NOT use this as the normal Backend deployment mechanism:

```bash
docker compose down
docker compose up -d
```

That would stop the whole stack.

Prefer:

```bash
docker compose pull backend
docker compose up -d backend
```

Target architecture:

```text
Backend changed
     ↓
Backend recreated
     ↓
PostgreSQL unaffected
Redis unaffected
Frontend unaffected
```

If Nginx configuration did not change, do not recreate Nginx unnecessarily.

---

# Part 17 — Health Check

CD must not simply assume that:

```bash
docker compose up -d backend
```

means the deployment succeeded.

After deployment:

1. Check container status.
2. Check application health endpoint if the application provides one.
3. Fail CD if the Backend does not become healthy within a reasonable timeout.

Preferred architecture:

```text
Deploy
 ↓
Wait
 ↓
Health check
 ↓
Healthy → success
Unhealthy → failure
```

Use the application's real health endpoint after inspecting the project.

For Spring Boot Actuator this may be something similar to:

```text
/actuator/health
```

Do not assume the endpoint exists; inspect the project first.

---

# Part 18 — Rollback

Design CD so rollback is possible.

Because images are tagged by Git SHA:

```text
backend:a81f3c2
backend:b72ac91
backend:c83bd02
```

a previous version can be redeployed.

Do not delete old images immediately after deployment.

The agent should document a manual rollback command/process.

Example concept:

```text
Set IMAGE_TAG to previous known-good SHA
 ↓
docker compose pull backend
 ↓
docker compose up -d backend
 ↓
health check
```

---

# Part 19 — Nginx and Public Networking

Expected public flow:

```text
Internet
   ↓
Server :80 / :443
   ↓
Nginx
   ↓
backend:8080
```

Only Nginx should normally bind the public HTTP/HTTPS ports for this Backend deployment.

Do not expose:

```text
PostgreSQL :5432
Redis :6379
Backend :8080
```

publicly unless explicitly required.

Docker internal networking should be used between services.

---

# Part 20 — Validation

Before declaring the task complete, validate locally where possible.

Application:

```bash
./mvnw clean verify
```

or:

```bash
./gradlew clean check
```

Docker:

```bash
docker build -t backend:local .
```

Compose syntax:

```bash
docker compose config
```

Start:

```bash
docker compose up -d
```

Inspect:

```bash
docker compose ps
```

Logs:

```bash
docker compose logs -f backend
```

Nginx logs:

```bash
docker compose logs -f nginx
```

Test the reverse proxy:

```bash
curl -i http://localhost/
```

Use the application's actual health endpoint if available.

---

# Part 21 — Expected Repository Structure

After implementation, the repository should approximately contain:

```text
.
├── .github/
│   └── workflows/
│       ├── ci.yml
│       └── cd.yml
│
├── nginx/
│   └── nginx.conf
│
├── Dockerfile
├── docker-compose.yml
├── .env.example
└── ...
```

Do not create files that are unnecessary for the actual project.

---

# Part 22 — Complete Flow

The final intended system:

```text
Developer
    ↓
Feature Branch
    ↓
Pull Request
    ↓
┌─────────────────────────────┐
│ CI                          │
│                             │
│ GitHub-hosted Runner        │
│ ↓                           │
│ Checkout                    │
│ ↓                           │
│ Setup JDK                   │
│ ↓                           │
│ Install dependencies        │
│ ↓                           │
│ Compile                     │
│ ↓                           │
│ Unit Test                   │
│ ↓                           │
│ Integration Test            │
│ ↓                           │
│ PASS / FAIL                 │
└──────────────┬──────────────┘
               │
             PASS
               ↓
        Leader Review
               ↓
          Merge → main
               ↓
┌─────────────────────────────┐
│ CD                          │
│                             │
│ Build Docker Image          │
│ ↓                           │
│ Tag with Git SHA            │
│ ↓                           │
│ Push → GHCR                 │
│ ↓                           │
│ SSH → Production Server     │
│ ↓                           │
│ Pull image                  │
│ ↓                           │
│ Deploy Backend              │
│ ↓                           │
│ Health Check                │
└──────────────┬──────────────┘
               ↓
          Production
```

---

# Part 23 — Engineering Constraints

## Do

- Inspect before modifying.
- Keep CI and CD separate.
- Use GitHub-hosted runners for CI.
- Use GHCR for Docker images.
- Use immutable Git SHA image tags.
- Keep production secrets out of Git.
- Deploy only the Backend service when Backend changes.
- Use Nginx as reverse proxy.
- Validate deployment health.
- Make rollback possible.

## Do not

- Do not hard-code secrets.
- Do not expose PostgreSQL/Redis unnecessarily.
- Do not use `docker compose down` for normal Backend deployment.
- Do not build the production image on the production server.
- Do not use `latest` as the only deployment identifier.
- Do not modify Frontend code.
- Do not create duplicate Nginx instances competing for ports 80/443.
- Do not claim CI/CD works without validating the relevant commands.
- Do not overwrite existing project configuration without inspecting it first.

---

# Final Engineering Principle

The objective is not merely:

```text
"Make GitHub Actions green."
```

The objective is a reproducible deployment pipeline:

```text
Code
 ↓
CI verifies code
 ↓
Human review
 ↓
Merge
 ↓
CD builds immutable artifact
 ↓
GHCR stores artifact
 ↓
Production pulls exact artifact
 ↓
Health check
 ↓
Running system
```

The important separation is:

```text
CI
→ "Is this code safe enough to proceed?"

CD
→ "How do I reliably deliver this verified code to production?"
```

The agent must implement the configuration according to the actual project discovered during inspection rather than blindly copying the examples above.
