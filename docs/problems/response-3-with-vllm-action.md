# me

vllm was started like:
sudo docker run -e HF_TOKEN=$HF_TOKEN --gpus all \
--name vllm-gpt-oss-20b -d -p 8000:8000 \
--ipc=host \
vllm/vllm-openai:v0.31.0 \
--model openai/gpt-oss-20b --max-model-len 65536 --tool-call-parser openai --enable-auto-tool-choice

# chatgpt response:

Your startup command reveals one definite configuration omission, and one important compatibility risk.

You're already using the correct GPT-OSS tool-call parser:

```
--tool-call-parser openai
--enable-auto-tool-choice
```

However, you're missing:

```
--reasoning-parser openai_gptoss
```

I would add that first. It ensures vLLM recognizes GPT-OSS reasoning channels instead of treating them as ordinary response content.

**But I would not expect this flag alone to fix your particular error.** There is a more specific streaming bug affecting GPT-OSS.

## **1. The likely underlying bug: GPT-OSS streaming corrupts tool names**

The particularly relevant report is vLLM [#32587](https://github.com/vllm-project/vllm/issues/32587?utm_source=chatgpt.com).

It describes this behavior:

\| Tool invocation | Returned tool name |\
\|---|---|\
\| First invocation | `listCities` |\
\| Subsequent invocation | `listCities<|channel|>commentary` |

This happens because the streaming tool-call parser can leak Harmony channel metadata into the function name.

The resulting tool name can then be stored in conversation history and sent back to vLLM in the next request.

That leads to a second error: Harmony cannot correctly reconstruct the previous assistant tool-call header.

This is especially interesting because the reported problem is explicitly streaming-dependent: non-streaming works with the same model and server configuration.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub



There's also a newer, particularly relevant report: vLLM [#65770](https://github.com/vllm-project/vllm/issues/65770?utm_source=chatgpt.com), opened November 5, 2026.

It reproduces a GPT-OSS conversation-history rendering failure on **vLLM 0.31.0**, using `oss-harmony 0.0.12`, and also reports the same underlying code in 0.32.0 and 0.33.0.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://git.littlebell.top\&sz=32)

GitHub



This is a much closer match to your environment than a generic SSE transport incompatibility.

## **2. First change: correct your Docker command**

I would change it to:

```

sudo docker run -e HF_TOKEN=$HF_TOKEN --gpus all \
  --name vllm-gpt-oss-20b -d -p 8000:8000 \
  --ipc=host \
  vllm/vllm-openai:v0.31.0 \
  --model openai/gpt-oss-20b \
  --max-model-len 65536 \
  --tool-call-parser openai \
  --reasoning-parser openai_gptoss \
  --enable-auto-tool-choice

```

This is the documented GPT-OSS parser combination.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://docs.vllm.ai\&sz=32)

vLLM+1



## **3. Second change: inspect tool-call names before they enter ChatMemory**

This is probably the most important diagnostic change in your Kotlin application.

Add temporary logging to your streaming response handling:

```
override fun onPartialToolCall(
    partialToolCall: PartialToolCall
) {
    log.info(
        "LLM tool call fragment: index={}, id={}, name={}, arguments={}",
        partialToolCall.index(),
        partialToolCall.id(),
        partialToolCall.name(),
        partialToolCall.arguments()
    )
}
```

Adapt the accessors to your LangChain4j version.

Look specifically for:

```
listCities<|channel|>commentary
operationHistory<|channel|>commentary
```

Also log the completed `AiMessage` and its `toolExecutionRequests()` before persisting it.

The distinction is critical:

- If the tool name is corrupted, vLLM's streaming parser is the likely culprit.
- If the tool name is clean but the next request fails, inspect the serialized conversation history and Harmony rendering.
- If the first tool invocation already fails, capture the raw HTTP response to distinguish a provider-side error from client-side parsing.

Do not silently strip special tokens from tool names in production without validating the original tool call. That could conceal malformed model output.

## **4. Third change: use non-streaming for the tool execution loop**

If the above confirms the streaming bug, my practical recommendation is to use non-streaming for GPT-OSS tool orchestration.

You don't need to abandon SSE in your application.

Instead:

**Recommended execution flow**

**1. User request**

Existing SSE endpoint

**2. GPT-OSS non-streaming agent execution**

Normal tool selection and execution, including multiple tool calls

**3. Final assistant response**

Obtain the completed response

**4. Application SSE**

Emit the final response through your existing SSE infrastructure.

This preserves your existing frontend streaming contract and avoids vLLM's problematic GPT-OSS streaming tool-call parsing path.

You can still use actual token streaming for models that don't exhibit this issue.

## **5. What I would give your implementation agent**

Here is the concrete implementation task I'd use:

# Fix GPT-OSS-20B tool-call streaming compatibility with vLLM

## Context

The application uses LangChain4j 1.23.0 with GPT-OSS-20B served by vLLM 0.31.0.

The current vLLM configuration uses:

- `--tool-call-parser openai`
- `--enable-auto-tool-choice`

Tool-using streaming conversations intermittently fail with:

`unexpected tokens remaining in message header: ["to=functions.operationHistory"]`

Investigate the known GPT-OSS streaming tool-call parsing issues in vLLM, particularly:

- vLLM #32587: Harmony channel metadata leaking into streamed tool names.
- vLLM #65770: GPT-OSS conversation-history rendering failures involving malformed tool names.

## Tasks

1. Update the vLLM startup configuration to include:

   `--reasoning-parser openai_gptoss`
2. Instrument LangChain4j streaming responses to capture:
   - Partial tool-call names and IDs.
   - Completed tool execution requests.
   - Assistant messages before ChatMemory persistence.
   - Serialized assistant tool-call messages in subsequent requests.
3. Detect malformed tool names containing Harmony special-token text, such as `<|channel|>commentary` or `<|end|>`.
4. Verify whether LangChain4j 1.23.0 preserves the correct tool-call name and arguments across the complete streaming lifecycle.
5. Implement a configurable execution strategy for GPT-OSS:
   - Non-streaming LLM execution for tool orchestration.
   - Preserve the existing application SSE interface.
   - Emit the final assistant response through SSE.
   - Retain existing conversation memory and tool execution behavior.
6. Keep the existing streaming implementation available for models/providers where tool-call streaming works correctly.
7. Add regression tests covering:
   - Multiple consecutive tool calls.
   - Tool execution followed by another LLM invocation.
   - Multiple tool calls within the same conversation.
   - Malformed tool names.
   - Conversation-history serialization and replay.
   - Non-streaming tool execution followed by SSE delivery.

## Acceptance criteria

- GPT-OSS can execute multiple consecutive tool calls without malformed tool names.
- Conversation history remains valid when replayed to vLLM.
- No Harmony metadata leaks into registered function names.
- Existing SSE consumers remain compatible.
- Failures identify whether they originate from vLLM, LangChain4j, or tool execution.
- No silent sanitization of malformed tool calls.

Avoid changing the overall agent architecture or replacing LangChain4j unless necessary.

## **My final assessment**



Recommended

**Keep LangChain4j 1.23.0 and vLLM 0.31.0 initially, add the missing reasoning parser, and test non-streaming tool orchestration.**



I would not spend time changing JDK HTTP Client to OkHttp, nor upgrade LangChain4j in the hope of fixing this particular problem.

One final nuance: the earlier suggestion to enable `sendThinking(true)` was too speculative. That setting is primarily intended for reasoning providers such as DeepSeek; it is not an established fix for GPT-OSS's Harmony streaming parser bug.

The next meaningful improvement would be testing a vLLM build containing a verified fix for the specific GPT-OSS streaming issue, rather than simply moving to a newer version number.

You can also connect GitHub to inspect the upstream issues and patches directly from ChatGPT if you want to investigate the relevant fixes further.
