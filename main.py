from machine import Pin, PWM, UART
from time import sleep_ms, ticks_ms, ticks_diff, ticks_add

try:
    import rp2
except ImportError:
    rp2 = None


# Raspberry Pi Pico / RP2040 traction cart controller.
# MicroPython version. Copy this file to the Pico as main.py.
#
# 2026-07 Pico-BLE hardware note:
# The Waveshare Pico-BLE base board uses GP0/GP1 as the default UART to the
# Bluetooth module and GP15 as the module STATE output. Do not drive GP15.

# -----------------------------
# Pin configuration
# -----------------------------

# Waveshare Pico-BLE / WS-B01 UART Bluetooth module.
# Default solder jumper on the board is UART0:
#   Pico GP0 TXD0 -> Bluetooth RX
#   Pico GP1 RXD0 -> Bluetooth TX
# If you move the board jumper to UART1, change these to UART(1), GP4, GP5.
BLE_ENABLED = True
BLE_UART_ID = 0
BLE_UART_TX = 0
BLE_UART_RX = 1
BLE_UART_BAUD = 115200
BLE_STATE_PIN = 15
BLE_STATE_ACTIVE_HIGH = True
BLE_LINE_MAX = 160
PROTOCOL_VERSION = "pico-cart-ble-2026-07-15-front-ir"

# HX711 modules. Each S-type load cell uses one HX711.
LEFT_HX711_DOUT = 6
LEFT_HX711_SCK = 7
RIGHT_HX711_DOUT = 8
RIGHT_HX711_SCK = 9

# WHEELTEC D50A-style driver mode: one PWM pin + INA + INB for each motor.
LEFT_MOTOR_PWM = 10
LEFT_MOTOR_INA = 11
LEFT_MOTOR_INB = 12
RIGHT_MOTOR_PWM = 13
RIGHT_MOTOR_INA = 14

# Moved from GP15 because GP15 is the Pico-BLE STATE pin.
# Rewire D50A control2 INB2 to Pico GP17.
RIGHT_MOTOR_INB = 17

# Optional emergency stop input.
# Recommended wiring: GP16 -> switch -> GND, using internal pull-up.
# Pressed = 0, released = 1.
ESTOP_PIN = 16

# Front reflective infrared obstacle module. The common three-pin module pulls
# OUT low when an obstacle is detected. GP22 uses an internal pull-up so an
# unplugged sensor remains clear instead of stopping the cart at boot.
FRONT_OBSTACLE_ENABLED = True
FRONT_OBSTACLE_PIN = 22
FRONT_OBSTACLE_ACTIVE_LOW = True

# Non-W Pico uses GP25. Pico W/Pico 2 W MicroPython uses Pin("LED").
FALLBACK_LED_PIN = 25

# Onboard LED status:
# disconnected = off, connected = solid on, command/motor activity = blink,
# identify command = fast blink for a short time.
LED_ACTIVITY_MS = 900
LED_ACTIVITY_BLINK_MS = 180
LED_FORCE_MIN_RAW = 3000
LED_FORCE_FULL_RAW = 120000
LED_FORCE_SLOW_MS = 650
LED_FORCE_FAST_MS = 80
LED_IDENTIFY_DEFAULT_MS = 5000
LED_IDENTIFY_BLINK_MS = 80


# -----------------------------
# Control tuning
# -----------------------------

PWM_FREQ_HZ = 16000

# Keep this conservative for first bench tests.
MAX_PWM = 0.45

# Minimum target PWM once the cart decides it should move.
# Wheelchair motors often need a small kick before they rotate.
MIN_MOVE_PWM = 0.14

# Raw HX711 threshold after tare. You must tune these with serial output.
PULL_START_RAW = 25000
PULL_FULL_RAW = 180000

# Difference between left and right pull. Higher = sharper steering.
STEER_GAIN = 0.75

# If your S-type sensor pull direction reads negative, change to -1.
LEFT_FORCE_SIGN = 1
RIGHT_FORCE_SIGN = 1

# Per-side force calibration. Use these to balance imperfect linkage/sensors.
LEFT_FORCE_GAIN = 1.0
RIGHT_FORCE_GAIN = 1.0

# Use this if one motor spins backward relative to the other.
LEFT_MOTOR_REVERSE = False
RIGHT_MOTOR_REVERSE = False

# Per-side motor trim. If one wheel is faster, reduce that side, e.g. 0.90.
LEFT_MOTOR_GAIN = 1.0
RIGHT_MOTOR_GAIN = 1.0

# Output smoothing, expressed as the PWM delta per nominal LOOP_MS.
# Actual slew is compensated with elapsed time so sensor delays do not distort it.
RAMP_STEP = 0.012
DECEL_RAMP_STEP = 0.018
LOOP_MS = 25

# Low-pass filter for load-cell readings.
FILTER_ALPHA = 0.22

# Safety: if raw value jumps far beyond normal, stop.
MAX_SAFE_RAW = 650000

# Print USB debug line every N ms.
DEBUG_EVERY_MS = 250

# Send status over Bluetooth only after "stream on" to avoid flooding phones.
BLE_STREAM_DEFAULT = False
BLE_STREAM_EVERY_MS = 500

# Bluetooth manual-drive mode is intentionally limited and times out quickly.
ALLOW_BLE_MANUAL_DRIVE = True
MANUAL_MAX_PWM = 0.25
MANUAL_TIMEOUT_MS = 1200
REVERSE_NEUTRAL_MS = 120
MOTOR_ZERO_EPSILON = 0.002

# Keep the obstacle latched until the signal has remained clear for this long.
FRONT_OBSTACLE_CLEAR_MS = 400

# Hardware diagnostics stay in RAM so a driving cart never blocks on Pico flash
# writes. The Android app can request the ring buffer through the BLE UART.
HARDWARE_LOG_CAPACITY = 192
HARDWARE_LOG_SNAPSHOT_MS = 250
HARDWARE_LOG_OVERRUN_MS = 100
HARDWARE_LOG_OVERRUN_REPEAT_MS = 1000
HARDWARE_LOG_MAX_RECORD_CHARS = 124

# Set True when you only want to test the motor driver without HX711 sensors.
MOTOR_TEST_MODE = False
MOTOR_TEST_PWM = 0.20
MOTOR_TEST_STEP_MS = 1800

# Tow-rope (auto) safety: both sensors must be released below this before arming.
TOW_RELEASE_THRESHOLD_RAW = 2500
# Minimum per-side pull required for tow drive to engage after release.
TOW_MIN_VALID_PULL_RAW = 3000
# Capture each sensor's current minimum after entering tow mode. Motors remain stopped.
TOW_BASELINE_CAPTURE_MS = 700
# Optional additional per-side raw compensation, after the per-session baseline.
TOW_LEFT_COMP_RAW = 0
TOW_RIGHT_COMP_RAW = 0

MODE_AUTO = "tow"
MODE_IDLE = "idle"
MODE_MANUAL = "manual"
AUTO_MODE_ON_START = False


def clamp(value, low, high):
    if value < low:
        return low
    if value > high:
        return high
    return value


def map_range(value, in_min, in_max, out_min, out_max):
    if in_max <= in_min:
        return out_min
    value = clamp(value, in_min, in_max)
    ratio = (value - in_min) / (in_max - in_min)
    return out_min + ratio * (out_max - out_min)


