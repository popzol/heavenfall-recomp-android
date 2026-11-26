// Where Heavenfall's own HUD puts the target camera's face on the top screen, and the HUD size its mod was made with
// (header only, no game state: hooks.cpp's HUD parts, and the host test mh4n/port/tests/hud_face_test.cpp).
//
// The mod (core/interface/hud/code_patch.py face_params, patch_target_face, asm/target_face.c) draws a second copy of
// the layout ui601 on the top screen: its group ui601_panel00 at layout (x, y) with the face's null at the scale of the
// mod's HUD size `f` times FACE_SCALE. Both come from f alone, so the group's translation that the game uploads for the
// object (nLayout::objectConstants: the group's position plus its animation offset, x grows to the left and y up, the
// screen's pixel is (200 - x, 120 - y)) tells where the face is drawn and which f the mod has. The face is the one
// sprite of ui601_icon00, 40 x 40 pixels at scale 1.2 (FACE_ART), centred at (120, 86) in the null ui601_target_icon00
// at x -40, so at (FACE_LOCAL_X, FACE_LOCAL_Y) from the group at the null's scale. The mod keeps its right edge
// FACE_RIGHT_GAP * f pixels from the screen's right edge and its centre at the height of the selected item's icon
// (layout y ITEM_ICON_Y, which the item bar's own HUD size moves towards the bottom-right corner by f).
#pragma once

#include <cmath>

namespace Port::HudFace {

constexpr float FACE_SCALE = 0.6f;         // the face's size against the touch screen's, at the HUD size 1
constexpr float FACE_RIGHT_GAP = 144.0f;   // its right edge from the screen's right edge, at the HUD size 1
constexpr float ITEM_ICON_Y = -87.0f;      // the layout y of the selected item's icon, at the HUD size 1
constexpr float FACE_ART = 40.0f * 1.2f;   // the face's side before the scale
constexpr float FACE_LOCAL_X = 120.0f - 40.0f, FACE_LOCAL_Y = 86.0f;  // its centre in the group's layout units
constexpr float HALF_WIDTH = 200.0f, HALF_HEIGHT = 120.0f;            // the top screen's centre

struct Box {
    float x0, y0, x1, y1;  // screen pixels, x1 and y1 the far edges
};

/// The group's x for the HUD size f: face_params' x, with the right edge at 400 - FACE_RIGHT_GAP * f.
constexpr float GroupX(float f) {
    return -HALF_WIDTH + FACE_RIGHT_GAP * f + (FACE_ART / 2 - FACE_LOCAL_X) * FACE_SCALE * f;
}
/// The group's y for f: the centre at the item icon's height.
constexpr float GroupY(float f) {
    return -HALF_HEIGHT + (ITEM_ICON_Y + HALF_HEIGHT) * f - FACE_LOCAL_Y * FACE_SCALE * f;
}

/// The HUD size of the mod that drew the group at layout (x, y), or 0 when that is not where the mod puts its face
/// (another layout's group, no mod): f from x, then y must agree.
inline float ModScale(float x, float y) {
    const float f = (x + HALF_WIDTH) / (GroupX(1.0f) + HALF_WIDTH);
    if (!(f >= 0.25f && f <= 1.05f)) {
        return 0.0f;
    }
    return std::fabs(y - GroupY(f)) <= 1.0f ? f : 0.0f;
}

/// Where the face is drawn (its art) for the group at layout (x, y) and the HUD size f.
inline Box FaceBox(float x, float y, float f) {
    const float s = FACE_SCALE * f, half = FACE_ART * s / 2;
    const float cx = HALF_WIDTH - (x + FACE_LOCAL_X * s), cy = HALF_HEIGHT - (y + FACE_LOCAL_Y * s);
    return {cx - half, cy - half, cx + half, cy + half};
}

/// `b` (a part's box at the HUD size 1, the game's own) as the mod's HUD size f draws it: every pane of a layout moves
/// towards its corner (core/interface/hud/scale.py: p' = anchor + (p - anchor) * f, the anchor in screen pixels).
inline Box ScaledToward(Box b, float anchor_x, float anchor_y, float f) {
    return {anchor_x + (b.x0 - anchor_x) * f, anchor_y + (b.y0 - anchor_y) * f, anchor_x + (b.x1 - anchor_x) * f,
            anchor_y + (b.y1 - anchor_y) * f};
}

}  // namespace Port::HudFace
