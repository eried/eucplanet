#include <pebble.h>

// AppMessage keys -- MUST match app/src/main/java/com/eried/eucplanet/pebble/
// PebbleProtocol.kt (PebbleKeys). Phone sends canonical metric ints (x10 for
// speed/volts/amps/temp) plus unit-code strings; the watch converts locally.
#define KEY_CONNECTED  0
#define KEY_SPEED      1   // km/h * 10
#define KEY_BATTERY    2   // percent
#define KEY_VOLTAGE    3   // V * 10
#define KEY_CURRENT    4   // A * 10
#define KEY_PWM        5   // percent
#define KEY_TEMP       6   // C * 10
#define KEY_UNIT_SPEED 7   // "kmh" | "mph" | "ms" | "kn"
#define KEY_UNIT_TEMP  8   // "C" | "F" | "K"
#define KEY_ACCENT     9   // 0xAARRGGBB

// Dev aid: when 1, seed sample telemetry at launch so the emulator shows a
// populated dial without a phone. MUST be 0 in committed/shipped builds.
#define EUC_DEMO 0

static Window *s_window;
static Layer *s_dial_layer;

static bool s_connected = false;
static bool s_have_data = false;
static int s_speed10 = 0, s_battery = 0, s_voltage10 = 0, s_current10 = 0;
static int s_pwm = 0, s_temp10 = 0;
static char s_unit_speed[8] = "kmh";
static char s_unit_temp[4] = "C";

// Speed in the rider's unit, *10 (km/h is the canonical wire unit).
static int speed_in_unit_x10(void) {
  if (strcmp(s_unit_speed, "mph") == 0) return (s_speed10 * 6214) / 10000;
  if (strcmp(s_unit_speed, "ms") == 0)  return (s_speed10 * 2778) / 10000;
  if (strcmp(s_unit_speed, "kn") == 0)  return (s_speed10 * 5400) / 10000;
  return s_speed10;
}
static int temp_in_unit(void) {
  int c = s_temp10 / 10;
  if (strcmp(s_unit_temp, "F") == 0) return c * 9 / 5 + 32;
  if (strcmp(s_unit_temp, "K") == 0) return c + 273;
  return c;
}

static void draw_bar(GContext *ctx, GRect frame, int pct, GColor fill) {
  if (pct < 0) pct = 0;
  if (pct > 100) pct = 100;
  graphics_context_set_fill_color(ctx, GColorDarkGray);
  graphics_fill_rect(ctx, frame, 3, GCornersAll);
  GRect inner = GRect(frame.origin.x, frame.origin.y, frame.size.w * pct / 100, frame.size.h);
  graphics_context_set_fill_color(ctx, fill);
  graphics_fill_rect(ctx, inner, 3, GCornersAll);
}

static void label_pair(GContext *ctx, GRect b, int y, const char *label, const char *value) {
  graphics_draw_text(ctx, label, fonts_get_system_font(FONT_KEY_GOTHIC_14),
    GRect(6, y - 2, 70, 16), GTextOverflowModeFill, GTextAlignmentLeft, NULL);
  graphics_draw_text(ctx, value, fonts_get_system_font(FONT_KEY_GOTHIC_14),
    GRect(b.size.w - 76, y - 2, 70, 16), GTextOverflowModeFill, GTextAlignmentRight, NULL);
}

