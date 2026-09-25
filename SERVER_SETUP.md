# SERVER_SETUP.md

## Mục tiêu

Setup production server lần đầu cho Backend.

- Host: `115.146.126.49`
- SSH port: `2228`
- User: `trucvb`
- Docker: đã cài
- Registry: GitHub Container Registry (GHCR)
- Backend chưa được deploy lên server

> Không commit SSH private key, GHCR token, password, JWT secret hoặc production `.env` vào Git.

## 1. SSH vào server

Từ máy local:

```bash
ssh -p 2228 trucvb@115.146.126.49
```

Kiểm tra:

```bash
whoami
hostname
pwd
docker --version
docker compose version
docker info
```

Nếu `docker ps` bị permission denied:

```bash
sudo usermod -aG docker $USER
```

Sau đó logout/login lại:

```bash
exit
ssh -p 2228 trucvb@115.146.126.49
```

## 2. Kiểm tra port

```bash
sudo ss -lntp
```

Hoặc:

```bash
sudo ss -lntp | grep -E ':80|:443|:5432|:6379|:8080'
```

Mục tiêu:

```text
Internet
   ↓ :80/:443
 Nginx
   ↓
Backend :8080
   ↓
PostgreSQL / Redis
```

Không expose PostgreSQL/Redis/Backend public nếu không cần.

## 3. Tạo deployment directory

```bash
sudo mkdir -p /opt/backend/nginx
sudo chown -R $USER:$USER /opt/backend
cd /opt/backend
```

Cấu trúc cuối:

```text
/opt/backend/
├── docker-compose.yml
├── .env
└── nginx/
    └── nginx.conf
```

Production **không cần clone source repository**. Server sẽ pull Docker image từ GHCR.

## 4. Login GHCR lần đầu

Tạo GitHub token có tối thiểu quyền:

```text
read:packages
```

Trên server:

```bash
docker login ghcr.io
```

Nhập:

```text
Username: YOUR_GITHUB_USERNAME
Password: YOUR_GHCR_READ_TOKEN
```

Hoặc an toàn hơn:

```bash
read -s GHCR_TOKEN
echo "$GHCR_TOKEN" | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin
unset GHCR_TOKEN
```

Kết quả cần có:

```text
Login Succeeded
```

Không dùng:

```bash
docker login ghcr.io -u user -p TOKEN
```

vì credential có thể lộ trong history/process list.

## 5. Kiểm tra pull image

Sau khi GitHub Actions đã push image đầu tiên:

```bash
docker pull ghcr.io/OWNER/REPOSITORY:IMAGE_TAG
```

Ví dụ:

```bash
docker pull ghcr.io/my-org/my-backend:a81f3c2
```

Nếu `unauthorized`/`denied`, kiểm tra:

1. `docker login ghcr.io`
2. Token có `read:packages`
3. Package permission
4. Image name
5. Image tag

## 6. Tạo production `.env`

```bash
cd /opt/backend
nano .env
```

Chỉ khai báo các biến mà Backend thực sự sử dụng. Ví dụ:

```env
IMAGE_TAG=REPLACE_WITH_COMMIT_SHA

POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=backend
POSTGRES_USER=backend
POSTGRES_PASSWORD=CHANGE_ME

REDIS_HOST=redis
REDIS_PORT=6379

JWT_SECRET=CHANGE_ME
```

Bảo vệ file:

```bash
chmod 600 /opt/backend/.env
```

Không commit file này.

## 7. Production Compose

`/opt/backend/docker-compose.yml` phải reference image GHCR, không build source trên server.

Concept:

```yaml
services:
  backend:
    image: ghcr.io/OWNER/REPOSITORY:${IMAGE_TAG}
    restart: unless-stopped
    expose:
      - "8080"
    env_file:
      - .env

  postgres:
    image: postgres:VERSION
    restart: unless-stopped
    # configuration theo Backend thực tế

  redis:
    image: redis:VERSION
    restart: unless-stopped

  nginx:
    image: nginx:VERSION
    restart: unless-stopped
    ports:
      - "80:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
```

Không copy nguyên ví dụ nếu project dùng cấu hình khác. `AGENT_CI_CD.md` phải inspect Backend để xác định dependency/version/port/env.

## 8. Nginx

File:

```text
/opt/backend/nginx/nginx.conf
```

Concept cơ bản:

```nginx
events {}

http {
    upstream backend {
        server backend:8080;
    }

    server {
        listen 80;

        location / {
            proxy_pass http://backend;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
    }
}
```

