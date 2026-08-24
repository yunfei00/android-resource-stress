if(NOT DEFINED SPIRV_PATH OR NOT EXISTS "${SPIRV_PATH}")
    message(FATAL_ERROR "SPIR-V input was not generated: ${SPIRV_PATH}")
endif()
if(NOT DEFINED HEADER_PATH OR NOT DEFINED SYMBOL_BASE)
    message(FATAL_ERROR "HEADER_PATH and SYMBOL_BASE are required")
endif()

file(READ "${SPIRV_PATH}" SPIRV_HEX HEX)
string(REGEX REPLACE "([0-9a-fA-F][0-9a-fA-F])" "0x\\1, " SPIRV_BYTES "${SPIRV_HEX}")
file(WRITE "${HEADER_PATH}"
    "#pragma once\n\n"
    "#include <cstddef>\n"
    "#include <cstdint>\n\n"
    "alignas(4) inline constexpr std::uint8_t k${SYMBOL_BASE}Spirv[] = {\n"
    "${SPIRV_BYTES}\n"
    "};\n"
    "inline constexpr std::size_t k${SYMBOL_BASE}SpirvSize = sizeof(k${SYMBOL_BASE}Spirv);\n")
