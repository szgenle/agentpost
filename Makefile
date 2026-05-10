# AgentPost 开发常用命令
# 使用方式：make <target>，例如 make install-run

ADB := /Users/ws/Library/Android/sdk/platform-tools/adb
PKG := com.szgenle.agentpost

.PHONY: help devices install run stop log log-all logcat-clear install-run uninstall clean build

help:
	@echo "可用命令："
	@echo "  make devices       查看已连接设备"
	@echo "  make build         仅编译 debug 包（不安装）"
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
