# AgentPost 开发常用命令
# 使用方式：make <target>，例如 make install-run

ADB := /Users/ws/Library/Android/sdk/platform-tools/adb
PKG := com.szgenle.agentpost

.PHONY: help devices install run stop log log-all logcat-clear install-run uninstall clean build build-release sign-release verify-release

help:
	@echo "可用命令："
	@echo "  make devices       查看已连接设备"
	@echo "  make build         仅编译 debug 包（不安装）"
	@echo "  make build-release 编译 release APK（未签名）"
	@echo "  make sign-release  对 release APK 进行签名（需提供 KEYSTORE/KEY_ALIAS）"
	@echo "  make verify-release 验证 APK 签名，并可选自动比对 keystore 指纹"
	@echo "                     用法：make verify-release APK=<APK路径> [KEYSTORE=<jks> KEY_ALIAS=<别名>]"
	@echo "  make install       编译并安装到手机"
	@echo "  make run           启动 App"
	@echo "  make stop          强制停止 App"
	@echo "  make log           只看本 App 的日志（需 App 已运行）"
	@echo "  make log-all       查看全量 logcat"
	@echo "  make logcat-clear  清空 logcat 缓冲区"
	@echo "  make install-run   一键：安装 + 启动 + 跟日志"
	@echo "  make uninstall     卸载 App"
	@echo "  make clean         清理 Gradle 构建产物"

devices:
	$(ADB) devices

build:
	./gradlew :app:assembleDebug

# Release 构建与签名
# 用法示例：
#   make build-release
#   make sign-release KEYSTORE=~/keystores/agentpost.jks KEY_ALIAS=agentpost
# 也可通过环境变量提供 KEYSTORE_PASSWORD / KEY_PASSWORD，避免交互输入。
BUILD_TOOLS_VERSION ?= $(shell ls $${ANDROID_HOME:-$$HOME/Library/Android/sdk}/build-tools 2>/dev/null | sort -V | tail -n 1)
APKSIGNER := $${ANDROID_HOME:-$$HOME/Library/Android/sdk}/build-tools/$(BUILD_TOOLS_VERSION)/apksigner
UNSIGNED_APK := app/build/outputs/apk/release/app-release-unsigned.apk
SIGNED_APK   := app/build/outputs/apk/release/app-release.apk
KEYSTORE     ?=
KEY_ALIAS    ?=
# 展开 KEYSTORE 路径中的 ~ 前缀（双引号包裹时 shell 不会自动展开 ~）
KEYSTORE_RESOLVED := $(KEYSTORE:~%=$(HOME)%)

build-release:
	./gradlew :app:assembleRelease
	@echo "未签名 APK 输出：$(UNSIGNED_APK)"

sign-release:
	@if [ -z "$(KEYSTORE)" ] || [ -z "$(KEY_ALIAS)" ]; then \
		echo "用法：make sign-release KEYSTORE=<keystore路径> KEY_ALIAS=<别名>"; \
		echo "可选环境变量：KEYSTORE_PASSWORD、KEY_PASSWORD"; exit 1; \
	fi
	@if [ ! -f "$(UNSIGNED_APK)" ]; then \
		echo "未找到 $(UNSIGNED_APK)，请先 make build-release"; exit 1; \
	fi
	@if [ ! -x "$(APKSIGNER)" ]; then \
		echo "未找到 apksigner：$(APKSIGNER)"; \
		echo "请确认 ANDROID_HOME 与 build-tools 已安装"; exit 1; \
	fi
	$(APKSIGNER) sign \
		--ks "$(KEYSTORE_RESOLVED)" \
		--ks-key-alias "$(KEY_ALIAS)" \
		$(if $(KEYSTORE_PASSWORD),--ks-pass pass:$(KEYSTORE_PASSWORD),) \
		$(if $(KEY_PASSWORD),--key-pass pass:$(KEY_PASSWORD),) \
		--out $(SIGNED_APK) \
		$(UNSIGNED_APK)
	$(APKSIGNER) verify --verbose $(SIGNED_APK)
	@echo "已签名 APK 输出：$(SIGNED_APK)"