`backend` là Compose service name. Không dùng `localhost:8080` từ trong Nginx container.

## 9. Validate Compose

```bash
cd /opt/backend
docker compose config
```

Chỉ tiếp tục khi command không báo lỗi.

## 10. First deployment

Sau khi image đã tồn tại trên GHCR:

```bash
cd /opt/backend
docker compose pull
docker compose up -d
```

Kiểm tra:

```bash
docker compose ps
```

Logs:

```bash
docker compose logs -f backend
```

```bash
docker compose logs -f nginx
```

## 11. Health check

Nếu project có Spring Boot Actuator và health endpoint phù hợp:

```bash
curl -i http://localhost/actuator/health
```

Nếu không, dùng endpoint health thực tế của application.

Test qua Nginx:

```bash
curl -i http://localhost/
```

Từ local:

```bash
curl -i http://115.146.126.49/
```

Chỉ coi deployment thành công khi application thực sự healthy.

## 12. Firewall

Kiểm tra:

```bash
sudo ufw status
```

Thông thường public chỉ cần:

```text
SSH
HTTP :80
HTTPS :443
```

Không mở public:

```text
5432 PostgreSQL
6379 Redis
8080 Backend
```

**Không tự chạy `ufw enable` trên remote server** nếu chưa chắc rule SSH đã cho phép port `2228`, tránh tự khóa quyền truy cập.

## 13. Deployment về sau

CD sẽ thực hiện:

```text
Merge main
 ↓
CD
 ↓
Docker build
 ↓
Tag = Git SHA
 ↓
Push GHCR
 ↓
SSH server :2228
 ↓
Update IMAGE_TAG
 ↓
docker compose pull backend
 ↓
docker compose up -d backend
 ↓
Health check
```

Không dùng mặc định:

```bash
docker compose down
docker compose up -d
```

vì sẽ tác động toàn bộ stack.

Backend thay đổi nên target:

```bash
docker compose pull backend
docker compose up -d backend
```

Mục tiêu:

```text
Backend     → updated
Nginx       → unchanged
PostgreSQL  → unchanged
Redis       → unchanged
Frontend    → unchanged
```

## 14. Rollback

Image nên được tag bằng Git SHA:

```text
backend:a81f3c2
backend:b72ac91
backend:c83bd02
```

Nếu version hiện tại lỗi, đổi `.env` về SHA tốt trước đó:

```env
IMAGE_TAG=b72ac91
```

Sau đó:

```bash
cd /opt/backend
docker compose pull backend
docker compose up -d backend
```

Kiểm tra health.

## 15. Không clone repository production

Không cần:

```bash
git clone ...
git pull ...
```

Production chỉ cần:

```text
Docker
Docker Compose
Nginx
.env
docker-compose.yml
GHCR access
```

Source code được build thành image ở CD, sau đó production pull image.

## 16. Checklist

- [ ] SSH vào server bằng port `2228`
- [ ] Docker hoạt động
- [ ] Docker Compose hoạt động
- [ ] `/opt/backend` tồn tại
- [ ] `/opt/backend/nginx` tồn tại
- [ ] Server login được GHCR
- [ ] Server pull được private image
- [ ] `.env` đã tạo và chmod `600`
- [ ] `docker-compose.yml` đã đặt trên server
- [ ] `nginx/nginx.conf` đã đặt trên server
- [ ] `docker compose config` PASS
- [ ] First deployment PASS
- [ ] Backend healthy
- [ ] Nginx reverse proxy hoạt động
- [ ] PostgreSQL/Redis không public không cần thiết
- [ ] Rollback procedure đã kiểm tra

## 17. Architecture cuối

```text
                 Internet
                    │
               :80 / :443
                    │
                    ▼
             ┌─────────────┐
             │    Nginx    │
             │   Reverse   │
             │    Proxy    │
             └──────┬──────┘
                    │
                    ▼
             ┌─────────────┐
             │   Backend   │
             │   :8080     │
             └──────┬──────┘
                    │
              ┌─────┴─────┐
              ▼           ▼
        PostgreSQL       Redis
```

Deployment:

```text
GitHub
  ↓
CI
  ├── Compile
  ├── Unit Test
  └── Integration Test
  ↓
Merge main
  ↓
CD
  ├── Docker Build
  ├── Push → GHCR
  └── SSH → Production
             ↓
        Pull exact SHA
             ↓
        Deploy Backend
             ↓
        Health Check
```
