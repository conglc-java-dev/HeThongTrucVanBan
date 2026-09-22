# Backend production deployment

The production host runs immutable Backend images from GHCR. It does not clone or build the source repository. PostgreSQL, Redis, RabbitMQ, and MinIO are private Docker-network services; Nginx is the only service that publishes an HTTP port, mapped as host port `8088` to container port `80`.

Development/simulator private keys under `src/main/resources/keys` are excluded from the production Docker build. If a simulator key is deliberately needed outside development, mount it from a server-managed secret path and set `CLIENT_SIMULATOR_PRIVATE_KEY_PATH`; never bake it into the image.

## 1. Configure GitHub

Create a protected GitHub environment named `production` and add these environment secrets:

- `SERVER_HOST`: `115.146.126.49`
- `SERVER_PORT`: `2228`
- `SERVER_USER`: `trucvb`
- `SERVER_SSH_KEY`: the private key for the deployment user

The workflow uses the short-lived `GITHUB_TOKEN` to publish this repository's GHCR package. The server uses the GHCR credentials configured during bootstrap to pull it. Protect `main` and require the `Backend CI / verify` check before merge. After merge, CI verifies the resulting `main` commit again; CD starts only after that CI run succeeds.

## 2. Bootstrap the server once

Connect and create the deployment directory:

```bash
ssh -p 2228 trucvb@115.146.126.49
sudo mkdir -p /opt/backend/nginx
sudo chown -R "$USER:$USER" /opt/backend
exit
```

From a trusted workstation in this repository, copy only the deployment configuration:

```bash
scp -P 2228 docker-compose.yml trucvb@115.146.126.49:/opt/backend/docker-compose.yml
scp -P 2228 nginx/nginx.conf trucvb@115.146.126.49:/opt/backend/nginx/nginx.conf
scp -P 2228 .env.example trucvb@115.146.126.49:/opt/backend/.env.example
```

Reconnect, create `/opt/backend/.env` from `.env.example`, and replace every placeholder with production values. Keep the file readable only by the deployment user:

```bash
cd /opt/backend
cp .env.example .env
chmod 600 .env
```

Set `BACKEND_IMAGE` to the lowercase `ghcr.io/<owner>/<repository>` path and set `IMAGE_TAG` to an existing full commit SHA. Generate security values with a cryptographically secure tool; for example, `openssl rand -base64 32` creates a suitable JWT secret. Authenticate to GHCR using a read-only package token supplied through standard input:

```bash
read -rsp 'GHCR token: ' GHCR_TOKEN
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username '<github-user>' --password-stdin
unset GHCR_TOKEN
```

Start the initial stack and verify it:

```bash
docker compose pull
docker compose up -d
docker compose ps
curl --fail http://localhost:8088/api/v1/actuator/health
```

No database, Redis, RabbitMQ, MinIO, or Backend ports are published. To administer those services, use SSH and `docker compose exec` rather than opening their ports.

## 3. Normal deployment

A pull request to `main` runs Maven `clean verify`. After the required check passes and the pull request is merged, CD repeats the quality gate, builds one image tagged with the full Git SHA, pushes it to GHCR, and runs only:

```bash
docker compose pull backend
docker compose up -d --no-deps backend
```

CD uses `docker compose up --wait` to wait for the container's `/api/v1/actuator/health` check. This requires Docker Compose 2.20 or newer. It does not stop or recreate infrastructure or Nginx.

## 4. Manual rollback

Find the previous known-good full SHA in GHCR or GitHub Actions. On the server, update `IMAGE_TAG` in `/opt/backend/.env`, then redeploy only Backend:

```bash
cd /opt/backend
docker compose pull backend
docker compose up -d --no-deps backend
docker compose ps backend
```

Confirm `backend` becomes healthy and test `curl --fail http://localhost:8088/api/v1/actuator/health`. Immutable SHA tags are retained, so rollback does not rebuild source or restart unrelated services.

## 5. Updating deployment configuration

Compose and Nginx changes are operational changes and are intentionally not overwritten by every application deployment. Review and copy changed files to `/opt/backend` explicitly. Reload only the affected service; for an Nginx-only change:

```bash
cd /opt/backend
docker compose exec nginx nginx -t
docker compose exec nginx nginx -s reload
```

This Backend stack publishes Nginx on host port `8088`, so it does not compete for ports `80` and `443`. A future shared public proxy can forward Backend traffic to port `8088`, or the teams can connect it through an agreed Docker network.
