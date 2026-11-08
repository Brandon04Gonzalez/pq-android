//! the console's buttons drawn over the window for a touch screen, a phone's
//! most of all, and touches on the bottom screen made the console's own.
//! held sideways the screens fill the window and the buttons go over them,
//! as the Persona Q2 port has them: the circle pad and the D-pad on the
//! left, the face buttons on the right above the bottom screen, L, R and the
//! menu along the top, Start and Select along the bottom. held upright they
//! go under the screens.

use std::collections::HashMap;

use egui::{Align2, Color32, CornerRadius, FontId, Pos2, Rect, Stroke, StrokeKind, Vec2};
use winit::event::{Touch, TouchPhase};
use zakuro_core::services::hid::{InputState, PadState};
use zakuro_gpu::Viewport;

/// what a finger is on.
#[derive(Debug, Clone, Copy, PartialEq)]
enum Target {
    Button(PadState),
    /// the circle pad, which follows the finger from where it went down.
    Circle,
    /// the bottom screen, touched as the console's own.
    Screen,
    /// the menu over the game.
    Menu,
}

/// a percentage the app's launcher sets in name, as a fraction, when it is
/// one of range.
fn launcher_percent(name: &str, range: std::ops::RangeInclusive<u32>) -> Option<f32> {
    let value = std::env::var(name).ok()?.trim().parse::<u32>().ok()?;
    range.contains(&value).then_some(value as f32 / 100.0)
}

/// how big the controls are drawn, ZAKURO_CONTROLS_SIZE in percent.
fn size_factor() -> f32 {
    launcher_percent("ZAKURO_CONTROLS_SIZE", 50..=150).unwrap_or(1.0)
}

/// how opaque the controls are drawn, ZAKURO_CONTROLS_OPACITY in percent.
fn opacity() -> f32 {
    launcher_percent("ZAKURO_CONTROLS_OPACITY", 10..=100).unwrap_or(1.0)
}

/// where everything goes in a window, in egui's points.
struct Places {
    circle: (Pos2, f32),
    dpad: (Pos2, f32),
    face: (Pos2, f32),
    l: Rect,
    r: Rect,
    start: Rect,
    select: Rect,
    menu: Rect,
    screen: Option<Rect>,
    /// for a phone held upright, the controls under the screens.
    upright: bool,
}

impl Places {
    /// lays the controls out over the screens, in a window of size points
    /// whose bottom screen shows at bottom, also in points. sized by a unit
    /// that grows with the window, a seventeenth of its width or a seventh
    /// of its height, so that a tablet's are as big as a thumb needs.
    fn new(size: Vec2, top: Option<Rect>, bottom: Option<Rect>) -> Places {
        if size.y > size.x {
            return Places::upright(size, top, bottom);
        }
        let (w, h) = (size.x, size.y);
        let u = (w / 17.0).min(h / 7.2) * size_factor();
        let shoulder = |left: f32| Rect::from_min_max(Pos2::new(left, 0.2 * u), Pos2::new(left + 2.4 * u, 1.1 * u));
        let dpad = (Pos2::new(1.7 * u, h - 1.6 * u), 1.2 * u);
        let circle_radius = 1.15 * u;
        // the circle pad between L and the D-pad
        let circle_y = (h * 0.42).min(dpad.0.y - dpad.1 * 1.2 - circle_radius * 1.2).max(1.2 * u + circle_radius);
        // the face buttons over the bottom screen when it is at the right,
        // under R all the same
        let face_radius = 1.75 * u;
        let reach = face_radius * 0.62 + face_radius * 0.4;
        let over = bottom.map_or(h * 0.45, |bottom| (h * 0.45).min(bottom.min.y - reach - 0.1 * u));
        let face_y = over.max(1.1 * u + 0.15 * u + reach);
        let small = |left: f32| Rect::from_min_max(Pos2::new(left, h - 0.85 * u), Pos2::new(left + 1.8 * u, h - 0.2 * u));
        Places {
            circle: (Pos2::new(1.7 * u, circle_y), circle_radius),
            dpad,
            face: (Pos2::new(w - 2.0 * u, face_y), face_radius),
            l: shoulder(0.2 * u),
            r: shoulder(w - 2.6 * u),
            select: small(3.4 * u),
            start: small(5.5 * u),
            menu: Rect::from_center_size(Pos2::new(w / 2.0, 0.65 * u), Vec2::new(1.7 * u, 0.85 * u)),
            screen: bottom,
            upright: false,
        }
    }

