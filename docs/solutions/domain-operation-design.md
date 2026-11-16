# Domain Operation Tool Design

## Purpose

This document describes the design principles, patterns, and concrete tool taxonomy for building LangChain4j agent tools that delegate complex, multi-entity operations to deterministic application services — keeping the LLM as an intent interpreter rather than a state coordinator.

## The Problem

When the LLM manages application state through individual CRUD tool calls, it must:

1. Retrieve the complete set of entities (e.g., all cities).
2. Retain every entity in conversation memory.
3. Calculate changes for each entity.
4. Construct and send update payloads individually or in batches.

This is fragile because the LLM can:
- Omit entities it forgot.
- Make arithmetic or rounding errors.
- Hallucinate what happened on previous rounds.
- Lose track of entities between tool calls.

**Example failure:** A user says "add 50 to each city." The LLM calls `listCities`, then tries `updateCity` for each city it remembers. If there are 12 cities and the model only recalls 9, three cities are silently unchanged. The LLM has no way to verify completeness.

## The Solution

Move multi-entity mutations from the LLM's conversation loop into deterministic application services. The LLM expresses **intent** — the application executes the operation **deterministically**.

```
LLM                          Application
  |                                |
  +-- updateAllCities(delta=50) -->+-- Retrieves all cities from DB
  |                                +-- Computes new populations
  |                                +-- Updates atomically
  |                                +-- Returns affected count + details
  +-- "Applied to 12 cities:..." <-+
```

The LLM never constructs the list of entities to update. It simply says what it wants done, and the application handles the rest.

## Design Principles

### 1. LLM = Intent, Application = Execution

The model expresses *what* should happen. The application determines *how* to execute it — including retrieving current state, calculating intermediate values, and performing atomic mutations.

### 2. Persist Operation History

Every operation is recorded in the database with:
- Operation type (CREATE, UPDATE_ALL, DELETE, UNDO, SNAPSHOT)
- Arguments (e.g., population delta)
- Affected entity count and IDs
- Before/after values (for undo/restore)
- Transaction status (COMMITTED, FAILED)
- Timestamp and conversation context

Operation history is the source of truth — not conversation memory.

### 3. Atomic Operations Per Tool

Each tool method executes one atomic unit of work. `updateAllCities(50)` updates every city in one transaction, or none at all. Partial failures are impossible because the service owns the transaction boundary.

### 4. Undo/Restore from Persisted History

Undo operations reference the persisted history, not the model's memory. To undo the last bulk population change, the application fetches the last `UPDATE_ALL` operation record and applies the inverse delta.

### 5. Log Every Tool Invocation

Every tool call is logged with: operation ID, arguments, affected entity IDs, before/after values, and transaction status. This makes it possible to distinguish model reasoning failures from tool implementation failures.

### 6. Authorization at Tool Boundary

Every tool method validates authorization before executing. Rejections throw `ToolErrorVisibleToLlm` (not return error strings), so the LLM receives a clear, actionable error message.

## Tool Taxonomy

### Read Tools

Expose current state to the model. These are safe, side-effect-free operations.

| Tool | Purpose | Example |
|------|---------|---------|
| `listCities()` | Retrieve all entities | "What cities exist?" |
| `lookupCityByName(name)` | Retrieve a specific entity | "What is Tokyo's population?" |
| `listOperations()` | Review operation history | "Show me all recent changes." |

### CRUD Tools

Individual entity create, update, and delete operations. These remain available for simple, single-entity requests.

| Tool | Purpose | Example |
|------|---------|---------|
| `createCity(name, population)` | Add one entity | "Add Oslo with 1M population." |
| `updateCity(existingName, name, population)` | Modify one entity | "Rename Paris to City of Light." |
| `deleteCity(name, population?)` | Remove one entity | "Delete London." |

### Domain Operation Tools

High-level operations that act on multiple entities deterministically. These replace patterns where the LLM would otherwise construct bulk update payloads.

| Tool | Purpose | Example |
|------|---------|---------|
| `updateAllCities(delta)` | Apply a numeric change to every entity | "Add 50 to each city." |
| `undoLastOperation()` | Reverse the last committed operation | "Undo the last bulk update." |
| `createSnapshot(name)` | Record named state for restore | "Save current state as 'baseline'." |

### Context/History Tools

Tools for reviewing conversation and operation context.

