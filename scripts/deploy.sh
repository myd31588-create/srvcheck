#!/usr/bin/env bash
# ============================================================
# 服务器体检仪 · 一键远程体检
# 用法:
#   ./scripts/deploy.sh user@server-ip [--port 22] [--domain example.com] [--contact "微信: xxx"]
# 流程:
#   构建自包含部署包(jar+裁剪JRE) -> scp 上传 -> 远程解压运行 -> 拉回 HTML 报告
# 依赖: 本机有 ssh/scp/mvn/JDK17；目标机只需 Linux + tar + bash，无需 Java
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

RED='\033[31m'; GREEN='\033[32m'; BOLD='\033[1m'; NC='\033[0m'
info() { echo -e "${GREEN}[deploy]${NC} $*"; }
err()  { echo -e "${RED}[deploy]${NC} $*" >&2; }

usage() {
    sed -n '2,7p' "$0"
    exit 1
}

# ---------- 参数解析 ----------
TARGET="" SSH_PORT=22 DOMAIN="" CONTACT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --port)    SSH_PORT="$2"; shift 2 ;;
        --domain)  DOMAIN="$2"; shift 2 ;;
        --contact) CONTACT="$2"; shift 2 ;;
        -h|--help) usage ;;
        -*)        err "未知参数: $1"; usage ;;
        *)
            [ -n "$TARGET" ] && { err "只能指定一个目标主机"; usage; }
            TARGET="$1"; shift ;;
    esac
done
[ -z "$TARGET" ] && { err "缺少目标主机 user@ip"; usage; }

command -v ssh >/dev/null 2>&1 || { err "本机没有 ssh 命令"; exit 1; }
command -v scp >/dev/null 2>&1 || { err "本机没有 scp 命令"; exit 1; }

# ---------- 1. 构建部署包 ----------
info "步骤 1/4: 构建自包含部署包..."
./scripts/build-bundle.sh
BUNDLE="srvcheck-bundle.tar.gz"
[ -f "$BUNDLE" ] || { err "构建失败"; exit 1; }

# ---------- 2. 上传 ----------
info "步骤 2/4: 上传部署包到 $TARGET ..."
scp -P "$SSH_PORT" -o ConnectTimeout=10 "$BUNDLE" "$TARGET:/tmp/srvcheck-bundle.tar.gz"

# ---------- 3. 远程执行体检 ----------
# 通过环境变量传参（单引号在远端展开，规避空格/特殊字符问题），bash 数组保证参数不被拆散
info "步骤 3/4: 在 $TARGET 上执行体检（端口 $SSH_PORT）..."
ssh -p "$SSH_PORT" -o ConnectTimeout=10 "$TARGET" \
    "SRV_DOMAIN='$DOMAIN' SRV_CONTACT='$CONTACT' bash -s" <<'REMOTE'
set -e
rm -rf /tmp/srvcheck && mkdir -p /tmp/srvcheck
tar -xzf /tmp/srvcheck-bundle.tar.gz -C /tmp/srvcheck
# Windows 打的 tar 无执行权限位，必须补上
chmod +x /tmp/srvcheck/runtime/bin/*
cd /tmp/srvcheck
ARGS=(--html /tmp/srvcheck-report.html)
[ -n "$SRV_DOMAIN" ]  && ARGS+=(--domain "$SRV_DOMAIN")
[ -n "$SRV_CONTACT" ] && ARGS+=(--contact "$SRV_CONTACT")
./runtime/bin/java -jar srvcheck.jar "${ARGS[@]}"
REMOTE

# ---------- 4. 拉回报告 ----------
mkdir -p reports
STAMP=$(date +%Y%m%d-%H%M%S)
HOST_TAG=$(echo "$TARGET" | cut -d@ -f2 | tr '.' '-')
REPORT="reports/${HOST_TAG}-${STAMP}.html"
info "步骤 4/4: 拉回报告..."
scp -P "$SSH_PORT" -q "$TARGET:/tmp/srvcheck-report.html" "$REPORT"
ssh -p "$SSH_PORT" "$TARGET" \
    "rm -rf /tmp/srvcheck /tmp/srvcheck-bundle.tar.gz /tmp/srvcheck-report.html" || true

echo ""
echo -e "${BOLD}${GREEN}体检完成${NC}"
echo -e "  报告文件: ${BOLD}$REPORT${NC}（本地副本已保存，目标机临时文件已清理）"
echo -e "  下一步: 微信发送报告给负责人，或用浏览器打开查看"