    /// the controls under the screens, for a phone held upright as the
    /// console is: the circle pad over the D-pad on the left, the face
    /// buttons on the right, L, R and the menu along the top of the room,
    /// Start and Select at the bottom.
    fn upright(size: Vec2, top: Option<Rect>, bottom: Option<Rect>) -> Places {
        let (w, h) = (size.x, size.y);
        let free_top = bottom.or(top).map_or(h * 0.55, |rect| rect.max.y);
        let free = (h - free_top).max(h * 0.3);
        let free_top = h - free;
        // L, R and the menu, the circle pad over the D-pad, Start and
        // Select, one under another, take 7.3 units
        let u = (w / 8.5).min(free / 7.3) * size_factor();
        let row = free_top + 0.2 * u;
        let bottom_row = h - 0.85 * u;
        // what room is left over goes between them
        let spare = (bottom_row - row - 7.1 * u).max(0.0);
        let circle_y = row + 2.35 * u + spare / 3.0;
        let dpad_y = circle_y + 2.55 * u + spare / 3.0;
        let shoulder = |left: f32| Rect::from_min_max(Pos2::new(left, row), Pos2::new(left + 2.35 * u, row + 0.95 * u));
        let small = |left: f32| Rect::from_min_max(Pos2::new(left, bottom_row), Pos2::new(left + 1.9 * u, bottom_row + 0.65 * u));
        Places {
            circle: (Pos2::new(1.75 * u, circle_y), 1.2 * u),
            dpad: (Pos2::new(1.75 * u, dpad_y), 1.25 * u),
            face: (Pos2::new(w - 2.15 * u, (circle_y + dpad_y) / 2.0), 1.75 * u),
            l: shoulder(0.25 * u),
            r: shoulder(w - 2.6 * u),
            select: small(w / 2.0 - 2.3 * u),
            start: small(w / 2.0 + 0.4 * u),
            menu: Rect::from_center_size(Pos2::new(w / 2.0, row + 0.475 * u), Vec2::new(1.7 * u, 0.95 * u)),
            screen: bottom,
            upright: true,
        }
    }

    /// the face buttons and which way each lies from their middle, a
    /// diamond as on the console.
    const FACE: [(PadState, Vec2); 4] =
        [(PadState::A, Vec2::new(1.0, 0.0)), (PadState::B, Vec2::new(0.0, 1.0)), (PadState::Y, Vec2::new(-1.0, 0.0)), (PadState::X, Vec2::new(0.0, -1.0))];

    /// the D-pad's direction or face button under a point, by which way it
    /// lies from the middle, a dead zone in it.
    fn quarter(center: Pos2, radius: f32, at: Pos2, buttons: [PadState; 4]) -> Option<PadState> {
        let offset = at - center;
        if offset.length() > radius * 1.2 || offset.length() < radius * 0.15 {
            return None;
        }
        // right, down, left, up
        let index = if offset.x.abs() > offset.y.abs() {
            if offset.x > 0.0 { 0 } else { 2 }
        } else if offset.y > 0.0 {
            1
        } else {
            3
        };
        Some(buttons[index])
    }

