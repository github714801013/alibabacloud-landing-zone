#!/usr/bin/env bash
# AutoWonder 本地构建、离线打包和远端部署脚本。
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
ENV_EXAMPLE="$PROJECT_ROOT/.env.example"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
DIST_DIR="$SCRIPT_DIR/dist"
ARCHIVE="$DIST_DIR/autowonder.tar.gz"
CHECKSUM_FILE="$DIST_DIR/autowonder.tar.gz.sha256"
PROJECT_NAME="autowonder"
APP_IMAGE="autowonder-community:latest"
MINIO_IMAGE="quay.io/minio/minio:latest"
MC_IMAGE="quay.io/minio/mc:latest"
REMOTE_TARGET="root@10.1.14.158"
REMOTE_DIR="/data2/project/autoWinder"
REMOTE_ENV="$REMOTE_DIR/.env"
SSH_OPTIONS=(-o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=yes)
REMOTE_STAGE=""

log() { printf '[autowonder] %s\n' "$*"; }
die() { printf '[autowonder] 错误：%s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
用法：
  deploy/deploy.sh          本地构建并打包镜像
  deploy/deploy.sh --check  检查本地配置和 Compose，不构建、不部署
  deploy/deploy.sh --deploy 本地构建打包后上传并部署到远端
  deploy/deploy.sh --help   显示帮助

说明：--deploy 会直接执行远端变更，不再交互询问；部署前必须填写 .env 中的外部服务参数。
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

env_value() {
  local key="$1" line
  line="$(awk -F= -v key="$key" '$1 == key { line=$0 } END { print line }' "$ENV_FILE")"
  line="${line%$'\r'}"
  [[ -n "$line" ]] || return 0
  printf '%s' "${line#*=}"
}

env_has_key() {
  awk -F= -v key="$1" '$1 == key { found=1 } END { exit !found }' "$ENV_FILE"
}

replace_placeholder() {
  local placeholder="$1" value="$2" escaped
  escaped="$(printf '%s' "$value" | sed 's/[&|\\]/\\&/g')"
  sed -i "s|$placeholder|$escaped|g" "$ENV_FILE"
}

set_env_value() {
  local key="$1" value="$2" temp
  temp="$(mktemp "$ENV_FILE.tmp.XXXXXX")"
  chmod 600 "$temp"
  awk -v key="$key" -v value="$value" '
    BEGIN { found = 0 }
    $0 ~ ("^" key "=") { print key "=" value; found = 1; next }
    { print }
    END { if (!found) print key "=" value }
  ' "$ENV_FILE" >"$temp"
  mv -f "$temp" "$ENV_FILE"
}

ensure_generated_app_credentials() {
  local app_user app_password
  if env_has_key MINIO_APP_USER && env_has_key MINIO_APP_PASSWORD; then
    return
  fi
  require_command openssl
  app_user="awapp$(openssl rand -hex 6)"
  app_password="$(openssl rand -hex 32)"
  set_env_value MINIO_APP_USER "$app_user"
  set_env_value MINIO_APP_PASSWORD "$app_password"
  set_env_value S3_ACCESS_KEY_ID "$app_user"
  set_env_value S3_ACCESS_KEY_SECRET "$app_password"
}

ensure_env() {
  [[ -f "$ENV_EXAMPLE" ]] || die "缺少配置模板：$ENV_EXAMPLE"
  if [[ -f "$ENV_FILE" ]]; then
    ensure_generated_app_credentials
    chmod 600 "$ENV_FILE"
    restrict_windows_env_acl
    return
  fi

  require_command openssl
  cp "$ENV_EXAMPLE" "$ENV_FILE"
  replace_placeholder '<GENERATED_MASTER_KEY>' "$(openssl rand -base64 32 | tr -d '\r\n')"
  replace_placeholder '<GENERATED_JWT_SECRET>' "$(openssl rand -base64 48 | tr -d '\r\n')"
  replace_placeholder '<GENERATED_MINIO_USER>' "awroot$(openssl rand -hex 6)"
  replace_placeholder '<GENERATED_MINIO_PASSWORD>' "$(openssl rand -hex 32)"
  replace_placeholder '<GENERATED_APP_USER>' "awapp$(openssl rand -hex 6)"
  replace_placeholder '<GENERATED_APP_PASSWORD>' "$(openssl rand -hex 32)"
  chmod 600 "$ENV_FILE"
  restrict_windows_env_acl
  log "已创建本地 .env；请填写 OceanBase 和 Redis 参数后再使用 --deploy。"
}

restrict_windows_env_acl() {
  [[ "${OS:-}" == Windows_NT ]] || return 0
  command -v icacls.exe >/dev/null 2>&1 || die "Windows 环境缺少 icacls.exe，无法保护 .env"
  command -v cygpath >/dev/null 2>&1 || die "Windows 环境缺少 cygpath，无法保护 .env"
  local windows_path
  windows_path="$(cygpath -w "$ENV_FILE")"
  MSYS2_ARG_CONV_EXCL='*' icacls.exe "$windows_path" /inheritance:r \
    /grant:r "${USERNAME}:(F)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" >/dev/null \
    || die "无法收紧 .env 的 Windows ACL"
}

ensure_env_acl() {
  restrict_windows_env_acl
}

value_is_placeholder() {
  local value="$1"
  [[ -z "$value" || "$value" == *'<'* || "$value" == *'>'* || "$value" == *'GENERATED_'* || "$value" == *'CHANGE_ME'* ]]
}

validate_deploy_env() {
  local key value
  local required_nonempty=(
    SPRING_DATASOURCE_URL
    SPRING_DATASOURCE_USERNAME
    SPRING_DATASOURCE_PASSWORD
    REDIS_HOST
    REDIS_PORT
    AUTOWONDER_SECRET_MASTER_KEY
    AUTOWONDER_JWT_SECRET
    AUTOWONDER_PUBLIC_BASE_URL
    S3_PUBLIC_ENDPOINT
    S3_ACCESS_KEY_ID
    S3_ACCESS_KEY_SECRET
    MINIO_APP_USER
    MINIO_APP_PASSWORD
    OSS_BUCKET
    MINIO_ROOT_USER
    MINIO_ROOT_PASSWORD
  )
  local required_present=(REDIS_PASSWORD)

  for key in "${required_nonempty[@]}"; do
    value="$(env_value "$key")"
    value_is_placeholder "$value" && die "部署配置缺少有效值：$key"
  done
  for key in "${required_present[@]}"; do
    env_has_key "$key" || die "部署配置缺少变量：$key"
    value="$(env_value "$key")"
    [[ "$value" != *'<'* && "$value" != *'>'* && "$value" != *'GENERATED_'* ]] \
      || die "部署配置含占位符：$key"
  done

  [[ "$(env_value S3_ENABLED)" == "true" ]] || die "S3_ENABLED 必须为 true"
  [[ "$(env_value OSS_ENABLED)" == "false" ]] || die "OSS_ENABLED 必须为 false"
  [[ "$(env_value S3_ENDPOINT)" == "http://minio:9000" ]] || die "S3_ENDPOINT 必须为 http://minio:9000"
  [[ "$(env_value S3_REGION)" == "us-east-1" ]] || die "S3_REGION 必须为 us-east-1"
  [[ "$(env_value MINIO_ROOT_USER)" != "$(env_value S3_ACCESS_KEY_ID)" ]] \
    || die "S3_ACCESS_KEY_ID 不得使用 MinIO root 用户"
  [[ "$(env_value MINIO_ROOT_PASSWORD)" != "$(env_value S3_ACCESS_KEY_SECRET)" ]] \
    || die "S3_ACCESS_KEY_SECRET 不得使用 MinIO root 密码"
  [[ "$(env_value MINIO_APP_USER)" == "$(env_value S3_ACCESS_KEY_ID)" ]] \
    || die "S3_ACCESS_KEY_ID 必须与 MINIO_APP_USER 一致"
  [[ "$(env_value MINIO_APP_PASSWORD)" == "$(env_value S3_ACCESS_KEY_SECRET)" ]] \
    || die "S3_ACCESS_KEY_SECRET 必须与 MINIO_APP_PASSWORD 一致"
  [[ "$(env_value MINIO_ROOT_USER)" =~ ^[A-Za-z0-9_-]+$ ]] || die "MINIO_ROOT_USER 仅允许字母、数字、下划线和连字符"
  [[ "$(env_value MINIO_ROOT_PASSWORD)" =~ ^[A-Za-z0-9_-]+$ ]] || die "MINIO_ROOT_PASSWORD 仅允许字母、数字、下划线和连字符"
  [[ "$(env_value MINIO_APP_USER)" =~ ^[A-Za-z0-9_-]+$ ]] || die "MINIO_APP_USER 仅允许字母、数字、下划线和连字符"
  [[ "$(env_value MINIO_APP_PASSWORD)" =~ ^[A-Za-z0-9_-]+$ ]] || die "MINIO_APP_PASSWORD 仅允许字母、数字、下划线和连字符"

  local master="$(env_value AUTOWONDER_SECRET_MASTER_KEY)"
  [[ "$master" =~ ^[A-Za-z0-9+/]{43}=$ ]] || die "AUTOWONDER_SECRET_MASTER_KEY 必须是 32 字节 Base64"
  local jwt="$(env_value AUTOWONDER_JWT_SECRET)"
  [[ ${#jwt} -ge 32 ]] || die "AUTOWONDER_JWT_SECRET 长度不能小于 32"
}

compose_config_check() {
  require_command docker
  docker compose version >/dev/null 2>&1 || die "Docker Compose v2 不可用"
  local compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -p "$PROJECT_NAME")
  "${compose[@]}" config --quiet || die "Compose 配置校验失败"
}

show_git_state() {
  local head dirty
  head="$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || printf 'unknown')"
  if [[ -n "$(git -C "$PROJECT_ROOT" status --porcelain 2>/dev/null || true)" ]]; then
    dirty=yes
  else
    dirty=no
  fi
  printf '%s\n%s\n' "$head" "$dirty"
}

pull_or_use_local() {
  local image="$1"
  if docker pull --platform linux/amd64 "$image"; then
    return 0
  fi
  docker image inspect "$image" >/dev/null 2>&1 \
    || die "无法拉取镜像且本地不存在：$image"
  log "镜像仓库不可用，复用已存在的本地镜像：$image"
}

build_archive() {
  require_command docker
  require_command gzip
  require_command sha256sum
  mkdir -p "$DIST_DIR"
  compose_config_check

  log "构建 AutoWonder linux/amd64 镜像。"
  docker build --platform linux/amd64 --pull \
    -f "$PROJECT_ROOT/APP-META/docker-config/Dockerfile" \
    -t "$APP_IMAGE" "$PROJECT_ROOT"

  log "刷新 MinIO 和 mc 镜像。"
  pull_or_use_local "$MINIO_IMAGE"
  pull_or_use_local "$MC_IMAGE"
  docker image inspect "$APP_IMAGE" "$MINIO_IMAGE" "$MC_IMAGE" >/dev/null

  log "生成离线镜像归档：$ARCHIVE"
  local archive_tmp="$ARCHIVE.tmp"
  rm -f "$archive_tmp"
  docker save "$APP_IMAGE" "$MINIO_IMAGE" "$MC_IMAGE" | gzip -c > "$archive_tmp"
  mv -f "$archive_tmp" "$ARCHIVE"
  sha256sum "$ARCHIVE" > "$CHECKSUM_FILE"
  log "镜像归档校验值已写入 $CHECKSUM_FILE。"
}

remote_preflight() {
  require_command ssh
  require_command scp
  log "执行远端预检。"
  ssh "${SSH_OPTIONS[@]}" "$REMOTE_TARGET" bash -s -- "$REMOTE_DIR" "$PROJECT_NAME" <<'REMOTE_PREFLIGHT'
set -euo pipefail
remote_dir="$1"
project="$2"
marker="$remote_dir/.autowonder-managed"
die() { printf '[远端预检] 错误：%s\n' "$*" >&2; exit 1; }

    command -v docker >/dev/null 2>&1 || die '远端缺少 docker'
    command -v curl >/dev/null 2>&1 || die '远端缺少 curl'
    command -v sha256sum >/dev/null 2>&1 || die '远端缺少 sha256sum'
    command -v flock >/dev/null 2>&1 || die '远端缺少 flock'
    command -v stat >/dev/null 2>&1 || die '远端缺少 stat'
docker version >/dev/null 2>&1 || die '远端 Docker 不可用'
docker compose version >/dev/null 2>&1 || die '远端 Docker Compose 不可用'
  parent="$(dirname "$remote_dir")"
  test -d "$parent" || die "父目录不存在：$parent"
  test -w "$parent" || die "父目录不可写：$parent"
if [[ -e "$remote_dir" ]]; then
  [[ -d "$remote_dir" && ! -L "$remote_dir" ]] || die "目标路径不是安全目录：$remote_dir"
  owner="$(stat -c '%u' "$remote_dir")"
  mode="$(stat -c '%a' "$remote_dir")"
  [[ "$owner" == 0 ]] || die "目标目录必须归 root 所有：$remote_dir"
  other_bits=$((8#$mode & 8#022))
  [[ "$other_bits" == 0 ]] || die "目标目录不得允许组或其他用户写入：$remote_dir"
  [[ -f "$marker" && ! -L "$marker" ]] || die "目标目录已存在但没有安全的管理标识：$remote_dir"
  [[ "$(tr -d '\r\n' <"$marker")" == 'autowonder-managed-v1' ]] || die '目标目录管理标识不匹配'
else
  install -d -m 0700 "$remote_dir"
  printf 'autowonder-managed-v1\n' >"$marker"
  chmod 600 "$marker"
fi

port_check() {
  local port="$1" service="$2" ids id labels listener
  ids="$(docker ps --filter "publish=$port" -q)"
  if [[ -n "$ids" ]]; then
    while IFS= read -r id; do
      [[ -n "$id" ]] || continue
      labels="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}} {{index .Config.Labels "com.docker.compose.service"}}' "$id")"
      [[ "$labels" == "$project $service" ]] || die "端口 $port 已被其他容器占用"
    done <<<"$ids"
    return
  fi
  if command -v ss >/dev/null 2>&1; then
    listener=0
    if ss -H -ltn | awk -v suffix=":$port" 'index($4, suffix) == length($4)-length(suffix)+1 { found=1 } END { exit !found }'; then
      listener=1
    fi
  elif command -v netstat >/dev/null 2>&1; then
    listener=0
    if netstat -lnt 2>/dev/null | awk -v suffix=":$port" 'index($4, suffix) == length($4)-length(suffix)+1 { found=1 } END { exit !found }'; then
      listener=1
    fi
  else
    die '远端缺少 ss/netstat，无法执行端口冲突检查'
  fi
  [[ "$listener" == 0 ]] || die "端口 $port 已被非本部署进程占用"
}
port_check 7001 autowonder
port_check 9000 minio
port_check 9001 minio

df -Pk "$parent" >/dev/null
printf '[远端预检] 通过：%s\n' "$remote_dir"
REMOTE_PREFLIGHT
}

check_remote_stable_keys() {
  ssh "${SSH_OPTIONS[@]}" "$REMOTE_TARGET" bash -s -- "$REMOTE_ENV" <<'REMOTE_KEYS'
set -euo pipefail
env_file="$1"
[[ -f "$env_file" ]] || exit 0
keys=(AUTOWONDER_SECRET_MASTER_KEY AUTOWONDER_JWT_SECRET MINIO_ROOT_USER MINIO_ROOT_PASSWORD MINIO_APP_USER MINIO_APP_PASSWORD)
for key in "${keys[@]}"; do
  value="$(awk -F= -v key="$key" '$1 == key { line=$0 } END { sub(/^[^=]*=/, "", line); sub(/\r$/, "", line); print line }' "$env_file")"
  printf '%s=%s\n' "$key" "$(printf '%s' "$value" | sha256sum | awk '{print $1}')"
done
REMOTE_KEYS
}

local_stable_key_hashes() {
  local key value
  for key in AUTOWONDER_SECRET_MASTER_KEY AUTOWONDER_JWT_SECRET MINIO_ROOT_USER MINIO_ROOT_PASSWORD MINIO_APP_USER MINIO_APP_PASSWORD; do
    value="$(env_value "$key")"
    printf '%s=%s\n' "$key" "$(printf '%s' "$value" | sha256sum | awk '{print $1}')"
  done
}

upload_and_deploy() {
  local archive_sha git_head dirty built_at local_hashes remote_hashes stage_id compose_sha env_sha
  archive_sha="$(cut -d' ' -f1 "$CHECKSUM_FILE")"
  compose_sha="$(sha256sum "$COMPOSE_FILE" | awk '{print $1}')"
  env_sha="$(sha256sum "$ENV_FILE" | awk '{print $1}')"
  git_head="$(show_git_state | sed -n '1p')"
  dirty="$(show_git_state | sed -n '2p')"
  built_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

  remote_preflight
  # 只比较摘要，避免在本地或远端输出持久化密钥。
  local_hashes="$(local_stable_key_hashes)"
  remote_hashes="$(check_remote_stable_keys)"
  [[ -n "$remote_hashes" && "$remote_hashes" != "$local_hashes" ]] &&
    die "远端持久化密钥与本地 .env 不一致；拒绝覆盖"
  log "上传镜像归档并执行远端校验。"
  stage_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
  REMOTE_STAGE="$REMOTE_DIR/.staging-$stage_id"
  ssh "${SSH_OPTIONS[@]}" "$REMOTE_TARGET" install -d -m 0700 "$REMOTE_STAGE"
  scp "${SSH_OPTIONS[@]}" "$ARCHIVE" "$REMOTE_TARGET:$REMOTE_STAGE/autowonder.tar.gz"
  local remote_sha
  remote_sha="$(ssh "${SSH_OPTIONS[@]}" "$REMOTE_TARGET" sha256sum "$REMOTE_STAGE/autowonder.tar.gz" | awk '{print $1}')"
  [[ "$remote_sha" == "$archive_sha" ]] || die "远端镜像归档校验失败"
  scp "${SSH_OPTIONS[@]}" "$COMPOSE_FILE" "$REMOTE_TARGET:$REMOTE_STAGE/docker-compose.yml"
  scp "${SSH_OPTIONS[@]}" "$ENV_FILE" "$REMOTE_TARGET:$REMOTE_STAGE/.env"
  ssh "${SSH_OPTIONS[@]}" "$REMOTE_TARGET" chmod 700 "$REMOTE_STAGE"
  ssh "${SSH_OPTIONS[@]}" "$REMOTE_TARGET" chmod 600 "$REMOTE_STAGE/.env"

  log "执行远端部署。"
  ssh "${SSH_OPTIONS[@]}" "$REMOTE_TARGET" bash -s -- "$REMOTE_DIR" "$PROJECT_NAME" "$archive_sha" "$compose_sha" "$env_sha" "$git_head" "$dirty" "$built_at" "$REMOTE_STAGE" <<'REMOTE_DEPLOY'
set -euo pipefail
remote_dir="$1"
project="$2"
archive_sha="$3"
compose_sha="$4"
env_sha="$5"
git_head="$6"
git_dirty="$7"
built_at="$8"
staging_dir="$9"
archive="$remote_dir/autowonder.tar.gz"
archive_tmp="$staging_dir/autowonder.tar.gz"
compose_file="$remote_dir/docker-compose.yml"
env_file="$remote_dir/.env"
compose_tmp="$staging_dir/docker-compose.yml"
env_tmp="$staging_dir/.env"
marker="$remote_dir/.autowonder-managed"
app_image='autowonder-community:latest'
minio_image='quay.io/minio/minio:latest'
exec 9>"$remote_dir/.deploy.lock"
flock -n 9 || { printf '[远端部署] 错误：另一个部署正在进行。\n' >&2; exit 1; }
trap 'rm -rf "$staging_dir"' EXIT

fail() { printf '[远端部署] 错误：%s\n' "$*" >&2; exit 1; }
[[ "$staging_dir" == "$remote_dir"/.staging-* && -d "$staging_dir" && ! -L "$staging_dir" ]] || fail '暂存目录无效'
[[ "$(tr -d '\r\n' <"$marker")" == 'autowonder-managed-v1' ]] || fail '管理标识丢失'
[[ "$(sha256sum "$archive_tmp" | awk '{print $1}')" == "$archive_sha" ]] || fail '镜像归档校验失败'
[[ "$(sha256sum "$compose_tmp" | awk '{print $1}')" == "$compose_sha" ]] || fail 'Compose 文件校验失败'
[[ "$(sha256sum "$env_tmp" | awk '{print $1}')" == "$env_sha" ]] || fail '环境文件校验失败'

docker load -i "$archive_tmp" >/dev/null
mv -f "$archive_tmp" "$archive"
mv -f "$compose_tmp" "$compose_file"
mv -f "$env_tmp" "$env_file"
chmod 600 "$env_file"

  compose=(docker compose --env-file "$env_file" -f "$compose_file" -p "$project")
  "${compose[@]}" config --quiet || fail '远端 Compose 配置校验失败'

digits_only() { [[ "$1" =~ ^[0-9]+$ ]]; }
mapfile -t app_ids < <(docker run --rm --platform linux/amd64 --entrypoint /bin/sh "$app_image" -c 'id -u autowonder; id -g autowonder')
mapfile -t minio_ids < <(docker run --rm --platform linux/amd64 --entrypoint /bin/sh "$minio_image" -c 'id -u; id -g')
[[ "${#app_ids[@]}" == 2 && "${#minio_ids[@]}" == 2 ]] || fail '无法读取镜像运行 UID/GID'
digits_only "${app_ids[0]}" && digits_only "${app_ids[1]}" || fail 'AutoWonder UID/GID 无效'
digits_only "${minio_ids[0]}" && digits_only "${minio_ids[1]}" || fail 'MinIO UID/GID 无效'

prepare_mount() {
  local dir="$1" uid="$2" gid="$3" image="$4" target="$5" test_name="$6"
  install -d -m 0750 "$dir"
  chown "$uid:$gid" "$dir"
  if ! docker run --rm --platform linux/amd64 --user "$uid:$gid" \
      -v "$dir:$target" --entrypoint /bin/sh "$image" \
      -c "touch '$target/$test_name' && rm -f '$target/$test_name'" >/dev/null 2>&1; then
    chown -R "$uid:$gid" "$dir" || fail "无法设置目录属主：$dir"
    docker run --rm --platform linux/amd64 --user "$uid:$gid" \
      -v "$dir:$target" --entrypoint /bin/sh "$image" \
      -c "touch '$target/$test_name' && rm -f '$target/$test_name'" >/dev/null 2>&1 \
      || fail "容器用户无法写入目录：$dir"
  fi
}
prepare_mount "$remote_dir/logs" "${app_ids[0]}" "${app_ids[1]}" "$app_image" /app/logs .autowonder-write-test
prepare_mount "$remote_dir/minio-data" "${minio_ids[0]}" "${minio_ids[1]}" "$minio_image" /data .autowonder-write-test

minio_id="$("${compose[@]}" ps -q minio 2>/dev/null || true)"
minio_running=no
if [[ -n "$minio_id" ]] && [[ "$(docker inspect -f '{{.State.Running}}' "$minio_id" 2>/dev/null || true)" == true ]]; then
  minio_running=yes
fi
if [[ "$minio_running" != yes ]]; then
  "${compose[@]}" up -d minio
fi
for attempt in $(seq 1 30); do
  minio_id="$("${compose[@]}" ps -q minio 2>/dev/null || true)"
  [[ -n "$minio_id" ]] || { sleep 5; continue; }
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$minio_id" 2>/dev/null || true)"
  [[ "$health" == healthy ]] && break
  [[ "$health" == unhealthy ]] && fail 'MinIO 健康检查失败'
  sleep 5
done
[[ "${health:-}" == healthy ]] || fail '等待 MinIO 健康超时'

# MinIO 健康后幂等创建 bucket 与应用账户；不删除任何已有对象。
"${compose[@]}" run --rm -T --no-deps minio-init </dev/null >/dev/null
"${compose[@]}" up -d --force-recreate --no-deps autowonder

image_id="$(docker image inspect --format '{{.Id}}' "$app_image")"
minio_image_id="$(docker image inspect --format '{{.Id}}' "$minio_image")"
mc_image_id="$(docker image inspect --format '{{.Id}}' 'quay.io/minio/mc:latest')"
cat >"$remote_dir/deploy-manifest.env" <<MANIFEST
MANIFEST_VERSION=1
COMPOSE_PROJECT=$project
IMAGE=$app_image
IMAGE_ID=$image_id
MINIO_IMAGE_ID=$minio_image_id
MC_IMAGE_ID=$mc_image_id
GIT_HEAD=$git_head
GIT_WORKTREE_DIRTY=$git_dirty
BUILT_AT_UTC=$built_at
ARCHIVE_SHA256=$archive_sha
MANIFEST
chmod 600 "$remote_dir/deploy-manifest.env"

ready=no
app_id="$("${compose[@]}" ps -q autowonder 2>/dev/null || true)"
[[ -n "$app_id" ]] || fail '未找到 AutoWonder 容器'
stable_checks=0
for attempt in $(seq 1 12); do
  status_line="$(docker inspect -f '{{.State.Running}} {{.RestartCount}}' "$app_id" 2>/dev/null || true)"
  running="${status_line%% *}"
  restart_count="${status_line##* }"
  body="$(curl -fsS --max-time 5 http://127.0.0.1:7001/checkpreload.htm 2>/dev/null || true)"
  branding_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:7001/api/platform/branding/public 2>/dev/null || true)"
  capabilities_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:7001/api/integrations/capabilities 2>/dev/null || true)"
  if [[ "$running" == true && "$restart_count" == 0 && "$body" == success \
      && "$branding_code" == 200 && "$capabilities_code" == 200 ]]; then
    stable_checks=$((stable_checks + 1))
  else
    stable_checks=0
  fi
  if [[ "$stable_checks" -ge 3 ]]; then
    ready=yes
    printf '[远端部署] 健康检查通过，连续稳定检查=%s。\n' "$stable_checks"
    break
  fi
  sleep 10
done
if [[ "$ready" != yes ]]; then
  printf '[远端部署] 健康检查失败；保留容器、日志和镜像现场，不自动回滚。\n' >&2
  "${compose[@]}" stop autowonder >&2 || true
  "${compose[@]}" ps >&2 || true
  printf '[远端部署] 请在远端查看：docker compose --env-file %s -f %s -p %s logs --tail=100 autowonder\n' \
    "$env_file" "$compose_file" "$project" >&2
  exit 1
fi
"${compose[@]}" ps
printf '[远端部署] 完成：%s\n' "$remote_dir"
REMOTE_DEPLOY
}

main() {
  [[ $# -le 1 ]] || { usage >&2; exit 2; }
  local mode="build"
  case "${1:-}" in
    '') ;;
    --check) mode=check ;;
    --deploy) mode=deploy ;;
    --help|-h) usage; return 0 ;;
    *) usage >&2; exit 2 ;;
  esac

  require_command awk
  require_command sed
  require_command git
  ensure_env
  ensure_env_acl

  if [[ "$mode" == check ]]; then
    compose_config_check
    log "本地检查通过；未构建、未上传、未部署。"
    return 0
  fi

  if [[ "$mode" == deploy ]]; then
    validate_deploy_env
  fi
  build_archive
  if [[ "$mode" == deploy ]]; then
    upload_and_deploy
  else
    log "本地构建打包完成；未执行远端操作。"
  fi
}

main "$@"
