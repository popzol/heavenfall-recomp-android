// The HUD parts' boxes against where Heavenfall's own HUD draws them (src/hud_face.h, hooks.cpp's HUD parts), on the host:
// `hud_face_test` prints PASS or FAIL for each test and exits with the number that failed (run_hud_face_test.sh).
//
// The numbers' source (checked with Heavenfall's own code, Heavenfall checkout, game RomFS read only):
//  * FACE: core/interface/hud/code_patch.py face_params(f) gives the face's group (x, y); the face is the sprite
//    ui601_icon00 of the game's layout, put in its null (ui601_target_icon00) and drawn by core/interface/game_gui/geometry.py
//    (quad, Frame) with the group at (x, y) and the null's position and scale times face_params' scale: its screen box.
//  * LAYOUT BOXES: the box the same geometry gives each HUD group of the game's core_quest.arc (ui202, ui203, ui205) after
//    core/interface/hud/build.py hud_files(rom, 0.7) shrank them towards their corners: the Android set is made from the
//    Beta edition's defaults (editions/beta/overlay/.../gui_defaults.json: hud_scale 70, touchless_target on,
//    single_screen on) with core/game/data/curated/android_settings.json over them, which leaves both alone.
#include "../src/hud_face.h"

#include <cmath>
#include <cstdio>
#include <string>

using Port::HudFace::Box;

