/* sh1_hw_vk.cpp — v9.9 the VULKAN hardware backend (offscreen, headless).
 * ============================================================================
 * Renders the SAME HwBatch as the GLES backend (sh1_hw_render.c) with the
 * SAME shader logic (hw_mesh_vk.vert/.frag — the SPIR-V flavor of
 * sh1_hw_shaders.h; the dither block is the SWANSTATION-VERBATIM
 * emission, the UV DDA is the v9.8-verbatim block) into an offscreen
 * 2-attachment framebuffer (RGBA8 color + R32F flake-occlusion z), then
 * copies both images back to host memory. NO window system integration:
 * presentation stays the engine's own CPU blit in every backend, so no
 * ANativeWindow/Vulkan-surface interop is needed and the surface
 * lifecycle is IDENTICAL across all three backends.
 *
 * SWANSTATION-VERBATIM components (copied unmodified; only the util.h
 * include of the unused ../string.h was dropped — portability boiler-
 * plate, the v9.8 session's program.cpp precedent):
 *   - vk/vulkan_loader.{h,cpp}  (dep/vulkan-loader — the dlopen loader;
 *     on Android it finds libvulkan.so via the same search list)
 *   - vk/builders.{h,cpp}       (src/common/vulkan — DescriptorSetLayout/
 *     PipelineLayout/GraphicsPipelineBuilder: ALL pipeline creation)
 *   - vk/util.{h,cpp}           (src/common/vulkan — Safe* destroy
 *     helpers, SetViewportAndScissor)
 *
 * The SPIR-V is precompiled OFFLINE from the checked-in GLSL by
 * glslangValidator (the same toolchain family Swanstation's pre-baked
 * embedded_spirv pipeline uses — see its shader_cache.h: "every shader
 * in the Vulkan backend is pre-baked into the build at .inc-include
 * time"). sh1_hw_vk_spv.h embeds the IDENTICAL bytes on Android; they
 * are HAND-SYNCED after shader edits (no regeneration script exists in
 * this repo). The desktop harness loads the same embedded bytes (one
 * code path, no divergence).
 *
 * Fallback contract: any init/render failure returns 0 and the caller
 * (sh1_hw_render.c) falls back to the software renderer (logged).
 * v45 audit H-5: every init-failure path now also TEARS DOWN its
 * partial state (sh1_vk_hw_shutdown — the post-device paths' pattern):
 * no leaked VkInstance / device objects / dlopen'd libvulkan, and the
 * failure latch behaves identically on every path (shutdown resets it,
 * so each engine restart re-attempts init once — the pre-v45 early
 * paths leaked AND were sticky, the asymmetric combination H-5 flagged).
 * ============================================================================
 */
#include "sh1_hw_types.h"

#include "vk/vulkan_loader.h"
#include "vk/builders.h"
#include "vk/util.h"
#include "vk/sw_shim_deps.h"   /* the util.cpp portability shims */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define VKLOGI(...) __android_log_print(ANDROID_LOG_INFO,  "sh1-vk", __VA_ARGS__)
#define VKLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "sh1-vk", __VA_ARGS__)
#else
#define VKLOGI(...) do { printf("[sh1-vk] " __VA_ARGS__); printf("\n"); } while (0)
#define VKLOGE(...) do { fprintf(stderr, "[sh1-vk ERR] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

/* the embedded SPIR-V (hand-synced from hw_mesh_vk.vert/.frag — no
 * regeneration script exists; must stay byte-identical to the .spv
 * files) */
#include "sh1_hw_vk_spv.h"

/* ==========================================================================
 * state
 * ========================================================================*/
static bool g_vk_ok = false;          /* fully initialized                   */
static bool g_vk_failed = false;      /* init failed — set by every failure
                                         path; sh1_vk_hw_shutdown() RESETS it
                                         (v45 H-5), so all failures retry on
                                         the next engine restart, exactly
                                         like the post-device paths always
                                         have                             */
static VkInstance g_inst = VK_NULL_HANDLE;
static VkPhysicalDevice g_pdev = VK_NULL_HANDLE;
static uint32_t g_gfxQueueFamily = 0;
static VkDevice g_dev = VK_NULL_HANDLE;
static VkQueue g_queue = VK_NULL_HANDLE;
static VkCommandPool g_cmdPool = VK_NULL_HANDLE;
static VkCommandBuffer g_cmd = VK_NULL_HANDLE;
static VkFence g_fence = VK_NULL_HANDLE;

static int g_w = 0, g_h = 0;

/* attachments */
static VkImage g_imgColor = VK_NULL_HANDLE, g_imgZ = VK_NULL_HANDLE;
static VkDeviceMemory g_memColor = VK_NULL_HANDLE, g_memZ = VK_NULL_HANDLE;
static VkImageView g_viewColor = VK_NULL_HANDLE, g_viewZ = VK_NULL_HANDLE;

/* the scene texture array */
static VkImage g_imgTex = VK_NULL_HANDLE;
static VkDeviceMemory g_memTex = VK_NULL_HANDLE;
static VkImageView g_viewTex = VK_NULL_HANDLE;
static VkSampler g_sampler = VK_NULL_HANDLE;
static VkDeviceMemory g_memStaging = VK_NULL_HANDLE;
static VkBuffer g_bufStaging = VK_NULL_HANDLE;
static void *g_stagingMap = NULL;

