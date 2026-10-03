#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"security fix no aplicado ({label}): patrón no encontrado en {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def apply_native_security_fixes(src: Path) -> None:
    cpp = src / "app/src/main/cpp"

    # 1) Real OOB write: GLLight.attenuation has 3 floats, but upstream copied 4.
    replace_once(
        cpp / "gladiorenderer/src/gl_renderer.c",
        "    memcpy(light->attenuation, attenuation, sizeof(position));",
        "    memcpy(light->attenuation, attenuation, sizeof(attenuation));",
        "gladio attenuation buffer overflow",
    )

    # 2) TGSI default object left Dim uninitialized.
    replace_once(
        cpp / "virglrenderer/src/gallium/auxiliary/tgsi/tgsi_build.c",
        "   struct tgsi_full_declaration  full_declaration;\n\n"
        "   full_declaration.Declaration  = tgsi_default_declaration();",
        "   struct tgsi_full_declaration  full_declaration = {0};\n\n"
        "   full_declaration.Declaration  = tgsi_default_declaration();",
        "tgsi full declaration initialization",
    )

    # 3) Harden bit scanners against zero masks and signed left-shift UB.
    replace_once(
        cpp / "virglrenderer/src/gallium/auxiliary/util/u_math.h",
        """static inline int u_bit_scan(unsigned *mask)
{
   int i = ffs(*mask) - 1;
   *mask &= ~(1 << i);
   return i;
}""",
        """static inline int u_bit_scan(unsigned *mask)
{
   if (!mask || *mask == 0)
      return -1;

   unsigned i = (unsigned)ffs(*mask) - 1u;
   *mask &= ~(1u << i);
   return (int)i;
}""",
        "u_bit_scan undefined shift",
    )

    replace_once(
        cpp / "virglrenderer/src/gallium/auxiliary/util/u_math.h",
        """static inline void
u_bit_scan_consecutive_range(unsigned *mask, int *start, int *count)
{
   if (*mask == 0xffffffff) {
      *start = 0;
      *count = 32;
      *mask = 0;
      return;
   }
   *start = ffs(*mask) - 1;
   *count = ffs(~(*mask >> *start)) - 1;
   *mask &= ~(((1u << *count) - 1) << *start);
}""",
        """static inline void
u_bit_scan_consecutive_range(unsigned *mask, int *start, int *count)
{
   if (!mask || !start || !count)
      return;

   if (*mask == 0) {
      *start = 0;
      *count = 0;
      return;
   }

   if (*mask == 0xffffffff) {
      *start = 0;
      *count = 32;
      *mask = 0;
      return;
   }

   *start = ffs(*mask) - 1;
   *count = ffs(~(*mask >> *start)) - 1;
   *mask &= ~(((1u << *count) - 1u) << *start);
}""",
        "u_bit_scan_consecutive_range undefined shift",
    )

    # 4) va_copy() must be paired with va_end(), including error exits.
    replace_once(
        cpp / "virglrenderer/src/vrend_strbuf.h",
        """   if (len >= (int)(sb->alloc_size - sb->size)) {
      if (!strbuf_grow(sb, len))
        return;
      vsnprintf(sb->buf + sb->size, sb->alloc_size - sb->size, fmt, cp);
   }
   sb->size += len;""",
        """   if (len >= (int)(sb->alloc_size - sb->size)) {
      if (!strbuf_grow(sb, len)) {
        va_end(cp);
        return;
      }
      vsnprintf(sb->buf + sb->size, sb->alloc_size - sb->size, fmt, cp);
   }
   va_end(cp);
   sb->size += len;""",
        "strbuf_vappendf va_end",
    )

    replace_once(
        cpp / "virglrenderer/src/vrend_strbuf.h",
        """   if (len >= (int)(sb->alloc_size)) {
      if (!strbuf_grow(sb, len))
        return;
      vsnprintf(sb->buf, sb->alloc_size, fmt, cp);
   }
   sb->size = len;""",
        """   if (len >= (int)(sb->alloc_size)) {
      if (!strbuf_grow(sb, len)) {
        va_end(cp);
        return;
      }
      vsnprintf(sb->buf, sb->alloc_size, fmt, cp);
   }
   va_end(cp);
   sb->size = len;""",
        "strbuf_vfmt va_end",
    )

    # 5) Real use-after-scope: srcs was redirected to an array declared inside an if block.
    vrend_shader = cpp / "virglrenderer/src/vrend_shader.c"
    replace_once(
        vrend_shader,
        """   int sampler_index;
   const char *tex_ext;

   set_texture_reqs(ctx, inst, sinfo->sreg_index);""",
        """   int sampler_index;
   const char *tex_ext;
   char rect_coord_buf[255] = "";
   const char *rect_srcs[4] = { NULL, NULL, NULL, NULL };

   set_texture_reqs(ctx, inst, sinfo->sreg_index);""",
        "vrend texture persistent scratch storage",
    )

    replace_once(
        vrend_shader,
        """      char buf[255];
      const char *new_srcs[4] = { buf, srcs[1], srcs[2], srcs[3] };

      switch (inst->Instruction.Opcode) {
      case TGSI_OPCODE_TXP:
         snprintf(buf, 255, "vec4(%s)/vec4(textureSize(%s, 0), 1, 1)", srcs[0], srcs[sampler_index]);
         break;

      case TGSI_OPCODE_TG4:
         snprintf(buf, 255, "%s.xy/vec2(textureSize(%s, 0))", srcs[0], srcs[sampler_index]);
         break;

      default:
         /* Non TG4 ops have the compare value in the z components */
         if (inst->Texture.Texture == TGSI_TEXTURE_SHADOWRECT) {
            snprintf(buf, 255, "vec3(%s.xy/vec2(textureSize(%s, 0)), %s.z)", srcs[0], srcs[sampler_index], srcs[0]);
         } else
            snprintf(buf, 255, "%s.xy/vec2(textureSize(%s, 0))", srcs[0], srcs[sampler_index]);
      }
      srcs = new_srcs;""",
        """      rect_srcs[0] = rect_coord_buf;
      rect_srcs[1] = srcs[1];
      rect_srcs[2] = srcs[2];
      rect_srcs[3] = srcs[3];

      switch (inst->Instruction.Opcode) {
      case TGSI_OPCODE_TXP:
         snprintf(rect_coord_buf, sizeof(rect_coord_buf), "vec4(%s)/vec4(textureSize(%s, 0), 1, 1)", srcs[0], srcs[sampler_index]);
         break;

      case TGSI_OPCODE_TG4:
         snprintf(rect_coord_buf, sizeof(rect_coord_buf), "%s.xy/vec2(textureSize(%s, 0))", srcs[0], srcs[sampler_index]);
         break;

      default:
         /* Non TG4 ops have the compare value in the z components */
         if (inst->Texture.Texture == TGSI_TEXTURE_SHADOWRECT) {
            snprintf(rect_coord_buf, sizeof(rect_coord_buf), "vec3(%s.xy/vec2(textureSize(%s, 0)), %s.z)", srcs[0], srcs[sampler_index], srcs[0]);
         } else
            snprintf(rect_coord_buf, sizeof(rect_coord_buf), "%s.xy/vec2(textureSize(%s, 0))", srcs[0], srcs[sampler_index]);
      }
      srcs = rect_srcs;""",
        "vrend texture use-after-scope",
    )

    # 6) Do not let pNext in a caller-owned VkMemoryAllocateInfo escape pointing at stack locals.
    resource_memory = cpp / "vortekrenderer/src/resource_memory.c"
    replace_once(
        resource_memory,
        """ResourceMemory* ResourceMemory_allocate(VkContext* context, VkDevice device, VkMemoryAllocateInfo* memoryInfo) {
    uint64_t maxAllocationSize = (VkDeviceSize)context->maxDeviceMemory << 20;""",
        """ResourceMemory* ResourceMemory_allocate(VkContext* context, VkDevice device, VkMemoryAllocateInfo* memoryInfo) {
    if (!memoryInfo) return NULL;

    VkMemoryAllocateInfo localMemoryInfo = *memoryInfo;
    VkMemoryAllocateInfo* allocationInfo = &localMemoryInfo;

    uint64_t maxAllocationSize = (VkDeviceSize)context->maxDeviceMemory << 20;""",
        "Vulkan pNext stack lifetime",
    )

    resource_text = resource_memory.read_text(encoding="utf-8")
    start = resource_text.index("ResourceMemory* ResourceMemory_allocate")
    end = resource_text.index("\nvoid ResourceMemory_free", start)
    region = resource_text[start:end]
    original_region = region
    region = region.replace("memoryInfo->", "allocationInfo->")
    region = region.replace(
        "vkAllocateMemory(device, memoryInfo,",
        "vkAllocateMemory(device, allocationInfo,",
    )
    if region == original_region:
        raise SystemExit("security fix no aplicado (Vulkan allocationInfo): sin reemplazos")
    resource_memory.write_text(
        resource_text[:start] + region + resource_text[end:],
        encoding="utf-8",
    )

    # 7) Async semaphore request owns both the duplicated input and request object.
    replace_once(
        cpp / "vortekrenderer/src/timeline_semaphore.c",
        """    CLOSEFD(waitSemaphoresRequest->notifyFd);

    vt_free(&memoryPool);
}""",
        """    CLOSEFD(waitSemaphoresRequest->notifyFd);

    vt_free(&memoryPool);
    MEMFREE(waitSemaphoresRequest->inputBuffer);
    MEMFREE(waitSemaphoresRequest);
}""",
        "timeline semaphore async allocation leak",
    )

    # 8) Avoid a fixed 1 KiB shader declaration buffer fed by guest-controlled identifiers.
    shader_converter = cpp / "gladiorenderer/src/shader_converter.c"
    replace_once(
        shader_converter,
        """static char* stringifyShaderVariable(ShaderVariable* variable) {
    char result[1024] = {0};
    if (variable->type == GL_INTERFACE_BLOCK) {
        strcat(result, getTypeQualifierAsString(variable->typeQualifier));
        strcat(result, " ");
        strcat(result, variable->blockName);
        strcat(result, " {");

        ShaderVariable* member = variable->members;
        while (member) {
            strcat(result, " ");
            char* string = stringifyShaderVariable(member);
            strcat(result, string);
            free(string);
            member = member->members;
        }

        strcat(result, " }");
    }
    else {
        if (variable->typeQualifier != TYPE_QUALIFIER_NONE) {
            strcat(result, getTypeQualifierAsString(variable->typeQualifier));
            strcat(result, " ");
        }

        strcat(result, getGLTypeAsString(variable->type));
    }

    strcat(result, " ");
    strcat(result, variable->name);
    if (variable->arraySize > 0) {
        char value[8];
        sprintf(value, "%d", variable->arraySize);
        strcat(result, "[");
        strcat(result, value);
        strcat(result, "]");
    }
    strcat(result, ";");
    return strdup(result);
}""",
        """static bool appendShaderString(char** result, const char* value) {
    if (!result || !value) return false;

    size_t currentLength = *result ? strlen(*result) : 0;
    size_t valueLength = strlen(value);
    if (currentLength > ((size_t)-1) - valueLength - 1) return false;

    char* resized = realloc(*result, currentLength + valueLength + 1);
    if (!resized) return false;

    memcpy(resized + currentLength, value, valueLength + 1);
    *result = resized;
    return true;
}

static char* stringifyShaderVariable(ShaderVariable* variable) {
    if (!variable || !variable->name) return NULL;

    char* result = calloc(1, 1);
    if (!result) return NULL;

    if (variable->type == GL_INTERFACE_BLOCK) {
        const char* qualifier = getTypeQualifierAsString(variable->typeQualifier);
        if (!qualifier || !variable->blockName ||
            !appendShaderString(&result, qualifier) ||
            !appendShaderString(&result, " ") ||
            !appendShaderString(&result, variable->blockName) ||
            !appendShaderString(&result, " {")) {
            free(result);
            return NULL;
        }

        ShaderVariable* member = variable->members;
        while (member) {
            char* string = stringifyShaderVariable(member);
            if (!string || !appendShaderString(&result, " ") || !appendShaderString(&result, string)) {
                free(string);
                free(result);
                return NULL;
            }
            free(string);
            member = member->members;
        }

        if (!appendShaderString(&result, " }")) {
            free(result);
            return NULL;
        }
    }
    else {
        if (variable->typeQualifier != TYPE_QUALIFIER_NONE) {
            const char* qualifier = getTypeQualifierAsString(variable->typeQualifier);
            if (!qualifier || !appendShaderString(&result, qualifier) || !appendShaderString(&result, " ")) {
                free(result);
                return NULL;
            }
        }

        const char* type = getGLTypeAsString(variable->type);
        if (!type || !appendShaderString(&result, type)) {
            free(result);
            return NULL;
        }
    }

    if (!appendShaderString(&result, " ") || !appendShaderString(&result, variable->name)) {
        free(result);
        return NULL;
    }

    if (variable->arraySize > 0) {
        char value[32];
        snprintf(value, sizeof(value), "%d", variable->arraySize);
        if (!appendShaderString(&result, "[") ||
            !appendShaderString(&result, value) ||
            !appendShaderString(&result, "]")) {
            free(result);
            return NULL;
        }
    }

    if (!appendShaderString(&result, ";")) {
        free(result);
        return NULL;
    }

    return result;
}""",
        "shader variable fixed buffer overflow",
    )

    # 9) tmpDir comes from asprintf and is not owned by adrenotools.
    replace_once(
        cpp / "vortekrenderer/src/main.c",
        """        libvulkan = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir, nativeLibraryDirC, libvulkanDir, libvulkanName, NULL, NULL);

        (*env)->ReleaseStringUTFChars""",
        """        libvulkan = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir, nativeLibraryDirC, libvulkanDir, libvulkanName, NULL, NULL);
        free(tmpDir);

        (*env)->ReleaseStringUTFChars""",
        "vortek temporary directory string leak",
    )


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 2:
        raise SystemExit("uso: native_security_fixes.py /ruta/a/winlator-app")
    apply_native_security_fixes(Path(sys.argv[1]).resolve())
    print("Native security fixes aplicados.")
