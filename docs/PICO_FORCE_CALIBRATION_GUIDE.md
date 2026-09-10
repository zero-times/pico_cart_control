# Pico 拉力校准

本指南对应固件 `0.2.4`。拉力校准复用 `pico_cart_cal.cfg`，保存 `force` 分组，不会覆盖已保存的轮速增益。
读数是相对原始量，不是公斤或牛顿。`tare` 零点和牵引会话基线每次卸载后重新建立，断电后不会自动恢复零点。

## 保存什么，不保存什么

| 长期保存 | 每次使用重新建立 |
|---|---|
| `left_force_gain` / `right_force_gain` | `tare` 零点 |
| `start_raw` / `full_raw` | 牵引模式卸载基线 `lbase` / `rbase` |
| `tow_left_comp` / `tow_right_comp` | 运行模式，不会因加载校准而自动进入牵引；进入后也不因蓝牙断开退出 |

`tow_left_comp` / `tow_right_comp` 是牵引通道的原始补偿，和增益叠加。优先用增益对齐左右读数；补偿只用于安装偏差，不要两边同时加大。

## 操作步骤

1. 停车，确认两路完全卸载，没有侧向力、绳子摩擦或传感器顶死。
2. 发送 `tare`，确认回包 `tared=1`。卸载后左右读数应低于 `start_raw`。
3. 用同一固定参考载荷分别压/拉左、右通道，各记录至少 3 次。
4. 调整 `left_force_gain` / `right_force_gain`，让同载荷下左右平均值接近。
5. 渐增拉力，确认能稳定越过 `start_raw` 启动，并在 `full_raw` 前平滑增大，而不是一侧提前满功率。
6. 停车后发送 `cal save force`，回读 `param` 和 `cal status`。轮速增益应保持不变。
7. 断电重启至少 3 次：长期增益和阈值仍在，但必须重新卸载归零后才能牵引。

## 失败与机械排查

- `start_raw >= full_raw`、非数字或越界：固件返回 `err threshold_order` 或 `err bad_value`，回读仍是原生效值。
- 未 tare、tare 失败、HX711 超时/超量程：`sensor=bad`，`tow` 返回 `err sensor_not_ready`，不会进入牵引。
- 未松绳时 `tare` 会把张力当成零点，后续读数偏低；必须卸载后再归零。
- 左右差很大时，先查安装是否歪、有无侧向力、连杆摩擦或传感器方向反了，再改增益。