namespace {

int failed = 0;

void report(const char* name, bool ok) {
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", name);
    failed += ok ? 0 : 1;
}

bool near(float a, float b, float tolerance) {
    return std::fabs(a - b) <= tolerance;
}

bool near(const Box& a, const Box& b, float tolerance) {
    return near(a.x0, b.x0, tolerance) && near(a.y0, b.y0, tolerance) && near(a.x1, b.x1, tolerance) &&
           near(a.y1, b.y1, tolerance);
}

/// `inner` lies inside `outer` (a few pixels of slack: the static boxes were measured on the screen's pixels).
bool inside(const Box& inner, const Box& outer, float slack) {
    return inner.x0 >= outer.x0 - slack && inner.y0 >= outer.y0 - slack && inner.x1 <= outer.x1 + slack &&
           inner.y1 <= outer.y1 + slack;
}

bool meets(const Box& a, const Box& b) {
    return a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < b.y1 && b.y0 < a.y1;
}

// FACE: face_params(f)'s group and the face's screen box drawn from the game's layout, for the HUD sizes the mod has
struct FaceCase {
    float f, x, y;
    Box box;
};
constexpr FaceCase FACES[] = {
    {1.0f, -89.6000f, -138.6000f, {227.2f, 192.6f, 256.0f, 221.4f}},
    {0.9f, -100.6400f, -136.7400f, {244.48f, 197.34f, 270.4f, 223.26f}},
    {0.8f, -111.6800f, -134.8800f, {261.76f, 202.08f, 284.8f, 225.12f}},
    {0.7f, -122.7200f, -133.0200f, {279.04f, 206.82f, 299.2f, 226.98f}},  // (the Android set's)
    {0.6f, -133.7600f, -131.1600f, {296.32f, 211.56f, 313.6f, 228.84f}},
};
// the box HUD_OBJECTS gave the target part, for the game's own HUD place of the old single-screen mod (top right)
constexpr Box STATIC_TARGET = {361, 13, 389, 39};

// LAYOUT BOXES at the HUD size 0.7: what the game's groups cover (the whole layout box, which holds the static art)
constexpr float F = 0.7f;
constexpr Box LAYOUT_ITEM_BAR = {304.1f, 205.7f, 397.2f, 240.0f};  // ui205_shita_ita and ui205_name_base
constexpr Box LAYOUT_CLOCK = {0.0f, 0.0f, 32.9f, 31.5f};           // ui202_time
constexpr Box LAYOUT_BARS = {29.4f, 1.4f, 197.4f, 10.5f};          // ui202_tairyoku and ui202_stamina
constexpr Box LAYOUT_SHARPNESS = {30.8f, 9.1f, 67.2f, 22.4f};      // ui202_kireaji and ui202_aucher00
constexpr Box LAYOUT_PARTY = {0.0f, 30.1f, 82.6f, 41.3f};          // ui203_name_base00
// HUD_OBJECTS' and the item bar zone's boxes of the game's own HUD (their last pixel included there: +1 here)
constexpr Box STATIC_ITEM_BAR = {273, 193, 378, 239};
constexpr Box STATIC_CLOCK = {2, 1, 44, 43};
constexpr Box STATIC_BARS = {44, 1, 278, 15};
constexpr Box STATIC_SHARPNESS = {46, 15, 92, 28};
constexpr Box STATIC_PARTY = {3, 46, 90, 58};

void test_the_face_is_where_the_mod_draws_it() {
    bool ok = true;
    for (const FaceCase& c : FACES) {
        ok = ok && near(Port::HudFace::FaceBox(c.x, c.y, c.f), c.box, 0.05f);
    }
    report("face: the box from the group's translation is the face the mod draws, at every HUD size (the ring went to the "
           "old top-right place)", ok);
}

void test_the_mod_hud_size_is_read_from_the_translation() {
    bool ok = true;
    for (const FaceCase& c : FACES) {
        ok = ok && near(Port::HudFace::ModScale(c.x, c.y), c.f, 0.002f);
    }
    report("face: the group's translation tells the mod's HUD size (the other parts' boxes follow it)", ok);
}

void test_another_group_is_no_face() {
    // (the touch screen's own ui601 at its place, a group at the layout's origin, a face height that disagrees)
    const bool ok = Port::HudFace::ModScale(0.0f, 0.0f) == 0.0f && Port::HudFace::ModScale(-89.6f, -100.0f) == 0.0f &&
                    Port::HudFace::ModScale(200.0f, -138.6f) == 0.0f && Port::HudFace::ModScale(-300.0f, -138.6f) == 0.0f;
    report("face: a translation that is not where the mod puts its face gives no HUD size (a wrong box would follow it)", ok);
}

void test_the_old_box_missed_the_face() {
    const Box face = Port::HudFace::FaceBox(FACES[3].x, FACES[3].y, FACES[3].f);
    report("face: the static box (top right) does not meet the face at the Android set's size, the new one is the face",
           !meets(STATIC_TARGET, face) && meets(face, face) && face.x0 > 200.0f && face.y0 > 120.0f);
}

void test_the_face_is_left_of_the_item_bar() {
    const Box face = Port::HudFace::FaceBox(FACES[3].x, FACES[3].y, FACES[3].f);
    const Box bar = Port::HudFace::ScaledToward({STATIC_ITEM_BAR.x0, STATIC_ITEM_BAR.y0, STATIC_ITEM_BAR.x1,
                                                 STATIC_ITEM_BAR.y1}, 400, 240, F);
    report("face: at the Android set's size the face is left of the item bar and level with it (the mod's own rule)",
           face.x1 < bar.x0 + 2.0f && face.y0 >= bar.y0 - 2.0f && face.y1 <= bar.y1 + 2.0f);
}

void test_the_item_bar_follows_the_mods_hud_size() {
    const Box now = Port::HudFace::ScaledToward(STATIC_ITEM_BAR, 400, 240, F);
    report("item bar: the touch zone's box at the mod's HUD size is inside the bar the layout draws; the game's own size "
           "reached above it (a tap beside the bar used an item)",
           inside(now, LAYOUT_ITEM_BAR, 1.5f) && !inside(STATIC_ITEM_BAR, LAYOUT_ITEM_BAR, 1.5f));
}

void test_the_left_parts_follow_the_mods_hud_size() {
    const struct {
        Box stat, layout;
    } parts[] = {{STATIC_CLOCK, LAYOUT_CLOCK}, {STATIC_BARS, LAYOUT_BARS}, {STATIC_SHARPNESS, LAYOUT_SHARPNESS},
                 {STATIC_PARTY, LAYOUT_PARTY}};
    bool shrunk = true, stale = false;
    for (const auto& p : parts) {
        shrunk = shrunk && inside(Port::HudFace::ScaledToward(p.stat, 0, 0, F), p.layout, 1.5f);
        stale = stale || !inside(p.stat, p.layout, 1.5f);
    }
    report("clock, bars, sharpness, party: the boxes toward the top left corner at the mod's HUD size are inside what the "
           "layout draws; the game's own size is not for at least one (the bars reached 80 pixels too far)", shrunk && stale);
}

}  // namespace

int main() {
    test_the_face_is_where_the_mod_draws_it();
    test_the_mod_hud_size_is_read_from_the_translation();
    test_another_group_is_no_face();
    test_the_old_box_missed_the_face();
    test_the_face_is_left_of_the_item_bar();
    test_the_item_bar_follows_the_mods_hud_size();
    test_the_left_parts_follow_the_mods_hud_size();
    return failed;
}