def read_bootsel_button():
    if rp2 is None or not hasattr(rp2, "bootsel_button"):
        return False
    return bool(rp2.bootsel_button())


def make_led():
    try:
        return Pin("LED", Pin.OUT)
    except Exception:
        return Pin(FALLBACK_LED_PIN, Pin.OUT)


def bool_text(value):
    return "1" if value else "0"


def parse_power(value):
    power = float(value)
    if abs(power) > 1.0:
        power = power / 100.0
    return clamp(power, -1.0, 1.0)


class BluetoothSerial:
    def __init__(self):
        self.uart = None
        self.state = None
        self.buffer = ""
        self.last_connected = -1

        if not BLE_ENABLED:
            return

        try:
            self.uart = UART(
                BLE_UART_ID,
                baudrate=BLE_UART_BAUD,
                tx=Pin(BLE_UART_TX),
                rx=Pin(BLE_UART_RX),
                timeout=0,
            )
        except Exception as err:
            print("ble_uart_error={}".format(err))
            self.uart = None

        try:
            self.state = Pin(BLE_STATE_PIN, Pin.IN)
            self.last_connected = self.connected()
        except Exception as err:
            print("ble_state_error={}".format(err))
            self.state = None

    def enabled(self):
        return self.uart is not None

    def connected(self):
        if self.state is None:
            return -1
        raw = self.state.value()
        if BLE_STATE_ACTIVE_HIGH:
            return raw
        return 0 if raw else 1

    def connection_event(self):
        now = self.connected()
        if now != self.last_connected:
            self.last_connected = now
            return now
        return None

    def write(self, text, force=False):
        if self.uart is None:
            return
        if not force and self.state is not None and self.connected() == 0:
            return
        if not text.endswith("\n"):
            text += "\n"
        try:
            self.uart.write(text)
        except Exception as err:
            print("ble_write_error={}".format(err))

    def poll_lines(self):
        lines = []
        if self.uart is None:
            return lines

        try:
            count = self.uart.any()
            if not count:
                return lines
            data = self.uart.read(count)
        except Exception as err:
            print("ble_read_error={}".format(err))
            return lines

        if not data:
            return lines

        try:
            chunk = data.decode("utf-8")
        except Exception:
            chunk = ""

        for ch in chunk:
            if ch == "\r" or ch == "\n":
                line = self.buffer.strip()
                self.buffer = ""
                if line:
                    lines.append(line)
            elif 32 <= ord(ch) <= 126:
                self.buffer += ch
                if len(self.buffer) > BLE_LINE_MAX:
                    self.buffer = self.buffer[-BLE_LINE_MAX:]

        return lines


class StatusLed:
    def __init__(self, led, ble):
        self.led = led
        self.ble = ble
        self.controller = None
        self.blink_state = 0
        self.last_blink = ticks_ms()
        self.activity_until = 0
        self.identify_until = 0
        self.led.value(0)

    def set_controller(self, controller):
        self.controller = controller

    def pulse(self, duration_ms=LED_ACTIVITY_MS):
        self.activity_until = ticks_add(ticks_ms(), duration_ms)

    def identify(self, duration_ms=LED_IDENTIFY_DEFAULT_MS):
        self.identify_until = ticks_add(ticks_ms(), duration_ms)
        self.pulse(duration_ms)

    def active_until(self, now, timestamp):
        return ticks_diff(timestamp, now) > 0

    def motor_active(self):
        if self.controller is None:
            return False
        return (
            abs(self.controller.left_motor.current) > 0.01
            or abs(self.controller.right_motor.current) > 0.01
            or abs(self.controller.manual_left) > 0.01
            or abs(self.controller.manual_right) > 0.01
        )

    def force_blink_interval(self):
        if self.controller is None or not self.controller.sensor_ok:
            return 0
        total = self.controller.left_force + self.controller.right_force
        if total < LED_FORCE_MIN_RAW:
            return 0
        return int(
            map_range(
                total,
                LED_FORCE_MIN_RAW,
                LED_FORCE_FULL_RAW,
                LED_FORCE_SLOW_MS,
                LED_FORCE_FAST_MS,
            )
        )

    def blink(self, now, interval_ms):
        if ticks_diff(now, self.last_blink) >= interval_ms:
            self.last_blink = now
            self.blink_state = 1 - self.blink_state
        self.led.value(self.blink_state)

    def update(self, now):
        if self.active_until(now, self.identify_until):
            self.blink(now, LED_IDENTIFY_BLINK_MS)
            return

        connected = self.ble.connected()
        if connected == 0:
            self.blink_state = 0
            self.led.value(0)
            return

        if self.active_until(now, self.activity_until) or self.motor_active():
            self.blink(now, LED_ACTIVITY_BLINK_MS)
            return

        force_interval = self.force_blink_interval()
        if force_interval:
            self.blink(now, force_interval)
            return

        self.blink_state = 1
        self.led.value(1)


class HX711:
    def __init__(self, dout_pin, sck_pin, gain=128):
        self.dout = Pin(dout_pin, Pin.IN)
        self.sck = Pin(sck_pin, Pin.OUT)
        self.sck.value(0)
        self.offset = 0
        if gain == 128:
            self.pulses = 1
        elif gain == 64:
            self.pulses = 3
        elif gain == 32:
            self.pulses = 2
        else:
            raise ValueError("HX711 gain must be 128, 64, or 32")

    def is_ready(self):
        return self.dout.value() == 0

    def read_raw(self, timeout_ms=80):
        start = ticks_ms()
        while not self.is_ready():
            if ticks_diff(ticks_ms(), start) > timeout_ms:
                raise OSError("HX711 timeout")

        value = 0
        for _ in range(24):
            self.sck.value(1)
            value = (value << 1) | self.dout.value()
            self.sck.value(0)

        for _ in range(self.pulses):
            self.sck.value(1)
            self.sck.value(0)

        # Convert 24-bit two's complement to signed int.
        if value & 0x800000:
            value -= 0x1000000
        return value

    def tare(self, samples=20):
        total = 0
        valid = 0
        for _ in range(samples):
            try:
                total += self.read_raw()
                valid += 1
            except OSError:
                pass
            sleep_ms(20)
        if valid == 0:
            raise OSError("HX711 tare failed")
        self.offset = total // valid
        return self.offset

    def read_tared(self):
        return self.read_raw() - self.offset