    fn hit(&self, at: Pos2) -> Option<Target> {
        for (rect, button) in [(self.l, PadState::L), (self.r, PadState::R), (self.start, PadState::START), (self.select, PadState::SELECT)] {
            if rect.expand(4.0).contains(at) {
                return Some(Target::Button(button));
            }
        }
        if self.menu.expand(4.0).contains(at) {
            return Some(Target::Menu);
        }
        // the controls go over the screens, a finger on one presses it
        let (center, radius) = self.circle;
        if at.distance(center) <= radius * 1.2 {
            return Some(Target::Circle);
        }
        let (center, radius) = self.dpad;
        if let Some(button) = Places::quarter(center, radius, at, [PadState::RIGHT, PadState::DOWN, PadState::LEFT, PadState::UP]) {
            return Some(Target::Button(button));
        }
        // the face buttons each where it is drawn, a little more, as they
        // can sit over the bottom screen, whose touches the gaps leave be
        let (center, radius) = self.face;
        for (button, direction) in Places::FACE {
            if at.distance(center + direction * radius * 0.62) <= radius * 0.4 * 1.25 {
                return Some(Target::Button(button));
            }
        }
        self.screen.filter(|screen| screen.contains(at)).map(|_| Target::Screen)
    }
}

/// Android's key codes for its back button and a controller's home button,
/// which open the menu.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub const MENU_BUTTONS: [u32; 2] = [4, 110];

/// the console's button for an Android controller's key code, the face
/// buttons by where they are, A on the right as on the console.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub fn controller_button(code: u32) -> Option<PadState> {
    Some(match code {
        97 => PadState::A,
        96 => PadState::B,
        100 => PadState::X,
        99 => PadState::Y,
        102 | 104 => PadState::L,
        103 | 105 => PadState::R,
        108 => PadState::START,
        109 => PadState::SELECT,
        19 => PadState::UP,
        20 => PadState::DOWN,
        21 => PadState::LEFT,
        22 => PadState::RIGHT,
        _ => return None,
    })
}

/// the fingers down on the window and what each is on.
#[derive(Default)]
pub struct TouchControls {
    fingers: HashMap<u64, (Target, Pos2)>,
    /// the buttons a controller holds.
    held: PadState,
    /// the circle pad's position, each axis -1 to 1, up positive.
    circle: Vec2,
    /// the bottom screen's point a finger is on, in its pixels.
    screen: Option<(u16, u16)>,
    /// the menu was asked for.
    menu: bool,
    /// the layout the touches were last taken with.
    places: Option<Places>,
}

impl TouchControls {
    /// the controls laid out for a window of physical size, at scale pixels
    /// to a point, with the screens at top and bottom in pixels.
    pub fn arrange(&mut self, size: (u32, u32), scale: f32, top: Option<Viewport>, bottom: Option<Viewport>) {
        let points = |viewport: Viewport| {
            Rect::from_min_size(Pos2::new(viewport.x / scale, viewport.y / scale), Vec2::new(viewport.width / scale, viewport.height / scale))
        };
        let size = Vec2::new(size.0 as f32 / scale, size.1 as f32 / scale);
        self.places = Some(Places::new(size, top.map(points), bottom.map(points)));
    }

    /// a controller's button went down or up.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
    pub fn hold(&mut self, button: PadState, down: bool) {
        self.held.set(button, down);
    }

    /// lets go of everything, as when the window loses focus or the menu opens.
    pub fn release(&mut self) {
        self.held = PadState::empty();
        self.fingers.clear();
        self.circle = Vec2::ZERO;
        self.screen = None;
    }

    /// where the frame rate goes, its top middle in points: under the menu
    /// button, held either way.
    pub fn fps_place(&self) -> Option<Pos2> {
        let places = self.places.as_ref()?;
        Some(Pos2::new(places.menu.center().x, places.menu.max.y + 6.0))
    }

    /// whether the menu was asked for since the last time this was asked.
    pub fn take_menu(&mut self) -> bool {
        std::mem::take(&mut self.menu)
    }

    /// a finger went down, moved or left, at a position in pixels.
    pub fn touch(&mut self, touch: &Touch, scale: f32) {
        let Some(places) = &self.places else { return };
        let at = Pos2::new(touch.location.x as f32 / scale, touch.location.y as f32 / scale);
        match touch.phase {
            TouchPhase::Started => {
                let Some(target) = places.hit(at) else { return };
                if target == Target::Menu {
                    self.menu = true;
                    return;
                }
                self.fingers.insert(touch.id, (target, at));
            }
            TouchPhase::Moved => {
                let Some((target, place)) = self.fingers.get_mut(&touch.id) else { return };
                *place = at;
                // a finger sliding over the D-pad or the face buttons
                // presses the one it is on now
                if let Target::Button(_) = target {
                    if let Some(Target::Button(button)) = places.hit(at) {
                        *target = Target::Button(button);
                    }
                }
            }
            TouchPhase::Ended | TouchPhase::Cancelled => {
                self.fingers.remove(&touch.id);
            }
        }
        self.update();
    }

