//! what the renderer draws with on GPUs short of Vulkan 1.3, as phones'
//! are: Mali and Adreno with drivers of Vulkan 1.1 or 1.2, AMD's Xclipse,
//! which has no D24S8, PowerVR and Mali without depth clamping. each thing
//! Vulkan 1.3 or an extension does has a way round it on a GPU that lacks
//! it: render passes for dynamic rendering, the depth, stencil and cull
//! state held by pipelines rather than set by draws, D32S8 for D24S8, and
//! depth clamped by the shaders. ZAKURO_RENDER_PASSES=1,
//! ZAKURO_FIXED_STATE=1, ZAKURO_DEPTH_FLOAT=1 and ZAKURO_NO_DEPTH_CLAMP=1
//! take each way round on any GPU, to compare them, or to step around a
//! driver that has the thing but gets it wrong, and ZAKURO_VULKAN=1.1 uses
//! a GPU as one of Vulkan 1.1 would be, with only its extensions.

use std::ffi::CStr;

use ash::vk;

use super::{vk_error, COLOR_FORMAT};

/// the newest Vulkan the renderer uses, the loader's own when older, or
/// ZAKURO_VULKAN's.
pub(crate) fn api_version(entry: &ash::Entry) -> u32 {
    // SAFETY: a query of the loader, which has no requirements
    let loader = unsafe { entry.try_enumerate_instance_version() }.ok().flatten().unwrap_or(vk::API_VERSION_1_0);
    let wanted = match std::env::var("ZAKURO_VULKAN").as_deref() {
        Ok("1.0") => vk::API_VERSION_1_0,
        Ok("1.1") => vk::API_VERSION_1_1,
        Ok("1.2") => vk::API_VERSION_1_2,
        _ => vk::API_VERSION_1_3,
    };
    loader.min(wanted)
}

/// whether an environment variable is set to anything but 0.
fn forced(name: &str) -> bool {
    std::env::var(name).is_ok_and(|value| !value.is_empty() && value != "0")
}

/// how draws go into images.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Rendering {
    /// Vulkan 1.3's dynamic rendering.
    Dynamic,
    /// render passes and framebuffers, every attachment loaded and stored
    /// in the general layout, as the dynamic renderings do.
    Passes,
}

/// where the depth, stencil and cull state of a draw comes from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum States {
    /// set by the draw, Vulkan 1.3's commands.
    Core,
    /// set by the draw, VK_EXT_extended_dynamic_state's.
    Extension,
    /// held by the pipeline, one for each combination draws use.
    Fixed,
}

/// what the GPU lets the renderer use, and the ways round what it lacks.
#[derive(Clone, Copy)]
pub(crate) struct Caps {
    /// the Vulkan the device is used at, the older of the instance's and
    /// the device's.
    pub(crate) version: u32,
    pub(crate) rendering: Rendering,
    pub(crate) states: States,
    /// whether a pipeline can be asked for only when the cache holds it,
    /// and whether that is VK_EXT_pipeline_creation_cache_control's.
    pub(crate) cache_control: bool,
    pub(crate) cache_control_extension: bool,
    /// whether the rasterizer clamps depth, or else raster.vert clamps the
    /// vertices' and draws past the range work it out per fragment.
    pub(crate) depth_clamp: bool,
    /// D24S8, or D32S8 where there is none, whose depth is a float.
    pub(crate) depth_format: vk::Format,
}

impl Caps {
    /// whether depth buffers hold floats rather than 24-bit words.
    pub(crate) fn float_depth(&self) -> bool {
        self.depth_format != vk::Format::D24_UNORM_S8_UINT
    }

    /// the word a depth image is given for a 24-bit depth value.
    pub(crate) fn depth_word(&self, d24: u32) -> u32 {
        match self.float_depth() {
            true => (((d24 & 0xFF_FFFF) as f64 / 16_777_215.0) as f32).to_bits(),
            false => d24,
        }
    }

    /// the 24-bit depth value a word read out of a depth image stands for.
    pub(crate) fn depth_value(&self, word: u32) -> u32 {
        match self.float_depth() {
            true => (f32::from_bits(word).clamp(0.0, 1.0) as f64 * 16_777_215.0).round() as u32,
            false => word & 0xFF_FFFF,
        }
    }