class Motor:
    def __init__(self, pwm_pin, ina_pin, inb_pin, reverse=False):
        self.pwm = PWM(Pin(pwm_pin))
        self.pwm.freq(PWM_FREQ_HZ)
        self.ina = Pin(ina_pin, Pin.OUT)
        self.inb = Pin(inb_pin, Pin.OUT)
        self.reverse = reverse
        self.current = 0.0
        self.last_update_ms = ticks_ms()
        self.write(0.0)

    def write(self, command):
        command = clamp(command, -1.0, 1.0)
        if self.reverse:
            command = -command

        duty = abs(command)
        if duty <= 0:
            self.ina.value(0)
            self.inb.value(0)
        elif command > 0:
            self.ina.value(1)
            self.inb.value(0)
        else:
            self.ina.value(0)
            self.inb.value(1)

        self.pwm.duty_u16(int(clamp(duty, 0.0, 1.0) * 65535))

    def ramp_to(self, target, now=None):
        target = clamp(target, -MAX_PWM, MAX_PWM)
        now = ticks_ms() if now is None else now
        elapsed_ms = ticks_diff(now, self.last_update_ms)
        self.last_update_ms = now
        elapsed_ms = clamp(elapsed_ms, 1, 200)

        accelerating = (
            self.current == 0.0
            or (self.current * target >= 0.0 and abs(target) > abs(self.current))
        )
        step_per_loop = RAMP_STEP if accelerating else DECEL_RAMP_STEP
        max_delta = step_per_loop * elapsed_ms / LOOP_MS

        if target > self.current + max_delta:
            self.current += max_delta
        elif target < self.current - max_delta:
            self.current -= max_delta
        else:
            self.current = target
        self.write(self.current)

    def stop(self):
        self.current = 0.0
        self.last_update_ms = ticks_ms()
        self.write(0.0)


class FrontObstacleSensor:
    def __init__(self, pin):
        self.pin = pin
        self.blocked = False
        self.clear_started_ms = None
        self.stop_callback = None

    def signal_active(self):
        if not FRONT_OBSTACLE_ENABLED:
            return False
        raw = self.pin.value()
        return raw == 0 if FRONT_OBSTACLE_ACTIVE_LOW else raw == 1

    def attach_stop_callback(self, callback):
        self.stop_callback = callback
        if self.signal_active():
            self.blocked = True
            callback()

        trigger = Pin.IRQ_FALLING if FRONT_OBSTACLE_ACTIVE_LOW else Pin.IRQ_RISING
        try:
            self.pin.irq(trigger=trigger, handler=self._handle_active_edge, hard=False)
        except TypeError:
            self.pin.irq(trigger=trigger, handler=self._handle_active_edge)

    def _handle_active_edge(self, _pin):
        # A single active edge is enough to stop. Clearing is deliberately
        # debounced in update() so signal chatter cannot restart the cart.
        if self.signal_active() and not self.blocked:
            self.blocked = True
            self.clear_started_ms = None
            if self.stop_callback is not None:
                self.stop_callback()

    def update(self, now):
        if self.signal_active():
            self.clear_started_ms = None
            if not self.blocked:
                self.blocked = True
                if self.stop_callback is not None:
                    self.stop_callback()
        elif self.blocked:
            if self.clear_started_ms is None:
                self.clear_started_ms = now
            elif ticks_diff(now, self.clear_started_ms) >= FRONT_OBSTACLE_CLEAR_MS:
                self.blocked = False
                self.clear_started_ms = None
        return self.blocked


class HardwareLogger:
    def __init__(self):
        self.records = [None] * HARDWARE_LOG_CAPACITY
        self.head = 0
        self.count = 0
        self.sequence = 1
        self.last_snapshot_ms = ticks_ms()
        self.last_state = ""
        self.last_overrun_ms = 0
        self.export_records = None
        self.export_index = 0

    def event(self, event, fields="", now=None):
        now = ticks_ms() if now is None else now
        record = "s={} t={} e={}".format(self.sequence, now, event)
        if fields:
            record += " " + fields.replace("\r", "_").replace("\n", "_")
        record = record[:HARDWARE_LOG_MAX_RECORD_CHARS]
        self.records[self.head] = record
        self.head = (self.head + 1) % HARDWARE_LOG_CAPACITY
        self.count = min(self.count + 1, HARDWARE_LOG_CAPACITY)
        self.sequence += 1

    def observe(self, controller, now):
        state_fields = (
            "mode={} drive={} sensor={} err={} unsafe={} tow={} bt={} front={}"
        ).format(
            controller.mode,
            controller.drive_status,
            "ok" if controller.sensor_ok else "bad",
            controller.sensor_error or "-",
            controller.unsafe_reason or "-",
            bool_text(controller.tow_armed),
            controller.ble.connected(),
            bool_text(controller.front_obstacle.blocked),
        )
        snapshot_fields = (
            "mode={} drive={} sensor={} err={} unsafe={} tow={} bt={} front={} "
            "l={:.0f} r={:.0f} pl={:.2f} pr={:.2f} age={} loop={}"
        ).format(
            controller.mode,
            controller.drive_status,
            "ok" if controller.sensor_ok else "bad",
            controller.sensor_error or "-",
            controller.unsafe_reason or "-",
            bool_text(controller.tow_armed),
            controller.ble.connected(),
            bool_text(controller.front_obstacle.blocked),
            controller.left_force,
            controller.right_force,
            controller.left_motor.current,
            controller.right_motor.current,
            max(0, ticks_diff(now, controller.last_manual_ms))
            if controller.mode == MODE_MANUAL else 0,
            controller.last_loop_ms,
        )
        if state_fields != self.last_state:
            self.last_state = state_fields
            self.event("state", state_fields, now)
        if ticks_diff(now, self.last_snapshot_ms) >= HARDWARE_LOG_SNAPSHOT_MS:
            self.last_snapshot_ms = now
            self.event("snap", snapshot_fields, now)

    def record_loop_time(self, elapsed_ms, now):
        if (elapsed_ms >= HARDWARE_LOG_OVERRUN_MS and
                ticks_diff(now, self.last_overrun_ms) >= HARDWARE_LOG_OVERRUN_REPEAT_MS):
            self.last_overrun_ms = now
            self.event("loop_overrun", "ms={}".format(elapsed_ms), now)

    def begin_export(self):
        self.event("hwlog_dump", "n={}".format(self.count))
        start = (self.head - self.count) % HARDWARE_LOG_CAPACITY
        self.export_records = [
            self.records[(start + index) % HARDWARE_LOG_CAPACITY]
            for index in range(self.count)
        ]
        self.export_index = 0
        return len(self.export_records)

    def clear(self):
        self.records = [None] * HARDWARE_LOG_CAPACITY
        self.head = 0
        self.count = 0
        self.export_records = None
        self.export_index = 0
        self.event("hwlog_clear")

    def next_export_line(self):
        if self.export_records is None:
            return None
        total = len(self.export_records)
        if self.export_index < total:
            self.export_index += 1
            record = self.export_records[self.export_index - 1]
            return "hwlog i={} n={} {}".format(self.export_index, total, record)
        self.export_records = None
        self.export_index = 0
        return "hwlog_end n={}".format(total)


def safe_force(raw, sign):
    force = raw * sign
    if force < 0:
        return 0
    return force


def compute_targets(left_force, right_force):
    total = left_force + right_force

    if total < PULL_START_RAW:
        return 0.0, 0.0, total, 0.0

    base = map_range(total, PULL_START_RAW, PULL_FULL_RAW, MIN_MOVE_PWM, MAX_PWM)

    # Positive steer means pull is stronger on the right side.
    # The cart should turn right: left wheel faster, right wheel slower.
    steer = (right_force - left_force) / max(total, 1)
    steer = clamp(steer * STEER_GAIN, -0.85, 0.85)

    left_target = base * (1.0 + steer)
    right_target = base * (1.0 - steer)

    left_target = clamp(left_target, 0.0, MAX_PWM)
    right_target = clamp(right_target, 0.0, MAX_PWM)
    return left_target, right_target, total, steer


