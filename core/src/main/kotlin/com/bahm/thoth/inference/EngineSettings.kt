package com.bahm.thoth.inference

import com.google.ai.edge.litertlm.Backend

/**
 * Platform-supplied engine knobs. The only thing that differs between Android and desktop is the
 * inference [backend]: Android leaves it null (library default), desktop passes Backend.GPU()
 * to run on the Vulkan backend.
 */
class EngineSettings(
    val backend: Backend? = null,
)