    /// a line for the log, of what the GPU goes without.
    pub(crate) fn describe(&self) -> String {
        let mut ways = Vec::new();
        if self.rendering == Rendering::Passes {
            ways.push("render passes");
        }
        match self.states {
            States::Core => {}
            States::Extension => ways.push("VK_EXT_extended_dynamic_state"),
            States::Fixed => ways.push("depth and stencil state in pipelines"),
        }
        if self.float_depth() {
            ways.push("D32S8 depth");
        }
        if !self.depth_clamp {
            ways.push("depth clamped in shaders");
        }
        if !self.cache_control {
            ways.push("no cache-only pipelines");
        }
        let version = format!("Vulkan {}.{}", vk::api_version_major(self.version), vk::api_version_minor(self.version));
        match ways.is_empty() {
            true => version,
            false => format!("{version}, {}", ways.join(", ")),
        }
    }
}

/// the depth format the renderer draws with on a GPU, none when it has no
/// D24S8 or D32S8 to draw into and copy to and from buffers.
pub(crate) fn depth_format(instance: &ash::Instance, physical: vk::PhysicalDevice) -> Option<vk::Format> {
    let usable = |format: vk::Format| {
        // SAFETY: a query on a GPU of the instance
        let features = unsafe { instance.get_physical_device_format_properties(physical, format) }.optimal_tiling_features;
        // Vulkan 1.0 drivers report no transfer bits, transfers are allowed
        // on every format there
        let transfers = vk::FormatFeatureFlags::TRANSFER_SRC | vk::FormatFeatureFlags::TRANSFER_DST;
        features.contains(vk::FormatFeatureFlags::DEPTH_STENCIL_ATTACHMENT)
            && (features.contains(transfers) || !features.intersects(transfers))
    };
    let wanted: &[vk::Format] = if forced("ZAKURO_DEPTH_FLOAT") {
        &[vk::Format::D32_SFLOAT_S8_UINT]
    } else {
        &[vk::Format::D24_UNORM_S8_UINT, vk::Format::D32_SFLOAT_S8_UINT]
    };
    wanted.iter().copied().find(|&format| usable(format))
}

/// what a GPU lets the renderer use, at the Vulkan the instance was made
/// with, and the extensions to turn on for it.
pub(crate) fn caps(
    entry: &ash::Entry,
    instance: &ash::Instance,
    physical: vk::PhysicalDevice,
    has: &dyn Fn(&CStr) -> bool,
) -> Result<Caps, String> {
    // SAFETY: queries on a GPU of the instance
    let (properties, features) =
        unsafe { (instance.get_physical_device_properties(physical), instance.get_physical_device_features(physical)) };
    let version = api_version(entry).min(properties.api_version);
    let core = version >= vk::API_VERSION_1_3;
    let depth_format = depth_format(instance, physical).ok_or("no D24S8 or D32S8 depth format")?;

    // the features of the extensions, which take Vulkan 1.1's query
    let mut dynamic_state = vk::PhysicalDeviceExtendedDynamicStateFeaturesEXT::default();
    let mut cache_control = vk::PhysicalDevicePipelineCreationCacheControlFeaturesEXT::default();
    let (dynamic_state_extension, cache_control_extension) = (
        has(ash::ext::extended_dynamic_state::NAME),
        has(ash::ext::pipeline_creation_cache_control::NAME),
    );
    if !core && version >= vk::API_VERSION_1_1 {
        let mut query = vk::PhysicalDeviceFeatures2::default();
        if dynamic_state_extension {
            query = query.push_next(&mut dynamic_state);
        }
        if cache_control_extension {
            query = query.push_next(&mut cache_control);
        }
        // SAFETY: as above, with structures that outlive the call
        unsafe { instance.get_physical_device_features2(physical, &mut query) };
    }

    let rendering = if core && !forced("ZAKURO_RENDER_PASSES") { Rendering::Dynamic } else { Rendering::Passes };
    let states = if forced("ZAKURO_FIXED_STATE") {
        States::Fixed
    } else if core {
        States::Core
    } else if dynamic_state_extension && dynamic_state.extended_dynamic_state == vk::TRUE {
        States::Extension
    } else {
        States::Fixed
    };
    let cache_control_extension = !core && cache_control_extension && cache_control.pipeline_creation_cache_control == vk::TRUE;
    Ok(Caps {
        version,
        rendering,
        states,
        cache_control: core || cache_control_extension,
        cache_control_extension,
        depth_clamp: features.depth_clamp == vk::TRUE && !forced("ZAKURO_NO_DEPTH_CLAMP"),
        depth_format,
    })
}

