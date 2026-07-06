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
PROTOCOL_VERSION = "pico-cart-ble-2026-07-04"

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

# Output smoothing. Bigger = faster start/stop.
RAMP_STEP = 0.018
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
MANUAL_TIMEOUT_MS = 700

# Set True when you only want to test the motor driver without HX711 sensors.
MOTOR_TEST_MODE = False
MOTOR_TEST_PWM = 0.20
MOTOR_TEST_STEP_MS = 1800

MODE_AUTO = "auto"
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

    def ramp_to(self, target):
        target = clamp(target, -MAX_PWM, MAX_PWM)
        if target > self.current + RAMP_STEP:
            self.current += RAMP_STEP
        elif target < self.current - RAMP_STEP:
            self.current -= RAMP_STEP
        else:
            self.current = target
        self.write(self.current)

    def stop(self):
        self.current = 0.0
        self.write(0.0)


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
    def __init__(self, left_hx, right_hx, left_motor, right_motor, estop, ble):
        self.left_hx = left_hx
        self.right_hx = right_hx
        self.left_motor = left_motor
        self.right_motor = right_motor
        self.estop = estop
        self.ble = ble

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

        self.manual_left = 0.0
        self.manual_right = 0.0
        self.last_manual_ms = ticks_ms()

    def stop(self, mode=MODE_IDLE):
        self.mode = mode
        self.manual_left = 0.0
        self.manual_right = 0.0
        self.left_motor.stop()
        self.right_motor.stop()

    def tare_sensors(self, samples=20):
        self.left_motor.stop()
        self.right_motor.stop()
        self.tared = False
        self.sensor_ok = False
        self.sensor_error = "taring"
        print("tare_start")
        try:
            left_offset = self.left_hx.tare(samples)
            right_offset = self.right_hx.tare(samples)
        except OSError as err:
            self.sensor_error = str(err)
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
        print("tare_left={}, tare_right={}".format(left_offset, right_offset))
        return True

    def read_sensors(self):
        if not self.tared:
            self.sensor_ok = False
            self.sensor_error = "not_tared"
            return False

        try:
            raw_left = self.left_hx.read_tared()
            raw_right = self.right_hx.read_tared()
        except OSError as err:
            self.sensor_ok = False
            self.sensor_error = str(err)
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
        self.mode = MODE_MANUAL
        self.manual_left = left
        self.manual_right = right
        self.last_manual_ms = ticks_ms()

    def enter_manual(self):
        self.mode = MODE_MANUAL
        self.manual_left = 0.0
        self.manual_right = 0.0
        self.last_manual_ms = ticks_ms()

    def update(self, now):
        self.read_sensors()
        self.unsafe_reason = self.safety_reason()

        if self.unsafe_reason:
            self.manual_left = 0.0
            self.manual_right = 0.0
            self.left_motor.ramp_to(0.0)
            self.right_motor.ramp_to(0.0)
            return

        if self.mode == MODE_IDLE:
            left_target = 0.0
            right_target = 0.0
            self.total = self.left_force + self.right_force
            self.steer = 0.0
        elif self.mode == MODE_MANUAL:
            if ticks_diff(now, self.last_manual_ms) > MANUAL_TIMEOUT_MS:
                self.manual_left = 0.0
                self.manual_right = 0.0
                left_target = 0.0
                right_target = 0.0
                self.unsafe_reason = "manual_timeout"
            else:
                left_target = self.manual_left
                right_target = self.manual_right
            self.total = self.left_force + self.right_force
            self.steer = 0.0
        else:
            left_target, right_target, self.total, self.steer = compute_targets(
                self.left_force, self.right_force
            )

        self.left_motor.ramp_to(left_target * LEFT_MOTOR_GAIN)
        self.right_motor.ramp_to(right_target * RIGHT_MOTOR_GAIN)

    def status_line(self):
        return (
            "stat mode={} sensor={} err={} lraw={:.0f} rraw={:.0f} l={:.0f} r={:.0f} "
            "total={:.0f} steer={:.2f} pwml={:.2f} pwmr={:.2f} estop={} bt={} unsafe={}"
        ).format(
            self.mode,
            "ok" if self.sensor_ok else "bad",
            self.sensor_error or "-",
            self.left_filtered,
            self.right_filtered,
            self.left_force,
            self.right_force,
            self.total,
            self.steer,
            self.left_motor.current,
            self.right_motor.current,
            self.estop.value(),
            self.ble.connected(),
            self.unsafe_reason or "-",
        )

    def param_line(self):
        return (
            "param max_pwm={:.2f} min_pwm={:.2f} start_raw={} full_raw={} "
            "steer_gain={:.2f} ramp={:.3f} manual_max={:.2f} timeout_ms={} "
            "left_motor_gain={:.2f} right_motor_gain={:.2f} "
            "left_force_gain={:.2f} right_force_gain={:.2f} "
            "led_force_min={} led_force_full={}"
        ).format(
            MAX_PWM,
            MIN_MOVE_PWM,
            PULL_START_RAW,
            PULL_FULL_RAW,
            STEER_GAIN,
            RAMP_STEP,
            MANUAL_MAX_PWM,
            MANUAL_TIMEOUT_MS,
            LEFT_MOTOR_GAIN,
            RIGHT_MOTOR_GAIN,
            LEFT_FORCE_GAIN,
            RIGHT_FORCE_GAIN,
            LED_FORCE_MIN_RAW,
            LED_FORCE_FULL_RAW,
        )

    def info_line(self):
        return (
            "info proto={} uart=UART{} tx=GP{} rx=GP{} state=GP{} "
            "right_inb=GP{} auto_start={}"
        ).format(
            PROTOCOL_VERSION,
            BLE_UART_ID,
            BLE_UART_TX,
            BLE_UART_RX,
            BLE_STATE_PIN,
            RIGHT_MOTOR_INB,
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
            "help cmd: ver info pins status param stream on|off identify [S] auto manual idle stop tare drive L R f [P] b [P] l [P] r [P] motor SIDE DIR [P] [MS] set NAME VALUE"
        )
        self.reply(
            "help set: max_pwm min_pwm start_raw full_raw steer_gain ramp manual_max left_motor_gain right_motor_gain left_force_gain right_force_gain"
        )

    def pins_line(self):
        return (
            "pins left_pwm=GP{} left_ina=GP{} left_inb=GP{} "
            "right_pwm=GP{} right_ina=GP{} right_inb=GP{} estop=GP{} ble_state=GP{}"
        ).format(
            LEFT_MOTOR_PWM,
            LEFT_MOTOR_INA,
            LEFT_MOTOR_INB,
            RIGHT_MOTOR_PWM,
            RIGHT_MOTOR_INA,
            RIGHT_MOTOR_INB,
            ESTOP_PIN,
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
            if self.controller.estop.value() == 0:
                self.controller.left_motor.stop()
                self.controller.right_motor.stop()
                self.reply("err estop")
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
        global STEER_GAIN, RAMP_STEP, MANUAL_MAX_PWM
        global LEFT_MOTOR_GAIN, RIGHT_MOTOR_GAIN, LEFT_FORCE_GAIN, RIGHT_FORCE_GAIN

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
        elif name == "manual_max":
            MANUAL_MAX_PWM = clamp(value, 0.05, MAX_PWM)
            stored = MANUAL_MAX_PWM
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
            left = -power
            right = power
        else:
            left = power
            right = -power

        self.controller.set_manual_drive(left, right)
        self.reply("ok drive left={:.2f} right={:.2f}".format(left, right))

    def handle(self, line):
        print("ble_cmd={}".format(line))
        self.status_led.pulse()
        parts = line.strip().split()
        if not parts:
            return

        command = parts[0].lower()

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
            elif command == "auto":
                if not self.controller.sensor_ok:
                    self.controller.stop(MODE_IDLE)
                    self.reply("err sensor_not_ready")
                else:
                    self.controller.mode = MODE_AUTO
                    self.reply("ok mode=auto")
            elif command == "manual" or command == "man":
                self.controller.enter_manual()
                self.reply("ok mode=manual")
            elif command == "idle":
                self.controller.stop(MODE_IDLE)
                self.reply("ok mode=idle")
            elif command == "stop" or command == "s":
                self.controller.stop(MODE_IDLE)
                self.reply("ok stop")
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
                    self.controller.set_manual_drive(left, right)
                    self.reply("ok drive left={:.2f} right={:.2f}".format(left, right))
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


def run_motor_test(left_motor, right_motor, led_mode):
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
                led_mode.update(now)
                left_motor.ramp_to(left_target)
                right_motor.ramp_to(right_target)
                sleep_ms(LOOP_MS)


def main():
    led = make_led()
    ble = BluetoothSerial()
    estop = Pin(ESTOP_PIN, Pin.IN, Pin.PULL_UP)

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

    led_mode = StatusLed(led, ble)

    if MOTOR_TEST_MODE:
        run_motor_test(left_motor, right_motor, led_mode)

    left_hx = HX711(LEFT_HX711_DOUT, LEFT_HX711_SCK)
    right_hx = HX711(RIGHT_HX711_DOUT, RIGHT_HX711_SCK)
    controller = CartController(left_hx, right_hx, left_motor, right_motor, estop, ble)
    led_mode.set_controller(controller)
    commands = CommandInterface(controller, ble, led_mode)

    print("Keep both load cells unloaded. Taring...")
    led_mode.pulse(2000)
    controller.tare_sensors()
    controller.stop(MODE_IDLE)
    print(
        "ble_commands: help pins status identify stream on|off auto manual stop tare drive L R f b l r motor set"
    )

    last_debug = ticks_ms()
    last_ble_stream = ticks_ms()

    while True:
        now = ticks_ms()
        led_mode.update(now)

        connection_event = ble.connection_event()
        if connection_event == 1:
            print("ble_connected=1")
            if controller.mode == MODE_AUTO:
                controller.stop(MODE_IDLE)
            commands.send_hello()
        elif connection_event == 0:
            print("ble_connected=0")
            if controller.mode == MODE_MANUAL or controller.mode == MODE_AUTO:
                controller.stop(MODE_IDLE)

        for line in ble.poll_lines():
            commands.handle(line)

        controller.update(now)

        now = ticks_ms()
        led_mode.update(now)

        if ticks_diff(now, last_debug) >= DEBUG_EVERY_MS:
            last_debug = now
            print(controller.status_line())

        if commands.stream and ticks_diff(now, last_ble_stream) >= BLE_STREAM_EVERY_MS:
            last_ble_stream = now
            ble.write(controller.status_line())

        sleep_ms(LOOP_MS)


try:
    main()
except KeyboardInterrupt:
    print("stopped")