| Tool | Purpose | Example |
|------|---------|---------|
| `operationHistory()` | Review this conversation's activity | "What did I ask you to do?" |

## Implementation Example

### Service Layer

```kotlin
/**
 * Apply a population delta to ALL cities atomically.
 * Records the operation with before/after values.
 */
fun updateAllPopulation(delta: Int): UpdateResult {
    val changes = mutableListOf<CityChange>()
    connection.autoCommit = false
    try {
        val allCities = readAll()
        val stmtUpdate = connection.prepareStatement(UPDATE_CITY)

        for (city in allCities) {
            val before = city.population
            val after = before + delta
            stmtUpdate.executeUpdate(city.id, city.name, after)
            changes.add(CityChange(city.id, city.name, before, after))
        }

        recordOperation("UPDATE_ALL", delta, changes.size, "COMMITTED", changes)
        connection.commit()
        UpdateResult(changes.size, changes)
    } catch (e: Exception) {
        connection.rollback()
        throw e
    } finally {
        connection.autoCommit = true
    }
}
```

### Tool Method

```kotlin
@Tool("Apply a population delta to all cities atomically. Use for bulk updates like 'add 50 to each city'.")
fun updateAllCities(
    @P("The population change to apply to every city") delta: Int,
    @ToolMemoryId conversationId: String,
): String {
    checkAuthorization(conversationId, Permission.CRUD_UPDATE)
    return runCatching {
        val result = cityService.updateAllPopulation(delta)
        val actor = executionContexts.getValue(conversationId)
        val changesText = result.changes.joinToString("\n") {
            "  ${it.name}: ${it.beforePopulation} → ${it.afterPopulation}"
        }
        val msg = "Applied delta $delta to ${result.affectedCount} cities:\n$changesText"
        recordOperation(conversationId, "updateAllCities delta=$delta", actor.id)
        msg
    }.getOrElse { "Failed to update all cities: ${it.message}" }
}
```

## System Prompt Guidance

Include explicit instructions in the `@SystemMessage` so the LLM knows when to use domain operation tools:

```
For bulk operations (e.g., "add 50 to each city"), use domain operation tools 
like updateAllCities instead of constructing individual update payloads. 
The application handles the actual mutation deterministically.
```

## Anti-Patterns to Avoid

| Anti-Pattern | Why It Fails | Correct Approach |
|-------------|--------------|-----------------|
| LLM builds update payload from `listCities` result | Model can forget/omit entities | Use `updateAllCities(delta)` — app retrieves all |
| LLM stores entity state between calls | Conversation memory is bounded and unreliable | Persist operation history in the database |
| Tool returns error as string result | LLM interprets it as success | Throw `ToolErrorVisibleToLlm` — LLM receives actionable error |
| LLM calculates arithmetic | Model makes math errors, especially with large numbers | Service computes before/after values in SQL/memory |
| Multiple tool calls for one atomic intent | Partial execution, inconsistent state | One domain operation tool handles the full scope |

## Testing Checklist

| Scenario | Expected Behavior |
|----------|------------------|
| Update all entities | Every entity in the database is updated |
| Update all, including newly added entities | Complete current database set is used |
| Two sequential increments | Both are applied independently |
| Undo last increment | Only the last operation is reversed |
| Reset to initial snapshot | Exact original values restored |
| Update entity missing from previous context | Entity is retrieved from database, not conversation |
| Failed bulk update | No misleading success response; transaction rolled back |
| Tool result contradicts conversation history | Actual database state takes precedence |
| Model invents an operation history | Application rejects unsupported assumptions |

## File Locations

| File | Role |
|------|------|
| `src/main/kotlin/CityService.kt` | Service layer: `updateAllPopulation`, `undoLastOperation`, `createSnapshot`, `listOperations` |
| `src/main/kotlin/CityAgent.kt` | LangChain4j tool methods: `updateAllCities`, `undoLastOperation`, `createSnapshot`, `listOperations` |
| `src/main/kotlin/CitySchema.kt` | Database schema: `city_operations` table, data classes |
| `docs/solutions/domain-operation-design.md` | This document |

## See Also

- `docs/problems/empty-tool-response.md` — Diagnosing empty LLM responses during tool execution.
- LangChain4j documentation: [Tool Error Handling](https://docs.langchain4j.dev/tutorials/tools#error-handling)
