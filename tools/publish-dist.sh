#!/usr/bin/env bash
# 把 APK 发布到 dist 分支与 dist-v<版本> 标签，供 jsDelivr 镜像。
#
# 为什么要这么做：GitHub 的 release 资产国内经常访问不了，而 jsDelivr 的 /gh/
# 只能取 **git 仓库里**的东西。所以 APK 必须进 git —— 但不能进 main（1.5 MB
# 的二进制会污染历史），于是用一个孤儿分支 + 每版一个标签：
#
#   dist            只放 version.json（小、每次覆盖）
#   dist-v<版本>    放 dshgo-app.apk（不可变 → jsDelivr 永久缓存，快且稳）
#
# ★ 顺序很重要：必须在 gh release create **之前**跑这个脚本（或者至少让它
#   先把 version.json 提交推送）。gh release create 会给提交打 tag，
#   而 App 读的是 @latest/version.json —— tag 里没有就会查不到更新。
#
# 用法：
#   tools/publish-dist.sh <版本> <APK路径> [发布说明]
#
# 例：
#   tools/publish-dist.sh 3.8.0 android/app/build/outputs/apk/release/app-release.apk
set -euo pipefail

VER="${1:?用法: publish-dist.sh <版本> <APK路径> [说明]}"
APK="${2:?缺少 APK 路径}"
NOTES="${3:-}"

REPO="xiazhi88/dshgo"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

[ -f "$APK" ] || { echo "找不到 APK: $APK" >&2; exit 1; }

# 派生文件的地址。jsDelivr 的 /gh/ 形式。
CDN="https://cdn.jsdelivr.net/gh/${REPO}"
APK_URL="${CDN}@dist-v${VER}/dshgo-app.apk"
GH_URL="https://github.com/${REPO}/releases/latest/download/dshgo-app.apk"
MANIFEST_URL="${CDN}@dist/version.json"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ★ 先把 version.json 写进 main 并推送 —— 必须在 release 打 tag **之前**，
#   因为 App 读的是 jsDelivr 的 @latest/version.json（@latest 解析到最新 tag）。
#   顺序错了的话，tag 里就没有这次的新版本，App 也就查不到更新。
echo "▸ 更新仓库根的 version.json（供 @latest/version.json 读取）"
cat > "$ROOT/version.json" <<JSON
{
  "version": "${VER}",
  "apk": "${APK_URL}",
  "apkFallback": "${GH_URL}",
  "notes": $(printf '%s' "$NOTES" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')
}
JSON
git -C "$ROOT" add version.json
if git -C "$ROOT" diff --cached --quiet; then
  echo "    内容没变，跳过"
else
  git -C "$ROOT" -c user.name=dshgo -c user.email=dshgo@local \
    commit -q -m "chore: version.json → ${VER}"
  git -C "$ROOT" -c "http.https://github.com.proxy=http://127.0.0.1:7890" push -q origin main
  echo "    已提交并推送"
fi

echo "▸ 准备 dist 分支"
git fetch -q origin dist 2>/dev/null || true
if git rev-parse --verify -q origin/dist >/dev/null; then
  git worktree add -q --detach "$TMP/dist" origin/dist
else
  # 首次：建一个没有任何历史的孤儿分支
  git worktree add -q --detach "$TMP/dist"
  ( cd "$TMP/dist" && git checkout -q --orphan dist && git rm -rq --cached . 2>/dev/null || true )
fi

D="$TMP/dist"
mkdir -p "$D"

cat > "$D/version.json" <<JSON
{
  "version": "${VER}",
  "apk": "${APK_URL}",
  "apkFallback": "${GH_URL}",
  "notes": $(printf '%s' "$NOTES" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
  "sha256": "$(shasum -a 256 "$APK" | cut -d' ' -f1)"
}
JSON

echo "▸ version.json"
cat "$D/version.json" | sed 's/^/    /'

( cd "$D" && git add -A && git -c user.name=dshgo -c user.email=dshgo@local \
    commit -q -m "dist: v${VER}" --allow-empty )

echo "▸ 推送 dist 分支"
git -C "$D" -c "http.https://github.com.proxy=http://127.0.0.1:7890" \
  push -q --force origin HEAD:dist

echo "▸ 建 dist-v${VER} 标签并放 APK + 清单"
# 标签用独立提交、不可变 —— jsDelivr 永久缓存。
# 这里**同时放一份 version.json**：jsDelivr 的 @latest 究竟解析到哪一个最新 tag
# 取决于它是按 release 还是按 tag 时间排的，两处都放就不用去猜这个规则。
T="$TMP/tag"
git worktree add -q --detach "$T"
cp "$APK" "$T/dshgo-app.apk"
cp "$ROOT/version.json" "$T/version.json" 2>/dev/null || true
( cd "$T" && git add -A && git -c user.name=dshgo -c user.email=dshgo@local \
    commit -q -m "apk: v${VER}" )
git -C "$T" tag -f "dist-v${VER}" >/dev/null
git -C "$T" -c "http.https://github.com.proxy=http://127.0.0.1:7890" \
  push -q --force origin "refs/tags/dist-v${VER}"

# jsDelivr 对**分支**的内容会缓存约 12 小时 —— 不清的话，用户半天内都看不到新版本。
# purge 接口是官方提供的，清完立刻生效（实测：清完马上就是新版本）。
echo "▸ 清除 jsDelivr 缓存"
PURGE="https://purge.jsdelivr.net/gh/${REPO}@dist/version.json"
PURGE_RESULT="$(curl -s --max-time 45 "$PURGE" || true)"
if printf '%s' "$PURGE_RESULT" | grep -q '"status": *"finished"'; then
  echo "    已清除 ✓"
else
  echo "    ⚠ 清除未确认，清单可能要等一会儿才更新：$PURGE"
fi

# 复查一次，确认清单真的换成新版本了
sleep 4
GOT="$(curl -s --max-time 30 "$MANIFEST_URL" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("version",""))' 2>/dev/null || true)"
if [ "$GOT" = "$VER" ]; then
  echo "    清单已确认是 $VER ✓"
else
  echo "    ⚠ 清单目前是 ${GOT:-（取不到）}，期望 $VER —— 稍后会更新"
fi

echo
echo "✔ 完成"
echo "    清单  ${MANIFEST_URL}"
echo "    镜像  ${APK_URL}"
echo "    官方  ${GH_URL}"
