#!/usr/bin/env bash
# scripts/scan-release-artifacts.sh — 产物洁净扫描（GC #4/#15）
# 扫描 git 追踪内容、日志与 APK 产物，确认无密钥/令牌/个性化 URL/硬编码常量。
# 用法：bash scripts/scan-release-artifacts.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

PATTERNS=(
  '[?&]key=[A-Za-z0-9_-]{16,}'          # 带密钥查询参数
  '[?&]token=[A-Za-z0-9_-]{16,}'        # 带令牌查询参数
  'X-API-Key[[:space:]]*[:=][[:space:]]*[^[:space:]]{8,}'  # 明文 API Key 头
  'LINGLAN_(SECRET|KEY|TOKEN)[[:space:]]*[=:]'             # 硬编码常量
)
violations=0
scan() { # $1=标签 $2=目标
  local tag="$1" target="$2"
  for p in "${PATTERNS[@]}"; do
    if grep -aE "$p" "$target" >/dev/null 2>&1; then
      echo "违规[$tag]: $target 匹配 $p"; violations=$((violations + 1))
    fi
  done
}

# 1) git 已追踪内容（只搜文本类）
for f in $(git ls-files '*.kt' '*.xml' '*.gradle' '*.kts' '*.properties' '*.php' '*.json' '*.cjs' '*.md' 2>/dev/null || true); do
  scan "tracked" "$f"
done

# 2) 日志文件
for f in $(git ls-files '*.log' 2>/dev/null || true); do scan "log" "$f"; done

# 3) APK 产物（二进制内 ASCII 串）
for apk in $(find app/build/outputs/apk -name '*.apk' 2>/dev/null || true); do
  scan "apk" "$apk"
done

if [ "$violations" -gt 0 ]; then
  echo "发现 $violations 处潜在违规。"; exit 1
fi
echo "扫描通过：未发现密钥/个性化 URL/硬编码令牌。"
