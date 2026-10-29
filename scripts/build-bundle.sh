#!/usr/bin/env bash
# ============================================================
# 构建 srvcheck 自包含部署包（jar + jlink 裁剪的最小 JRE）
# 产物: srvcheck-bundle.tar.gz —— 目标 Linux 服务器无需安装 Java
#
# 平台说明:
#   jlink 裁剪的运行时分平台。本脚本自动处理:
#   - 在 Linux 上构建    -> 直接用本机 jmods
#   - 在 Windows 上构建  -> 用缓存的 Linux JDK jmods 跨平台裁剪
#                           （首次会自动从清华镜像下载，缓存于 .cache/）
# 用法: ./scripts/build-bundle.sh
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

GREEN='\033[32m'; YELLOW='\033[33m'; NC='\033[0m'
info() { echo -e "${GREEN}[build]${NC} $*"; }
warn() { echo -e "${YELLOW}[build]${NC} $*"; }

MODULES="java.base,java.logging,java.management,jdk.crypto.ec,jdk.crypto.cryptoki"

# ---------- 定位 JDK 17+（本机 jlink 所在目录） ----------
find_jdk() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then
        echo "$JAVA_HOME"; return
    fi
    for d in "/d/soft/jdk-17/jdk-17.0.20.1+1" \
             "/c/Program Files/Java/jdk-17" \
             "/usr/lib/jvm/java-17-openjdk-amd64" \
             "/usr/lib/jvm/java-17-openjdk" \
             "$(dirname "$(command -v java 2>/dev/null || echo /nonexistent)")/.."; do
        [ -x "$d/bin/jlink" ] && { echo "$d"; return; }
    done
    echo ""
}

# ---------- 定位 Maven ----------
find_mvn() {
    if command -v mvn >/dev/null 2>&1; then command -v mvn; return; fi
    for d in "/d/soft/apache-maven-3.9.16/bin/mvn" "/opt/maven/bin/mvn" "/usr/local/maven/bin/mvn"; do
        [ -x "$d" ] && { echo "$d"; return; }
    done
    echo ""
}

JDK="$(find_jdk)"
[ -n "$JDK" ] || { warn "未找到带 jlink 的 JDK 17+，请设置 JAVA_HOME"; exit 1; }
info "使用 JDK: $JDK"
export JAVA_HOME="$JDK"

MVN="$(find_mvn)"
[ -n "$MVN" ] || { warn "未找到 mvn"; exit 1; }

# ---------- 1. 打 jar ----------
info "步骤 1/3: Maven 打包..."
"$MVN" -q package -DskipTests
JAR="target/srvcheck.jar"
[ -f "$JAR" ] || { warn "打包失败: 未找到 $JAR"; exit 1; }

# ---------- 2. jlink 裁剪运行时（按目标平台选 jmods） ----------
OS_HOST="$(uname -s)"
rm -rf bundle
mkdir -p bundle

JLINK_ARGS=(--add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files --compress=2)

if [ "$OS_HOST" != "Linux" ]; then
    # Windows / macOS 构建: 需要目标平台(Linux x64)的 jmods 跨平台裁剪
    info "当前非 Linux 主机，使用缓存的 Linux jmods 跨平台裁剪..."
    JMODS_DIR="$(ls -d .cache/jdk-17*/jmods 2>/dev/null | head -1 || true)"
    if [ -z "$JMODS_DIR" ]; then
        info "首次运行: 下载 Linux x64 JDK 17（约 185MB，之后走本地缓存）..."
        mkdir -p .cache
        curl -sL --retry 2 -o .cache/linux-jdk17.tar.gz \
            "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz"
        tar -xzf .cache/linux-jdk17.tar.gz -C .cache --wildcards "jdk-17*/jmods"
        JMODS_DIR="$(ls -d .cache/jdk-17*/jmods | head -1)"
    fi
    info "使用 Linux jmods: $JMODS_DIR"
    "$JDK/bin/jlink" --module-path "$JMODS_DIR" "${JLINK_ARGS[@]}" --output bundle/runtime
else
    info "Linux 主机: 直接用本机 jmods 裁剪..."
    "$JDK/bin/jlink" --module-path "$JAVA_HOME/jmods" "${JLINK_ARGS[@]}" --output bundle/runtime
fi

cp "$JAR" bundle/srvcheck.jar

# ---------- 3. 打 bundle ----------
info "步骤 3/3: 打包部署包..."
tar -czf srvcheck-bundle.tar.gz -C bundle runtime srvcheck.jar

SIZE=$(du -h srvcheck-bundle.tar.gz | cut -f1)
info "完成: srvcheck-bundle.tar.gz ($SIZE, 内含 Linux 运行时)"
info "上传到服务器后执行:"
info "  mkdir -p /tmp/srvcheck && tar -xzf srvcheck-bundle.tar.gz -C /tmp/srvcheck"
info "  chmod +x /tmp/srvcheck/runtime/bin/*   # Windows 打的 tar 无执行权限位，必须补上"
info "  /tmp/srvcheck/runtime/bin/java -jar /tmp/srvcheck/srvcheck.jar"