# 验签：默认验证本地构建的 $(SIGNED_APK)，可通过 APK=<路径> 指定下载下来的 APK
# 同时传入 KEYSTORE 与 KEY_ALIAS 时，会自动比对 APK 证书指纹与 keystore 指纹
# 用法示例：
#   make verify-release
#   make verify-release APK=~/Downloads/app-release.apk
#   make verify-release APK=~/Downloads/app-release.apk KEYSTORE=~/keystores/agentpost.jks KEY_ALIAS=agentpost
APK ?= $(SIGNED_APK)
APK_RESOLVED := $(APK:~%=$(HOME)%)

verify-release: SHELL := /bin/bash
verify-release:
	@if [ ! -f "$(APK_RESOLVED)" ]; then \
		echo "未找到 APK：$(APK_RESOLVED)"; \
		echo "请通过 APK=<路径> 指定，或先 make sign-release 生成 $(SIGNED_APK)"; exit 1; \
	fi
	@if [ ! -x "$(APKSIGNER)" ]; then \
		echo "未找到 apksigner：$(APKSIGNER)"; \
		echo "请确认 ANDROID_HOME 与 build-tools 已安装"; exit 1; \
	fi
	@echo "==> 验签：$(APK_RESOLVED)"
	@TMP=$$(mktemp); \
	$(APKSIGNER) verify --verbose --print-certs "$(APK_RESOLVED)" | tee "$$TMP"; \
	RC=$${PIPESTATUS[0]}; \
	if [ $$RC -ne 0 ]; then \
		rm -f "$$TMP"; echo "❌ APK 验签未通过"; exit $$RC; \
	fi; \
	APK_SHA=$$(awk '/Signer #1 certificate SHA-256 digest:/ {print $$NF}' "$$TMP" | tr 'A-Z' 'a-z'); \
	rm -f "$$TMP"; \
	echo ""; \
	echo "APK     SHA-256: $$APK_SHA"; \
	if [ -n "$(KEYSTORE)" ] && [ -n "$(KEY_ALIAS)" ]; then \
		if [ ! -f "$(KEYSTORE_RESOLVED)" ]; then \
			echo "❌ 未找到 keystore：$(KEYSTORE_RESOLVED)"; exit 1; \
		fi; \
		KS_OUT=$$(mktemp); \
		if ! keytool -list -v -keystore "$(KEYSTORE_RESOLVED)" -alias "$(KEY_ALIAS)" \
			$(if $(KEYSTORE_PASSWORD),-storepass "$(KEYSTORE_PASSWORD)",) \
			>"$$KS_OUT" </dev/tty; then \
			rm -f "$$KS_OUT"; echo "❌ keytool 读取 keystore 失败"; exit 1; \
		fi; \
		KS_SHA=$$(awk '/SHA256:/ {print $$2; exit}' "$$KS_OUT" | tr -d ':' | tr 'A-Z' 'a-z'); \
		rm -f "$$KS_OUT"; \
		echo "Keystore SHA-256: $$KS_SHA"; \
		if [ -n "$$APK_SHA" ] && [ "$$APK_SHA" = "$$KS_SHA" ]; then \
			echo "✅ 指纹一致：APK 由该 keystore 签名"; \
		else \
			echo "❌ 指纹不一致：APK 不是该 keystore 签名的"; exit 1; \
		fi; \
	else \
		echo "提示：追加 KEYSTORE=<路径> KEY_ALIAS=<别名> 可自动比对指纹"; \
	fi

install:
	./gradlew :app:installDebug

run:
	$(ADB) shell monkey -p $(PKG) -c android.intent.category.LAUNCHER 1 >/dev/null

stop:
	$(ADB) shell am force-stop $(PKG)

log:
	@PID=$$($(ADB) shell pidof -s $(PKG)); \
	if [ -z "$$PID" ]; then \
		echo "App 未运行，请先 make run"; exit 1; \
	fi; \
	echo "跟随 PID=$$PID 的日志（Ctrl+C 退出）"; \
	$(ADB) logcat --pid=$$PID

log-all:
	$(ADB) logcat

logcat-clear:
	$(ADB) logcat -c

install-run: install run
	@echo "等待 App 启动..."
	@sleep 1
	@$(MAKE) log

uninstall:
	$(ADB) uninstall $(PKG)

clean:
	./gradlew clean