class CartController:
    def __init__(
        self, left_hx, right_hx, left_motor, right_motor, estop, front_obstacle, ble,
        hardware_log
    ):
        self.left_hx = left_hx
        self.right_hx = right_hx
        self.left_motor = left_motor
        self.right_motor = right_motor
        self.estop = estop
        self.front_obstacle = front_obstacle
        self.ble = ble
        self.hardware_log = hardware_log

        self.mode = MODE_AUTO if AUTO_MODE_ON_START else MODE_IDLE
        self.tared = False
        self.sensor_ok = False
        self.sensor_error = "not_tared"
        self.left_filtered = 0.0
        self.right_filtered = 0.0
        self.left_force = 0.0
        self.right_force = 0.0
        self.total = 0.0
        self.steer = 0.0
        self.unsafe_reason = ""
        self.last_unsafe_reason = ""
        self.last_loop_ms = 0

        self.manual_left = 0.0
        self.manual_right = 0.0
        self.pending_manual_left = 0.0
        self.pending_manual_right = 0.0
        self.last_manual_ms = ticks_ms()
        self.drive_status = "idle"
        self.reverse_state = ""
        self.reverse_neutral_started_ms = 0
        self.soft_stop_pending = False
        self.soft_stop_reason = ""
        self.tow_armed = False
        self.tow_baseline_started_ms = 0
        self.tow_left_baseline = 0.0
        self.tow_right_baseline = 0.0
        self.tow_left_force = 0.0
        self.tow_right_force = 0.0

    def stop(self, mode=MODE_IDLE, reason="stop"):
        self.mode = mode
        self.manual_left = 0.0
        self.manual_right = 0.0
        self.pending_manual_left = 0.0
        self.pending_manual_right = 0.0
        self.drive_status = "idle"
        self.reverse_state = ""
        self.soft_stop_pending = False
        self.soft_stop_reason = ""
        self.tow_armed = False
        self.tow_left_force = 0.0
        self.tow_right_force = 0.0
        self.left_motor.stop()
        self.right_motor.stop()
        self.hardware_log.event("stop", "reason={} mode={}".format(reason, mode))

    def front_obstacle_stop(self):
        # Never preserve a forward target across an obstacle event. Manual mode
        # returns to idle; tow mode must see the rope released before re-arming.
        self.manual_left = 0.0
        self.manual_right = 0.0
        self.pending_manual_left = 0.0
        self.pending_manual_right = 0.0
        self.reverse_state = ""
        self.soft_stop_pending = False
        self.soft_stop_reason = ""
        self.tow_armed = False
        if self.mode == MODE_MANUAL:
            self.mode = MODE_IDLE
        self.drive_status = "front_obstacle"
        self.left_motor.stop()
        self.right_motor.stop()
        self.hardware_log.event("stop", "reason=front_obstacle")

    def front_blocks_targets(self, left, right):
        return self.front_obstacle.blocked and (
            left > MOTOR_ZERO_EPSILON or right > MOTOR_ZERO_EPSILON
        )

    def tare_sensors(self, samples=20):
        self.left_motor.stop()
        self.right_motor.stop()
        self.tared = False
        self.sensor_ok = False
        self.sensor_error = "taring"
        self.hardware_log.event("tare_start", "samples={}".format(samples))
        print("tare_start")
        try:
            left_offset = self.left_hx.tare(samples)
            right_offset = self.right_hx.tare(samples)
        except OSError as err:
            self.sensor_error = str(err)
            self.hardware_log.event("tare_error", "err={}".format(self.sensor_error))
            print("tare_error={}".format(self.sensor_error))
            return False

        self.left_filtered = 0.0
        self.right_filtered = 0.0
        self.left_force = 0.0
        self.right_force = 0.0
        self.total = 0.0
        self.steer = 0.0
        self.tared = True
        self.sensor_ok = True
        self.sensor_error = ""
        self.hardware_log.event("tare_ok")
        print("tare_left={}, tare_right={}".format(left_offset, right_offset))
        return True

    def read_sensors(self):
        if not self.tared:
            self.sensor_ok = False
            self.sensor_error = "not_tared"
            return False

        was_sensor_ok = self.sensor_ok
        previous_error = self.sensor_error
        try:
            raw_left = self.left_hx.read_tared()
        except OSError as err:
            self.sensor_ok = False
            self.sensor_error = "left_{}".format(str(err).replace(" ", "_"))
            if was_sensor_ok or self.sensor_error != previous_error:
                self.hardware_log.event("hx_error", "side=left err={}".format(self.sensor_error))
            return False

        try:
            raw_right = self.right_hx.read_tared()
        except OSError as err:
            self.sensor_ok = False
            self.sensor_error = "right_{}".format(str(err).replace(" ", "_"))
            if was_sensor_ok or self.sensor_error != previous_error:
                self.hardware_log.event("hx_error", "side=right err={}".format(self.sensor_error))
            return False

        self.left_filtered = (
            self.left_filtered * (1.0 - FILTER_ALPHA) + raw_left * FILTER_ALPHA
        )
        self.right_filtered = (
            self.right_filtered * (1.0 - FILTER_ALPHA) + raw_right * FILTER_ALPHA
        )
        self.left_force = safe_force(self.left_filtered, LEFT_FORCE_SIGN) * LEFT_FORCE_GAIN
        self.right_force = safe_force(self.right_filtered, RIGHT_FORCE_SIGN) * RIGHT_FORCE_GAIN
        self.sensor_ok = True
        self.sensor_error = ""
        if not was_sensor_ok:
            self.hardware_log.event("sensor_recovered")
        return True

    def safety_reason(self):
        if self.estop.value() == 0:
            return "estop"
        if abs(self.left_filtered) > MAX_SAFE_RAW:
            return "left_raw"
        if abs(self.right_filtered) > MAX_SAFE_RAW:
            return "right_raw"
        if self.mode == MODE_AUTO and not self.sensor_ok:
            return self.sensor_error or "sensor"
        return ""

    def set_manual_drive(self, left, right):
        left = clamp(left, -MANUAL_MAX_PWM, MANUAL_MAX_PWM)
        right = clamp(right, -MANUAL_MAX_PWM, MANUAL_MAX_PWM)
        if self.front_blocks_targets(left, right):
            self.front_obstacle_stop()
            self.hardware_log.event("drive_rejected", "reason=front_obstacle")
            return False
        now = ticks_ms()
        self.mode = MODE_MANUAL
        self.last_manual_ms = now
        self.soft_stop_pending = False
        self.soft_stop_reason = ""

        reversing = (
            self.direction_reverses(self.left_motor.current, left)
            or self.direction_reverses(self.right_motor.current, right)
        )
        if reversing:
            self.pending_manual_left = left
            self.pending_manual_right = right
            self.manual_left = 0.0
            self.manual_right = 0.0
            self.reverse_state = "braking"
            self.drive_status = "reverse_braking"
        else:
            self.manual_left = left
            self.manual_right = right
            self.pending_manual_left = 0.0
            self.pending_manual_right = 0.0
            self.reverse_state = ""
            self.drive_status = "active"
        self.hardware_log.event("drive_command", "l={:.2f} r={:.2f}".format(left, right))
        return True

    def direction_reverses(self, current, target):
        return (
            (current > MOTOR_ZERO_EPSILON and target < -MOTOR_ZERO_EPSILON)
            or (current < -MOTOR_ZERO_EPSILON and target > MOTOR_ZERO_EPSILON)
        )

    def refresh_manual_lease(self):
        if self.mode != MODE_MANUAL or self.soft_stop_pending:
            return False
        self.last_manual_ms = ticks_ms()
        return True

    def soft_stop(self, reason="soft_stop"):
        self.manual_left = 0.0
        self.manual_right = 0.0
        self.pending_manual_left = 0.0
        self.pending_manual_right = 0.0
        self.reverse_state = ""
        self.soft_stop_pending = True
        self.soft_stop_reason = reason
        self.drive_status = reason
        self.hardware_log.event("soft_stop", "reason={}".format(reason))

    def motors_stopped(self):
        return (
            abs(self.left_motor.current) <= MOTOR_ZERO_EPSILON
            and abs(self.right_motor.current) <= MOTOR_ZERO_EPSILON
        )

    def enter_manual(self):
        self.mode = MODE_MANUAL
        self.manual_left = 0.0
        self.manual_right = 0.0
        self.pending_manual_left = 0.0
        self.pending_manual_right = 0.0
        self.drive_status = "active"
        self.reverse_state = ""
        self.soft_stop_pending = False
        self.soft_stop_reason = ""
        self.last_manual_ms = ticks_ms()
        self.hardware_log.event("mode", "to=manual")

    def enter_tow(self):
        if self.front_obstacle.blocked:
            self.stop(MODE_IDLE, "front_obstacle")
            self.drive_status = "front_obstacle"
            return False
        # Treat the lowest unloaded readings after mode entry as this session's zero.
        self.mode = MODE_AUTO
        self.tow_armed = False
        self.tow_baseline_started_ms = ticks_ms()
        self.tow_left_baseline = self.left_force
        self.tow_right_baseline = self.right_force
        self.tow_left_force = 0.0
        self.tow_right_force = 0.0
        self.drive_status = "calibrating"
        self.left_motor.stop()
        self.right_motor.stop()
        self.hardware_log.event("mode", "to=tow")
        return True

    def update_tow_forces(self):
        self.tow_left_force = max(
            0.0, self.left_force - self.tow_left_baseline - TOW_LEFT_COMP_RAW
        )
        self.tow_right_force = max(
            0.0, self.right_force - self.tow_right_baseline - TOW_RIGHT_COMP_RAW
        )

    def observe_hardware(self, now):
        self.hardware_log.observe(self, now)

    def update(self, now):
        self.front_obstacle.update(now)
        self.read_sensors()
        now = ticks_ms()
        self.front_obstacle.update(now)
        self.unsafe_reason = self.safety_reason()

        if self.unsafe_reason:
            if self.unsafe_reason != self.last_unsafe_reason:
                self.hardware_log.event(
                    "safety_stop", "reason={}".format(self.unsafe_reason), now
                )
            self.last_unsafe_reason = self.unsafe_reason
            self.manual_left = 0.0
            self.manual_right = 0.0
            self.tow_armed = False
            self.drive_status = "idle"
            self.left_motor.ramp_to(0.0, now)
            self.right_motor.ramp_to(0.0, now)
            self.observe_hardware(now)
            return

        if self.last_unsafe_reason:
            self.hardware_log.event(
                "safety_recovered", "reason={}".format(self.last_unsafe_reason), now
            )
            self.last_unsafe_reason = ""

        if self.mode == MODE_AUTO and self.front_obstacle.blocked:
            self.tow_armed = False
            self.total = self.left_force + self.right_force
            self.steer = 0.0
            self.drive_status = "front_obstacle"
            self.left_motor.stop()
            self.right_motor.stop()
            self.observe_hardware(now)
            return

        if self.mode == MODE_IDLE:
            left_target = 0.0
            right_target = 0.0
            self.total = self.left_force + self.right_force
            self.steer = 0.0
            self.drive_status = (
                "front_obstacle" if self.front_obstacle.blocked else "idle"
            )
        elif self.mode == MODE_MANUAL:
            if (not self.soft_stop_pending and
                    ticks_diff(now, self.last_manual_ms) > MANUAL_TIMEOUT_MS):
                # Dead-man expiry is a controlled stop, not a hardware fault.
                self.hardware_log.event(
                    "manual_timeout",
                    "age={}".format(ticks_diff(now, self.last_manual_ms)),
                    now,
                )
                self.soft_stop("timeout")

            if self.reverse_state == "braking":
                left_target = 0.0
                right_target = 0.0
                self.drive_status = "reverse_braking"
                if self.motors_stopped():
                    self.reverse_state = "neutral"
                    self.reverse_neutral_started_ms = now
                    self.drive_status = "reverse_neutral"
            elif self.reverse_state == "neutral":
                left_target = 0.0
                right_target = 0.0
                self.drive_status = "reverse_neutral"
                if ticks_diff(now, self.reverse_neutral_started_ms) >= REVERSE_NEUTRAL_MS:
                    self.manual_left = self.pending_manual_left
                    self.manual_right = self.pending_manual_right
                    self.pending_manual_left = 0.0
                    self.pending_manual_right = 0.0
                    self.reverse_state = ""
                    left_target = self.manual_left
                    right_target = self.manual_right
                    self.drive_status = "active"
            elif self.soft_stop_pending:
                left_target = 0.0
                right_target = 0.0
                self.drive_status = self.soft_stop_reason
            else:
                left_target = self.manual_left
                right_target = self.manual_right
                self.drive_status = "active"
            self.total = self.left_force + self.right_force
            self.steer = 0.0
        else:
            # Tow-rope mode calibrates its own unloaded baseline on every entry.
            calibrating = ticks_diff(now, self.tow_baseline_started_ms) < TOW_BASELINE_CAPTURE_MS
            if calibrating:
                self.tow_left_baseline = min(self.tow_left_baseline, self.left_force)
                self.tow_right_baseline = min(self.tow_right_baseline, self.right_force)
                self.tow_left_force = 0.0
                self.tow_right_force = 0.0
                self.total = 0.0
                self.steer = 0.0
                self.drive_status = "calibrating"
                left_target = 0.0
                right_target = 0.0
            elif not self.tow_armed:
                self.update_tow_forces()
                self.total = self.tow_left_force + self.tow_right_force
                # Wait for both force sensors to drop below the release
                # threshold (rope slack) before arming the drive.
                if (self.tow_left_force < TOW_RELEASE_THRESHOLD_RAW and
                        self.tow_right_force < TOW_RELEASE_THRESHOLD_RAW):
                    self.tow_armed = True
                    self.drive_status = "armed"
                else:
                    self.drive_status = "waiting_release"
                left_target = 0.0
                right_target = 0.0
                self.steer = 0.0
            else:
                self.update_tow_forces()
                self.total = self.tow_left_force + self.tow_right_force
                if (self.total >= PULL_START_RAW and
                        self.tow_left_force >= TOW_MIN_VALID_PULL_RAW and
                        self.tow_right_force >= TOW_MIN_VALID_PULL_RAW):
                    left_target, right_target, _, self.steer = compute_targets(
                        self.tow_left_force, self.tow_right_force
                    )
                    self.drive_status = "active"
                else:
                    left_target = 0.0
                    right_target = 0.0
                    self.steer = 0.0
                    self.drive_status = "idle"

        left_target *= LEFT_MOTOR_GAIN
        right_target *= RIGHT_MOTOR_GAIN
        if self.front_blocks_targets(left_target, right_target):
            self.front_obstacle_stop()
            self.observe_hardware(now)
            return

        self.left_motor.ramp_to(left_target, now)
        self.right_motor.ramp_to(right_target, now)

        if self.mode == MODE_MANUAL and self.soft_stop_pending and self.motors_stopped():
            self.mode = MODE_IDLE
            self.drive_status = self.soft_stop_reason

        self.observe_hardware(now)

    def status_line(self):
        return (
            "stat mode={} sensor={} err={} lraw={:.0f} rraw={:.0f} l={:.0f} r={:.0f} "
            "ltow={:.0f} rtow={:.0f} lbase={:.0f} rbase={:.0f} "
            "total={:.0f} steer={:.2f} targetl={:.2f} targetr={:.2f} "
            "pwml={:.2f} pwmr={:.2f} age_ms={} estop={} front={} front_signal={} "
            "bt={} unsafe={} drive={} loop_ms={}"
        ).format(
            self.mode,
            "ok" if self.sensor_ok else "bad",
            self.sensor_error or "-",
            self.left_filtered,
            self.right_filtered,
            self.left_force,
            self.right_force,
            self.tow_left_force,
            self.tow_right_force,
            self.tow_left_baseline,
            self.tow_right_baseline,
            self.total,
            self.steer,
            self.manual_left,
            self.manual_right,
            self.left_motor.current,
            self.right_motor.current,
            max(0, ticks_diff(ticks_ms(), self.last_manual_ms)) if self.mode == MODE_MANUAL else 0,
            self.estop.value(),
            bool_text(self.front_obstacle.blocked),
            bool_text(self.front_obstacle.signal_active()),
            self.ble.connected(),
            self.unsafe_reason or "-",
            self.drive_status,
            self.last_loop_ms,
        )

    def param_line(self):
        return (
            "param max_pwm={:.2f} min_pwm={:.2f} start_raw={} full_raw={} "
            "steer_gain={:.2f} ramp={:.3f} decel_ramp={:.3f} manual_max={:.2f} "
            "timeout_ms={} reverse_neutral_ms={} "
            "left_motor_gain={:.2f} right_motor_gain={:.2f} "
            "left_force_gain={:.2f} right_force_gain={:.2f} "
            "tow_left_comp={} tow_right_comp={} "
            "led_force_min={} led_force_full={}"
        ).format(
            MAX_PWM,
            MIN_MOVE_PWM,
            PULL_START_RAW,
            PULL_FULL_RAW,
            STEER_GAIN,
            RAMP_STEP,
            DECEL_RAMP_STEP,
            MANUAL_MAX_PWM,
            MANUAL_TIMEOUT_MS,
            REVERSE_NEUTRAL_MS,
            LEFT_MOTOR_GAIN,
            RIGHT_MOTOR_GAIN,
            LEFT_FORCE_GAIN,
            RIGHT_FORCE_GAIN,
            TOW_LEFT_COMP_RAW,
            TOW_RIGHT_COMP_RAW,
            LED_FORCE_MIN_RAW,
            LED_FORCE_FULL_RAW,
        )

    def info_line(self):
        return (
            "info proto={} uart=UART{} tx=GP{} rx=GP{} state=GP{} "
            "right_inb=GP{} front=GP{} front_active={} tow_start={}"
        ).format(
            PROTOCOL_VERSION,
            BLE_UART_ID,
            BLE_UART_TX,
            BLE_UART_RX,
            BLE_STATE_PIN,
            RIGHT_MOTOR_INB,
            FRONT_OBSTACLE_PIN,
            "low" if FRONT_OBSTACLE_ACTIVE_LOW else "high",
            bool_text(AUTO_MODE_ON_START),
        )


