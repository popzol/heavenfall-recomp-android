// Where a 3D view that frames itself inside a HUD box goes in the wide picture (src/viewport_region.h, hooks.cpp's
// UpdateRegion), on the host: `viewport_region_test` prints PASS or FAIL for each test and exits with the number that
// failed (run_viewport_region_test.sh).
//
// The numbers come from the game's FUN_00aee0f0 (the only function that opens such a view, through FUN_00c042ec):
// for a view whose rectangle is (left, top, right, bottom) in the top screen's pixels it writes the rows
// y0 = (main view's rows) - right and y1 = y0 + (right - left), where the main view's rows are 400 on the console and W
// in the wide render (the app scales the main view's rows to W every frame). A pixel x of the screen is the row W - y.
// The HUD is 400 pixels wide and centred, so its frame at x is drawn at x + (W - 400) / 2 in the wide picture
// (B171: the Smithy's weapon preview was drawn 68 pixels left of its frame at W = 536).
#include "../src/viewport_region.h"

#include <cstdint>
#include <cstdio>

using Port::ViewportRegion::Moved;
using Port::ViewportRegion::Rows;
using Port::ViewportRegion::SUB_VIEW_MODE;

namespace {

int failed = 0;

void report(const char* name, bool ok) {
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", name);
    failed += ok ? 0 : 1;
}

/// What Place decided: whether the region moves and the rows it has after.
struct Out {
    bool moved;
    Rows rows;
};
Out Place(Moved& moved, uint32_t viewport, uint32_t camera, uint32_t mode, uint32_t w, Rows now) {
    Out out{false, now};
    out.moved = moved.Place(viewport, camera, mode, w, now, out.rows);
    return out;
}

/// The rows FUN_00aee0f0 writes for a rectangle (left, right) when the main view has `main_rows` rows.
Rows Opened(uint32_t main_rows, uint32_t left, uint32_t right) {
    const uint32_t y0 = main_rows - right;
    return {y0, y0 + (right - left)};
}

/// The screen pixels a region covers in a target of W rows: its left edge and its right edge.
struct Span {
    uint32_t x0, x1;
};
Span Screen(uint32_t w, Rows r) {
    return {w - r.y1, w - r.y0};
}

// The forge's preview and Layered Gear's: (8, 21, 184, 217), the left of the top screen.
void forge_preview_follows_its_frame() {
    for (const uint32_t w : {424u, 536u, 640u}) {  // 16:9, 20:9 and wider
        Moved moved;
        const uint32_t shift = (w - 400) / 2;
        const Rows now = Opened(w, 8, 184);
        const Out out = Place(moved, 1, 0x1000, SUB_VIEW_MODE, w, now);
        const Span at = Screen(w, out.rows);
        char name[200];
        std::snprintf(name, sizeof(name), "W %u: the forge's preview (x 8..184 of the 400) is drawn at x + %u, in its frame", w, shift);
        report(name, at.x0 == 8 + shift && at.x1 == 184 + shift);
    }
}

// A view on the right half: its rows end under 400, where the app used to add the shift (the wrong way).
void a_view_on_the_right_moves_the_same_way() {
    const uint32_t w = 536;
    Moved moved;
    const Out out = Place(moved, 1, 0x1000, SUB_VIEW_MODE, w, Opened(w, 216, 392));
    const Span at = Screen(w, out.rows);
    report("W 536: a view at x 216..392 is drawn at x + 68, in its frame (not 68 the other way)", at.x0 == 216 + 68 && at.x1 == 392 + 68);
}

void a_moved_view_is_not_moved_again() {
    const uint32_t w = 536;
    Moved moved;
    const Out first = Place(moved, 1, 0x1000, SUB_VIEW_MODE, w, Opened(w, 8, 184));
    const Out again = Place(moved, 1, 0x1000, SUB_VIEW_MODE, w, first.rows);
    report("a region seen again as the app left it stays as it is (once, not on every call)", first.moved && !again.moved);
}

void a_reopened_view_is_moved_again() {
    const uint32_t w = 536;
    Moved moved;
    const Rows now = Opened(w, 8, 184);
    const Out first = Place(moved, 1, 0x1000, SUB_VIEW_MODE, w, now);
    const Out second = Place(moved, 1, 0x1000, SUB_VIEW_MODE, w, now);  // the game wrote the same rows again
    report("a view the game opens again (the same rows written again) is moved again",
           first.moved && second.moved && first.rows.y0 == second.rows.y0 && first.rows.y1 == second.rows.y1);
}

void a_new_view_with_the_moved_rows_is_new() {
    const uint32_t w = 536;
    Moved moved;
    const Out first = Place(moved, 1, 0x1000, SUB_VIEW_MODE, w, Opened(w, 8, 184));
    // another view in the slot (another camera) whose rows are the moved ones by chance (its frame at x 76..252)
    const Out other = Place(moved, 1, 0x2000, SUB_VIEW_MODE, w, first.rows);
    report("a new view whose rows are the moved ones' by chance is moved (told apart by its camera)", other.moved);
}

void the_console_picture_is_left_alone() {
    Moved moved;
    report("400 rows (no wide render): nothing moves", !Place(moved, 1, 0x1000, SUB_VIEW_MODE, 400, Opened(400, 8, 184)).moved);
}

void a_whole_screen_region_widens() {
    Moved moved;
    const Out out = Place(moved, 3, 0x1000, 12, 536, {0, 400});
    report("an owner's region over the whole 400 rows is widened to W", out.moved && out.rows.y0 == 0 && out.rows.y1 == 536);
}

void an_owner_with_console_rows_keeps_going_up() {
    Moved moved;
    const Out out = Place(moved, 3, 0x1000, 12, 536, {216, 392});
    report("an owner of mode 12 (rows of the console's 400, not made from the main view) is moved by +68, as before",
           out.moved && out.rows.y0 == 216 + 68 && out.rows.y1 == 392 + 68);
}

void a_rectangle_that_is_not_ours_is_left() {
    Moved moved;
    report("rows past the target (a rectangle that is not of the top screen) stay", !Place(moved, 1, 0x1000, SUB_VIEW_MODE, 536, {500, 700}).moved);
    report("rows that would go past the screen's right edge stay", !Place(moved, 2, 0x1000, SUB_VIEW_MODE, 536, {20, 100}).moved);
}

}  // namespace

int main() {
    forge_preview_follows_its_frame();
    a_view_on_the_right_moves_the_same_way();
    a_moved_view_is_not_moved_again();
    a_reopened_view_is_moved_again();
    a_new_view_with_the_moved_rows_is_new();
    the_console_picture_is_left_alone();
    a_whole_screen_region_widens();
    an_owner_with_console_rows_keeps_going_up();
    a_rectangle_that_is_not_ours_is_left();
    return failed;
}
