# Pico 校准参数保存

本指南对应固件 `0.2.2`。轮速校准和后续拉力校准共用一份配置，不写两套文件。
参数修改与保存分开：`set` 只改当前运行值，`cal save` 才写入 Flash。保存必须在停车后进行。

## 文件与字段

Pico 使用 `pico_cart_cal.cfg`，先写临时文件再改名，避免半份配置。格式版本为 `fmt=1`。

| 分组 | 字段 | 范围 | 本任务 |
|---|---|---|---|
| motor | `left_motor_gain` / `right_motor_gain` | 0.50–1.20 | 保存轮速左右增益 |
| force | `left_force_gain` / `right_force_gain` / `start_raw` / `full_raw` / `tow_left_comp` / `tow_right_comp` | 固件限幅，且 `full_raw > start_raw` | 留给 PICOCART-3，本任务只预留字段 |

缺失、损坏或非法配置会回退到固件默认值，小车保持 `idle`，不会自动开始运动。保存 `motor` 不会重置 `force`，反之亦然。

## 命令

```text
param
cal status
set left_motor_gain 0.95
set right_motor_gain 1.05
cal save motor
```

`cal status` 回包区分当前值和已保存值。`dirty=1` 表示还有未保存改动。行驶中保存会返回 `err cal_not_idle`。

## 轮速校准步骤

1. 连接后查看当前左右增益和已保存值。
2. 停车，轮子可着地或按实际场地记录地面、电量和负载。
3. 用低功率、有限时长分别试左轮和右轮。
4. 微调较快一侧的增益，再短距直行对比跑偏。
5. 停车后保存 `motor` 分组，回读 `param` 确认生效。
6. 断电重启至少 3 次，确认增益仍在。

未点保存的临时修改不能显示为已保存。试转过程中急停、前障碍、命令超时和蓝牙断开仍应停车。
