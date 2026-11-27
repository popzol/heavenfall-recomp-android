// Where a viewport region its owner set goes in the wide picture (header only, no game state: hooks.cpp's UpdateRegion,
// and the host test mh4n/port/tests/viewport_region_test.cpp).
//
// The game's top-screen viewport regions are rows of the scene target: y = 0 is the screen's right edge and y grows to the
// left (a rectangle at x pixels from the left edge, 400 wide, has y = 400 - x). With wide_render the target has W rows
// (W > 400): the app scales the whole-screen regions to W, and the HUD, which keeps its 400-pixel layout, is centred, so a
// 3D view that sits inside a HUD frame (the Smithy's weapon preview, Layered Gear's, the Felynes', the hunter's and the
// quest's) has to move with the frame: by half the extra rows, towards the centre.
#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

namespace Port::ViewportRegion {

constexpr uint32_t TOP_ROWS = 400;     // the console's top screen, in rows of the scene target
constexpr uint32_t SUB_VIEW_MODE = 1;  // sCamera::Viewport::mMode that FUN_00aee0f0 gives the views it opens

struct Rows {
    uint32_t y0, y1;
};

/// The rows each viewport with a region of its owner's was last given here, so that the region is moved once and not again
/// on every call. `camera` tells a new view in the same slot from the one moved: a new view whose rows are the moved ones'
/// by chance is still new.
class Moved {
public:
    /// Whether the region (`viewport`, owned by `camera`, of `mode`, now `now`) moves in a target of `rows` rows; its new
    /// rows are `out`. It stays when the picture is not wide, when it was moved already or when it is not a rectangle of
    /// the rows this knows.
    bool Place(uint32_t viewport, uint32_t camera, uint32_t /*mode*/, uint32_t rows, Rows now, Rows& out) {
        if (rows <= TOP_ROWS) {
            return false;
        }
        auto it = std::find_if(placed_.begin(), placed_.end(), [viewport](const Entry& e) { return e.viewport == viewport; });
        if (it != placed_.end() && it->camera == camera && it->rows.y0 == now.y0 && it->rows.y1 == now.y1) {
            return false;  // moved already
        }
        if (now.y0 > now.y1 || now.y1 > TOP_ROWS) {
            return false;  // not a region of the native 400 rows
        }
        const bool whole = now.y0 == 0 && now.y1 == TOP_ROWS;
        const uint32_t shift = (rows - TOP_ROWS) / 2;
        const Rows moved{whole ? 0 : now.y0 + shift, whole ? rows : now.y1 + shift};
        if (it == placed_.end()) {
            placed_.push_back({viewport, camera, moved});
        } else {
            *it = {viewport, camera, moved};
        }
        out = moved;
        return true;
    }

private:
    struct Entry {
        uint32_t viewport, camera;
        Rows rows;
    };
    std::vector<Entry> placed_;
};

}  // namespace Port::ViewportRegion
