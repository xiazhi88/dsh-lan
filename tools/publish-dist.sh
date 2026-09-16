#!/usr/bin/env bash
# 把新版本发布到镜像标签，供 jsDelivr 提供 APK 下载。
#
# ## 为什么需要
#
# GitHub 的 release 资源国内经常访问不了，而 jsDelivr 的 /gh/ 只能取
# **git 仓库里**的东西。所以 APK 必须进 git —— 但不能进 main
# （1.5 MB 的二进制会污染历史），于是用**每版一个不可变标签**：
#
#     dist-v<版本>   放 dshgo-app.apk（不可变 → jsDelivr 永久缓存）
#
# ## 不再有 dist 分支
#
# 早期版本还维护一个 `dist` 分支放 version.json，App 读它来做更新检查。
# 那条路已经废弃（分支缓存能卡死三个版本，实测过），现在 App 读的是
# jsDelivr 的**包元数据接口**（tag 列表），不需要任何中间分支。
#
# 继续推那个分支的副作用是：GitHub 会在仓库首页弹一条
# 「dist had recent pushes — Compare & pull request」，诱导你把它合进 main ——
# 而合进去就等于把发布产物塞进主分支，正是这个设计要避免的。所以删掉了。
#
# ## 顺序
#
# version.json 要**先提交进 main 再打 release tag**：gh release create 会给
# 当前提交打 tag，而 tag 一旦建立内容就固定了。
#
# 用法：
#   tools/publish-dist.sh <版本> <APK路径> [发布说明]
set -euo pipefail

VER="${1:?用法: publish-dist.sh <版本> <APK路径> [说明]}"
APK="${2:?缺少 APK 路径}"
NOTES="${3:-}"

REPO="xiazhi88/dshgo"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

GIT_PROXY=(-c "http.https://github.com.proxy=http://127.0.0.1:7890")

[ -f "$APK" ] || { echo "找不到 APK: $APK" >&2; exit 1; }

CDN="https://cdn.jsdelivr.net/gh/${REPO}"
APK_URL="${CDN}@dist-v${VER}/dshgo-app.apk"
GH_URL="https://github.com/${REPO}/releases/latest/download/dshgo-app.apk"

# ① 更新仓库根的 version.json（给人看的记录；App 不读它）
echo "▸ 更新 version.json"
cat > "$ROOT/version.json" <<JSON
{
  "version": "${VER}",
  "apk": "${APK_URL}",
  "apkFallback": "${GH_URL}",
  "notes": $(printf '%s' "$NOTES" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')
}
JSON
git add version.json
if git diff --cached --quiet; then
  echo "    内容没变，跳过"
else
  git -c user.name=dshgo -c user.email=dshgo@local commit -q -m "chore: version.json → ${VER}"
  git "${GIT_PROXY[@]}" push -q origin main
  echo "    已提交并推送"
fi

# ② 建不可变标签，把 APK 放进去
echo "▸ 建 dist-v${VER} 标签并放 APK"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; git worktree prune' EXIT
git worktree add -q --detach "$TMP"
cp "$APK" "$TMP/dshgo-app.apk"
# 标签里也放一份清单：jsDelivr 的 @latest 解析到哪个 tag 取决于它的排序规则，
# 两处都放就不必去猜
cp "$ROOT/version.json" "$TMP/version.json" 2>/dev/null || true
( cd "$TMP" && git add -A && git -c user.name=dshgo -c user.email=dshgo@local \
    commit -q -m "apk: v${VER}" )
git -C "$TMP" tag -f "dist-v${VER}" >/dev/null
git -C "$TMP" "${GIT_PROXY[@]}" push -q --force origin "refs/tags/dist-v${VER}"

# ③ 复查镜像是否可用
echo "▸ 复查镜像"
sleep 6
CODE="$(curl -sL -o /dev/null -w '%{http_code}' --max-time 40 "$APK_URL" || echo 000)"
if [ "$CODE" = "200" ]; then
  echo "    $APK_URL → 200 ✓"
else
  echo "    ⚠ HTTP $CODE —— jsDelivr 对新标签有索引延迟，稍后会可用"
fi

echo
echo "✔ 完成"
echo "    镜像  ${APK_URL}"
echo "    官方  ${GH_URL}"