class CommandInterface:
    def __init__(self, controller, ble, status_led):
        self.controller = controller
        self.ble = ble
        self.status_led = status_led
        self.stream = BLE_STREAM_DEFAULT

    def reply(self, text, force=False):
        print("ble_reply={}".format(text))
        self.ble.write(text, force=force)

    def send_hello(self):
        self.reply(self.controller.info_line())
        self.reply(self.controller.param_line())
        self.reply(self.controller.status_line())

    def help(self):
        self.reply(
            "help cmd: ver info pins status param stream on|off hwlog dump|clear|status identify [S] tow auto manual idle stop softstop keepalive tare drive L R f [P] b [P] l [P] r [P] motor SIDE DIR [P] [MS] set NAME VALUE"
        )
        self.reply(
            "help set: max_pwm min_pwm start_raw full_raw steer_gain ramp decel_ramp manual_max timeout_ms reverse_neutral_ms left_motor_gain right_motor_gain left_force_gain right_force_gain tow_left_comp tow_right_comp"
        )

    def pins_line(self):
        return (
            "pins left_pwm=GP{} left_ina=GP{} left_inb=GP{} "
            "right_pwm=GP{} right_ina=GP{} right_inb=GP{} estop=GP{} "
            "front=GP{} ble_state=GP{}"
        ).format(
            LEFT_MOTOR_PWM,
            LEFT_MOTOR_INA,
            LEFT_MOTOR_INB,
            RIGHT_MOTOR_PWM,
            RIGHT_MOTOR_INA,
            RIGHT_MOTOR_INB,
            ESTOP_PIN,
            FRONT_OBSTACLE_PIN,
            BLE_STATE_PIN,
        )

    def handle_motor_test(self, parts):
        if len(parts) < 3:
            self.reply("err usage: motor SIDE DIR [POWER] [MS]")
            return

        side = parts[1].lower()
        direction = parts[2].lower()
        power = 0.18
        duration_ms = 1500

        if len(parts) >= 4:
            power = abs(parse_power(parts[3]))
        if len(parts) >= 5:
            duration_ms = int(clamp(float(parts[4]), 100, 5000))

        power = clamp(power, 0.0, MANUAL_MAX_PWM)
        if direction in ("f", "forward", "+", "cw"):
            signed = power
        elif direction in ("b", "r", "reverse", "-", "ccw"):
            signed = -power
        elif direction in ("stop", "s", "0"):
            signed = 0.0
        else:
            self.reply("err motor_dir")
            return

        if side in ("left", "l", "1"):
            left_target = signed
            right_target = 0.0
        elif side in ("right", "r", "2"):
            left_target = 0.0
            right_target = signed
        elif side in ("both", "all", "a"):
            left_target = signed
            right_target = signed
        else:
            self.reply("err motor_side")
            return

        self.controller.stop(MODE_IDLE)
        self.status_led.identify(duration_ms)
        self.reply(
            "ok motor side={} dir={} power={:.2f} ms={}".format(
                side, direction, power, duration_ms
            )
        )

        start = ticks_ms()
        while ticks_diff(ticks_ms(), start) < duration_ms:
            self.controller.front_obstacle.update(ticks_ms())
            if self.controller.estop.value() == 0:
                self.controller.left_motor.stop()
                self.controller.right_motor.stop()
                self.reply("err estop")
                return
            if self.controller.front_blocks_targets(left_target, right_target):
                self.controller.left_motor.stop()
                self.controller.right_motor.stop()
                self.reply("err front_obstacle")
                return
            self.controller.left_motor.ramp_to(left_target)
            self.controller.right_motor.ramp_to(right_target)
            self.status_led.update(ticks_ms())
            sleep_ms(LOOP_MS)

        self.controller.left_motor.stop()
        self.controller.right_motor.stop()
        self.reply("ok motor_done")

    def handle_set(self, parts):
        global MAX_PWM, MIN_MOVE_PWM, PULL_START_RAW, PULL_FULL_RAW
        global STEER_GAIN, RAMP_STEP, DECEL_RAMP_STEP, MANUAL_MAX_PWM
        global MANUAL_TIMEOUT_MS, REVERSE_NEUTRAL_MS
        global LEFT_MOTOR_GAIN, RIGHT_MOTOR_GAIN, LEFT_FORCE_GAIN, RIGHT_FORCE_GAIN
        global TOW_LEFT_COMP_RAW, TOW_RIGHT_COMP_RAW

        if len(parts) != 3:
            self.reply("err usage: set NAME VALUE")
            return

        name = parts[1].lower()
        try:
            value = float(parts[2])
        except ValueError:
            self.reply("err bad_value")
            return

        stored = value
        if name == "max_pwm":
            MAX_PWM = clamp(value, 0.05, 0.80)
            stored = MAX_PWM
        elif name == "min_pwm":
            MIN_MOVE_PWM = clamp(value, 0.0, MAX_PWM)
            stored = MIN_MOVE_PWM
        elif name == "start_raw":
            PULL_START_RAW = int(max(0, value))
            stored = PULL_START_RAW
        elif name == "full_raw":
            PULL_FULL_RAW = int(max(PULL_START_RAW + 1, value))
            stored = PULL_FULL_RAW
        elif name == "steer_gain":
            STEER_GAIN = clamp(value, 0.0, 2.0)
            stored = STEER_GAIN
        elif name == "ramp":
            RAMP_STEP = clamp(value, 0.001, 0.08)
            stored = RAMP_STEP
        elif name == "decel_ramp":
            DECEL_RAMP_STEP = clamp(value, 0.001, 0.10)
            stored = DECEL_RAMP_STEP
        elif name == "manual_max":
            MANUAL_MAX_PWM = clamp(value, 0.05, MAX_PWM)
            stored = MANUAL_MAX_PWM
        elif name == "timeout_ms":
            MANUAL_TIMEOUT_MS = int(clamp(value, 500, 5000))
            stored = MANUAL_TIMEOUT_MS
        elif name == "reverse_neutral_ms":
            REVERSE_NEUTRAL_MS = int(clamp(value, 50, 1000))
            stored = REVERSE_NEUTRAL_MS
        elif name == "left_motor_gain":
            LEFT_MOTOR_GAIN = clamp(value, 0.50, 1.20)
            stored = LEFT_MOTOR_GAIN
        elif name == "right_motor_gain":
            RIGHT_MOTOR_GAIN = clamp(value, 0.50, 1.20)
            stored = RIGHT_MOTOR_GAIN
        elif name == "left_force_gain":
            LEFT_FORCE_GAIN = clamp(value, 0.20, 3.00)
            stored = LEFT_FORCE_GAIN
        elif name == "right_force_gain":
            RIGHT_FORCE_GAIN = clamp(value, 0.20, 3.00)
            stored = RIGHT_FORCE_GAIN
        elif name == "tow_left_comp":
            TOW_LEFT_COMP_RAW = int(clamp(value, 0, MAX_SAFE_RAW))
            stored = TOW_LEFT_COMP_RAW
        elif name == "tow_right_comp":
            TOW_RIGHT_COMP_RAW = int(clamp(value, 0, MAX_SAFE_RAW))
            stored = TOW_RIGHT_COMP_RAW
        else:
            self.reply("err unknown_set_name")
            return

        self.reply("ok set {}={}".format(name, stored))
        self.reply(self.controller.param_line())

    def drive_shortcut(self, command, parts):
        power = 0.16
        if len(parts) > 1:
            power = abs(parse_power(parts[1]))
        power = clamp(power, 0.0, MANUAL_MAX_PWM)

        if command == "f":
            left = power
            right = power
        elif command == "b":
            left = -power
            right = -power
        elif command == "l":
            # Turn physical cart left: drive left, slow/reverse right.
            left = power
            right = -power
        else:
            # command == "r": turn physical cart right.
            left = -power
            right = power

        if self.controller.set_manual_drive(left, right):
            self.reply("ok drive left={:.2f} right={:.2f}".format(left, right))
        else:
            self.reply("err front_obstacle")

    def handle(self, line):
        print("ble_cmd={}".format(line))
        self.status_led.pulse()
        parts = line.strip().split()
        if not parts:
            return

        command = parts[0].lower()
        if command not in ("keepalive", "status"):
            self.controller.hardware_log.event("command", "name={}".format(command))

        try:
            if command == "help" or command == "?":
                self.help()
            elif command == "ver" or command == "info":
                self.reply(self.controller.info_line())
            elif command == "pins":
                self.reply(self.pins_line())
            elif command == "ping":
                self.reply("ok pong")
            elif command == "status":
                self.reply(self.controller.status_line())
            elif command == "param" or command == "params":
                self.reply(self.controller.param_line())
            elif command == "identify" or command == "id" or command == "led":
                seconds = 5.0
                if len(parts) > 1:
                    seconds = clamp(float(parts[1]), 0.5, 20.0)
                self.status_led.identify(int(seconds * 1000))
                self.reply("ok identify seconds={:.1f}".format(seconds))
            elif command == "stream":
                value = parts[1].lower() if len(parts) == 2 else ""
                if value in ("on", "1", "true"):
                    self.stream = True
                    self.reply("ok stream=on")
                elif value in ("off", "0", "false"):
                    self.stream = False
                    self.reply("ok stream=off")
                else:
                    self.reply("err usage: stream on|off")
            elif command == "hwlog" or command == "diag":
                action = parts[1].lower() if len(parts) == 2 else ""
                if action == "dump" or action == "export":
                    total = self.controller.hardware_log.begin_export()
                    self.reply("ok hwlog_export n={}".format(total))
                elif action == "clear":
                    self.controller.hardware_log.clear()
                    self.reply("ok hwlog_clear")
                elif action == "status":
                    self.reply(
                        "ok hwlog_status n={} exporting={}".format(
                            self.controller.hardware_log.count,
                            bool_text(
                                self.controller.hardware_log.export_records is not None
                            ),
                        )
                    )
                else:
                    self.reply("err usage: hwlog dump|clear|status")
            elif command == "auto" or command == "tow":
                if not self.controller.sensor_ok:
                    self.controller.stop(MODE_IDLE)
                    self.reply("err sensor_not_ready")
                elif not self.controller.enter_tow():
                    self.reply("err front_obstacle")
                else:
                    self.reply("ok mode=tow")
            elif command == "manual" or command == "man":
                self.controller.enter_manual()
                self.reply("ok mode=manual")
            elif command == "idle":
                self.controller.stop(MODE_IDLE)
                self.reply("ok mode=idle")
            elif command == "stop" or command == "s":
                self.controller.stop(MODE_IDLE)
                self.reply("ok stop")
            elif command == "softstop":
                if self.controller.mode == MODE_MANUAL:
                    self.controller.soft_stop()
                else:
                    self.controller.stop(MODE_IDLE)
                self.reply("ok softstop")
            elif command == "keepalive":
                # Intentionally no success reply: this high-rate lease refresh must
                # not compete with status notifications on the UART bridge.
                if not self.controller.refresh_manual_lease():
                    self.reply("err keepalive_inactive")
            elif command == "tare":
                ok = self.controller.tare_sensors()
                self.controller.stop(MODE_IDLE)
                self.reply("ok tare" if ok else "err {}".format(self.controller.sensor_error))
            elif command == "drive":
                if not ALLOW_BLE_MANUAL_DRIVE:
                    self.reply("err manual_disabled")
                elif len(parts) != 3:
                    self.reply("err usage: drive LEFT RIGHT")
                else:
                    left = parse_power(parts[1])
                    right = parse_power(parts[2])
                    left = clamp(left, -MANUAL_MAX_PWM, MANUAL_MAX_PWM)
                    right = clamp(right, -MANUAL_MAX_PWM, MANUAL_MAX_PWM)
                    if self.controller.set_manual_drive(left, right):
                        self.reply("ok drive left={:.2f} right={:.2f}".format(left, right))
                    else:
                        self.reply("err front_obstacle")
            elif command in ("f", "b", "l", "r"):
                if not ALLOW_BLE_MANUAL_DRIVE:
                    self.reply("err manual_disabled")
                else:
                    self.drive_shortcut(command, parts)
            elif command == "motor" or command == "motortest":
                self.handle_motor_test(parts)
            elif command == "set":
                self.handle_set(parts)
            else:
                self.reply("err unknown_cmd")
        except Exception as err:
            self.reply("err {}".format(err))