/// the render passes draws and clears go through when there is no dynamic
/// rendering, into a color buffer, a color buffer and a depth buffer, or a
/// depth buffer alone. what is drawn stays in the images in the general
/// layout, and the batch's own barriers order everything around them, as
/// with dynamic rendering.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Passes {
    pub(crate) color: vk::RenderPass,
    pub(crate) both: vk::RenderPass,
    pub(crate) depth: vk::RenderPass,
}

impl Passes {
    pub(crate) fn new(device: &ash::Device, depth_format: vk::Format) -> Result<Passes, String> {
        let color = vk::AttachmentDescription::default()
            .format(COLOR_FORMAT)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::LOAD)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::GENERAL)
            .final_layout(vk::ImageLayout::GENERAL);
        let depth = vk::AttachmentDescription::default()
            .format(depth_format)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::LOAD)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::LOAD)
            .stencil_store_op(vk::AttachmentStoreOp::STORE)
            .initial_layout(vk::ImageLayout::GENERAL)
            .final_layout(vk::ImageLayout::GENERAL);
        let pass = |attachments: &[vk::AttachmentDescription], with_color: bool, with_depth: bool| {
            let color_ref = [vk::AttachmentReference { attachment: 0, layout: vk::ImageLayout::GENERAL }];
            let depth_ref = vk::AttachmentReference { attachment: with_color as u32, layout: vk::ImageLayout::GENERAL };
            let mut subpass = vk::SubpassDescription::default().pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS);
            if with_color {
                subpass = subpass.color_attachments(&color_ref);
            }
            if with_depth {
                subpass = subpass.depth_stencil_attachment(&depth_ref);
            }
            let subpasses = [subpass];
            let info = vk::RenderPassCreateInfo::default().attachments(attachments).subpasses(&subpasses);
            // SAFETY: a plain object of the device, the infos outlive the call
            unsafe { device.create_render_pass(&info, None) }.map_err(vk_error("create a render pass"))
        };
        let color_pass = pass(&[color], true, false)?;
        let both = pass(&[color, depth], true, true).inspect_err(|_| {
            // SAFETY: made just above, not used yet
            unsafe { device.destroy_render_pass(color_pass, None) };
        })?;
        let depth_pass = pass(&[depth], false, true).inspect_err(|_| {
            // SAFETY: as above
            unsafe {
                device.destroy_render_pass(color_pass, None);
                device.destroy_render_pass(both, None);
            }
        })?;
        Ok(Passes { color: color_pass, both, depth: depth_pass })
    }

    /// the pass for a rendering into the views given.
    pub(crate) fn of(&self, color: bool, depth: bool) -> vk::RenderPass {
        match (color, depth) {
            (true, true) => self.both,
            (true, false) => self.color,
            _ => self.depth,
        }
    }

    /// SAFETY: once nothing made with them is in use.
    pub(crate) unsafe fn destroy(&self, device: &ash::Device) {
        unsafe {
            device.destroy_render_pass(self.color, None);
            device.destroy_render_pass(self.both, None);
            device.destroy_render_pass(self.depth, None);
        }
    }
}

/// the depth, stencil and cull state of a draw, part of its pipeline's key
/// when draws can't set it, the same for draws that differ only in what
/// their state leaves unused.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) struct Fixed {
    pub(crate) cull: u32,
    pub(crate) depth_test: bool,
    pub(crate) depth_compare: i32,
    pub(crate) depth_write: bool,
    pub(crate) stencil_test: bool,
    /// the fail, depth fail and pass ops and the compare op, the PICA's.
    pub(crate) stencil_ops: u32,
    pub(crate) stencil_compare: i32,
}