    /// the circle pad and the bottom screen from where the fingers are.
    fn update(&mut self) {
        let Some(places) = &self.places else { return };
        self.circle = Vec2::ZERO;
        self.screen = None;
        for (target, at) in self.fingers.values() {
            match target {
                Target::Circle => {
                    let (center, radius) = places.circle;
                    let offset = (*at - center) / (radius * 0.8);
                    let offset = if offset.length() > 1.0 { offset.normalized() } else { offset };
                    self.circle = Vec2::new(offset.x, -offset.y);
                }
                Target::Screen => {
                    if let Some(rect) = places.screen {
                        let x = ((at.x - rect.min.x) / rect.width() * 320.0).clamp(0.0, 319.0);
                        let y = ((at.y - rect.min.y) / rect.height() * 240.0).clamp(0.0, 239.0);
                        self.screen = Some((x as u16, y as u16));
                    }
                }
                Target::Button(_) | Target::Menu => {}
            }
        }
    }

    fn held(&self, button: PadState) -> bool {
        self.fingers.values().any(|(target, _)| *target == Target::Button(button))
    }

    /// the console's input with what the fingers hold added.
    pub fn apply(&self, mut input: InputState) -> InputState {
        input.buttons.insert(self.held);
        for (target, _) in self.fingers.values() {
            if let Target::Button(button) = target {
                input.buttons.insert(*button);
            }
        }
        if self.circle != Vec2::ZERO {
            input.circle_x = self.circle.x;
            input.circle_y = self.circle.y;
        }
        if self.screen.is_some() {
            input.touch = self.screen;
        }
        input
    }

