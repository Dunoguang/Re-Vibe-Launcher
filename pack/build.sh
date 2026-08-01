#!/usr/bin/env bash
# 构建两个前端子项目并复制到 Android assets
set -e
cd "$(dirname "$0")"

echo "==> 构建主页面 (main) ..."
npm run build -w main

echo "==> 构建控制中心 (control-center) ..."
npm run build -w control-center

echo "==> 复制到 app/src/main/assets ..."
ASSETS="$(cd ../app/src/main && pwd)/assets"
mkdir -p "$ASSETS"

cp main/dist/index.html "$ASSETS/index.html"
cp control-center/dist/control_center.html "$ASSETS/control_center.html"

echo "==> 完成"
find "$ASSETS" -type f | sort
