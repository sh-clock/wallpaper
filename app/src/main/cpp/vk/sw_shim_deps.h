/* sw_shim_deps.h — the minimal portability shims for the SWANSTATION
 * VERBATIM util.cpp (its ../log.h + ../string_util.h + "context.h"
 * includes pull the full DuckStation common stack; util.cpp uses exactly
 * StringUtil::StdStringFromFormatV, Log::Writef, and
 * g_vulkan_context->GetDevice() from those). This is the same class of
 * adaptation the v9.8 PoC made for GL::Program (the glad loader
 * boilerplate) — the Vulkan LOGIC in util.cpp/builders.cpp stays
 * character-for-character verbatim.
 */
#pragma once
#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>

namespace StringUtil {
inline std::string StdStringFromFormatV(const char *fmt, va_list ap) {
    va_list apCopy;
    va_copy(apCopy, ap);
    int len = std::vsnprintf(nullptr, 0, fmt, apCopy);
    va_end(apCopy);
    std::string s;
    if (len > 0) {
        s.resize((size_t)len);
        std::vsnprintf(&s[0], (size_t)len + 1, fmt, ap);
    }
    return s;
}
} // namespace StringUtil

enum class LogLevel { Error = 1, Warning = 2, Info = 3, Dev = 4 };

namespace Log {
inline void Writef(const char *channel, const char *func, LogLevel level,
                   const char *fmt, ...) {
    (void)channel; (void)func; (void)level;
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(stderr, fmt, ap);
    std::fprintf(stderr, "\n");
    va_end(ap);
}
} // namespace Log

namespace Vulkan {
/* the context shim: util.cpp's SafeDestroy* call g_vulkan_context->
 * GetDevice(); our headless backend sets the device right after
 * vkCreateDevice (ShimSetDevice)                                */
inline VkDevice &ShimDeviceRef() {
    static VkDevice d = VK_NULL_HANDLE;
    return d;
}
inline void ShimSetDevice(VkDevice d) { ShimDeviceRef() = d; }
class VulkanContextShim {
public:
    VkDevice GetDevice() const { return ShimDeviceRef(); }
    void FreeGlobalDescriptorSet(VkDescriptorSet& ds) {
        if (ds != VK_NULL_HANDLE) {
            vkFreeDescriptorSets(ShimDeviceRef(), descriptorPoolRef(), 1, &ds);
            ds = VK_NULL_HANDLE;
        }
    }
    VkDescriptorPool descriptorPoolRefStorage = VK_NULL_HANDLE;
    VkDescriptorPool &descriptorPoolRef() { return descriptorPoolRefStorage; }
};
inline VulkanContextShim g_vulkan_context_instance;
inline VulkanContextShim *g_vulkan_context = &g_vulkan_context_instance;
} // namespace Vulkan
