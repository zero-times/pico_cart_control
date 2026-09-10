from machine import Pin, PWM, UART
from time import sleep_ms, ticks_ms, ticks_diff, ticks_add
import os
import sys

try:
    import select
except ImportError:
    import uselect as select

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
# Ignore STATE pin glitches; a real drop still stops well before timeout_ms.
BLE_STATE_DEBOUNCE_MS = 80
PROTOCOL_VERSION = "pico-cart-ble-2026-09-10-cal"
FIRMWARE_VERSION = "0.2.2"

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
DEBUG_EVERY_MS = 1000

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
CALIBRATION_PATH = "pico_cart_cal.cfg"
CALIBRATION_TEMP_PATH = "pico_cart_cal.cfg.tmp"
CALIBRATION_FORMAT = 1
HARDWARE_LOG_SNAPSHOT_MS = 250
HARDWARE_LOG_OVERRUN_MS = 100
HARDWARE_LOG_OVERRUN_REPEAT_MS = 1000
HARDWARE_LOG_MAX_RECORD_CHARS = 288
DIAGNOSTIC_TX_CHUNK = 96
DIAGNOSTIC_QUEUE_CAPACITY = 8

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


MOTOR_GAIN_MIN = 0.50
MOTOR_GAIN_MAX = 1.20
FORCE_GAIN_MIN = 0.20
FORCE_GAIN_MAX = 3.00
CALIBRATION_GROUPS = {
    "motor": ("left_motor_gain", "right_motor_gain"),
    "force": ("left_force_gain", "right_force_gain", "start_raw", "full_raw", "tow_left_comp", "tow_right_comp"),
}
CALIBRATION_FIELDS = (
    CALIBRATION_GROUPS["motor"] + CALIBRATION_GROUPS["force"]
)


def default_calibration_values():
    return {
        "left_motor_gain": 1.0,
        "right_motor_gain": 1.0,
        "left_force_gain": 1.0,
        "right_force_gain": 1.0,
        "start_raw": 25000,
        "full_raw": 180000,
        "tow_left_comp": 0,
        "tow_right_comp": 0,
    }


def current_calibration_values():
    return {
        "left_motor_gain": LEFT_MOTOR_GAIN,
        "right_motor_gain": RIGHT_MOTOR_GAIN,
        "left_force_gain": LEFT_FORCE_GAIN,
        "right_force_gain": RIGHT_FORCE_GAIN,
        "start_raw": PULL_START_RAW,
        "full_raw": PULL_FULL_RAW,
        "tow_left_comp": TOW_LEFT_COMP_RAW,
        "tow_right_comp": TOW_RIGHT_COMP_RAW,
    }


def format_calibration_value(name, value):
    if name in ("start_raw", "full_raw", "tow_left_comp", "tow_right_comp"):
        return str(int(value))
    if name in ("left_motor_gain", "right_motor_gain", "left_force_gain", "right_force_gain"):
        return "{:.2f}".format(value)
    return str(value)


def validate_calibration_value(name, value):
    try:
        if name in ("left_motor_gain", "right_motor_gain"):
            return clamp(float(value), MOTOR_GAIN_MIN, MOTOR_GAIN_MAX)
        if name in ("left_force_gain", "right_force_gain"):
            return clamp(float(value), FORCE_GAIN_MIN, FORCE_GAIN_MAX)
        if name == "start_raw":
            return int(max(0, float(value)))
        if name == "full_raw":
            return int(max(1, float(value)))
        if name in ("tow_left_comp", "tow_right_comp"):
            return int(clamp(float(value), 0, MAX_SAFE_RAW))
    except (TypeError, ValueError):
        raise ValueError("bad_value")
    raise ValueError("unknown_field")


