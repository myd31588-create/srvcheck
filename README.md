# 服务器体检仪（srvcheck）

> 一条命令体检云服务器，生成**老板能看懂**的中文诊断报告。
> 只读探测 · 不修改任何系统配置 · 不上传任何数据 · 报告单文件可直接微信发送。

## 它解决什么问题

市面上的服务器检测工具（Lynis 等）是给运维看的：英文输出、满屏术语、没有结论。
**服务器体检仪**面向小公司老板/非技术负责人：打分 + 风险等级 + "会导致什么后果 + 修好大概多少钱"。
它既是作品集，也是获客工具——免费帮企业跑一次体检，报告尾部的服务入口就在替你卖后续服务。

## 快速开始

```bash
# 构建（Java 17+，零第三方依赖）
mvn package -DskipTests

# 在目标 Linux 服务器上运行
java -jar target/srvcheck.jar

# 常用参数
java -jar target/srvcheck.jar \
  --html report.html \
  --json report.json \
  --domain example.com \
  --contact "微信: your-wechat"
```

### 一键远程体检（推荐）

目标服务器上**不需要安装 Java**：脚本会用 `jlink` 裁剪一个最小 JRE（约 17MB 的自包含部署包）一起传上去，解压即跑，跑完拉回报告并清理远端临时文件。

```bash
# 从本机对任意 Linux 服务器做一次完整体检
./scripts/deploy.sh root@1.2.3.4

# 带网站检查和报告尾部的联系方式
./scripts/deploy.sh root@1.2.3.4 --domain example.com --contact "微信: your-wechat"

# 非标准 SSH 端口
./scripts/deploy.sh root@1.2.3.4 --port 2222

# 只构建部署包，不上传（产物: srvcheck-bundle.tar.gz）
./scripts/build-bundle.sh

# 快速验证（目标机已装 Java 时，只传 jar 更快）
./scripts/verify-jar.sh root@1.2.3.4
```

报告保存在本地 `reports/<IP>-<时间>.html`。

| 参数 | 说明 |
|------|------|
| `--html <文件>` | HTML 报告输出路径（默认 `srvcheck-report.html`） |
| `--json <文件>` | 额外输出 JSON（供自动化流水线对接） |
| `--domain <域名>` | 启用网站可访问性 / SSL 证书 / TLS 版本 / 安全响应头检查 |
| `--contact <文案>` | 报告尾部"服务入口"的联系方式 |

## 检查项（23 项）

- **系统健康（30 分）**：磁盘水位、内存可用量、CPU 负载、Swap、近 7 天 OOM 记录、僵尸进程
- **安全配置（40 分）**：SSH root 登录、密码认证、默认端口、防火墙、fail2ban、公网开放端口（识别 MySQL/Redis 等敏感服务）、root 级异常账户、空密码账户
- **数据安全（20 分）**：MySQL/Redis 公网暴露、Redis 弱口令、备份任务存在性、备份文件新鲜度
- **网站与证书（10 分）**：网站可访问性、SSL 证书有效期、TLS 版本、HTTP 安全响应头（需 `--domain`）

每项检查输出：给老板看的大白话结论 + 给技术人看的命令级明细 + 高危项的后果与修复费用区间。

## 安全边界（设计原则）

1. **只读探测**：所有检查仅读取 `/proc`、配置文件与探测命令，绝不修改目标系统
2. **数据不出机器**：报告在本地生成，无任何网络上传
3. **弱口令仅提示性验证**：不做暴力破解
4. **权限不足优雅降级**：如 `/etc/shadow` 读取失败，该项标记"未检测"而非报错

## 路线图

- [ ] GraalVM Native Image 编译为单二进制（目标机器无需 JDK）
- [ ] `--all-domains`：自动从 Nginx 配置发现站点并批量检查证书
- [ ] 体检基线对比：与上次报告 diff，量化"这一个月服务器的变化"
- [ ] Windows 机器上的说明：非 Linux 环境所有检查项自动跳过，报告仅作格式演示

## 项目结构

```
src/main/java/com/srvcheck/
├── Main.java                    # CLI 入口、参数解析、终端摘要
├── core/
│   ├── Check.java               # 检查项接口
│   ├── BaseCheck.java           # 基类：非 Linux 降级 + 异常兜底
│   ├── CheckResult.java         # 单项结果（含"翻译成钱"的 hint 字段）
│   ├── Category.java            # 四大分类及满分权重
│   ├── Severity.java            # 风险等级与得分系数
│   ├── CheckRegistry.java       # 检查项组装
│   └── Report.java              # 评分与总评
├── checks/
│   ├── SystemChecks.java        # 系统健康 6 项
│   ├── SecurityChecks.java      # 安全配置 8 项
│   ├── DataChecks.java          # 数据安全 5 项
│   └── WebChecks.java           # 网站与证书 4 项
├── report/
│   ├── HtmlReportWriter.java    # 单文件 HTML（内联 CSS/SVG，离线可看）
│   └── JsonReportWriter.java    # JSON 输出
├── util/
│   └── Probe.java               # 只读探测工具（命令执行/文件读取）
└── scripts/
    ├── build-bundle.sh          # 构建 jar + jlink 最小运行时的自包含部署包
    └── deploy.sh                # 一键远程体检：上传->运行->拉回报告->清理
```