/* pipelines + layout */
static VkRenderPass g_renderPass = VK_NULL_HANDLE;
static VkFramebuffer g_framebuffer = VK_NULL_HANDLE;
static VkDescriptorSetLayout g_dsl = VK_NULL_HANDLE;
static VkDescriptorPool g_descPool = VK_NULL_HANDLE;
static VkDescriptorSet g_descSet = VK_NULL_HANDLE;
static VkPipelineLayout g_pipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_pipelineZ = VK_NULL_HANDLE;     /* att0 masked OFF    */
static VkPipeline g_pipelineC = VK_NULL_HANDLE;     /* att1 masked OFF    */

/* per-frame vertex buffers (host-visible, persistent mapping) */
static const size_t VK_MAX_TRIS = 8192;
static VkBuffer g_bufVerts = VK_NULL_HANDLE, g_bufInsts = VK_NULL_HANDLE;
static VkBuffer g_bufZVerts = VK_NULL_HANDLE, g_bufZInsts = VK_NULL_HANDLE;
static VkDeviceMemory g_memVerts = VK_NULL_HANDLE, g_memInsts = VK_NULL_HANDLE;
static VkDeviceMemory g_memZVerts = VK_NULL_HANDLE, g_memZInsts = VK_NULL_HANDLE;
static HwVert *g_mapVerts = NULL, *g_mapZVerts = NULL;
static HwInst *g_mapInsts = NULL, *g_mapZInsts = NULL;

/* readback buffers */
static VkBuffer g_bufReadC = VK_NULL_HANDLE, g_bufReadZ = VK_NULL_HANDLE;
static VkDeviceMemory g_memReadC = VK_NULL_HANDLE, g_memReadZ = VK_NULL_HANDLE;
static unsigned char *g_mapReadC = NULL;
static float *g_mapReadZ = NULL;

/* ==========================================================================
 * small helpers (memory type search)
 * ========================================================================*/
static bool vk_find_memory(uint32_t typeBits, VkMemoryPropertyFlags props,
                           uint32_t *outIdx) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(g_pdev, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) &&
            (mp.memoryTypes[i].propertyFlags & props) == props) {
            *outIdx = i;
            return true;
        }
    }
    return false;
}

static bool vk_alloc_buf(VkBuffer *buf, VkDeviceMemory *mem, void **map,
                         VkDeviceSize size, VkBufferUsageFlags usage) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = usage;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(g_dev, &bi, nullptr, buf) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(g_dev, *buf, &mr);
    uint32_t idx;
    if (!vk_find_memory(mr.memoryTypeBits,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &idx))
        return false;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = idx;
    if (vkAllocateMemory(g_dev, &ai, nullptr, mem) != VK_SUCCESS) return false;
    if (vkBindBufferMemory(g_dev, *buf, *mem, 0) != VK_SUCCESS) return false;
    if (map) {
        if (vkMapMemory(g_dev, *mem, 0, size, 0, map) != VK_SUCCESS) return false;
    }
    return true;
}

static bool vk_alloc_img(VkImage *img, VkDeviceMemory *mem, VkImageView *view,
                         uint32_t w, uint32_t h, uint32_t layers,
                         VkFormat fmt, VkImageUsageFlags usage,
                         VkImageViewType viewType) {
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = fmt;
    ci.extent = {w, h, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = layers;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateImage(g_dev, &ci, nullptr, img) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(g_dev, *img, &mr);
    uint32_t idx;
    if (!vk_find_memory(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        &idx)) {
        if (!vk_find_memory(mr.memoryTypeBits,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, &idx))
            return false;
    }
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = idx;
    if (vkAllocateMemory(g_dev, &ai, nullptr, mem) != VK_SUCCESS) return false;
    if (vkBindImageMemory(g_dev, *img, *mem, 0) != VK_SUCCESS) return false;
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = *img;
    vi.viewType = viewType;
    vi.format = fmt;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, layers};
    return vkCreateImageView(g_dev, &vi, nullptr, view) == VK_SUCCESS;
}

static void vk_barrier(VkCommandBuffer cb, VkImage img,
                       VkImageLayout oldL, VkImageLayout newL) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.oldLayout = oldL;
    b.newLayout = newL;
    b.image = img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, VK_REMAINING_ARRAY_LAYERS};
    b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                         VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
                         0, nullptr, 0, nullptr, 1, &b);
}

/* ==========================================================================
 * init
 * ========================================================================*/
