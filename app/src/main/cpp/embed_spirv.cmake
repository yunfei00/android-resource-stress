if(NOT DEFINED SPIRV_PATH OR NOT EXISTS "${SPIRV_PATH}")
    message(FATAL_ERROR "SPIR-V input was not generated: ${SPIRV_PATH}")
endif()
if(NOT DEFINED HEADER_PATH)
    message(FATAL_ERROR "HEADER_PATH is required")
endif()

file(READ "${SPIRV_PATH}" SPIRV_HEX HEX)
string(REGEX REPLACE "([0-9a-fA-F][0-9a-fA-F])" "0x\\1, " SPIRV_BYTES "${SPIRV_HEX}")
file(WRITE "${HEADER_PATH}"
    "#pragma once\n\n"
    "#include <cstddef>\n"
    "#include <cstdint>\n\n"
    "alignas(4) inline constexpr std::uint8_t kStressShaderSpirv[] = {\n"
    "${SPIRV_BYTES}\n"
    "};\n"
    "inline constexpr std::size_t kStressShaderSpirvSize = sizeof(kStressShaderSpirv);\n")
