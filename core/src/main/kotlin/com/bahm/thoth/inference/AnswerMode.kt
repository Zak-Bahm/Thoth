package com.bahm.thoth.inference

/**
 * How a question is answered.
 *
 * [QUICK] is the default: retrieve in code, inject the top passages into a single prompt, and
 * generate one grounded sentence with reasoning and tools disabled — fast.
 * [THOROUGH] is the original agentic tool-calling loop (searchKnowledge → submitAnswer), offered
 * on demand via "Search in detail" when a quick answer isn't enough.
 */
enum class AnswerMode { QUICK, THOROUGH }