impl Fixed {
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn new(
        cull: vk::CullModeFlags,
        depth_test: bool,
        depth_compare: vk::CompareOp,
        depth_write: bool,
        stencil_test: bool,
        stencil_ops: u32,
        stencil_compare: vk::CompareOp,
    ) -> Fixed {
        Fixed {
            cull: cull.as_raw(),
            depth_test,
            depth_compare: if depth_test { depth_compare.as_raw() } else { vk::CompareOp::ALWAYS.as_raw() },
            // Vulkan writes depth only while testing it
            depth_write: depth_test && depth_write,
            stencil_test,
            stencil_ops: if stencil_test { stencil_ops & 0x777 } else { 0 },
            stencil_compare: if stencil_test { stencil_compare.as_raw() } else { vk::CompareOp::ALWAYS.as_raw() },
        }
    }

    pub(crate) fn cull(&self) -> vk::CullModeFlags {
        vk::CullModeFlags::from_raw(self.cull)
    }

    /// the pipeline's depth and stencil state, the masks and the reference
    /// left to the draw.
    pub(crate) fn depth_stencil(&self) -> vk::PipelineDepthStencilStateCreateInfo<'static> {
        let op = |raw: u32| vk::StencilOp::from_raw((raw & 7) as i32);
        let face = vk::StencilOpState {
            fail_op: op(self.stencil_ops),
            pass_op: op(self.stencil_ops >> 8),
            depth_fail_op: op(self.stencil_ops >> 4),
            compare_op: vk::CompareOp::from_raw(self.stencil_compare),
            compare_mask: 0,
            write_mask: 0,
            reference: 0,
        };
        vk::PipelineDepthStencilStateCreateInfo::default()
            .depth_test_enable(self.depth_test)
            .depth_write_enable(self.depth_write)
            .depth_compare_op(vk::CompareOp::from_raw(self.depth_compare))
            .stencil_test_enable(self.stencil_test)
            .front(face)
            .back(face)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// every 24-bit depth value comes back the same out of a float depth
    /// buffer.
    #[test]
    fn depth_values_survive_a_float_depth_buffer() {
        let caps = Caps {
            version: vk::API_VERSION_1_1,
            rendering: Rendering::Passes,
            states: States::Fixed,
            cache_control: false,
            cache_control_extension: false,
            depth_clamp: false,
            depth_format: vk::Format::D32_SFLOAT_S8_UINT,
        };
        for d24 in (0..=0xFF_FFFFu32).step_by(7).chain([0, 1, 0x7F_FFFF, 0x80_0000, 0xFF_FFFE, 0xFF_FFFF]) {
            assert_eq!(caps.depth_value(caps.depth_word(d24)), d24, "{d24:06X}");
        }
        // the clears' values, made in single precision
        for d24 in [0u32, 1, 0x12_3456, 0xFF_FFFE, 0xFF_FFFF] {
            assert_eq!(caps.depth_value((d24 as f32 / 16_777_215.0).to_bits()), d24);
        }
        let d24 = Caps { depth_format: vk::Format::D24_UNORM_S8_UINT, ..caps };
        assert_eq!(d24.depth_value(0xAB12_3456), 0x12_3456);
        assert_eq!(d24.depth_word(0x12_3456), 0x12_3456);
    }

    /// state a draw leaves unused does not tell pipelines apart.
    #[test]
    fn unused_state_does_not_tell_pipelines_apart() {
        let off = |compare, write, ops| Fixed::new(vk::CullModeFlags::NONE, false, compare, write, false, ops, compare);
        assert_eq!(off(vk::CompareOp::LESS, true, 0x123), off(vk::CompareOp::GREATER, false, 0x456));
        let on = |compare| Fixed::new(vk::CullModeFlags::BACK, true, compare, true, false, 0, vk::CompareOp::NEVER);
        assert_ne!(on(vk::CompareOp::LESS), on(vk::CompareOp::GREATER));
    }
}
