# Pico 固件检测与手机推送

本指南对应固件 `0.2.5`。Pico 运行的是 MicroPython 上的 `main.py`。手机推送只替换这份控制程序，不刷写 UF2 引导程序。

## 能升级什么

- 检测 Pico 当前 `fw=` 版本，并和手机内置固件包比较。
- Android App 把仓库里的 `main.py` 打包进 App，经 BLE 推到 Pico。
- Pico 先写到 `main.py.ota`，校验大小和 CRC32 后，再改名为 `main.py` 并重启。
- 原文件会备份为 `main.py.bak`。升级期间电机会停车。

微信小程序只显示版本和是否支持推包，不发送完整固件。完整推送请用 Android App。

## 命令

```text
info
ota status
ota begin SIZE CRC32HEX
ota data OFFSET HEXBYTES
ota end
ota abort
```

`info` 回包含 `fw=`、`ota=1` 和 `ota_max=`。默认最大约 180KB。数据包用十六进制，每包约 96 字节。

## Android 操作

1. 安装带固件包的 App，连接 Pico。
2. 工程调试页会显示 Pico 版本和手机包版本。
3. 若 Pico 更旧，点“更新固件”，确认后等待推送完成。
4. Pico 重启后重新连接，确认版本已更新。

第一次要从电脑用 USB/`mpremote` 刷入 `0.2.5`。更早版本还没有推包命令。

## 失败处理

- 校验失败、中断或超时：临时文件删除，继续运行旧固件。
- 推送中请靠近 Pico，不要同时点动或导出日志。
- 若重启后不运行，用 USB 把 `main.py.bak` 拷回 `main.py`，或重新复制仓库里的 `main.py`。