def apply_calibration_values(values, source="runtime"):
    global LEFT_MOTOR_GAIN, RIGHT_MOTOR_GAIN, LEFT_FORCE_GAIN, RIGHT_FORCE_GAIN
    global PULL_START_RAW, PULL_FULL_RAW, TOW_LEFT_COMP_RAW, TOW_RIGHT_COMP_RAW
    applied = {}
    if "left_motor_gain" in values:
        LEFT_MOTOR_GAIN = validate_calibration_value("left_motor_gain", values["left_motor_gain"])
        applied["left_motor_gain"] = LEFT_MOTOR_GAIN
    if "right_motor_gain" in values:
        RIGHT_MOTOR_GAIN = validate_calibration_value("right_motor_gain", values["right_motor_gain"])
        applied["right_motor_gain"] = RIGHT_MOTOR_GAIN
    if "left_force_gain" in values:
        LEFT_FORCE_GAIN = validate_calibration_value("left_force_gain", values["left_force_gain"])
        applied["left_force_gain"] = LEFT_FORCE_GAIN
    if "right_force_gain" in values:
        RIGHT_FORCE_GAIN = validate_calibration_value("right_force_gain", values["right_force_gain"])
        applied["right_force_gain"] = RIGHT_FORCE_GAIN
    start_raw = validate_calibration_value("start_raw", values.get("start_raw", PULL_START_RAW))
    full_raw = validate_calibration_value("full_raw", values.get("full_raw", PULL_FULL_RAW))
    if full_raw <= start_raw:
        if source == "file":
            raise ValueError("threshold_order")
        full_raw = start_raw + 1
    if "start_raw" in values or "full_raw" in values:
        PULL_START_RAW = start_raw
        PULL_FULL_RAW = full_raw
        applied["start_raw"] = PULL_START_RAW
        applied["full_raw"] = PULL_FULL_RAW
    if "tow_left_comp" in values:
        TOW_LEFT_COMP_RAW = validate_calibration_value("tow_left_comp", values["tow_left_comp"])
        applied["tow_left_comp"] = TOW_LEFT_COMP_RAW
    if "tow_right_comp" in values:
        TOW_RIGHT_COMP_RAW = validate_calibration_value("tow_right_comp", values["tow_right_comp"])
        applied["tow_right_comp"] = TOW_RIGHT_COMP_RAW
    return applied


