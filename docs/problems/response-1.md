As of **November 13, 2026, the latest official LangChain4j release I can verify is&#x20;**`1.23.0` (released October 28, 2026).

So, unfortunately, simply upgrading from `1.23.0` to a newer stable release does not currently appear to be an option.

Sources:

- [LangChain4j official releases](https://github.com/langchain4j/langchain4j/releases?utm_source=chatgpt.com)
- [Maven Central version history](https://central.sonatype.com/artifact/dev.langchain4j/langchain4j/versions?utm_source=chatgpt.com)

However, there are some important changes in 1.23.0 and some details in your diagnosis that I would investigate before implementing a workaround.

## **1. Important changes in LangChain4j 1.23.0**

There are a few relevant improvements:

| Change                                                              | Relevance to your problem                         |
| ------------------------------------------------------------------- | ------------------------------------------------- |
| OpenAI streaming: detect provider errors inside SSE streams (#6722) | **High** – better error detection and propagation |
| Improved context propagation for asynchronous AI Service operations | Useful for reactive applications                  |
| Streaming cancellation improvements                                 | Better lifecycle management                       |
| Custom executor for complete streaming responses                    | Useful for controlling execution                  |
| Improved observability events                                       | Better diagnostics                                |
| HTTP client configuration documentation                             | Useful when tuning transport                      |

Source: [Official 1.23.0 changelog](https://github.com/langchain4j/langchain4j/releases?utm_source=chatgpt.com).

The most interesting one is #6722. LangChain4j explicitly improved handling of errors returned by providers *inside* an HTTP 200 streaming response.

However, this is error handling, not necessarily a fix for malformed tool-call events.

## **2. I think your root-cause diagnosis needs one correction**

The exact error message is particularly revealing:

```
unexpected tokens remaining in message header: ["to=functions.operationHistory"]
```

This is not a typical OpenAI SSE parsing error.

It is an error associated with **OpenAI Harmony message-header parsing**.

In fact, there are documented vLLM issues with almost exactly this error:

- [vLLM issue #51977 – Harmony parser failure with tool calls](https://github.com/vllm-project/vllm/issues/51977?utm_source=chatgpt.com)
- [vLLM issue #23567 – unexpected tokens remaining in message header](https://github.com/vllm-project/vllm/issues/23567?timeline_page=1\&utm_source=chatgpt.com)

The first issue documents the same error on vLLM 0.26.0 with GPT-OSS and tool calling enabled.

This changes the diagnosis considerably.

### **The likely failure path**

If your model is emitting Harmony-formatted tool calls, the relevant processing chain may be:

**LLM generation**

Harmony tokens, including recipient metadata such as `to=functions.operationHistory`.

**vLLM Harmony parser**

Potentially fails to interpret the generated message header.

**OpenAI-compatible API / SSE**

Returns or propagates the provider-side error.

**LangChain4j**

Receives the error and exposes it through `onError`.

This is a different problem from LangChain4j failing to parse an otherwise valid OpenAI `delta.tool_calls` event.

The distinction matters because changing the HTTP client won't fix a server-side Harmony parsing failure.

## **3. What I would actually try**

I would prioritize the following:

**A. Establish whether the error originates from vLLM**

Capture the actual HTTP response, including status code and SSE payload, rather than relying exclusively on the LangChain4j exception.

Specifically look for:

- HTTP 500 from `/v1/chat/completions`.
- An SSE `data:` event containing an `error` object.
- Whether the exception occurs before or after the first tool-call delta.
- Whether the same request succeeds with `stream: false`.

If vLLM returns the Harmony exception, you've identified the responsible component.

**B. Check the vLLM model configuration**

If you're running GPT-OSS, pay particular attention to:

```
--enable-auto-tool-choice
--tool-call-parser openai
--reasoning-parser openai_gptoss
```

These are the relevant settings documented in the reported vLLM issue. They don't guarantee that every Harmony parsing edge case is resolved.

Also check whether you're running a vLLM release containing subsequent Harmony/tool-calling fixes.

**C. Upgrade LangChain4j anyway**

I'd stay on the 1.23.0 release train, including its BOM, rather than mixing individual module versions.

There is also an important distinction between the main artifact and experimental modules: some modules are published as `1.23.0-beta33`.

**D. Don't switch HTTP clients yet**

Your proposed OKHttp alternative is unlikely to help if the problem is malformed Harmony output or a vLLM-side parser failure.

Similarly, non-streaming is worth testing as a diagnostic, but I wouldn't implement it as the architectural solution until you establish that it actually avoids the failure.

## **4. My recommended architectural approach**

Given that your application depends heavily on tool calling, I would avoid disabling streaming as the first response.

I'd retain the following architecture:

- Streaming for ordinary assistant responses.
- Streaming tool calls where the provider supports them correctly.
- Proper propagation of provider errors.
- Non-streaming fallback for recoverable streaming failures, if verified to work.
- Observability that distinguishes transport errors, provider errors, malformed tool calls and client-side parsing errors.

One additional LangChain4j setting worth knowing about:

```
OpenAiStreamingChatModel.builder()
    .baseUrl(baseUrl)
    .apiKey(apiKey)
    .modelName(modelName)
    .accumulateToolCallId(false)
    .build()
```

This is useful for providers such as Qwen and DeepSeek that repeat complete tool-call IDs in successive streaming chunks. It is not a fix for Harmony header parsing, though.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



## **5. One question that would help pinpoint this**

Which model is actually producing these tool calls?

**Model**

GPT-OSS-20B

Qwen3.6-35B-A3B

Qwen3.5-9B

Other



**vLLM version**

0.31.0

0.30.x

0.26.x

Other / unknown

Submit

**My current suspicion:** this is more likely a vLLM/model-specific Harmony tool-call parsing problem than a LangChain4j SSE implementation bug. I'd establish that before investing time in replacing the streaming transport.
