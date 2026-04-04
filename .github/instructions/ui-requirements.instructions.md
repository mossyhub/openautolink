---
description: "Use when building or modifying Compose UI screens, ViewModels, navigation, theming, or AAOS display integration. Covers screen requirements, overlay behavior, and AAOS constraints."
applyTo: "app/**/ui/**"
---
# UI Requirements

## Core Principle
The app is a transparent window to Android Auto. The projection surface is the UI. Everything else is secondary and must stay out of the way.

## Screens

### ProjectionScreen (Main — 95% of user time)
- **Full-screen SurfaceView** filling all available display area. No padding, no app bars
- **Touch overlay**: invisible Compose layer forwarding all MotionEvents to bridge. Must not consume or delay events
- **Connection HUD**: small centered overlay text when not streaming ("Connecting to bridge...", "Waiting for phone...", "Connected"). Fades when video starts
- **Overlay buttons**: small floating buttons for Settings and Stats. Draggable, semi-transparent, positions persisted via DataStore. Can be toggled off via settings. Must NOT intercept touch when video is playing (only their icon area)
- **Stats overlay**: optional top-right overlay showing FPS, codec, frames dropped, audio underruns, connection latency. Toggle via overlay button. Semi-transparent background so video is still visible
- **No back navigation** during streaming — the SurfaceView IS the app

### SettingsScreen
Opened from overlay button or from ProjectionScreen when disconnected.

**Sections:**
- **Connection**: Bridge IP (text field), mDNS discover button, connection status indicator
- **Video**: Codec picker (H.264/H.265/VP9), resolution tier (maps to 800×480 through 1920×1080), FPS (30/60)
- **Audio**: (reserved — no user-configurable audio settings initially)
- **Display**: Display mode picker (see Display Modes below), overlay button visibility toggles
- **About**: App version, bridge firmware version (from hello), device info

Changes that affect the bridge require "Apply & Reconnect" — bridge must restart its AA session with new quality parameters.

## Display Modes

Five modes controlling how the projection surface fits the physical display:

### 1. System UI Visible (Default)
Status bar and nav bar always visible. Recommended for GM AAOS. Standard AAOS layout.

### 2. Status Bar Hidden
Hides status bar only. Nav bar stays visible.

### 3. Nav Bar Hidden
Hides nav bar/dock only. Status bar stays visible.

### 4. Fullscreen Immersive
SurfaceView fills entire display, all system bars hidden. Swipe edge to reveal. Projection stretches/crops to fill.

### 5. Custom Viewport
User-defined projection area with draggable edges and aspect ratio snapping. See [docs/custom-viewport.md](../../docs/custom-viewport.md) for full design.

**Summary:**
- Viewport is anchored to the **bottom-left** of the AAOS usable area — only the top edge and right edge are adjustable
- Two draggable edge handles (top, right) define the projection rectangle
- Aspect ratio lock toggle (ON by default) — snaps to nearest standard ratio (16:9, 21:9, 32:9, 4:3, etc.)
- When ratio-locked, dragging one edge adjusts the other to maintain ratio
- Tapping the dimension label opens manual pixel input (for edges physically unreachable by finger)
- Area outside the viewport is solid black (not transparent — avoids showing whatever is behind)
- Viewport width/height persisted to DataStore
- Preview mode: shows the viewport bounds with handles while configuring. Exits to normal projection when done
- The SurfaceView AND touch coordinate scaling both use the custom rect — touch must map correctly to the smaller surface

### DiagnosticsScreen
Developer-facing. Accessible from Settings or a long-press gesture.

**Tabs:**
- **System**: Android version, display resolution/DPI, SoC, available codecs with HW/SW indicator
- **Network**: Bridge IP, control/video/audio TCP state, bytes transferred, latency
- **Bridge**: Bridge-reported stats (from control channel `stats` messages)
- **Logs**: Scrollable log view with severity filter, export button

## AAOS Wide-Screen Layout Patterns

The GM Blazer EV has a 2914×1134 display (~2.57:1 aspect ratio). Standard phone/tablet layouts look bad at this width. Follow these patterns:

### NavigationRail for Multi-Section Screens
- **Always use `NavigationRail`** (left sidebar with icon+label tabs) for screens with multiple sections (Settings, Diagnostics)
- **Never use a top `TopAppBar` with full-width scrolling content** — it wastes the extreme width and looks like a phone app stretched sideways
- Back button goes at the top of the rail column, above the tab icons
- Content pane fills the remaining width to the right of the rail
- Use `WindowInsets.safeDrawing` padding on the outer `Row`, not on individual elements

### Content Width Constraints
- Text fields and option lists should use `fillMaxWidth(0.5f)` to `fillMaxWidth(0.7f)` — full-width text fields at 2914px look absurd
- Radio button groups and settings rows: cap at 70% width
- Long-form text (descriptions, help): cap at 60% width for readability

### Touch Target Sizing
- AAOS guidelines: **76dp minimum** touch targets
- Overlay buttons on the projection screen: use `FilledTonalButton` or `FilledTonalIconButton` with adequate padding
- Settings radio buttons and list items: ensure the entire row is clickable, not just the radio circle

### Overlay vs Full-Screen
- The **ProjectionScreen** is always full-screen (it IS the app)
- **SettingsScreen** is a full navigation destination (not a dialog) — uses the NavigationRail layout
- **Stats overlay** and **Connection HUD** are transparent overlays on the projection surface
- **Floating buttons** (Settings, Stats) use `DraggableOverlayButton` — semi-transparent, position-persisted, bottom-right default

## AAOS Display Constraints
- GM Blazer EV: 2914×1134 physical, ~2628×800 usable with nav bar hidden
- App should request fullscreen (hide status bar + nav bar) in projection mode

### Display Safe Zones (CRITICAL)
- The GM Blazer EV has a **curved/tapered right edge** that clips content in the rightmost ~150px of the display
- **NEVER place interactive elements (buttons, toggles, inputs) at the far right** of any screen — they will be partially or fully hidden by the physical screen bezel
- Prefer **left-aligned** or **center-left** placement for all interactive controls
- Use `fillMaxWidth(0.5f)` to `fillMaxWidth(0.7f)` for content to naturally stay within the safe zone
- Status indicators and read-only labels are acceptable further right, but buttons and touch targets must stay within the left ~85% of the content pane
- This applies to **all screens**: Settings, Diagnostics, any future UI
- Use `WindowInsetsController.hide(WindowInsets.Type.systemBars())` — NOT legacy flags
- Respect display cutouts: `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`

## Theming
- Material3 dark theme as default (car interiors are dark)
- No light theme needed — this isn't a phone app
- Minimal color palette: dark surface, white text, accent for connected/streaming states
- Large touch targets (AAOS guidelines: 76dp minimum)

## Navigation
- Single Activity, Compose NavHost
- `ProjectionScreen` is the start destination and default
- `SettingsScreen` and `DiagnosticsScreen` are overlay destinations (slide up / dialog style)
- No bottom nav, no drawer — these occlude the projection

## Overlay Behavior
- Overlays (stats, buttons) use `Modifier.pointerInput` with hit-test only on their bounds
- Touch events outside overlays pass through to the SurfaceView touch handler
- Overlay buttons: 48dp icons, 0.7 alpha, draggable within screen bounds
- On tap: toggle stats or open settings. On drag: reposition (save to DataStore on release)