extern "C" int sh1_vk_hw_init(int w, int h, const unsigned char *texBlob,
                              const HwTex *tex, int texCount) {
    if (g_vk_failed) return 0;
    if (g_vk_ok) return 1;
    (void)texBlob; (void)tex;

    /* --- the Swanstation loader: dlopen libvulkan + the fn pointers --- */
    if (!Vulkan::LoadVulkanLibrary()) {
        VKLOGE("libvulkan not loadable — staying on software");
        g_vk_failed = true;
        return 0;
    }

    /* --- instance (headless: NO surface extensions) ------------------- */
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "sh1-fog";
    app.pEngineName = "sh1poc";
    app.apiVersion = VK_API_VERSION_1_0;   /* the Android-safe baseline */
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    if (vkCreateInstance(&ici, nullptr, &g_inst) != VK_SUCCESS) {
        VKLOGE("vkCreateInstance failed");
        g_vk_failed = true;
        sh1_vk_hw_shutdown();   /* v45 H-5: no instance — release the dlopen */
        return 0;
    }
    if (!Vulkan::LoadVulkanInstanceFunctions(g_inst)) {
        VKLOGE("instance function loading failed");
        g_vk_failed = true;
        /* v45 audit H-5: symmetric cleanup, with one guard — a load
         * FAILURE means some REQUIRED instance point is null, possibly
         * exactly vkDestroyInstance (which shutdown() would call on
         * the live g_inst). If so, the instance cannot be destroyed
         * and leaks to process exit; the dlopen'd library is still
         * released.                                                          */
        if (vkDestroyInstance != nullptr)
            sh1_vk_hw_shutdown();
        else
            Vulkan::UnloadVulkanLibrary();
        return 0;
    }

    /* --- physical device + graphics queue ------------------------------ */
    uint32_t nDev = 0;
    vkEnumeratePhysicalDevices(g_inst, &nDev, nullptr);
    if (nDev == 0) { VKLOGE("no Vulkan devices"); g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
    std::vector<VkPhysicalDevice> devs(nDev);
    vkEnumeratePhysicalDevices(g_inst, &nDev, devs.data());
    bool found = false;
    for (VkPhysicalDevice d : devs) {
        uint32_t nQ = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(d, &nQ, nullptr);
        std::vector<VkQueueFamilyProperties> qf(nQ);
        vkGetPhysicalDeviceQueueFamilyProperties(d, &nQ, qf.data());
        for (uint32_t i = 0; i < nQ; i++) {
            if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                g_pdev = d;
                g_gfxQueueFamily = i;
                found = true;
                break;
            }
        }
        if (found) break;
    }
    if (!found) { VKLOGE("no graphics queue"); g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }

    /* --- format check: R32F as a color attachment (the z attachment;
     * core Vulkan makes this OPTIONAL — query, and fall back cleanly) --- */
    {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(g_pdev, VK_FORMAT_R32_SFLOAT, &fp);
        if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT)) {
            VKLOGE("R32F not color-attachable on this device — staying on "
                   "software");
            g_vk_failed = true;
            sh1_vk_hw_shutdown();   /* v45 H-5: was a bare return — leaked
                                     * the VkInstance + the dlopen'd lib */
            return 0;
        }
    }

    const float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = g_gfxQueueFamily;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    if (vkCreateDevice(g_pdev, &dci, nullptr, &g_dev) != VK_SUCCESS) {
        VKLOGE("vkCreateDevice failed");
        g_vk_failed = true;
        sh1_vk_hw_shutdown();   /* v45 H-5: was a bare return — leaked */
        return 0;
    }
    if (!Vulkan::LoadVulkanDeviceFunctions(g_dev)) {
        VKLOGE("device function loading failed");
        g_vk_failed = true;
        /* v45 audit H-5: same guard class as the instance loader site
         * above — a failed device load may have nulled exactly the
         * pointers shutdown() needs for the live g_dev (vkDeviceWaitIdle
         * / vkDestroyDevice are REQUIRED points). Best effort then:
         * destroy the instance (its points ARE fully loaded) and
         * release the library; the device object cannot be destroyed
         * and leaks to process exit.                                       */
        if (vkDeviceWaitIdle != nullptr && vkDestroyDevice != nullptr) {
            sh1_vk_hw_shutdown();
        } else {
            vkDestroyInstance(g_inst, nullptr);
            g_inst = VK_NULL_HANDLE;
            Vulkan::UnloadVulkanLibrary();
        }
        return 0;
    }
    vkGetDeviceQueue(g_dev, g_gfxQueueFamily, 0, &g_queue);
    Vulkan::ShimSetDevice(g_dev);   /* util.cpp SafeDestroy* target */

    /* --- command pool / buffer / fence --------------------------------- */
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = g_gfxQueueFamily;
    if (vkCreateCommandPool(g_dev, &pci, nullptr, &g_cmdPool) != VK_SUCCESS)
        { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = g_cmdPool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(g_dev, &cai, &g_cmd) != VK_SUCCESS)
        { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(g_dev, &fci, nullptr, &g_fence) != VK_SUCCESS)
        { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }

    g_w = w; g_h = h;

    /* --- the two color-attachment images ------------------------------- */
    if (!vk_alloc_img(&g_imgColor, &g_memColor, &g_viewColor, (uint32_t)w,
                      (uint32_t)h, 1, VK_FORMAT_R8G8B8A8_UNORM,
                      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                      VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                      VK_IMAGE_VIEW_TYPE_2D) ||
        !vk_alloc_img(&g_imgZ, &g_memZ, &g_viewZ, (uint32_t)w, (uint32_t)h, 1,
                      VK_FORMAT_R32_SFLOAT,
                      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                      VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                      VK_IMAGE_VIEW_TYPE_2D)) {
        VKLOGE("attachment image allocation failed");
        g_vk_failed = true;
        sh1_vk_hw_shutdown();   /* v45 H-5: the audit's ":307/:313/:316/..."
                                 * claim missed this one — same pattern */
        return 0;
    }

    /* --- the scene texture array (padded layers, wrap is in-shader) ----- */
    int layers = texCount > 0 ? texCount : 1;
    int maxW = 1, maxH = 1;
    for (int i = 0; i < texCount; i++) {
        if (tex[i].w > maxW) maxW = tex[i].w;
        if (tex[i].h > maxH) maxH = tex[i].h;
    }
    if (maxW > 2048) maxW = 2048;
    if (maxH > 2048) maxH = 2048;
    if (!vk_alloc_img(&g_imgTex, &g_memTex, &g_viewTex, (uint32_t)maxW,
                      (uint32_t)maxH, (uint32_t)layers,
                      VK_FORMAT_R8G8B8A8_UNORM,
                      VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                      VK_IMAGE_USAGE_SAMPLED_BIT,
                      VK_IMAGE_VIEW_TYPE_2D_ARRAY)) {
        VKLOGE("texture array allocation failed");
        g_vk_failed = true;
        sh1_vk_hw_shutdown();   /* v45 H-5 */
        return 0;
    }

    /* --- the render pass (2 attachments, single subpass) ---------------- */
    VkAttachmentDescription att[2];
    att[0] = {};
    att[0].format = VK_FORMAT_R8G8B8A8_UNORM;
    att[0].samples = VK_SAMPLE_COUNT_1_BIT;
    att[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att[0].finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    att[1] = {};
    att[1].format = VK_FORMAT_R32_SFLOAT;
    att[1].samples = VK_SAMPLE_COUNT_1_BIT;
    att[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att[1].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att[1].finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    VkAttachmentReference refs[2] = {{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL},
                                     {1, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL}};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 2;
    sub.pColorAttachments = refs;
    VkRenderPassCreateInfo rpi{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rpi.attachmentCount = 2;
    rpi.pAttachments = att;
    rpi.subpassCount = 1;
    rpi.pSubpasses = &sub;
    if (vkCreateRenderPass(g_dev, &rpi, nullptr, &g_renderPass) != VK_SUCCESS)
        { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
    {
        VkFramebufferCreateInfo fbi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fbi.renderPass = g_renderPass;
        fbi.attachmentCount = 2;
        VkImageView views[2] = {g_viewColor, g_viewZ};
        fbi.pAttachments = views;
        fbi.width = (uint32_t)w;
        fbi.height = (uint32_t)h;
        fbi.layers = 1;
        if (vkCreateFramebuffer(g_dev, &fbi, nullptr, &g_framebuffer) != VK_SUCCESS)
            { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
    }

    /* --- the texture upload (staging buffer + copy) --------------------- */
    {
        /* upload each layer padded into a host staging buffer             */
        size_t layerBytes = (size_t)maxW * maxH * 4;
        size_t total = layerBytes * (size_t)layers;
        if (!vk_alloc_buf(&g_bufStaging, &g_memStaging, &g_stagingMap,
                          (VkDeviceSize)total,
                          VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            VKLOGE("staging buffer failed");
            g_vk_failed = true;
            sh1_vk_hw_shutdown();   /* v45 H-5 */
            return 0;
        }
        memset(g_stagingMap, 0, total);
        for (int i = 0; i < texCount; i++) {
            /* >2048 (or empty) layers are SKIPPED silently — the SW path
             * has no size limit, so such a texture would render in
             * software yet vanish here (the layer stays zero = alpha-0
             * discard). PS1 texture pages are <=256x256, so the real
             * corpus never hits this; the GLES backend skips
             * identically (sh1_hw_render.c). */
            if (tex[i].w <= 0 || tex[i].h <= 0 || tex[i].w > 2048 ||
                tex[i].h > 2048)
                continue;
            const unsigned char *src = texBlob + tex[i].off;
            unsigned char *dst = (unsigned char *)g_stagingMap +
                                 (size_t)i * layerBytes;
            for (int y = 0; y < tex[i].h; y++)
                memcpy(dst + (size_t)y * maxW * 4,
                       src + (size_t)y * tex[i].w * 4,
                       (size_t)tex[i].w * 4);
        }
        VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(g_cmd, &cbi);
        vk_barrier(g_cmd, g_imgTex, VK_IMAGE_LAYOUT_UNDEFINED,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkBufferImageCopy cpy{};
        cpy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0,
                                (uint32_t)layers};
        cpy.imageExtent = {(uint32_t)maxW, (uint32_t)maxH, 1};
        vkCmdCopyBufferToImage(g_cmd, g_bufStaging, g_imgTex,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &cpy);
        vk_barrier(g_cmd, g_imgTex, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        vkEndCommandBuffer(g_cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &g_cmd;
        vkResetFences(g_dev, 1, &g_fence);
        vkQueueSubmit(g_queue, 1, &si, g_fence);
        vkWaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
    }

    /* --- sampler + descriptor set (set 0, binding 0: the array) --------- */
    {
        VkSamplerCreateInfo sci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        sci.magFilter = VK_FILTER_NEAREST;
        sci.minFilter = VK_FILTER_NEAREST;
        sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sci.maxLod = 1.0f;
        if (vkCreateSampler(g_dev, &sci, nullptr, &g_sampler) != VK_SUCCESS)
            { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
    }
    {
        /* SWANSTATION VERBATIM: DescriptorSetLayoutBuilder */
        Vulkan::DescriptorSetLayoutBuilder dslb;
        dslb.AddBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                        VK_SHADER_STAGE_FRAGMENT_BIT);
        g_dsl = dslb.Create(g_dev);
        if (g_dsl == VK_NULL_HANDLE) { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
        VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
        VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dpi.maxSets = 1;
        dpi.poolSizeCount = 1;
        dpi.pPoolSizes = &poolSize;
        if (vkCreateDescriptorPool(g_dev, &dpi, nullptr, &g_descPool) != VK_SUCCESS)
            { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
        VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        dai.descriptorPool = g_descPool;
        dai.descriptorSetCount = 1;
        dai.pSetLayouts = &g_dsl;
        if (vkAllocateDescriptorSets(g_dev, &dai, &g_descSet) != VK_SUCCESS)
            { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
        VkDescriptorImageInfo dii{};
        dii.sampler = g_sampler;
        dii.imageView = g_viewTex;
        dii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w.dstSet = g_descSet;
        w.dstBinding = 0;
        w.descriptorCount = 1;
        w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        w.pImageInfo = &dii;
        vkUpdateDescriptorSets(g_dev, 1, &w, 0, nullptr);
    }

    /* --- the shader modules (the precompiled SPIR-V) --------------------- */
    VkShaderModule vsMod = VK_NULL_HANDLE, fsMod = VK_NULL_HANDLE;
    {
        VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        ci.codeSize = sizeof(g_hw_mesh_vk_vert_spv);
        ci.pCode = g_hw_mesh_vk_vert_spv;
        if (vkCreateShaderModule(g_dev, &ci, nullptr, &vsMod) != VK_SUCCESS)
            { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
        ci.codeSize = sizeof(g_hw_mesh_vk_frag_spv);
        ci.pCode = g_hw_mesh_vk_frag_spv;
        if (vkCreateShaderModule(g_dev, &ci, nullptr, &fsMod) != VK_SUCCESS)
            { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
    }

    /* --- the pipelines (SWANSTATION VERBATIM GraphicsPipelineBuilder) --- */
    {
        /* SWANSTATION VERBATIM: PipelineLayoutBuilder */
        Vulkan::PipelineLayoutBuilder plb;
        plb.AddDescriptorSet(g_dsl);
        plb.AddPushConstants(VK_SHADER_STAGE_VERTEX_BIT |
                             VK_SHADER_STAGE_FRAGMENT_BIT, 0, 16);
        g_pipelineLayout = plb.Create(g_dev);
        if (g_pipelineLayout == VK_NULL_HANDLE) { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
    }
    for (int pass = 0; pass < 2; pass++) {
        /* pass 0 = the Z pass (att0 write mask 0, att1 R); pass 1 = the
         * color pass (att0 RGBA, att1 mask 0) — the same MRT write-mask
         * split the GLES backend does with glColorMaski                  */
        Vulkan::GraphicsPipelineBuilder pb;
        pb.SetVertexShader(vsMod);
        pb.SetFragmentShader(fsMod);
        pb.AddVertexBuffer(0, sizeof(HwVert), VK_VERTEX_INPUT_RATE_VERTEX);
        pb.AddVertexBuffer(1, sizeof(HwInst), VK_VERTEX_INPUT_RATE_VERTEX);
        pb.AddVertexAttribute(0, 0, VK_FORMAT_R32G32_SFLOAT,
                              (uint32_t)offsetof(HwVert, sx));
        pb.AddVertexAttribute(1, 0, VK_FORMAT_R32G32_SFLOAT,
                              (uint32_t)offsetof(HwVert, u));
        pb.AddVertexAttribute(2, 0, VK_FORMAT_R32G32B32_SFLOAT,
                              (uint32_t)offsetof(HwVert, vr));
        pb.AddVertexAttribute(3, 0, VK_FORMAT_R32G32B32_SFLOAT,
                              (uint32_t)offsetof(HwVert, orr));
        pb.AddVertexAttribute(4, 0, VK_FORMAT_R32_SFLOAT,
                              (uint32_t)offsetof(HwVert, z));
        pb.AddVertexAttribute(5, 1, VK_FORMAT_R32G32B32A32_UINT,
                              (uint32_t)offsetof(HwInst, ddaU0));
        pb.AddVertexAttribute(6, 1, VK_FORMAT_R32G32_UINT,
                              (uint32_t)offsetof(HwInst, ddaDvdx));
        pb.AddVertexAttribute(7, 1, VK_FORMAT_R32G32B32A32_SINT,
                              (uint32_t)offsetof(HwInst, ddaX0));
        pb.AddVertexAttribute(8, 1, VK_FORMAT_R32G32_SINT,
                              (uint32_t)offsetof(HwInst, texW));
        pb.AddVertexAttribute(9, 1, VK_FORMAT_R32G32B32A32_SINT,
                              (uint32_t)offsetof(HwInst, flags));
        pb.SetPrimitiveTopology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, false);
        pb.SetNoCullRasterizationState();   /* raster_tri accepts both windings */
        pb.SetNoDepthTestState();
        pb.SetNoBlendingState();
        if (pass == 0) {
            pb.SetBlendAttachment(0, false, VK_BLEND_FACTOR_ONE,
                                  VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD,
                                  VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO,
                                  VK_BLEND_OP_ADD, 0);
            pb.SetBlendAttachment(1, false, VK_BLEND_FACTOR_ONE,
                                  VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD,
                                  VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO,
                                  VK_BLEND_OP_ADD, VK_COLOR_COMPONENT_R_BIT);
        } else {
            pb.SetBlendAttachment(0, false, VK_BLEND_FACTOR_ONE,
                                  VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD,
                                  VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO,
                                  VK_BLEND_OP_ADD,
                                  VK_COLOR_COMPONENT_R_BIT |
                                  VK_COLOR_COMPONENT_G_BIT |
                                  VK_COLOR_COMPONENT_B_BIT |
                                  VK_COLOR_COMPONENT_A_BIT);
            pb.SetBlendAttachment(1, false, VK_BLEND_FACTOR_ONE,
                                  VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD,
                                  VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO,
                                  VK_BLEND_OP_ADD, 0);
        }
        pb.SetViewport(0.0f, 0.0f, (float)w, (float)h, 0.0f, 1.0f);
        pb.SetScissorRect(0, 0, w, h);
        pb.SetPipelineLayout(g_pipelineLayout);
        pb.SetRenderPass(g_renderPass, 0);
        VkPipeline p = pb.Create(g_dev);
        if (p == VK_NULL_HANDLE) { g_vk_failed = true; sh1_vk_hw_shutdown(); return 0; }
        if (pass == 0) g_pipelineZ = p; else g_pipelineC = p;
    }
    vkDestroyShaderModule(g_dev, vsMod, nullptr);
    vkDestroyShaderModule(g_dev, fsMod, nullptr);

    /* --- the per-frame vertex buffers ------------------------------------ */
    if (!vk_alloc_buf(&g_bufVerts, &g_memVerts, (void **)&g_mapVerts,
                      (VkDeviceSize)sizeof(HwVert) * VK_MAX_TRIS * 3,
                      VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) ||
        !vk_alloc_buf(&g_bufInsts, &g_memInsts, (void **)&g_mapInsts,
                      (VkDeviceSize)sizeof(HwInst) * VK_MAX_TRIS * 3,
                      VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) ||
        !vk_alloc_buf(&g_bufZVerts, &g_memZVerts, (void **)&g_mapZVerts,
                      (VkDeviceSize)sizeof(HwVert) * VK_MAX_TRIS * 3,
                      VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) ||
        !vk_alloc_buf(&g_bufZInsts, &g_memZInsts, (void **)&g_mapZInsts,
                      (VkDeviceSize)sizeof(HwInst) * VK_MAX_TRIS * 3,
                      VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)) {
        VKLOGE("vertex buffer allocation failed");
        g_vk_failed = true;
        sh1_vk_hw_shutdown();   /* v45 H-5 */
        return 0;
    }

    /* --- the readback buffers --------------------------------------------- */
    if (!vk_alloc_buf(&g_bufReadC, &g_memReadC, (void **)&g_mapReadC,
                      (VkDeviceSize)w * h * 4,
                      VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        !vk_alloc_buf(&g_bufReadZ, &g_memReadZ, (void **)&g_mapReadZ,
                      (VkDeviceSize)w * h * 4,
                      VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
        VKLOGE("readback buffer allocation failed");
        g_vk_failed = true;
        sh1_vk_hw_shutdown();   /* v45 H-5 */
        return 0;
    }

    VkPhysicalDeviceProperties pdp;
    vkGetPhysicalDeviceProperties(g_pdev, &pdp);
    VKLOGI("Vulkan backend ready: %s (%d tris cap, FBO %dx%d RGBA8+R32F)",
           pdp.deviceName, (int)VK_MAX_TRIS, w, h);
    g_vk_ok = true;
    return 1;
}

/* ==========================================================================
 * the frame render (the same z-pass/color-pass split as GLES)
 * ========================================================================*/
extern "C" int sh1_vk_hw_render(unsigned char *rgbOut, float *zOut, int w,
                                int h, int cr, int cg, int cb) {
    if (!g_vk_ok) return 0;
    if (w != g_w || h != g_h) return 0;
    if (g_hwBatch.nVert > VK_MAX_TRIS * 3) {
        VKLOGE("batch overflow: %zu verts > cap %zu — software fallback",
               g_hwBatch.nVert, VK_MAX_TRIS * 3);
        return 0;
    }

    /* upload the batch (host-visible persistent mappings) */
    memcpy(g_mapVerts, g_hwBatch.verts, sizeof(HwVert) * g_hwBatch.nVert);
    memcpy(g_mapInsts, g_hwBatch.insts, sizeof(HwInst) * g_hwBatch.nVert);
    memcpy(g_mapZVerts, g_hwBatch.zverts, sizeof(HwVert) * g_hwBatch.nZVert);
    memcpy(g_mapZInsts, g_hwBatch.zinsts, sizeof(HwInst) * g_hwBatch.nZVert);

    /* record */
    VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    if (vkBeginCommandBuffer(g_cmd, &cbi) != VK_SUCCESS) return 0;

    const float fogClear[4] = {cr / 255.0f, cg / 255.0f, cb / 255.0f, 1.0f};
    const float zClear[4] = {1e30f, 0.0f, 0.0f, 0.0f};
    VkClearValue clears[2];
    clears[0].color = {fogClear[0], fogClear[1], fogClear[2], fogClear[3]};
    clears[1].color = {zClear[0], zClear[1], zClear[2], zClear[3]};
    VkRenderPassBeginInfo rbi{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rbi.renderPass = g_renderPass;
    rbi.framebuffer = g_framebuffer;
    rbi.renderArea = {{0, 0}, {(uint32_t)w, (uint32_t)h}};
    rbi.clearValueCount = 2;
    rbi.pClearValues = clears;
    vkCmdBeginRenderPass(g_cmd, &rbi, VK_SUBPASS_CONTENTS_INLINE);

    const float pc[4] = {(float)w, (float)h, 1.0f, 0.0f};
    /* the PC layout: VS reads u_fbSize (offset 0-7); FS reads
     * u_scaled_dithering (8) + u_true_color (12) — the 16-byte range    */
    vkCmdPushConstants(g_cmd, g_pipelineLayout,
                       VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                       0, 16, pc);
    vkCmdBindDescriptorSets(g_cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            g_pipelineLayout, 0, 1, &g_descSet, 0, nullptr);

    if (g_hwBatch.nZVert > 0) {
        vkCmdBindPipeline(g_cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pipelineZ);
        VkBuffer zbufs[2] = {g_bufZVerts, g_bufZInsts};
        VkDeviceSize offs[2] = {0, 0};
        vkCmdBindVertexBuffers(g_cmd, 0, 2, zbufs, offs);
        vkCmdDraw(g_cmd, (uint32_t)g_hwBatch.nZVert, 1, 0, 0);
    }
    if (g_hwBatch.nVert > 0) {
        vkCmdBindPipeline(g_cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pipelineC);
        VkBuffer bufs[2] = {g_bufVerts, g_bufInsts};
        VkDeviceSize offs[2] = {0, 0};
        vkCmdBindVertexBuffers(g_cmd, 0, 2, bufs, offs);
        vkCmdDraw(g_cmd, (uint32_t)g_hwBatch.nVert, 1, 0, 0);
    }
    vkCmdEndRenderPass(g_cmd);

    /* readback copies (the render pass left the images in
     * TRANSFER_SRC_OPTIMAL — its finalLayout)                            */
    {
        VkBufferImageCopy c0{};
        c0.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        c0.imageExtent = {(uint32_t)w, (uint32_t)h, 1};
        vkCmdCopyImageToBuffer(g_cmd, g_imgColor,
                               VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               g_bufReadC, 1, &c0);
        c0.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        c0.imageExtent = {(uint32_t)w, (uint32_t)h, 1};
        vkCmdCopyImageToBuffer(g_cmd, g_imgZ,
                               VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               g_bufReadZ, 1, &c0);
    }
    if (vkEndCommandBuffer(g_cmd) != VK_SUCCESS) return 0;

    /* submit + wait (synchronous — the engine loop is serial) */
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &g_cmd;
    vkResetFences(g_dev, 1, &g_fence);
    if (vkQueueSubmit(g_queue, 1, &si, g_fence) != VK_SUCCESS) return 0;
    if (vkWaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS)
        return 0;

    /* copy out — Vulkan's framebuffer origin is TOP-LEFT, row 0 = the
     * engine's row 0: NO flip (unlike the GL readback)                   */
    for (int y = 0; y < h; y++) {
        const unsigned char *src = g_mapReadC + (size_t)y * w * 4;
        unsigned char *dst = rgbOut + (size_t)y * w * 3;
        for (int x = 0; x < w; x++) {
            dst[x * 3] = src[x * 4];
            dst[x * 3 + 1] = src[x * 4 + 1];
            dst[x * 3 + 2] = src[x * 4 + 2];
        }
    }
    memcpy(zOut, g_mapReadZ, (size_t)w * h * sizeof(float));
    return 1;
}

/* ==========================================================================
 * shutdown
 * ========================================================================*/
extern "C" void sh1_vk_hw_shutdown(void) {
    if (g_dev != VK_NULL_HANDLE) vkDeviceWaitIdle(g_dev);
    if (g_mapVerts) { vkUnmapMemory(g_dev, g_memVerts); g_mapVerts = NULL; }
    if (g_mapInsts) { vkUnmapMemory(g_dev, g_memInsts); g_mapInsts = NULL; }
    if (g_mapZVerts) { vkUnmapMemory(g_dev, g_memZVerts); g_mapZVerts = NULL; }
    if (g_mapZInsts) { vkUnmapMemory(g_dev, g_memZInsts); g_mapZInsts = NULL; }
    if (g_mapReadC) { vkUnmapMemory(g_dev, g_memReadC); g_mapReadC = NULL; }
    if (g_mapReadZ) { vkUnmapMemory(g_dev, g_memReadZ); g_mapReadZ = NULL; }
    if (g_stagingMap) { vkUnmapMemory(g_dev, g_memStaging); g_stagingMap = NULL; }
#define VK_DESTROY(obj, fn) do { if (obj != VK_NULL_HANDLE) { fn(g_dev, obj, nullptr); obj = VK_NULL_HANDLE; } } while (0)
    VK_DESTROY(g_pipelineZ, vkDestroyPipeline);
    VK_DESTROY(g_pipelineC, vkDestroyPipeline);
    VK_DESTROY(g_pipelineLayout, vkDestroyPipelineLayout);
    VK_DESTROY(g_descPool, vkDestroyDescriptorPool);
    Vulkan::Util::SafeDestroyDescriptorSetLayout(g_dsl);
    VK_DESTROY(g_sampler, vkDestroySampler);
    VK_DESTROY(g_framebuffer, vkDestroyFramebuffer);
    VK_DESTROY(g_renderPass, vkDestroyRenderPass);
    Vulkan::Util::SafeDestroyImageView(g_viewTex);
    Vulkan::Util::SafeDestroyImageView(g_viewColor);
    Vulkan::Util::SafeDestroyImageView(g_viewZ);
    if (g_imgTex != VK_NULL_HANDLE) { vkDestroyImage(g_dev, g_imgTex, nullptr); g_imgTex = VK_NULL_HANDLE; }
    if (g_imgColor != VK_NULL_HANDLE) { vkDestroyImage(g_dev, g_imgColor, nullptr); g_imgColor = VK_NULL_HANDLE; }
    if (g_imgZ != VK_NULL_HANDLE) { vkDestroyImage(g_dev, g_imgZ, nullptr); g_imgZ = VK_NULL_HANDLE; }
    if (g_memTex != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memTex, nullptr); g_memTex = VK_NULL_HANDLE; }
    if (g_memColor != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memColor, nullptr); g_memColor = VK_NULL_HANDLE; }
    if (g_memZ != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memZ, nullptr); g_memZ = VK_NULL_HANDLE; }
    if (g_bufStaging != VK_NULL_HANDLE) { vkDestroyBuffer(g_dev, g_bufStaging, nullptr); g_bufStaging = VK_NULL_HANDLE; }
    if (g_memStaging != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memStaging, nullptr); g_memStaging = VK_NULL_HANDLE; }
    if (g_bufVerts != VK_NULL_HANDLE) { vkDestroyBuffer(g_dev, g_bufVerts, nullptr); g_bufVerts = VK_NULL_HANDLE; }
    if (g_bufInsts != VK_NULL_HANDLE) { vkDestroyBuffer(g_dev, g_bufInsts, nullptr); g_bufInsts = VK_NULL_HANDLE; }
    if (g_bufZVerts != VK_NULL_HANDLE) { vkDestroyBuffer(g_dev, g_bufZVerts, nullptr); g_bufZVerts = VK_NULL_HANDLE; }
    if (g_bufZInsts != VK_NULL_HANDLE) { vkDestroyBuffer(g_dev, g_bufZInsts, nullptr); g_bufZInsts = VK_NULL_HANDLE; }
    if (g_memVerts != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memVerts, nullptr); g_memVerts = VK_NULL_HANDLE; }
    if (g_memInsts != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memInsts, nullptr); g_memInsts = VK_NULL_HANDLE; }
    if (g_memZVerts != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memZVerts, nullptr); g_memZVerts = VK_NULL_HANDLE; }
    if (g_memZInsts != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memZInsts, nullptr); g_memZInsts = VK_NULL_HANDLE; }
    if (g_bufReadC != VK_NULL_HANDLE) { vkDestroyBuffer(g_dev, g_bufReadC, nullptr); g_bufReadC = VK_NULL_HANDLE; }
    if (g_bufReadZ != VK_NULL_HANDLE) { vkDestroyBuffer(g_dev, g_bufReadZ, nullptr); g_bufReadZ = VK_NULL_HANDLE; }
    if (g_memReadC != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memReadC, nullptr); g_memReadC = VK_NULL_HANDLE; }
    if (g_memReadZ != VK_NULL_HANDLE) { vkFreeMemory(g_dev, g_memReadZ, nullptr); g_memReadZ = VK_NULL_HANDLE; }
    if (g_fence != VK_NULL_HANDLE) { vkDestroyFence(g_dev, g_fence, nullptr); g_fence = VK_NULL_HANDLE; }
    if (g_cmdPool != VK_NULL_HANDLE) { vkDestroyCommandPool(g_dev, g_cmdPool, nullptr); g_cmdPool = VK_NULL_HANDLE; }
    if (g_dev != VK_NULL_HANDLE) { vkDestroyDevice(g_dev, nullptr); g_dev = VK_NULL_HANDLE; }
    if (g_inst != VK_NULL_HANDLE) { vkDestroyInstance(g_inst, nullptr); g_inst = VK_NULL_HANDLE; }
    Vulkan::UnloadVulkanLibrary();
    g_vk_ok = false;
    g_vk_failed = false;
}
