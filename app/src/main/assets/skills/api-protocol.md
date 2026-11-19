# api-protocol.md · 接口与协议

## OpenAI 兼容要点

- 端点：`{baseUrl}/chat/completions`，baseUrl 通常到 `/v1`。
- 请求：`model`、`messages`、`temperature`、`max_tokens`、`stream`、`tools`。
- 推理强度：`reasoning_effort`（low/medium/high），部分上游支持。
- 流式：`data: {...}` 逐行，`[DONE]` 结束；每行自行解析，容忍空行。

## 工具调用

```json
{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"fs_write","arguments":"{\"path\":\"a.md\",\"content\":\"x\"}"}}]}
```

- arguments 是**字符串化的 JSON**，解析失败就原样回报错误，不要让模型猜。
- 工具结果回填 `role:"tool"` + `tool_call_id`，缺 id 会 400。

## 错误处理

| 码 | 含义 | 处理 |
| --- | --- | --- |
| 401 | 密钥错 | 提示去设置，不重试 |
| 429 | 限流 | 退避 2s/4s/8s，最多 3 次 |
| 5xx | 上游故障 | 提示稍后重试，保留上下文 |
