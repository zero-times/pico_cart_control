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
