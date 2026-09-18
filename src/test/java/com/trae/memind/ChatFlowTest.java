package com.trae.memind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式聊天编排的离线回归：接口契约、事件顺序、以及"聊完就记住"的写回闭环。
 *
 * <p>测试环境不配置对话模型，因此走的是<b>离线降级</b>分支——这不是绕过验证，恰恰相反：
 * 降级模式与真实模型模式共用同一套编排、事件与写回链路，只有"谁来生成正文"不同。
 * 真实模型的工具调用验证交给 {@code scripts/chat-flow-probe.ps1}（见 {@code chat-flow-report.md}）。
 *
 * <p>租户 ID 每次运行都不同，避免与库里既有数据互相干扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TENANT = "chatflow-" + Long.toString(System.nanoTime() % 1_000_000L, 36);
    private static final String USER = "u-chat";
    private static final String SESSION = "cf-session";
    private static final String QUESTION = "我是林悦，帮我看看最近30天的GMV是多少";

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    void seedMemories() throws Exception {
        JsonNode seeded = syncExtract(List.of(
                "我是林悦，负责增长运营，我偏好柱状图展示结果",
                "我关心最近30天的GMV和订单量"));
        assertTrue(seeded.path("itemCount").asInt() >= 1,
                "播种阶段至少要落一条 USER 记忆，否则召回断言无从谈起：" + seeded);
    }

    @Test
    @DisplayName("一轮对话：事件顺序完整、正文非空、且自动写回 USER 记忆")
    void streamsFullEventSequenceAndWritesBackMemory() throws Exception {
        int before = userMemories().size();

        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "message", QUESTION,
                "sessionId", SESSION,
                "scopes", List.of("USER"),
                "writeback", true);

        ResponseEntity<String> response = rest.postForEntity(
                "/open/v1/chat/stream", new HttpEntity<>(body, headers), String.class);

        // ① 是 SSE 而不是一次性 JSON
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(MediaType.parseMediaType(MediaType.TEXT_EVENT_STREAM_VALUE)
                        .isCompatibleWith(response.getHeaders().getContentType()),
                "Content-Type 必须是 text/event-stream，实际为 " + response.getHeaders().getContentType());

        List<Event> events = parse(response.getBody());
        List<String> names = events.stream().map(Event::name).toList();
        assertFalse(names.isEmpty(), "事件流为空");

        // ② 顺序固定：meta → delta* → done → writeback
        assertEquals("meta", names.get(0), "首帧必须是 meta，否则思考模型的长首字延迟会让界面假死");
        assertEquals("writeback", names.get(names.size() - 1));
        assertEquals("done", names.get(names.size() - 2), "done 应在 writeback 之前：回答先结束，写回随后补上");
        assertTrue(names.subList(1, names.size() - 2).stream().allMatch("delta"::equals),
                "meta 与 done 之间只应有 delta，实际为 " + names);

        // ③ meta 如实反映降级状态与召回量
        JsonNode meta = JSON.readTree(events.get(0).json());
        assertTrue(meta.path("degraded").asBoolean(), "测试环境没有 chat key，应当明确标记为降级");
        assertTrue(meta.path("memoryCount").asInt() > 0,
                "应当召回播种阶段写入的记忆，实际 meta=" + meta);
        assertTrue(meta.path("tools").isArray() && !meta.path("tools").isEmpty(),
                "meta 应公布已注册的工具清单，供前端展示模型可自主选择的能力：" + meta);

        // ④ 正文非空，且没有被思考过程污染
        StringBuilder content = new StringBuilder();
        for (Event event : events) {
            if ("delta".equals(event.name())) {
                content.append(JSON.readTree(event.json()).path("text").asText());
            }
        }
        assertFalse(content.toString().isBlank(), "正文不能为空");
        assertTrue(content.toString().contains("离线降级"),
                "降级模式应由规则应答生成正文，实际为：" + content);
        assertFalse(names.contains("reasoning"), "降级模式没有思维链，不应出现 reasoning 事件");

        // ⑤ 写回事件与真实落库一致，且命名空间严格隔离到 租户:用户
        JsonNode writeback = JSON.readTree(events.get(names.size() - 1).json());
        assertEquals("SUCCESS", writeback.path("userStatus").asText());
        assertTrue(writeback.path("userItems").asInt() >= 1, "本轮对话至少应沉淀出一条 USER 记忆");
        assertEquals("SKIPPED", writeback.path("agentStatus").asText(), "本轮没有工具调用，AGENT 侧应跳过");

        List<JsonNode> memories = userMemories();
        assertTrue(memories.size() > before, "写回后 USER 记忆条数应增加：" + before + " → " + memories.size());
        for (JsonNode memory : memories) {
            assertEquals(TENANT + ":" + USER, memory.path("namespace").asText(),
                    "命名空间必须是 租户:用户，越权隔离不能有例外");
        }

        // ⑥ 全程无 error 事件
        assertFalse(names.contains("error"), "不应出现 error 事件：" + events);
    }

    // ------------------------------------------------------------------ 辅助

    private record Event(String name, String json) {
    }

    /** 手工解析 SSE：与前端保持同一套分帧规则，顺便当作前端解析逻辑的一次交叉验证。 */
    private static List<Event> parse(String raw) {
        List<Event> events = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return events;
        }
        for (String block : raw.replace("\r\n", "\n").split("\n\n")) {
            String name = null;
            List<String> dataLines = new ArrayList<>();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataLines.add(line.substring(5).trim());
                }
            }
            if (name != null && !dataLines.isEmpty()) {
                events.add(new Event(name, String.join("\n", dataLines)));
            }
        }
        return events;
    }

    private JsonNode syncExtract(List<String> contents) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        for (String content : contents) {
            messages.add(Map.of("role", "user", "content", content));
        }
        Map<String, Object> body = Map.of(
                "sessionId", SESSION + "-seed",
                "contentType", "CONVERSATION",
                "messages", messages,
                "scope", "USER");
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "/open/v1/memory/sync/extract", new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "播种失败：" + response.getBody());
        return JSON.readTree(response.getBody());
    }

    private List<JsonNode> userMemories() throws Exception {
        ResponseEntity<String> response = rest.exchange(
                "/admin/v1/memories?scope=USER", HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<JsonNode> items = new ArrayList<>();
        JSON.readTree(response.getBody()).forEach(items::add);
        return items;
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TENANT);
        headers.set("X-User-Id", USER);
        headers.set("X-Memory-Scope", "USER");
        return headers;
    }
}