#include "patcher.h"
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <cstring>

#define LOG_TAG "VulkanCompatPatcher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::vector<uint8_t> hex_to_bytes(const std::string& hex) {
    std::vector<uint8_t> bytes;
    for (size_t i = 0; i < hex.length(); i += 2) {
        if (i + 1 >= hex.length()) break;
        std::string byte_string = hex.substr(i, 2);
        uint8_t byte = (uint8_t)strtol(byte_string.c_str(), nullptr, 16);
        bytes.push_back(byte);
    }
    return bytes;
}

bool patch_elf_library(
    const std::string& src_path,
    const std::string& out_dir,
    int patch_id,
    const std::vector<BinaryPatchEntry>& patches,
    std::string& out_patched_path
) {
    // 1. Read entire source library
    std::ifstream src_file(src_path, std::ios::binary);
    if (!src_file.is_open()) {
        LOGE("Failed to open source ELF library: %s", src_path.c_str());
        return false;
    }

    std::vector<uint8_t> buffer((std::istreambuf_iterator<char>(src_file)),
                                 std::istreambuf_iterator<char>());
    src_file.close();

    if (buffer.size() < 64) {
        LOGE("File too small to be a valid ELF binary: %zu bytes", buffer.size());
        return false;
    }

    // 2. Validate ELF Header Magic: 0x7F 'E' 'L' 'F'
    if (buffer[0] != 0x7F || buffer[1] != 'E' || buffer[2] != 'L' || buffer[3] != 'F') {
        LOGE("Not an ELF binary: magic header mismatch in %s", src_path.c_str());
        return false;
    }

    // Validate 64-bit ARM64 (EI_CLASS == 2, e_machine == 0xB7)
    uint8_t elf_class = buffer[4];
    if (elf_class != 2) { // 2 = 64-bit
        LOGE("ELF is not 64-bit (class=%d)", elf_class);
        return false;
    }
    uint16_t e_machine = buffer[18] | (buffer[19] << 8);
    if (e_machine != 0xB7) { // 0xB7 = EM_AARCH64 (183)
        LOGE("ELF architecture is not AArch64/ARM64 (e_machine=0x%04x)", e_machine);
        return false;
    }

    // 3. Apply binary patches
    for (const auto& patch : patches) {
        if (patch.file_offset + patch.replacement_bytes.size() > buffer.size()) {
            LOGE("Patch offset 0x%llx exceeds file size %zu",
                 (unsigned long long)patch.file_offset, buffer.size());
            return false;
        }

        // Verify expected bytes if provided
        if (!patch.expected_bytes.empty()) {
            if (memcmp(&buffer[patch.file_offset], patch.expected_bytes.data(), patch.expected_bytes.size()) != 0) {
                LOGE("Expected byte verification failed at offset 0x%llx (%s)",
                     (unsigned long long)patch.file_offset, patch.description.c_str());
                // Non-fatal warning: still proceed or abort depending on strategy
            }
        }

        // Apply replacement bytes
        memcpy(&buffer[patch.file_offset], patch.replacement_bytes.data(), patch.replacement_bytes.size());
        LOGI("Applied patch at offset 0x%llx (%zu bytes): %s",
             (unsigned long long)patch.file_offset, patch.replacement_bytes.size(), patch.description.c_str());
    }

    // 4. Construct destination path: "%s/%d_patched.so"
    char target_path[512];
    snprintf(target_path, sizeof(target_path), "%s/%d_patched.so", out_dir.c_str(), patch_id);

    std::ofstream out_file(target_path, std::ios::binary);
    if (!out_file.is_open()) {
        LOGE("Failed to create patched library at %s", target_path);
        return false;
    }

    out_file.write(reinterpret_cast<const char*>(buffer.data()), buffer.size());
    out_file.close();

    // Mark as executable
    chmod(target_path, 0755);

    out_patched_path = target_path;
    LOGI("Patched library generated successfully: %s", target_path);
    return true;
}

bool parse_patches_from_json(
    const std::string& json_str,
    std::vector<BinaryPatchEntry>& out_patches
) {
    // Lightweight parser for simple patch arrays:
    // [{"offset": 12345, "expected": "AA BB", "replacement": "CC DD", "desc": "Widescreen FOV"}]
    out_patches.clear();
    size_t pos = 0;
    while ((pos = json_str.find('{', pos)) != std::string::npos) {
        size_t end = json_str.find('}', pos);
        if (end == std::string::npos) break;

        std::string obj = json_str.substr(pos, end - pos + 1);

        BinaryPatchEntry entry;
        entry.file_offset = 0;

        // Parse offset
        size_t off_pos = obj.find("\"offset\"");
        if (off_pos != std::string::npos) {
            size_t colon = obj.find(':', off_pos);
            if (colon != std::string::npos) {
                entry.file_offset = std::strtoull(obj.c_str() + colon + 1, nullptr, 0);
            }
        }

        // Parse expected
        size_t exp_pos = obj.find("\"expected\"");
        if (exp_pos != std::string::npos) {
            size_t q1 = obj.find('"', exp_pos + 10);
            size_t q2 = (q1 != std::string::npos) ? obj.find('"', q1 + 1) : std::string::npos;
            if (q1 != std::string::npos && q2 != std::string::npos) {
                std::string hex = obj.substr(q1 + 1, q2 - q1 - 1);
                hex.erase(std::remove_if(hex.begin(), hex.end(), ::isspace), hex.end());
                entry.expected_bytes = hex_to_bytes(hex);
            }
        }

        // Parse replacement
        size_t rep_pos = obj.find("\"replacement\"");
        if (rep_pos != std::string::npos) {
            size_t q1 = obj.find('"', rep_pos + 13);
            size_t q2 = (q1 != std::string::npos) ? obj.find('"', q1 + 1) : std::string::npos;
            if (q1 != std::string::npos && q2 != std::string::npos) {
                std::string hex = obj.substr(q1 + 1, q2 - q1 - 1);
                hex.erase(std::remove_if(hex.begin(), hex.end(), ::isspace), hex.end());
                entry.replacement_bytes = hex_to_bytes(hex);
            }
        }

        // Parse desc
        size_t desc_pos = obj.find("\"desc\"");
        if (desc_pos != std::string::npos) {
            size_t q1 = obj.find('"', desc_pos + 6);
            size_t q2 = (q1 != std::string::npos) ? obj.find('"', q1 + 1) : std::string::npos;
            if (q1 != std::string::npos && q2 != std::string::npos) {
                entry.description = obj.substr(q1 + 1, q2 - q1 - 1);
            }
        }

        if (!entry.replacement_bytes.empty()) {
            out_patches.push_back(entry);
        }

        pos = end + 1;
    }

    return !out_patches.empty();
}