class CalibrationStore:
    def __init__(self, path=CALIBRATION_PATH, temp_path=CALIBRATION_TEMP_PATH):
        self.path = path
        self.temp_path = temp_path
        self.loaded = False
        self.last_error = ""
        self.saved = dict(default_calibration_values())

    def encode(self, values):
        lines = ["fmt={}".format(CALIBRATION_FORMAT), "fw={}".format(FIRMWARE_VERSION)]
        for name in CALIBRATION_FIELDS:
            lines.append("{}={}".format(name, format_calibration_value(name, values[name])))
        return "\n".join(lines) + "\n"

    def parse(self, text):
        values = {}
        fmt = None
        for raw in text.replace("\r", "\n").split("\n"):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                raise ValueError("bad_line")
            name, value = line.split("=", 1)
            name = name.strip().lower()
            value = value.strip()
            if name == "fmt":
                fmt = int(value)
                continue
            if name == "fw":
                continue
            if name not in CALIBRATION_FIELDS:
                continue
            values[name] = validate_calibration_value(name, value)
        if fmt != CALIBRATION_FORMAT:
            raise ValueError("bad_format")
        for name in CALIBRATION_FIELDS:
            if name not in values:
                raise ValueError("missing_" + name)
        if values["full_raw"] <= values["start_raw"]:
            raise ValueError("threshold_order")
        return values

    def load(self):
        try:
            with open(self.path, "r") as handle:
                text = handle.read()
        except OSError:
            self.last_error = "missing"
            self.loaded = False
            return None
        try:
            values = self.parse(text)
        except Exception as err:
            self.last_error = str(err) or "corrupt"
            self.loaded = False
            return None
        self.saved = values
        self.loaded = True
        self.last_error = ""
        return values

    def save(self, values):
        text = self.encode(values)
        try:
            with open(self.temp_path, "w") as handle:
                handle.write(text)
                handle.flush()
            os.rename(self.temp_path, self.path)
        except OSError as err:
            try:
                os.remove(self.temp_path)
            except OSError:
                pass
            raise OSError(str(err) or "write_failed")
        self.saved = dict(values)
        self.loaded = True
        self.last_error = ""
        return self.saved

    def status_line(self):
        dirty = 0
        current = current_calibration_values()
        for name in CALIBRATION_FIELDS:
            if abs(float(current[name]) - float(self.saved[name])) > 0.0005:
                dirty = 1
        return (
            "cal fmt={} loaded={} dirty={} err={} "
            "left_motor_gain={} right_motor_gain={} "
            "saved_left_motor_gain={} saved_right_motor_gain={}"
        ).format(
            CALIBRATION_FORMAT,
            "1" if self.loaded else "0",
            dirty,
            self.last_error or "-",
            format_calibration_value("left_motor_gain", current["left_motor_gain"]),
            format_calibration_value("right_motor_gain", current["right_motor_gain"]),
            format_calibration_value("left_motor_gain", self.saved["left_motor_gain"]),
            format_calibration_value("right_motor_gain", self.saved["right_motor_gain"]),
        )


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
        self.raw_connected = -1
        self.raw_changed_ms = ticks_ms()
        self.hardware_log = None
        self.tx_queue = []
        self.tx_offset = 0
        self.last_error_ms = None
        self.discard_line = False

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
            self.raw_connected = self.connected()
            self.last_connected = self.raw_connected
            self.raw_changed_ms = ticks_ms()
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
        if now != self.raw_connected:
            self.raw_connected = now
            self.raw_changed_ms = ticks_ms()
        if now == self.last_connected:
            return None
        if now == -1:
            self.last_connected = now
            return now
        if ticks_diff(ticks_ms(), self.raw_changed_ms) < BLE_STATE_DEBOUNCE_MS:
            return None
        self.last_connected = now
        return now

    def record_error(self, event, detail):
        now = ticks_ms()
        if self.hardware_log is not None and (
                self.last_error_ms is None or
                ticks_diff(now, self.last_error_ms) >= 1000):
            self.last_error_ms = now
            self.hardware_log.event(event, "detail={}".format(str(detail).replace(" ", "_")))

    def reset_connection_buffers(self):
        self.buffer = ""
        self.discard_line = False
        self.tx_queue = []
        self.tx_offset = 0

    def write(self, text, force=False):
        if self.uart is None:
            return False
        if not force and self.state is not None and self.connected() == 0:
            return False
        if len(self.tx_queue) >= DIAGNOSTIC_QUEUE_CAPACITY:
            self.record_error("uart_tx_full", "queue")
            return False
        if not text.endswith("\n"):
            text += "\n"
        self.tx_queue.append(text.encode("utf-8"))
        return True

    def flush(self):
        # Bound serial work per control-loop iteration. Keep partial writes on
        # the same line so replies cannot be interleaved with an exported row.
        if not self.tx_queue or self.uart is None:
            return
        try:
            data = self.tx_queue[0]
            chunk = data[self.tx_offset:self.tx_offset + DIAGNOSTIC_TX_CHUNK]
            written = self.uart.write(chunk) or 0
            if written < 0 or written > len(chunk):
                raise OSError("invalid_write_count")
            self.tx_offset += written
            if written < len(chunk):
                self.record_error("uart_write_short", "n={}".format(written))
            if self.tx_offset == len(data):
                self.tx_queue.pop(0)
                self.tx_offset = 0
        except Exception as err:
            self.record_error("uart_write_error", err)
            self.tx_queue = []
            self.tx_offset = 0

    def poll_lines(self):
        lines = []
        if self.uart is None:
            return lines

        try:
            count = self.uart.any()
            if not count:
                return lines
            data = self.uart.read(min(count, 256))
        except Exception as err:
            self.record_error("uart_read_error", err)
            return lines

        if not data:
            return lines

        try:
            chunk = data.decode("utf-8")
        except Exception:
            self.record_error("uart_decode_error", "utf8")
            chunk = ""

        for ch in chunk:
            if ch == "\r" or ch == "\n":
                line = self.buffer.strip()
                self.buffer = ""
                if line and not self.discard_line:
                    lines.append(line)
                self.discard_line = False
            elif 32 <= ord(ch) <= 126 and not self.discard_line:
                self.buffer += ch
                if len(self.buffer) > BLE_LINE_MAX:
                    self.buffer = ""
                    self.discard_line = True
                    self.record_error("uart_rx_long", "discard_line")

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


