"""Host simulations only: these tests never open a Pico or drive a motor."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import Mock, patch

PERIOD = 1 << 30
now = 0


def ticks_ms():
    return now % PERIOD


def ticks_diff(a, b):
    return (a - b + PERIOD // 2) % PERIOD - PERIOD // 2


def ticks_add(a, b):
    return (a + b) % PERIOD


machine = types.ModuleType("machine")
machine.Pin = Mock()
machine.PWM = Mock()
machine.UART = Mock()
spec = importlib.util.spec_from_file_location("cart_firmware", Path(__file__).resolve().parents[1] / "main.py")
fw = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {"machine": machine}), patch.multiple(
    "time", ticks_ms=ticks_ms, ticks_diff=ticks_diff, ticks_add=ticks_add,
    sleep_ms=lambda ms: None, create=True,
):
    spec.loader.exec_module(fw)


def fields(line):
    return dict(part.split("=", 1) for part in line.split() if "=" in part)


class DiagnosticsTests(unittest.TestCase):
    def setUp(self):
        global now
        now = 0

    def test_clock_wrap_and_resync_leave_monotonic_time_intact(self):
        global now
        now = PERIOD - 10
        clock = fw.DiagnosticClock()
        self.assertEqual(clock.unix_ms(), 0)
        clock.sync(1800000000000)
        now += 30
        self.assertEqual(clock.uptime_ms(), 30)
        self.assertEqual(clock.unix_ms(), 1800000000030)
        clock.sync(1799999990000)
        self.assertEqual(clock.uptime_ms(), 30)
        self.assertEqual(clock.unix_ms(), 1799999990000)
        with self.assertRaises(ValueError):
            clock.sync(12)
        self.assertEqual(clock.unix_ms(), 1799999990000)

    def test_log_unsynced_and_synced_events_have_version(self):
        global now
        log = fw.HardwareLogger()
        log.event("boot")
        now = 50
        log.clock.sync(1800000000000)
        log.event("time_sync")
        now += 20
        log.event("stop", "reason=estop")
        self.assertEqual(log.begin_export(), 3)
        rows = [fields(log.next_export_line()) for _ in range(3)]
        self.assertEqual([r["u"] for r in rows], ["0", "1800000000000", "1800000000020"])
        self.assertEqual([r["t"] for r in rows], ["0", "50", "70"])
        self.assertTrue(all(r["fw"] == fw.FIRMWARE_VERSION for r in rows))

    def test_ring_capacity_warning_and_overwrite_accounting(self):
        log = fw.HardwareLogger()
        for _ in range(172):
            log.event("test")
        self.assertEqual(fields(log.status_line())["used_pct"], "89")
        log.event("test")
        self.assertEqual(fields(log.status_line())["used_pct"], "90")
        for _ in range(30):
            log.event("test")
        self.assertEqual(log.count, 192)
        self.assertEqual(log.overwritten, 11)
        self.assertEqual(log.begin_export(), 192)
        rows = [fields(log.next_export_line()) for _ in range(192)]
        self.assertEqual([int(r["s"]) for r in rows], list(range(12, 204)))
        self.assertTrue(log.next_export_line().startswith("hwlog_end n=192"))

    def test_export_is_immutable_and_cleanup_retains_new_events(self):
        log = fw.HardwareLogger()
        for _ in range(10):
            log.event("before")
        log.begin_export()
        export_id = log.export_id
        last = log.export_last
        self.assertEqual(log.begin_export("usb"), None)
        with self.assertRaisesRegex(ValueError, "hwlog_busy"):
            log.clear()
        for _ in range(4):
            log.event("during")
        rows = [fields(log.next_export_line()) for _ in range(10)]
        self.assertTrue(all(r["x"] == str(export_id) and r["e"] == "before" for r in rows))
        self.assertEqual(fields(log.next_export_line())["last"], str(last))
        self.assertIsNone(log.next_export_line())
        log.clear(last)
        self.assertEqual(log.count, 5)  # 4 new events and the audit record.
        log.begin_export()
        retained = [fields(log.next_export_line())["e"] for _ in range(5)]
        self.assertEqual(retained, ["during"] * 4 + ["hwlog_clear"])

    def test_clearing_old_snapshot_after_ring_wrap_cannot_delete_new_events(self):
        log = fw.HardwareLogger()
        log.event("old")
        log.begin_export()
        last = log.export_last
        log.cancel_export()
        for _ in range(200):
            log.event("new")
        log.clear(last)
        self.assertEqual(log.count, 192)
        log.begin_export()
        events = [fields(log.next_export_line())["e"] for _ in range(192)]
        self.assertEqual(events.count("new"), 191)
        self.assertEqual(events[-1], "hwlog_clear")
        log.cancel_export()
        with self.assertRaises(ValueError):
            log.clear(log.sequence + 100)

    def test_export_cancel_retains_ram_and_empty_export_has_end(self):
        log = fw.HardwareLogger()
        self.assertEqual(log.begin_export(), 0)
        self.assertEqual(fields(log.next_export_line())["n"], "0")
        log.event("disconnect")
        log.begin_export()
        log.cancel_export()
        self.assertEqual(log.count, 1)
        log.begin_export()
        self.assertEqual(fields(log.next_export_line())["e"], "disconnect")

    def make_interface(self):
        log = fw.HardwareLogger()
        log.usb = Mock()
        controller = Mock(hardware_log=log)
        controller.info_line.return_value = "info fw=" + fw.FIRMWARE_VERSION
        controller.status_line.return_value = "stat mode=idle"
        return fw.CommandInterface(controller, Mock(), Mock()), log

    def test_time_command_ack_and_invalid_input(self):
        interface, log = self.make_interface()
        interface.handle("time sync 1800000000000")
        ack = interface.ble.write.call_args.args[0]
        self.assertEqual(fields(ack)["request_ms"], "1800000000000")
        self.assertEqual(fields(ack)["synced"], "1")
        interface.handle("time sync NaN")
        self.assertTrue(interface.ble.write.call_args.args[0].startswith("err"))
        self.assertEqual(log.clock.unix_ms(), 1800000000000)
        interface.controller.stop.assert_not_called()

    def test_usb_rejects_motion_clear_and_time_writes(self):
        interface, log = self.make_interface()
        for command in ["f 0.25", "time sync 1800000000000", "hwlog clear", "motor left f"]:
            interface.handle_usb(command)
            log.usb.write.assert_called_with("err usb_read_only")
        self.assertIsNone(log.clock.anchor_unix)
        interface.controller.stop.assert_not_called()
        interface.handle_usb("hwlog status")
        self.assertEqual(fields(log.usb.write.call_args.args[0])["cap"], "192")
        interface.handle_usb("hwlog dump")
        self.assertEqual(log.export_channel, "usb")
        self.assertTrue(log.usb.write.call_args.args[0].startswith("ok hwlog_export"))

    def test_ble_metadata_queries_do_not_fill_log(self):
        interface, log = self.make_interface()
        for _ in range(100):
            interface.handle("hwlog status")
            interface.handle("time status")
        self.assertEqual(log.count, 0)

    def make_ble(self):
        with patch.object(fw, "BLE_ENABLED", False):
            ble = fw.BluetoothSerial()
        ble.uart = Mock()
        ble.hardware_log = fw.HardwareLogger()
        return ble

    def test_bounded_uart_partial_writes_preserve_line_boundaries(self):
        ble = self.make_ble()
        actual = bytearray()
        def partial_write(chunk):
            self.assertLessEqual(len(chunk), fw.DIAGNOSTIC_TX_CHUNK)
            n = min(7, len(chunk))
            actual.extend(chunk[:n])
            return n
        ble.uart.write.side_effect = partial_write
        one, two = "hwlog " + "a" * 280, "ok stop"
        self.assertTrue(ble.write(one))
        self.assertTrue(ble.write(two))
        for _ in range(100):
            ble.flush()
        self.assertEqual(actual.decode(), one + "\n" + two + "\n")
        self.assertFalse(ble.tx_queue)

    def test_uart_fault_is_in_ram_and_queue_is_bounded(self):
        ble = self.make_ble()
        for _ in range(fw.DIAGNOSTIC_QUEUE_CAPACITY):
            self.assertTrue(ble.write("ok"))
        self.assertFalse(ble.write("overflow"))
        ble.uart.write.side_effect = OSError("transport_lost")
        ble.last_error_ms = None
        ble.flush()
        self.assertFalse(ble.tx_queue)
        self.assertGreaterEqual(ble.hardware_log.count, 2)

    def test_overlong_rx_discards_whole_line_not_motion_suffix(self):
        ble = self.make_ble()
        ble.uart.any.return_value = 220
        ble.uart.read.return_value = ("a" * 170 + "drive 0.2 0.2\nstatus\n").encode()
        self.assertEqual(ble.poll_lines(), ["status"])
        self.assertTrue(ble.hardware_log.count)

    def test_usb_polling_never_writes_to_nonwritable_host(self):
        usb = fw.UsbDiagnostics.__new__(fw.UsbDiagnostics)
        usb.stdout = Mock()
        usb.output_poll = Mock()
        usb.output_poll.poll.return_value = []
        usb.queue, usb.offset, usb.dropped = ["event\n"], 0, 0
        usb.flush()
        usb.stdout.write.assert_not_called()
        usb.output_poll.poll.assert_called_with(0)

    def test_state_debounce_ignores_brief_glitch_then_accepts_real_drop(self):
        global now
        now = 1000
        ble = self.make_ble()
        ble.state = Mock()
        ble.state.value.return_value = 1
        ble.last_connected = 1
        ble.raw_connected = 1
        ble.raw_changed_ms = now
        ble.state.value.return_value = 0
        self.assertIsNone(ble.connection_event())
        now += fw.BLE_STATE_DEBOUNCE_MS - 1
        self.assertIsNone(ble.connection_event())
        now += 2
        self.assertEqual(ble.connection_event(), 0)
        self.assertEqual(ble.last_connected, 0)

    def test_motor_test_does_not_block_stop_or_disconnect(self):
        global now
        now = 0
        controller = fw.CartController.__new__(fw.CartController)
        controller.front_obstacle = Mock(blocked=False)
        controller.left_motor = Mock()
        controller.right_motor = Mock()
        controller.hardware_log = fw.HardwareLogger()
        controller.mode = fw.MODE_IDLE
        controller.manual_left = 0.0
        controller.manual_right = 0.0
        controller.pending_manual_left = 0.0
        controller.pending_manual_right = 0.0
        controller.reverse_state = ""
        controller.soft_stop_pending = False
        controller.soft_stop_reason = ""
        controller.tow_armed = False
        controller.tow_left_force = 0.0
        controller.tow_right_force = 0.0
        controller.last_manual_ms = 0
        controller.drive_status = "idle"
        self.assertTrue(controller.start_motor_test(0.18, 0.0, 1500))
        self.assertEqual(controller.mode, fw.MODE_MANUAL)
        self.assertTrue(controller.motor_test_active())
        controller.stop(fw.MODE_IDLE, "ble_disconnect")
        self.assertFalse(controller.motor_test_active())
        self.assertEqual(controller.mode, fw.MODE_IDLE)
        controller.left_motor.stop.assert_called()
        controller.right_motor.stop.assert_called()

    def make_controller(self):
        controller = fw.CartController(
            Mock(),
            Mock(),
            Mock(current=0.0),
            Mock(current=0.0),
            Mock(),
            Mock(blocked=False),
            Mock(),
            fw.HardwareLogger(),
        )
        controller.estop.value.return_value = 1
        controller.front_obstacle.signal_active.return_value = False
        controller.front_obstacle.update = Mock()
        controller.ble.connected.return_value = 1
        controller.left_motor.ramp_to = Mock()
        controller.right_motor.ramp_to = Mock()
        controller.left_motor.stop = Mock()
        controller.right_motor.stop = Mock()
        controller.tared = True
        controller.sensor_ok = True
        controller.sensor_error = ""
        return controller

    def test_ble_disconnect_keeps_tow_and_stops_manual(self):
        controller = self.make_controller()
        controller.mode = fw.MODE_AUTO
        controller.tow_armed = True
        controller.drive_status = "active"
        controller.handle_ble_connection(False)
        self.assertEqual(controller.mode, fw.MODE_AUTO)
        controller.left_motor.stop.assert_not_called()
        controller.mode = fw.MODE_MANUAL
        controller.motor_test_running = False
        controller.handle_ble_connection(False)
        self.assertEqual(controller.mode, fw.MODE_IDLE)
        controller.left_motor.stop.assert_called()
        controller.right_motor.stop.assert_called()

    def test_tow_idle_timeout_exits_only_without_pull(self):
        global now
        now = 1000
        previous_timeout = fw.TOW_IDLE_TIMEOUT_MS
        fw.TOW_IDLE_TIMEOUT_MS = 10000
        self.addCleanup(lambda: setattr(fw, "TOW_IDLE_TIMEOUT_MS", previous_timeout))
        controller = self.make_controller()
        controller.enter_tow()
        self.assertEqual(controller.mode, fw.MODE_AUTO)
        controller.tow_armed = True
        controller.tow_left_force = 0.0
        controller.tow_right_force = 0.0
        now += 9999
        self.assertFalse(controller.apply_tow_idle_timeout(now))
        self.assertEqual(controller.mode, fw.MODE_AUTO)
        controller.tow_left_force = 4000
        now += 10000
        self.assertFalse(controller.apply_tow_idle_timeout(now))
        self.assertEqual(controller.mode, fw.MODE_AUTO)
        controller.tow_left_force = 0.0
        now += 10001
        self.assertTrue(controller.apply_tow_idle_timeout(now))
        self.assertEqual(controller.mode, fw.MODE_IDLE)

    def test_set_tow_idle_ms_is_reported_in_params(self):
        previous_timeout = fw.TOW_IDLE_TIMEOUT_MS
        fw.TOW_IDLE_TIMEOUT_MS = 300000
        self.addCleanup(lambda: setattr(fw, "TOW_IDLE_TIMEOUT_MS", previous_timeout))
        interface, _log = self.make_interface()
        interface.controller.param_line.return_value = "param tow_idle_ms=180000"
        interface.handle("set tow_idle_ms 180000")
        self.assertEqual(fw.TOW_IDLE_TIMEOUT_MS, 180000)
        replies = [call.args[0] for call in interface.ble.write.call_args_list]
        self.assertTrue(any(text.startswith("ok set tow_idle_ms=180000") for text in replies))
        self.assertEqual(replies[-1], "param tow_idle_ms=180000")

    def test_calibration_save_keeps_other_group_and_rejects_motion(self):
        store = fw.CalibrationStore(path="_cal_test.cfg", temp_path="_cal_test.cfg.tmp")
        motor = fw.apply_calibration_values({"left_motor_gain": 0.9, "right_motor_gain": 1.1})
        self.assertEqual(round(motor["left_motor_gain"], 2), 0.9)
        saved = store.save(fw.current_calibration_values())
        self.assertEqual(saved["left_motor_gain"], fw.LEFT_MOTOR_GAIN)
        fw.apply_calibration_values({"left_force_gain": 1.4, "start_raw": 20000, "full_raw": 160000})
        values = dict(store.saved)
        for name in fw.CALIBRATION_GROUPS["force"]:
            values[name] = fw.current_calibration_values()[name]
        store.save(values)
        reloaded = store.load()
        self.assertEqual(reloaded["left_motor_gain"], 0.9)
        self.assertEqual(reloaded["left_force_gain"], 1.4)
        self.assertEqual(reloaded["start_raw"], 20000)
        corrupt = Path("_cal_bad.cfg")
        corrupt.write_text("fmt=1\nleft_motor_gain=oops\n")
        bad = fw.CalibrationStore(path=str(corrupt), temp_path="_cal_bad.cfg.tmp")
        self.assertIsNone(bad.load())
        self.assertEqual(bad.last_error, "bad_value")
        interface, log = self.make_interface()
        interface.controller.mode = fw.MODE_MANUAL
        interface.controller.motors_stopped.return_value = False
        interface.controller.calibration = store
        interface.handle("cal save motor")
        interface.controller.stop.assert_called()
        self.assertEqual(interface.ble.write.call_args.args[0], "err cal_not_idle")
        self.addCleanup(lambda: [Path(name).unlink(missing_ok=True) for name in (
            "_cal_test.cfg", "_cal_test.cfg.tmp", "_cal_bad.cfg", "_cal_bad.cfg.tmp")])

    def test_force_thresholds_and_save_keep_motor_gains(self):
        store = fw.CalibrationStore(path="_force_cal.cfg", temp_path="_force_cal.cfg.tmp")
        fw.apply_calibration_values({"left_motor_gain": 0.88, "right_motor_gain": 1.05})
        store.save(fw.current_calibration_values())
        with self.assertRaisesRegex(ValueError, "threshold_order"):
            fw.apply_calibration_values({"start_raw": 50000, "full_raw": 40000}, source="runtime")
        fw.apply_calibration_values({"left_force_gain": 1.2, "right_force_gain": 0.9, "start_raw": 18000, "full_raw": 140000})
        values = dict(store.saved)
        for name in fw.CALIBRATION_GROUPS["force"]:
            values[name] = fw.current_calibration_values()[name]
        saved = store.save(values)
        self.assertEqual(saved["left_motor_gain"], 0.88)
        self.assertEqual(saved["left_force_gain"], 1.2)
        self.assertEqual(saved["start_raw"], 18000)
        reloaded = store.load()
        self.assertEqual(reloaded["right_motor_gain"], 1.05)
        self.assertEqual(reloaded["full_raw"], 140000)
        interface, log = self.make_interface()
        interface.controller.mode = fw.MODE_IDLE
        interface.controller.tared = False
        interface.controller.sensor_ok = False
        interface.controller.sensor_error = "not_tared"
        interface.handle("tow")
        self.assertTrue(interface.ble.write.call_args.args[0].startswith("err sensor_not_ready"))
        self.addCleanup(lambda: [Path(name).unlink(missing_ok=True) for name in ("_force_cal.cfg", "_force_cal.cfg.tmp")])

    def test_usb_partial_output_and_drop_accounting(self):
        usb = fw.UsbDiagnostics.__new__(fw.UsbDiagnostics)
        usb.stdout, usb.output_poll = Mock(), Mock()
        usb.output_poll.poll.return_value = [(1, fw.select.POLLOUT)]
        usb.queue, usb.offset, usb.dropped = [], 0, 0
        output = []
        def write(chunk):
            n = min(3, len(chunk))
            output.append(chunk[:n])
            return n
        usb.stdout.write.side_effect = write
        for _ in range(fw.DIAGNOSTIC_QUEUE_CAPACITY):
            self.assertTrue(usb.write("sample"))
        self.assertFalse(usb.write("overflow"))
        for _ in range(100):
            usb.flush()
        self.assertEqual("".join(output), "sample\n" * fw.DIAGNOSTIC_QUEUE_CAPACITY + "usb_dropped n=1 fw=" + fw.FIRMWARE_VERSION + "\n")


if __name__ == "__main__":
    unittest.main()
