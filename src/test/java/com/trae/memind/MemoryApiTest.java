package com.trae.memind;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST 接入层验证：租户请求头解析、写入语义、检索与上下文组装。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryApiTest {

    private static final String TENANT = "t-api";
    private static final String USER = "erin";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE memind_memories, memind_insight_nodes, "
                + "memind_conversation_logs, memind_raw_contents");
    }

    @Test
    @DisplayName("缺失租户请求头时拒绝请求")
    void rejectsRequestWithoutTenantHeader() throws Exception {
        mockMvc.perform(post("/open/v1/memory/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"看看数据\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("完整 HTTP 链路：同步抽取 → 手动整合 → 检索 → 组装上下文")
    void fullHttpFlow() throws Exception {
        mockMvc.perform(post("/open/v1/memory/sync/extract")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", USER)
                        .header("X-Memory-Scope", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"s1","contentType":"CONVERSATION","messages":[
                                  {"role":"user","content":"我负责增长运营这块工作"},
                                  {"role":"user","content":"销售额和订单量是我最关心的两个指标"},
                                  {"role":"user","content":"我主要看GMV和转化率"},
                                  {"role":"user","content":"所有报表都要按渠道拆分"},
                                  {"role":"user","content":"再按地区分组看一下"},
                                  {"role":"user","content":"最近30天是我的常用时间窗口"}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.rawContentId").isNotEmpty());

        mockMvc.perform(post("/admin/v1/consolidate")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", USER)
                        .header("X-Memory-Scope", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/admin/v1/insight")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", USER)
                        .header("X-Memory-Scope", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value(TENANT + ":" + USER))
                .andExpect(jsonPath("$.nodes").isNotEmpty());

        mockMvc.perform(post("/open/v1/memory/retrieve")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"帮我看看数据\",\"topK\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memories").isNotEmpty())
                .andExpect(jsonPath("$.insights").isNotEmpty())
                .andExpect(jsonPath("$.signals").doesNotExist());

        mockMvc.perform(post("/open/v1/memory/compile_context")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"帮我看看数据\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context").isNotEmpty())
                .andExpect(jsonPath("$.strategy").value("SIMPLE"));
    }

    @Test
    @DisplayName("fire-and-forget 写入返回 202 且携带原始内容 ID")
    void asyncExtractReturnsAccepted() throws Exception {
        mockMvc.perform(post("/open/v1/memory/extract")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"所有报表都要按渠道拆分\",\"contentType\":\"DOCUMENT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.rawContentId").isNotEmpty());
    }

    @Test
    @DisplayName("健康检查不需要租户上下文")
    void healthRequiresNoTenant() throws Exception {
        mockMvc.perform(get("/open/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.chatAvailable").value(false));
    }
}