def run_motor_test(left_motor, right_motor, front_obstacle, led_mode):
    print("motor_test_mode=on")
    print("Keep both wheels lifted before testing.")
    sequence = (
        ("left_forward", MOTOR_TEST_PWM, 0.0),
        ("left_reverse", -MOTOR_TEST_PWM, 0.0),
        ("right_forward", 0.0, MOTOR_TEST_PWM),
        ("right_reverse", 0.0, -MOTOR_TEST_PWM),
        ("both_forward", MOTOR_TEST_PWM, MOTOR_TEST_PWM),
        ("both_reverse", -MOTOR_TEST_PWM, -MOTOR_TEST_PWM),
        ("stop", 0.0, 0.0),
    )

    while True:
        for name, left_target, right_target in sequence:
            print("motor_test={}".format(name))
            led_mode.pulse(MOTOR_TEST_STEP_MS)
            start = ticks_ms()
            while ticks_diff(ticks_ms(), start) < MOTOR_TEST_STEP_MS:
                now = ticks_ms()
                front_obstacle.update(now)
                if (front_obstacle.blocked and
                        (left_target > MOTOR_ZERO_EPSILON or
                         right_target > MOTOR_ZERO_EPSILON)):
                    left_motor.stop()
                    right_motor.stop()
                    print("motor_test_blocked=front_obstacle")
                    break
                led_mode.update(now)
                left_motor.ramp_to(left_target)
                right_motor.ramp_to(right_target)
                sleep_ms(LOOP_MS)