class DiagnosticClock:
    """Wall-clock labels never change the ticks used by motor safety timers."""

    def __init__(self):
        self.last_tick = ticks_ms()
        self.elapsed = 0
        self.anchor_uptime = 0
        self.anchor_unix = None

    def uptime_ms(self, now=None):
        now = ticks_ms() if now is None else now
        delta = ticks_diff(now, self.last_tick)
        # Soft IRQ events can arrive between reads of the loop's timestamp.
        if delta >= 0:
            self.elapsed += delta
            self.last_tick = now
        return self.elapsed

    def unix_ms(self, uptime=None):
        uptime = self.uptime_ms() if uptime is None else uptime
        if self.anchor_unix is None:
            return 0
        return self.anchor_unix + uptime - self.anchor_uptime

    def sync(self, unix_ms):
        # Unix milliseconds (2020..2100), independent of MicroPython's epoch.
        if not 1577836800000 <= unix_ms <= 4102444800000:
            raise ValueError("time_range")
        self.anchor_uptime = self.uptime_ms()
        self.anchor_unix = unix_ms

    def status_line(self):
        uptime = self.uptime_ms()
        return "time synced={} unix_ms={} uptime_ms={} fw={}".format(
            bool_text(self.anchor_unix is not None), self.unix_ms(uptime),
            uptime, FIRMWARE_VERSION)


class UsbDiagnostics:
    """Bounded, polled USB CDC I/O; no input waits or per-event prints."""

    def __init__(self, stdin=None, stdout=None):
        self.stdin = sys.stdin if stdin is None else stdin
        self.stdout = sys.stdout if stdout is None else stdout
        self.rx = ""
        self.discard_line = False
        self.queue = []
        self.offset = 0
        self.dropped = 0
        self.input_poll = None
        self.output_poll = None
        try:
            self.input_poll = select.poll()
            self.input_poll.register(self.stdin, select.POLLIN)
            self.output_poll = select.poll()
            self.output_poll.register(self.stdout, select.POLLOUT)
        except (OSError, ValueError, TypeError, AttributeError):
            self.input_poll = None
            self.output_poll = None

    def write(self, line):
        if self.output_poll is None or len(self.queue) >= DIAGNOSTIC_QUEUE_CAPACITY:
            self.dropped += 1
            return False
        self.queue.append(line + "\n")
        return True

    def poll_lines(self):
        lines = []
        if self.input_poll is None:
            return lines
        for _ in range(64):
            if not any(event[1] & select.POLLIN for event in self.input_poll.poll(0)):
                break
            char = self.stdin.read(1)
            if not char:
                break
            if char in ("\n", "\r"):
                if self.rx.strip() and not self.discard_line:
                    lines.append(self.rx.strip())
                self.rx = ""
                self.discard_line = False
            elif 32 <= ord(char) <= 126 and not self.discard_line:
                self.rx += char
                if len(self.rx) > BLE_LINE_MAX:
                    self.rx = ""
                    self.discard_line = True
        return lines

    def flush(self):
        if self.output_poll is None:
            return
        if not self.queue and self.dropped:
            dropped = self.dropped
            self.dropped = 0
            self.write("usb_dropped n={} fw={}".format(dropped, FIRMWARE_VERSION))
        if not self.queue:
            return
        try:
            if not any(event[1] & select.POLLOUT for event in self.output_poll.poll(0)):
                return
            line = self.queue[0]
            chunk = line[self.offset:self.offset + DIAGNOSTIC_TX_CHUNK]
            written = self.stdout.write(chunk) or 0
            self.offset += written
            if self.offset >= len(line):
                self.queue.pop(0)
                self.offset = 0
        except (OSError, ValueError):
            # A detached USB host must not interrupt motor protection.
            self.dropped += len(self.queue)
            self.queue = []
            self.offset = 0