    /// draws the controls, faint enough to see the game through.
    pub fn draw(&self, painter: &egui::Painter) {
        let Some(places) = &self.places else { return };
        // as opaque as the launcher says, over the game
        let alpha = |value: f32| (value * opacity()) as u8;
        let fill = Color32::from_rgba_unmultiplied(20, 22, 28, alpha(110.0));
        let lit = Color32::from_rgba_unmultiplied(255, 214, 10, alpha(170.0));
        let edge = Stroke::new((places.l.height() * 0.04).max(1.5), Color32::from_rgba_unmultiplied(230, 230, 240, alpha(150.0)));
        let text = Color32::from_rgba_unmultiplied(245, 245, 250, alpha(220.0));
        let fill_for = |button: PadState| if self.held(button) { lit } else { fill };

        let rounded = |rect: Rect, button: Option<PadState>, label: &str, size: f32| {
            let color = button.map_or(fill, fill_for);
            painter.rect(rect, CornerRadius::same((rect.height() * 0.45) as u8), color, edge, StrokeKind::Inside);
            painter.text(rect.center(), Align2::CENTER_CENTER, label, FontId::proportional(size), text);
        };
        let label = places.l.height() * 0.5;
        rounded(places.l, Some(PadState::L), "L", label);
        rounded(places.r, Some(PadState::R), "R", label);
        rounded(places.select, Some(PadState::SELECT), "SELECT", places.select.height() * 0.42);
        rounded(places.start, Some(PadState::START), "START", places.start.height() * 0.42);
        rounded(places.menu, None, "MENÚ", places.menu.height() * 0.45);

        // the circle pad, its knob where the finger holds it
        let (center, radius) = places.circle;
        painter.circle(center, radius, fill, edge);
        let knob = center + Vec2::new(self.circle.x, -self.circle.y) * radius * 0.55;
        let knob_fill = if self.circle != Vec2::ZERO { lit } else { Color32::from_rgba_unmultiplied(70, 74, 86, alpha(170.0)) };
        painter.circle(knob, radius * 0.45, knob_fill, edge);

        // the D-pad, a cross
        let (center, radius) = places.dpad;
        let arm = radius * 0.62;
        let thick = radius * 0.62;
        for (button, direction) in [
            (PadState::UP, Vec2::new(0.0, -1.0)),
            (PadState::DOWN, Vec2::new(0.0, 1.0)),
            (PadState::LEFT, Vec2::new(-1.0, 0.0)),
            (PadState::RIGHT, Vec2::new(1.0, 0.0)),
        ] {
            let middle = center + direction * arm;
            let rect = Rect::from_center_size(middle, Vec2::splat(thick));
            painter.rect(rect, CornerRadius::same((thick * 0.2) as u8), fill_for(button), edge, StrokeKind::Inside);
            // an arrow pointing the way the button goes, drawn as the
            // fonts may have no arrows
            let across = Vec2::new(-direction.y, direction.x);
            let size = thick * 0.22;
            let arrow = vec![middle + direction * size, middle - direction * size * 0.6 + across * size, middle - direction * size * 0.6 - across * size];
            painter.add(egui::Shape::convex_polygon(arrow, text, Stroke::NONE));
        }

        // the face buttons, a diamond as on the console
        let (center, radius) = places.face;
        let spread = radius * 0.62;
        let size = radius * 0.4;
        for (button, direction, name) in [
            (PadState::A, Vec2::new(1.0, 0.0), "A"),
            (PadState::B, Vec2::new(0.0, 1.0), "B"),
            (PadState::Y, Vec2::new(-1.0, 0.0), "Y"),
            (PadState::X, Vec2::new(0.0, -1.0), "X"),
        ] {
            let middle = center + direction * spread;
            painter.circle(middle, size, fill_for(button), edge);
            painter.text(middle, Align2::CENTER_CENTER, name, FontId::proportional(size * 0.95), text);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use winit::dpi::PhysicalPosition;
    use winit::event::DeviceId;

    fn finger(id: u64, at: Pos2, phase: TouchPhase) -> Touch {
        Touch { device_id: DeviceId::dummy(), phase, location: PhysicalPosition::new(at.x as f64, at.y as f64), force: None, id }
    }

    /// the phone layout is the process's, so tests that lay it out take turns.
    static LAYOUT: std::sync::Mutex<()> = std::sync::Mutex::new(());

    /// the controls in a window of size at scale, the bottom screen percent
    /// of the top one, laid out as the app does.
    fn arranged(size: (u32, u32), scale: f32, percent: u32) -> TouchControls {
        let _turn = LAYOUT.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        zakuro_gpu::set_phone_layout(0.0, 0.0, percent);
        let mut controls = TouchControls::default();
        let arrangement = if size.1 > size.0 { zakuro_gpu::ScreenLayout::Upright } else { zakuro_gpu::ScreenLayout::Phone };
        let (top, bottom) = zakuro_gpu::layout(size.0, size.1, arrangement, 400.0, false);
        controls.arrange(size, scale, top, bottom);
        controls
    }

    fn controls() -> TouchControls {
        arranged((2400, 1080), 1.0, 100)
    }

    /// what each control covers as drawn.
    fn extents(places: &Places) -> Vec<(&'static str, Rect)> {
        let disc = |(center, radius): (Pos2, f32), reach: f32| Rect::from_center_size(center, Vec2::splat(radius * reach * 2.0));
        vec![
            ("circle", disc(places.circle, 1.0)),
            // the D-pad's arms, and the face buttons around the middle
            ("dpad", disc(places.dpad, 0.93)),
            ("face", disc(places.face, 1.02)),
            ("L", places.l),
            ("R", places.r),
            ("start", places.start),
            ("select", places.select),
            ("menu", places.menu),
        ]
    }

    /// phones of 16:9 to 21:9 and tablets, held either way, at their
    /// densities.
    const WINDOWS: [((u32, u32), f32); 8] = [
        ((2400, 1080), 2.5),
        ((1920, 1080), 2.75),
        ((2340, 1080), 2.75),
        ((2520, 1080), 2.625),
        ((3200, 1440), 3.5),
        ((1920, 1200), 1.5),
        ((2000, 1200), 1.5),
        ((2560, 1600), 2.0),
    ];

    #[test]
    fn no_control_reaches_another_or_leaves_the_window() {
        for (size, scale) in WINDOWS.into_iter().chain(WINDOWS.map(|((w, h), scale)| ((h, w), scale))) {
            for percent in [40, 50, 70, 100] {
                let controls = arranged(size, scale, percent);
                let places = controls.places.as_ref().unwrap();
                let points = Vec2::new(size.0 as f32, size.1 as f32) / scale;
                let all = extents(places);
                for (name, rect) in &all {
                    assert!(
                        rect.min.x >= -1.0 && rect.min.y >= -1.0 && rect.max.x <= points.x + 1.0 && rect.max.y <= points.y + 1.0,
                        "{size:?} at {percent}%: {name} {rect:?} is off the window"
                    );
                }
                for (at, (name, rect)) in all.iter().enumerate() {
                    for (other, other_rect) in &all[at + 1..] {
                        assert!(!rect.intersects(*other_rect), "{size:?} at {percent}%: {name} {rect:?} and {other} {other_rect:?} overlap");
                    }
                }
            }
        }
    }

    /// held sideways, the screens fill the window's width with the bottom
    /// one at half the top one's size, as in the Persona Q2 port, and a
    /// tablet's face buttons keep above the bottom screen.
    #[test]
    fn held_sideways_the_screens_fill_the_window() {
        let _turn = LAYOUT.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        zakuro_gpu::set_phone_layout(0.0, 0.0, 50);
        let (top, bottom) = zakuro_gpu::layout(1920, 1200, zakuro_gpu::ScreenLayout::Phone, 400.0, false);
        drop(_turn);
        let (top, bottom) = (top.unwrap(), bottom.unwrap());
        assert!(top.x.abs() < 1.0 && (bottom.x + bottom.width - 1920.0).abs() < 1.0, "{top:?} {bottom:?}");
        assert!((bottom.width - top.width * 320.0 / 400.0 * 0.5).abs() < 1.0);
        assert!((bottom.y + bottom.height - (top.y + top.height)).abs() < 1.0, "level at the bottom");

        let controls = arranged((1920, 1200), 1.5, 50);
        let places = controls.places.as_ref().unwrap();
        let screen = places.screen.unwrap();
        let face = extents(places)[2].1;
        assert!(!face.intersects(screen), "{face:?} over the bottom screen {screen:?}");
    }

    /// filling the window held sideways, the menu's full screen on
    /// Android, leaves no bars: on a tablet the top screen takes all of it and
    /// the bottom one goes over its corner, on a long phone the bottom one
    /// goes in the room at the right.
    #[test]
    fn filling_the_window_leaves_no_bars() {
        let _turn = LAYOUT.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        zakuro_gpu::set_phone_layout(0.0, 0.0, 50);
        zakuro_gpu::set_fill(true);
        let tablet = zakuro_gpu::layout(1920, 1200, zakuro_gpu::ScreenLayout::Phone, 400.0, false);
        let phone = zakuro_gpu::layout(2400, 1080, zakuro_gpu::ScreenLayout::Phone, 400.0, false);
        let upright = zakuro_gpu::layout(1200, 1920, zakuro_gpu::ScreenLayout::Upright, 400.0, false);
        zakuro_gpu::set_fill(false);
        drop(_turn);
        let (top, bottom) = (tablet.0.unwrap(), tablet.1.unwrap());
        assert_eq!((top.x, top.y, top.width, top.height), (0.0, 0.0, 1920.0, 1200.0));
        assert!((bottom.x + bottom.width - 1920.0).abs() < 0.5 && (bottom.y + bottom.height - 1200.0).abs() < 0.5);
        let (top, bottom) = (phone.0.unwrap(), phone.1.unwrap());
        assert!((top.height - 1080.0).abs() < 0.5 && (top.width - 1800.0).abs() < 0.5, "{top:?}");
        assert!(bottom.x >= top.x + top.width - 0.5 && (bottom.x + bottom.width - 2400.0).abs() < 0.5, "{bottom:?}");
        // upright it keeps the console's shape
        assert!(upright.0.unwrap().width < 1200.5 && upright.0.unwrap().height < 1920.0 * 0.5);
    }

    /// a tablet's controls grow with its screen rather than staying a
    /// phone's size.
    #[test]
    fn a_tablet_has_controls_as_big_as_its_screen() {
        let phone = arranged((2400, 1080), 2.5, 50);
        let tablet = arranged((1920, 1200), 1.5, 50);
        let radius = |controls: &TouchControls| controls.places.as_ref().unwrap().face.1;
        assert!(radius(&tablet) > radius(&phone) * 1.25, "{} against {}", radius(&tablet), radius(&phone));
        // the tablet's A button some 2 cm across at 240 dpi
        assert!(radius(&tablet) * 0.4 * 2.0 * 1.5 > 150.0);
    }

    #[test]
    fn a_smaller_bottom_screen_makes_the_top_one_bigger() {
        let full = arranged((2400, 1080), 2.5, 100);
        let small = arranged((2400, 1080), 2.5, 60);
        let screens = |controls: &TouchControls| {
            let places = controls.places.as_ref().unwrap();
            places.screen.unwrap()
        };
        assert!(screens(&small).width() < screens(&full).width() * 0.8);
        let _turn = LAYOUT.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        zakuro_gpu::set_phone_layout(0.0, 0.0, 60);
        let (top_small, _) = zakuro_gpu::layout(2400, 1080, zakuro_gpu::ScreenLayout::Phone, 400.0, false);
        zakuro_gpu::set_phone_layout(0.0, 0.0, 100);
        let (top_full, _) = zakuro_gpu::layout(2400, 1080, zakuro_gpu::ScreenLayout::Phone, 400.0, false);
        assert!(top_small.unwrap().width > top_full.unwrap().width * 1.1);
    }

    /// a finger on a control over the bottom screen presses the control.
    #[test]
    fn a_control_over_the_bottom_screen_takes_the_touch() {
        let mut controls = arranged((2400, 1080), 1.0, 50);
        let places = controls.places.as_ref().unwrap();
        let (center, radius) = places.face;
        let b = center + Vec2::new(0.0, radius * 0.62);
        controls.touch(&finger(4, b, TouchPhase::Started), 1.0);
        let input = controls.apply(InputState::default());
        assert!(input.buttons.contains(PadState::B) && input.touch.is_none());
    }

    #[test]
    fn a_finger_on_a_button_holds_it_until_it_leaves() {
        let mut controls = controls();
        let (center, radius) = controls.places.as_ref().unwrap().face;
        let a = center + Vec2::new(radius * 0.6, 0.0);
        controls.touch(&finger(1, a, TouchPhase::Started), 1.0);
        assert!(controls.apply(InputState::default()).buttons.contains(PadState::A));
        controls.touch(&finger(1, a, TouchPhase::Ended), 1.0);
        assert!(!controls.apply(InputState::default()).buttons.contains(PadState::A));
    }

    #[test]
    fn the_bottom_screen_takes_touches_in_its_own_pixels() {
        let mut controls = controls();
        let screen = controls.places.as_ref().unwrap().screen.unwrap();
        controls.touch(&finger(2, screen.center(), TouchPhase::Started), 1.0);
        let (x, y) = controls.apply(InputState::default()).touch.unwrap();
        assert!(x.abs_diff(160) <= 1 && y.abs_diff(120) <= 1, "{x}, {y}");
    }

    #[test]
    fn the_circle_pad_points_up_when_pushed_up() {
        let mut controls = controls();
        let (center, radius) = controls.places.as_ref().unwrap().circle;
        controls.touch(&finger(3, center, TouchPhase::Started), 1.0);
        controls.touch(&finger(3, center - Vec2::new(0.0, radius), TouchPhase::Moved), 1.0);
        let input = controls.apply(InputState::default());
        assert!(input.circle_y > 0.9 && input.circle_x.abs() < 0.01);
    }
}
