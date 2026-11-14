That narrows it down considerably. With **GPT-OSS-20B + vLLM 0.31.0**, I would investigate the Harmony parsing and conversation-history handling before changing anything in LangChain4j.

There is an important distinction here: GPT-OSS doesn't simply generate ordinary text followed by an OpenAI JSON tool call. It generates structured Harmony messages, which vLLM must translate into OpenAI-compatible tool-call deltas.

And there are documented vLLM problems involving precisely this boundary.

## **1. The most relevant known issue**

The closest match is vLLM issue [#51977](https://github.com/vllm-project/vllm/issues/51977?utm_source=chatgpt.com).

It reports:

```
openai_harmony.HarmonyError:
unexpected tokens remaining in message header:
Some("to=functions.web-browsing")
```

The environment was:

- vLLM 0.26.0
- GPT-OSS-120B
- Automatic tool calling enabled
- OpenAI tool-call parser

The issue also has a related proposed fix, #52055.

Importantly, subsequent reports describe similar failures on vLLM 0.29.0, including non-streaming requests. This suggests that the underlying problem isn't necessarily specific to SSE.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



There is another particularly relevant issue, [#32587](https://github.com/vllm-project/vllm/issues/32587?utm_source=chatgpt.com), documenting GPT-OSS leaking Harmony channel markers into tool names during streaming.

That can result in corrupted function names and failures on subsequent requests.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub



## **2. My strongest suspicion: reasoning is being lost between tool calls**

This is the first thing I would test in your LangChain4j integration.

GPT-OSS uses interleaved reasoning and tool calls. vLLM has specific support for preserving this reasoning state across multi-turn conversations.

A relevant vLLM change is [PR #35902](https://github.com/vllm-project/vllm/pull/35902?utm_source=chatgpt.com), which addressed analysis-channel handling and preservation of reasoning across turns.

The vLLM maintainers explicitly describe the integration requirement:

> Pass the assistant's reasoning back into the conversation history as a separate field.

See the [vLLM discussion on preserving reasoning state](https://discuss.vllm.ai/t/preserve-reasoning-state-across-turns/2672?utm_source=chatgpt.com).&#x20;

[image](https://www.google.com/s2/favicons?domain=https://discuss.vllm.ai\&sz=32)

vLLM Forums+1



Why this matters:

1. GPT-OSS generates an assistant response containing reasoning.
2. It generates a tool call.
3. LangChain4j executes the tool.
4. The tool result is appended to conversation history.
5. LangChain4j sends the next request to vLLM.

If the assistant's reasoning was discarded in step 3 or 4, the next request may contain an incomplete representation of the original Harmony conversation.

This is particularly relevant if your error occurs after several successful tool calls rather than on the first tool invocation.

### **The LangChain4j configuration I would test**

LangChain4j's `OpenAiStreamingChatModel` has two relevant builder settings:

- `returnThinking(true)` — capture reasoning returned by the provider.
- `sendThinking(true)` — include the captured reasoning when sending subsequent assistant messages.

For your version, try:

```
val model = OpenAiStreamingChatModel.builder()
    .baseUrl(baseUrl)
    .apiKey(apiKey)
    .modelName("gpt-oss-20b")
    .timeout(Duration.ofSeconds(120))
    .returnThinking(true)
    .sendThinking(true, "reasoning_content")
    .build()
```

**Important:** Verify the actual reasoning field in your vLLM 0.31.0 response first. Depending on the response format, it may be `reasoning` rather than `reasoning_content`. Use the exact field name returned by your server.

Also verify that your configured LangChain4j `ChatMemory` actually retains the `AiMessage.thinking()` value, including on assistant messages containing tool execution requests.

The two builder settings alone aren't sufficient if the conversation memory drops that information.

The relevant implementation is in [OpenAiStreamingChatModel.java](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-open-ai/src/main/java/dev/langchain4j/model/openai/OpenAiStreamingChatModel.java?utm_source=chatgpt.com).&#x20;

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



## **3. Verify your vLLM configuration**

For GPT-OSS-20B, vLLM 0.31.0 documents the following configuration:

```
--enable-auto-tool-choice \
--tool-call-parser openai \
--reasoning-parser openai_gptoss
```

These are the appropriate parser selections for GPT-OSS in this version.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://docs.vllm.ai\&sz=32)

vLLM+2



I would not replace `openai` with `gptoss` as the tool parser: vLLM's GPT-OSS tool parser is an internal stub, with the actual parsing handled by its Harmony parser.

## **4. A diagnostic test that should settle this**

Before changing your production architecture, run these three tests using the same conversation and tool definitions.

| Test | Configuration                      | What it tells us                             |
| ---- | ---------------------------------- | -------------------------------------------- |
| A    | Streaming, reasoning preserved     | Baseline                                     |
| B    | Streaming, reasoning omitted       | Whether reasoning preservation matters       |
| C    | Non-streaming, reasoning preserved | Whether the problem is specific to streaming |

Pay particular attention to the transition between the first tool invocation and the second model invocation.

Inspect the outgoing JSON request and verify that the assistant message contains both:

- Its tool calls (`tool_calls`).
- Its reasoning field (`reasoning` or `reasoning_content`, as appropriate).

Also inspect the HTTP response itself.

**This is important:** the error text matches the class of errors emitted by vLLM's Harmony parser. LangChain4j's `InternalServerException` can simply be the exception wrapping the provider's HTTP error. The fact that the Java exception surfaces inside `OpenAiStreamingChatModel.doChat()` does not establish that LangChain4j's SSE parser is the original source.

If vLLM returns HTTP 500 with a Harmony error, changing the Java HTTP transport will not solve it.

## **5. Should you upgrade vLLM?**

I would investigate the upstream fixes, but I would not assume upgrading from 0.31.0 alone resolves this.

In particular:

- vLLM issue #51977 remains relevant.
- PR #52055 proposes recovery when Harmony parsing fails during streaming.
- There are additional Harmony parsing fixes, but some address different failure modes.

See [#52055](https://github.com/vllm-project/vllm/pull/52055?utm_source=chatgpt.com) and [#51977](https://github.com/vllm-project/vllm/issues/51977?utm_source=chatgpt.com).&#x20;

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



A parser recovery patch would improve resilience, but it would not necessarily fix malformed model output or incomplete conversation history.

## **My recommendation, in priority order**

Priority 1

**Preserve GPT-OSS reasoning**

Enable `returnThinking(true)` and `sendThinking(true, ...)`, and verify that ChatMemory retains the reasoning.

Priority 2

**Verify vLLM parser configuration**

Confirm `openai` tool parser and `openai_gptoss` reasoning parser.

Priority 3

**Capture actual HTTP/SSE traffic**

Establish whether this is a vLLM HTTP 500 or a malformed successful SSE response.

Priority 4

**Evaluate upstream vLLM fixes**

Test a build containing the relevant Harmony parser fixes before deploying it.

I would retain streaming and LangChain4j for now. Your architecture doesn't need to sacrifice streaming just because GPT-OSS has some Harmony edge cases.

### **One particularly useful piece of information**

Could you share the following from your current implementation?

1. Your `OpenAiStreamingChatModel.builder()` configuration.
2. Your vLLM startup command.
3. Whether you use LangChain4j `MessageWindowChatMemory`, a custom `ChatMemoryStore`, or your own conversation-history implementation.

With those three pieces, I can narrow this down to a concrete implementation change rather than recommending generic retries or a non-streaming workaround.