def main():
    led = make_led()
    ble = BluetoothSerial()
    estop = Pin(ESTOP_PIN, Pin.IN, Pin.PULL_UP)
    hardware_log = HardwareLogger()
    front_obstacle = FrontObstacleSensor(
        Pin(FRONT_OBSTACLE_PIN, Pin.IN, Pin.PULL_UP)
    )

    left_motor = Motor(
        LEFT_MOTOR_PWM,
        LEFT_MOTOR_INA,
        LEFT_MOTOR_INB,
        LEFT_MOTOR_REVERSE,
    )
    right_motor = Motor(
        RIGHT_MOTOR_PWM,
        RIGHT_MOTOR_INA,
        RIGHT_MOTOR_INB,
        RIGHT_MOTOR_REVERSE,
    )

    print("Pico BLE traction cart controller")
    print(
        "ble_uart=UART{} tx=GP{} rx=GP{} baud={} state=GP{}".format(
            BLE_UART_ID, BLE_UART_TX, BLE_UART_RX, BLE_UART_BAUD, BLE_STATE_PIN
        )
    )
    print("right_motor_inb=GP{} gp15_reserved_for_ble_state".format(RIGHT_MOTOR_INB))
    print(
        "front_obstacle=GP{} active={} clear_ms={}".format(
            FRONT_OBSTACLE_PIN,
            "low" if FRONT_OBSTACLE_ACTIVE_LOW else "high",
            FRONT_OBSTACLE_CLEAR_MS,
        )
    )

    led_mode = StatusLed(led, ble)

    if MOTOR_TEST_MODE:
        run_motor_test(left_motor, right_motor, front_obstacle, led_mode)

    left_hx = HX711(LEFT_HX711_DOUT, LEFT_HX711_SCK)
    right_hx = HX711(RIGHT_HX711_DOUT, RIGHT_HX711_SCK)
    controller = CartController(
        left_hx, right_hx, left_motor, right_motor, estop, front_obstacle, ble,
        hardware_log
    )
    front_obstacle.attach_stop_callback(controller.front_obstacle_stop)
    led_mode.set_controller(controller)
    commands = CommandInterface(controller, ble, led_mode)

    print("Keep both load cells unloaded. Taring...")
    led_mode.pulse(2000)
    controller.tare_sensors()
    controller.stop(MODE_IDLE, "boot_ready")
    hardware_log.event("boot", "proto={}".format(PROTOCOL_VERSION))
    print(
        "ble_commands: help pins status identify stream on|off tow auto manual stop softstop keepalive tare drive L R f b l r motor set"
    )

    last_debug = ticks_ms()
    last_ble_stream = ticks_ms()

    while True:
        loop_started_ms = ticks_ms()
        now = ticks_ms()
        led_mode.update(now)

        connection_event = ble.connection_event()
        if connection_event == 1:
            print("ble_connected=1")
            hardware_log.event("ble_connect")
            if controller.mode == MODE_AUTO:
                controller.stop(MODE_IDLE, "ble_connect_auto_reset")
            commands.send_hello()
        elif connection_event == 0:
            print("ble_connected=0")
            hardware_log.event("ble_disconnect")
            if controller.mode == MODE_MANUAL or controller.mode == MODE_AUTO:
                controller.stop(MODE_IDLE, "ble_disconnect")

        for line in ble.poll_lines():
            commands.handle(line)

        controller.update(now)

        now = ticks_ms()
        controller.last_loop_ms = max(0, ticks_diff(now, loop_started_ms))
        hardware_log.record_loop_time(controller.last_loop_ms, now)
        led_mode.update(now)

        if ticks_diff(now, last_debug) >= DEBUG_EVERY_MS:
            last_debug = now
            print(controller.status_line())

        if commands.stream and ticks_diff(now, last_ble_stream) >= BLE_STREAM_EVERY_MS:
            last_ble_stream = now
            ble.write(controller.status_line())

        if ble.connected() == 1:
            hardware_line = hardware_log.next_export_line()
            if hardware_line is not None:
                ble.write(hardware_line)

        sleep_ms(LOOP_MS)


try:
    main()
except KeyboardInterrupt:
    print("stopped")