static void dial_update(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  graphics_context_set_text_color(ctx, GColorWhite);

  graphics_draw_text(ctx, s_connected ? "EUC PLANET" : "no wheel",
    fonts_get_system_font(FONT_KEY_GOTHIC_14),
    GRect(0, 4, b.size.w, 18), GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);

  // Big speed (whole number) + unit.
  char speedbuf[12];
  snprintf(speedbuf, sizeof(speedbuf), "%d", (s_have_data ? speed_in_unit_x10() : 0) / 10);
  graphics_draw_text(ctx, speedbuf, fonts_get_system_font(FONT_KEY_LECO_42_NUMBERS),
    GRect(0, 26, b.size.w, 50), GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  graphics_draw_text(ctx, s_unit_speed, fonts_get_system_font(FONT_KEY_GOTHIC_18),
    GRect(0, 78, b.size.w, 22), GTextOverflowModeFill, GTextAlignmentCenter, NULL);

  // Battery bar.
  int by = b.size.h - 78;
  char batbuf[16];
  snprintf(batbuf, sizeof(batbuf), "%d%%", s_battery);
  label_pair(ctx, b, by, "BATT", batbuf);
  GColor batcol = s_battery <= 20 ? GColorRed : (s_battery <= 40 ? GColorOrange : GColorGreen);
  draw_bar(ctx, GRect(6, by + 16, b.size.w - 12, 10), s_battery, batcol);

  // PWM bar (the headroom number that matters).
  int py = b.size.h - 46;
  char pwmbuf[16];
  snprintf(pwmbuf, sizeof(pwmbuf), "%d%%", s_pwm);
  label_pair(ctx, b, py, "PWM", pwmbuf);
  GColor pwmcol = s_pwm >= 90 ? GColorRed : (s_pwm >= 80 ? GColorOrange : GColorGreen);
  draw_bar(ctx, GRect(6, py + 16, b.size.w - 12, 10), s_pwm, pwmcol);

  // Volts + temp footer.
  char foot[40];
  snprintf(foot, sizeof(foot), "%d.%dV  %d%s", s_voltage10 / 10, s_voltage10 % 10,
           temp_in_unit(), s_unit_temp);
  graphics_draw_text(ctx, foot, fonts_get_system_font(FONT_KEY_GOTHIC_14),
    GRect(0, b.size.h - 18, b.size.w, 16), GTextOverflowModeFill, GTextAlignmentCenter, NULL);
}

static void inbox_received(DictionaryIterator *iter, void *context) {
  Tuple *t;
  if ((t = dict_find(iter, KEY_CONNECTED)))   s_connected = t->value->int32 != 0;
  if ((t = dict_find(iter, KEY_SPEED)))       s_speed10 = t->value->int32;
  if ((t = dict_find(iter, KEY_BATTERY)))     s_battery = t->value->int32;
  if ((t = dict_find(iter, KEY_VOLTAGE)))     s_voltage10 = t->value->int32;
  if ((t = dict_find(iter, KEY_CURRENT)))     s_current10 = t->value->int32;
  if ((t = dict_find(iter, KEY_PWM)))         s_pwm = t->value->int32;
  if ((t = dict_find(iter, KEY_TEMP)))        s_temp10 = t->value->int32;
  if ((t = dict_find(iter, KEY_UNIT_SPEED)))  strncpy(s_unit_speed, t->value->cstring, sizeof(s_unit_speed) - 1);
  if ((t = dict_find(iter, KEY_UNIT_TEMP)))   strncpy(s_unit_temp, t->value->cstring, sizeof(s_unit_temp) - 1);
  s_have_data = true;
  layer_mark_dirty(s_dial_layer);
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_dial_layer = layer_create(layer_get_bounds(root));
  layer_set_update_proc(s_dial_layer, dial_update);
  layer_add_child(root, s_dial_layer);
}

static void window_unload(Window *window) {
  layer_destroy(s_dial_layer);
}

static void init(void) {
  s_window = window_create();
  window_set_background_color(s_window, GColorBlack);
  window_set_window_handlers(s_window, (WindowHandlers){
    .load = window_load,
    .unload = window_unload,
  });
  app_message_register_inbox_received(inbox_received);
  app_message_open(app_message_inbox_size_maximum(), 64);
#if EUC_DEMO
  s_have_data = true;
  s_connected = true;
  s_speed10 = 234; s_battery = 77; s_voltage10 = 841; s_current10 = 52;
  s_pwm = 41; s_temp10 = 300;
#endif
  window_stack_push(s_window, true);
}

static void deinit(void) {
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
