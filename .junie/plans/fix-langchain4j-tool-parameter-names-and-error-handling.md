---
sessionId: session-261116-131621-1mhn
---

# Requirements

### Problem Overview

The city assistant's tool calls fail to match city names because the LLM generates tool arguments with normal ASCII spaces (U+0020) while the database stores city names containing non-breaking spaces (U+00A0, likely from the frontend rich-text editor or copy-paste). The `lookupCity` function in `CityAgent.kt` only calls `trim()` on the input name, which does not normalize internal Unicode whitespace. As a result, the LLM cannot find cities when asked to update or delete them — every lookup fails silently.

The application also logs two LangChain4j configuration warnings on startup:
1. **Missing parameter names**: The `createCity` tool's `arg0` parameter has no name at runtime (already fixed in prior work).
2. **Missing tool error handlers**: No explicit error handlers on the `CityAssistant` AI Service (already fixed in prior work).

### Goals

- Fix the city name matching so the LLM can find and modify cities regardless of whether the database stores non-breaking spaces.
- Keep the existing LangChain4j fixes from the prior session.

### Scope

- **In scope**: Modify `lookupCity` in [CityAgent.kt](file:///Users/scaleture/git/ktor-app/src/main/kotlin/CityAgent.kt) to normalize Unicode whitespace so U+00A0 is treated like U+0020.
- **Out of scope**: Changes to tool logic, business logic, or any other files.

# Delivery Steps

### ✓ Step 1: Enable Kotlin JVM parameter name retention
Already completed. Fixed by adding `-java-parameters` compiler flag in build.gradle.kts.

### ✓ Step 2: Add explicit tool error handlers to CityAssistant
Already completed. Fixed by adding `ToolArgumentsErrorHandler` and `ToolExecutionErrorHandler` to the AiServices builder.

### ✓ Step 3: Normalize Unicode whitespace in city name matching
Fix the root cause of city lookup failures by normalizing non-breaking spaces (U+00A0) to regular spaces (U+0020) in the `lookupCity` function so the LLM's tool arguments match database entries regardless of whitespace variant.

- Add a `normalizeCityName` helper that replaces all Unicode whitespace characters (including non-breaking space U+00A0) with a single ASCII space and trims.
- Update `lookupCity` to call `normalizeCityName` on both the input name and the database name before comparison, so `"New\u00A0York"` and `"New York"` are treated as equal.