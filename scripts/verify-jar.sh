#!/usr/bin/env bash
# ============================================================
# 快速验证脚本：把 jar 传到服务器跑一遍并拉回报告
# 前提: 目标服务器已安装 Java 8+（没装 Java 请用 deploy.sh，它自带运行时）
# 用法:
#   ./scripts/verify-jar.sh root@1.2.3.4 [--port 22] [--domain example.com] [--contact "微信: xxx"]
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

GREEN='\033[32m'; RED='\033[31m'; BOLD='\033[1m'; NC='\033[0m'
info() { echo -e "${GREEN}[verify]${NC} $*"; }
err()  { echo -e "${RED}[verify]${NC} $*" >&2; }

# ---------- 参数解析 ----------
TARGET="" SSH_PORT=22 DOMAIN="" CONTACT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --port)    SSH_PORT="$2"; shift 2 ;;
        --domain)  DOMAIN="$2"; shift 2 ;;
        --contact) CONTACT="$2"; shift 2 ;;
        *) [ -z "$TARGET" ] && TARGET="$1" || { err "多余参数: $1"; exit 1; }; shift ;;
    esac
done
[ -z "$TARGET" ] && { err "用法: $0 user@server-ip [--port 22] [--domain xx.com] [--contact '...']"; exit 1; }

JAR="target/srvcheck.jar"
[ -f "$JAR" ] || { err "未找到 $JAR，先执行: mvn package -DskipTests"; exit 1; }

# ---------- 1. 上传 jar ----------
info "步骤 1/3: 上传 $JAR 到 $TARGET ..."
scp -P "$SSH_PORT" -o ConnectTimeout=10 "$JAR" "$TARGET:/tmp/srvcheck.jar"

# ---------- 2. 远程执行（环境变量传参，规避空格/特殊字符） ----------
info "步骤 2/3: 远程执行体检..."
ssh -p "$SSH_PORT" -o ConnectTimeout=10 "$TARGET" \
    "SRV_DOMAIN='$DOMAIN' SRV_CONTACT='$CONTACT' bash -s" <<'REMOTE'
set -e
command -v java >/dev/null 2>&1 || { echo "[verify] 服务器没有 java 命令！请改用 ./scripts/deploy.sh（自带运行时）"; exit 3; }
cd /tmp
ARGS=(--html /tmp/srvcheck-report.html)
[ -n "$SRV_DOMAIN" ]  && ARGS+=(--domain "$SRV_DOMAIN")
[ -n "$SRV_CONTACT" ] && ARGS+=(--contact "$SRV_CONTACT")
java -jar srvcheck.jar "${ARGS[@]}"
REMOTE

# ---------- 3. 拉回报告 ----------
mkdir -p reports
STAMP=$(date +%Y%m%d-%H%M%S)
HOST_TAG=$(echo "$TARGET" | cut -d@ -f2 | tr '.' '-')
REPORT="reports/jar-${HOST_TAG}-${STAMP}.html"
info "步骤 3/3: 拉回报告..."
scp -P "$SSH_PORT" -q "$TARGET:/tmp/srvcheck-report.html" "$REPORT"
ssh -p "$SSH_PORT" "$TARGET" "rm -f /tmp/srvcheck.jar /tmp/srvcheck-report.html" || true

echo ""
echo -e "${BOLD}${GREEN}验证完成${NC}"
echo -e "  报告文件: ${BOLD}$REPORT${NC}"