class HardwareLogger:
    def __init__(self):
        self.records = [None] * HARDWARE_LOG_CAPACITY
        self.head = 0
        self.count = 0
        self.sequence = 1
        self.clock = DiagnosticClock()
        self.usb = None
        self.overwritten = 0
        self.last_snapshot_ms = ticks_ms()
        self.last_state = ""
        self.last_overrun_ms = 0
        self.export_records = None
        self.export_index = 0
        self.export_id = 0
        self.export_last = 0
        self.export_channel = None

    def event(self, event, fields="", now=None):
        now = ticks_ms() if now is None else now
        uptime = self.clock.uptime_ms(now)
        record = "s={} t={} u={} fw={} e={}".format(
            self.sequence, uptime, self.clock.unix_ms(uptime), FIRMWARE_VERSION, event)
        if fields:
            record += " " + fields.replace("\r", "_").replace("\n", "_")
        record = record[:HARDWARE_LOG_MAX_RECORD_CHARS]
        if self.count == HARDWARE_LOG_CAPACITY:
            self.overwritten += 1
        self.records[self.head] = record
        self.head = (self.head + 1) % HARDWARE_LOG_CAPACITY
        self.count = min(self.count + 1, HARDWARE_LOG_CAPACITY)
        self.sequence += 1
        if self.usb is not None and event != "snap":
            self.usb.write("usb_log " + record)

    def observe(self, controller, now):
        self.clock.uptime_ms(now)
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

    def begin_export(self, channel="ble"):
        if self.export_records is not None:
            return None
        start = (self.head - self.count) % HARDWARE_LOG_CAPACITY
        self.export_records = [
            self.records[(start + index) % HARDWARE_LOG_CAPACITY]
            for index in range(self.count)
        ]
        self.export_index = 0
        self.export_id += 1
        self.export_last = self.sequence - 1
        self.export_channel = channel
        return len(self.export_records)

    def cancel_export(self):
        self.export_records = None
        self.export_index = 0
        self.export_channel = None

    def clear(self, through=None):
        if self.export_records is not None:
            raise ValueError("hwlog_busy")
        if through is None:
            through = self.sequence - 1
        if through < 0 or through >= self.sequence:
            raise ValueError("hwlog_sequence")
        # Records are consecutive in the ring. Drop only the exported prefix;
        # events generated during transfer must survive a confirmed cleanup.
        first = self.sequence - self.count
        remove = min(self.count, max(0, through - first + 1))
        start = (self.head - self.count) % HARDWARE_LOG_CAPACITY
        for index in range(remove):
            self.records[(start + index) % HARDWARE_LOG_CAPACITY] = None
        self.count -= remove
        self.event("hwlog_clear", "through={} removed={}".format(through, remove))
        return self.count

    def status_line(self):
        return (
            "ok hwlog_status n={} cap={} used_pct={} overwritten={} "
            "exporting={} fw={} synced={}"
        ).format(
            self.count, HARDWARE_LOG_CAPACITY,
            self.count * 100 // HARDWARE_LOG_CAPACITY, self.overwritten,
            bool_text(self.export_records is not None), FIRMWARE_VERSION,
            bool_text(self.clock.anchor_unix is not None))

    def export_start_line(self):
        return "ok hwlog_export n={} x={} last={} fw={}".format(
            len(self.export_records), self.export_id, self.export_last, FIRMWARE_VERSION)

    def next_export_line(self):
        if self.export_records is None:
            return None
        total = len(self.export_records)
        if self.export_index < total:
            self.export_index += 1
            record = self.export_records[self.export_index - 1]
            return "hwlog x={} i={} n={} {}".format(
                self.export_id, self.export_index, total, record)
        line = "hwlog_end n={} x={} last={} fw={}".format(
            total, self.export_id, self.export_last, FIRMWARE_VERSION)
        self.cancel_export()
        return line


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
        self.calibration = CalibrationStore()
        self.motor_test_until = 0
        self.motor_test_left = 0.0
        self.motor_test_right = 0.0
        self.motor_test_running = False

    def clear_motor_test(self):
        self.motor_test_until = 0
        self.motor_test_left = 0.0
        self.motor_test_right = 0.0
        self.motor_test_running = False

    def motor_test_active(self, now=None):
        if not self.motor_test_running:
            return False
        now = ticks_ms() if now is None else now
        return ticks_diff(self.motor_test_until, now) > 0

    def start_motor_test(self, left, right, duration_ms):
        left = clamp(left, -MANUAL_MAX_PWM, MANUAL_MAX_PWM)
        right = clamp(right, -MANUAL_MAX_PWM, MANUAL_MAX_PWM)
        if self.front_blocks_targets(left, right):
            self.front_obstacle_stop()
            self.hardware_log.event("drive_rejected", "reason=front_obstacle")
            return False
        now = ticks_ms()
        self.mode = MODE_MANUAL
        self.motor_test_left = left
        self.motor_test_right = right
        self.motor_test_until = ticks_add(now, duration_ms)
        self.motor_test_running = True
        self.manual_left = left
        self.manual_right = right
        self.pending_manual_left = 0.0
        self.pending_manual_right = 0.0
        self.reverse_state = ""
        self.soft_stop_pending = False
        self.soft_stop_reason = ""
        self.last_manual_ms = now
        self.drive_status = "motor_test"
        self.hardware_log.event(
            "motor_test",
            "l={:.2f} r={:.2f} ms={}".format(left, right, duration_ms),
        )
        return True

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
        self.clear_motor_test()
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
        self.clear_motor_test()
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
        self.clear_motor_test()
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
        self.clear_motor_test()
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
        self.clear_motor_test()
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
            self.clear_motor_test()
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
            if self.motor_test_running and not self.motor_test_active(now):
                self.clear_motor_test()
                self.stop(MODE_IDLE, "motor_test_done")
                self.observe_hardware(now)
                return
            if (not self.soft_stop_pending and not self.motor_test_until and
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
            if self.motor_test_running:
                left_target = self.motor_test_left
                right_target = self.motor_test_right
                self.drive_status = "motor_test"
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
            "led_force_min={} led_force_full={} cal_loaded={}"
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
            "1" if self.calibration.loaded else "0",
        )

    def info_line(self):
        return (
            "info proto={} fw={} uart=UART{} tx=GP{} rx=GP{} state=GP{} "
            "right_inb=GP{} front=GP{} front_active={} tow_start={}"
        ).format(
            PROTOCOL_VERSION,
            FIRMWARE_VERSION,
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
        self.last_command = ""
        self.last_command_ms = ticks_ms()

    def reply(self, text, force=False):
        if self.controller.hardware_log.usb is not None:
            self.controller.hardware_log.usb.write("ble_reply=" + text)
        self.ble.write(text, force=force)

    def send_hello(self):
        self.reply(self.controller.info_line())
        self.reply(self.controller.param_line())
        self.reply(self.controller.status_line())

    def handle_diagnostic(self, parts, reply, channel):
        command = parts[0].lower()
        log = self.controller.hardware_log
        if command == "time":
            if len(parts) == 2 and parts[1] == "status":
                reply(log.clock.status_line())
            elif len(parts) == 3 and parts[1] == "sync" and channel == "ble":
                requested = int(parts[2])
                log.clock.sync(requested)
                log.event("time_sync", "source=phone")
                reply(log.clock.status_line() + " request_ms={}".format(requested))
            else:
                reply("err time_usage")
            return True
        if command not in ("hwlog", "diag"):
            return False
        action = parts[1].lower() if len(parts) >= 2 else ""
        if action in ("dump", "export") and len(parts) == 2:
            total = log.begin_export(channel)
            if total is None:
                reply("err hwlog_busy")
            else:
                reply(log.export_start_line())
        elif action == "status" and len(parts) == 2:
            reply(log.status_line())
        elif action == "clear" and len(parts) in (2, 3) and channel == "ble":
            remaining = log.clear(int(parts[2]) if len(parts) == 3 else None)
            reply("ok hwlog_clear n={}".format(remaining))
        else:
            reply("err hwlog_usage")
        return True

    def handle_usb(self, line):
        usb = self.controller.hardware_log.usb
        parts = line.strip().split()
        if not parts or usb is None:
            return
        # USB is a developer diagnostic channel, never a second drive source.
        allowed = line.strip().lower() in (
            "info", "ver", "status", "param", "time status", "hwlog status", "hwlog dump", "cal status")
        if not allowed:
            usb.write("err usb_read_only")
            return
        try:
            if self.handle_diagnostic(parts, usb.write, "usb"):
                return
            command = parts[0].lower()
            if command == "status":
                usb.write(self.controller.status_line())
            elif command == "param":
                usb.write(self.controller.param_line())
            elif command == "cal":
                usb.write(self.controller.calibration.status_line())
            else:
                usb.write(self.controller.info_line())
        except Exception as err:
            usb.write("err {}".format(err))

    def help(self):
        self.reply(
            "help cmd: ver info pins status param cal status|save [motor|force] time sync MS|status stream on|off hwlog dump|clear [SEQ]|status identify [S] tow auto manual idle stop softstop keepalive tare drive L R f [P] b [P] l [P] r [P] motor SIDE DIR [P] [MS] set NAME VALUE"
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

        if not self.controller.start_motor_test(left_target, right_target, duration_ms):
            self.reply("err front_obstacle")
            return
        self.status_led.identify(duration_ms)
        self.reply(
            "ok motor side={} dir={} power={:.2f} ms={}".format(
                side, direction, power, duration_ms
            )
        )

    def handle_set(self, parts):
        global MAX_PWM, MIN_MOVE_PWM, STEER_GAIN, RAMP_STEP, DECEL_RAMP_STEP
        global MANUAL_MAX_PWM, MANUAL_TIMEOUT_MS, REVERSE_NEUTRAL_MS

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
        if name in CALIBRATION_FIELDS:
            try:
                applied = apply_calibration_values({name: value}, source="runtime")
            except ValueError as err:
                self.reply("err {}".format(err))
                return
            stored = applied[name]
            self.controller.hardware_log.event(
                "cal_set", "name={} value={}".format(name, format_calibration_value(name, stored))
            )
        elif name == "max_pwm":
            MAX_PWM = clamp(value, 0.05, 0.80)
            stored = MAX_PWM
        elif name == "min_pwm":
            MIN_MOVE_PWM = clamp(value, 0.0, MAX_PWM)
            stored = MIN_MOVE_PWM
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
        else:
            self.reply("err unknown_set_name")
            return

        self.reply("ok set {}={}".format(name, format_calibration_value(name, stored) if name in CALIBRATION_FIELDS else stored))
        self.reply(self.controller.param_line())
        if name in CALIBRATION_FIELDS:
            self.reply(self.controller.calibration.status_line())

    def handle_cal(self, parts):
        store = self.controller.calibration
        action = parts[1].lower() if len(parts) >= 2 else "status"
        if action == "status" and len(parts) <= 2:
            self.reply(store.status_line())
            self.reply(self.controller.param_line())
            return
        if action == "save":
            group = parts[2].lower() if len(parts) == 3 else "all"
            if group not in ("all", "motor", "force"):
                self.reply("err cal_usage")
                return
            self.controller.stop(MODE_IDLE, "cal_save")
            if not self.controller.motors_stopped():
                self.reply("err cal_not_idle")
                return
            names = CALIBRATION_FIELDS if group == "all" else CALIBRATION_GROUPS[group]
            current = current_calibration_values()
            values = dict(store.saved)
            for name in names:
                values[name] = current[name]
            try:
                saved = store.save(values)
            except OSError as err:
                self.controller.hardware_log.event("cal_save_error", "group={} err={}".format(group, err))
                self.reply("err cal_save {}".format(err))
                return
            self.controller.hardware_log.event("cal_save", "group={}".format(group))
            self.reply(
                "ok cal_save group={} left_motor_gain={} right_motor_gain={}".format(
                    group,
                    format_calibration_value("left_motor_gain", saved["left_motor_gain"]),
                    format_calibration_value("right_motor_gain", saved["right_motor_gain"]),
                )
            )
            self.reply(store.status_line())
            self.reply(self.controller.param_line())
            return
        self.reply("err cal_usage")

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
        self.status_led.pulse()
        parts = line.strip().split()
        if not parts:
            return

        command = parts[0].lower()
        now = ticks_ms()
        if command not in ("keepalive", "status", "hwlog", "diag", "time", "cal", "param") and (
                command != self.last_command or ticks_diff(now, self.last_command_ms) >= 500):
            self.controller.hardware_log.event("command", "name={}".format(command))
            self.last_command = command
            self.last_command_ms = now

        try:
            if self.handle_diagnostic(parts, self.reply, "ble"):
                return
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
            elif command == "cal":
                self.handle_cal(parts)
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
                if self.controller.motor_test_running:
                    return
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
    usb = UsbDiagnostics()
    hardware_log.usb = usb
    ble.hardware_log = hardware_log
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

    print("Pico BLE traction cart controller fw={}".format(FIRMWARE_VERSION))
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
        try:
            run_motor_test(left_motor, right_motor, front_obstacle, led_mode)
        finally:
            left_motor.stop()
            right_motor.stop()

    left_hx = HX711(LEFT_HX711_DOUT, LEFT_HX711_SCK)
    right_hx = HX711(RIGHT_HX711_DOUT, RIGHT_HX711_SCK)
    controller = CartController(
        left_hx, right_hx, left_motor, right_motor, estop, front_obstacle, ble,
        hardware_log
    )
    front_obstacle.attach_stop_callback(controller.front_obstacle_stop)
    led_mode.set_controller(controller)
    commands = CommandInterface(controller, ble, led_mode)
    loaded = controller.calibration.load()
    if loaded is None:
        apply_calibration_values(default_calibration_values(), source="runtime")
        hardware_log.event(
            "cal_default",
            "reason={}".format(controller.calibration.last_error or "missing"),
        )
        print("cal_default reason={}".format(controller.calibration.last_error or "missing"))
    else:
        try:
            apply_calibration_values(loaded, source="file")
            hardware_log.event("cal_load", "source=file")
            print("cal_load source=file")
        except ValueError as err:
            apply_calibration_values(default_calibration_values(), source="runtime")
            controller.calibration.loaded = False
            controller.calibration.last_error = str(err)
            hardware_log.event("cal_default", "reason={}".format(err))
            print("cal_default reason={}".format(err))

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
    export_started_ms = None

    try:
        while True:
            loop_started_ms = ticks_ms()
            now = ticks_ms()
            led_mode.update(now)

            connection_event = ble.connection_event()
            if connection_event == 1:
                ble.reset_connection_buffers()
                hardware_log.event("ble_connect", "state=1 cause=unknown")
                if controller.mode in (MODE_AUTO, MODE_MANUAL) or controller.motor_test_running:
                    controller.stop(MODE_IDLE, "ble_connect_reset")
                commands.send_hello()
            elif connection_event == 0:
                ble.reset_connection_buffers()
                if hardware_log.export_channel == "ble":
                    hardware_log.cancel_export()
                    hardware_log.event("hwlog_interrupted", "reason=ble_disconnect")
                hardware_log.event("ble_disconnect", "state=0 cause=unknown")
                if controller.mode in (MODE_MANUAL, MODE_AUTO) or controller.motor_test_running:
                    controller.stop(MODE_IDLE, "ble_disconnect")

            for line in ble.poll_lines():
                commands.handle(line)

            controller.update(now)

            for line in usb.poll_lines():
                commands.handle_usb(line)

            now = ticks_ms()
            controller.last_loop_ms = max(0, ticks_diff(now, loop_started_ms))
            hardware_log.record_loop_time(controller.last_loop_ms, now)
            led_mode.update(now)

            if ticks_diff(now, last_debug) >= DEBUG_EVERY_MS:
                last_debug = now
                usb.write("usb_status fw={} {}".format(FIRMWARE_VERSION, controller.status_line()))

            if (commands.stream and hardware_log.export_records is None and
                    ticks_diff(now, last_ble_stream) >= BLE_STREAM_EVERY_MS):
                last_ble_stream = now
                ble.write(controller.status_line())

            if hardware_log.export_records is not None:
                if export_started_ms is None:
                    export_started_ms = now
                channel = hardware_log.export_channel
                transport = ble if channel == "ble" else usb
                queue = ble.tx_queue if channel == "ble" else usb.queue
                if ticks_diff(now, export_started_ms) > 60000:
                    hardware_log.cancel_export()
                    hardware_log.event("hwlog_interrupted", "reason=timeout channel=" + channel)
                elif not queue and (channel == "usb" or ble.connected() != 0):
                    hardware_line = hardware_log.next_export_line()
                    if not transport.write(hardware_line):
                        hardware_log.cancel_export()
                        hardware_log.event("hwlog_interrupted", "reason=transport channel=" + channel)
            else:
                export_started_ms = None

            ble.flush()
            usb.flush()
            # Include diagnostic output work in the measured loop latency.
            controller.last_loop_ms = max(0, ticks_diff(ticks_ms(), loop_started_ms))
            hardware_log.record_loop_time(controller.last_loop_ms, ticks_ms())

            sleep_ms(LOOP_MS)

    finally:
        # Ctrl-C/REPL entry and unexpected failures must de-energize both motors.
        controller.stop(MODE_IDLE, "shutdown")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("stopped fw={}".format(FIRMWARE_VERSION))
