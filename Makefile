# AgentPost 开发常用命令
# 使用方式：make <target>，例如 make install-run

ADB := /Users/ws/Library/Android/sdk/platform-tools/adb
PKG := com.szgenle.agentpost

.PHONY: help devices install run stop log log-all logcat-clear install-run uninstall clean build build-release sign-release

help:
	@echo "可用命令："
	@echo "  make devices       查看已连接设备"
	@echo "  make build         仅编译 debug 包（不安装）"
	@echo "  make build-release 编译 release APK（未签名）"
	@echo "  make sign-release  对 release APK 进行签名（需提供 KEYSTORE/KEY_ALIAS）"
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
		--ks "$(KEYSTORE)" \
		--ks-key-alias "$(KEY_ALIAS)" \
		$(if $(KEYSTORE_PASSWORD),--ks-pass pass:$(KEYSTORE_PASSWORD),) \
		$(if $(KEY_PASSWORD),--key-pass pass:$(KEY_PASSWORD),) \
		--out $(SIGNED_APK) \
		$(UNSIGNED_APK)
	$(APKSIGNER) verify --verbose $(SIGNED_APK)
	@echo "已签名 APK 输出：$(SIGNED_APK)"

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
