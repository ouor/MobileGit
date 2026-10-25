#!/usr/bin/env bash
# Rebuilds app/libs/ngrokmobile.aar (ngrok-go v2 via gomobile). Needs Go >= 1.25 (go.mod pins toolchain go1.26.10: earlier 1.26.x trust the compromised eMudhra CA, CVE-2026-111943), Android SDK + NDK (r28 used).
# go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest && gomobile init
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?set ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
gomobile bind -target=android/arm64,android/amd64 -androidapi 26 -javapkg com.ouor.mobilegit.ngrok \
  -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" -o ../app/libs/ngrokmobile.aar .
rm -f ../app/libs/ngrokmobile-sources.jar
