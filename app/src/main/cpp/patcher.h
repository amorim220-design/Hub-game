#pragma once

#include <cstdint>
#include <string>
#include <vector>

struct BinaryPatchEntry {
    uint64_t file_offset;
    std::vector<uint8_t> expected_bytes;
    std::vector<uint8_t> replacement_bytes;
    std::string description;
};

// Applies binary patches to an ELF shared library and writes the resulting
// file to "%s/%d_patched.so", returning true on success.
bool patch_elf_library(
    const std::string& src_path,
    const std::string& out_dir,
    int patch_id,
    const std::vector<BinaryPatchEntry>& patches,
    std::string& out_patched_path
);

// Parses JSON patch descriptor containing array of { offset, expected_hex, replacement_hex, desc }
bool parse_patches_from_json(
    const std::string& json_str,
    std::vector<BinaryPatchEntry>& out_patches
);
