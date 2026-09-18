package com.trae.memind.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.retrieval.RetrievalEngine;
import com.trae.memind.retrieval.RetrievalResult;
import com.trae.memind.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 工具与技能注册表：既是对模型暴露的 tools schema 来源，也是服务端的实际执行入口。
 *
 * <p>10 个能力里 8 个是 {@link ToolDefinition.ToolType#TOOL}、2 个是
 * {@link ToolDefinition.ToolType#SKILL}——后者对外同样只是一个函数名，内部才是多步编排。
 * 电商域与通用域的实现基于 {@link MockData}（不触碰真实数据源），
 * {@code search_memory} 走本项目的检索链路，{@code get_weather} / {@code search_web}
 * 则真的会访问互联网（见 {@link WebTools}）。
 *
 * <p>工具执行发生在 SSE 工作线程内（见 {@code ChatService}），因此
 * {@code search_memory} 可以直接读到调用方绑定好的 {@link TenantContext}。
 */
@Service
public class ToolRegistry {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private final ObjectMapper mapper;
    private final RetrievalEngine retrievalEngine;
    private final WebTools webTools;
    private final Map<String, ToolDefinition> definitions;

    public ToolRegistry(ObjectMapper mapper, RetrievalEngine retrievalEngine, WebTools webTools) {
        this.mapper = mapper;
        this.retrievalEngine = retrievalEngine;
        this.webTools = webTools;

        Map<String, ToolDefinition> all = new LinkedHashMap<>();
        for (ToolDefinition definition : List.of(
                querySales(), runSql(), gmvReport(), cohortAnalysis(),
                searchMemory(), getCurrentTime(), calculator(), weeklySummary(),
                getWeather(), searchWeb())) {
            all.put(definition.name(), definition);
        }
        this.definitions = Collections.unmodifiableMap(all);
    }

    /** 转成 OpenAI function calling 的 tools 数组。 */
    public ArrayNode toOpenAiTools() {
        ArrayNode tools = mapper.createArrayNode();
        for (ToolDefinition definition : definitions.values()) {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", definition.parameters());
        }
        return tools;
    }

    public Optional<ToolDefinition> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(definitions.get(name));
    }

    /** 全部能力声明，按注册顺序；供 meta 事件与报告展示。 */
    public List<ToolDefinition> all() {
        return List.copyOf(definitions.values());
    }

    // ------------------------------------------------------------------ 电商域

    private ToolDefinition querySales() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = propertiesOf(schema);
        stringProperty(properties, "metric", "要查询的指标", "gmv", "orders", "conversion_rate", "uv");
        stringProperty(properties, "time_range", "时间窗口", "1d", "7d", "30d", "90d");
        require(schema, "metric", "time_range");
        String description = "查询电商业务指标的汇总值。适用于用户问「最近30天GMV多少」「这个月订单量」"
                + "「转化率是多少」这类只需要一个数字的问题。metric 取值：gmv（成交额）、orders（订单量）、"
                + "conversion_rate（转化率）、uv（访客数）。";
        return new ToolDefinition("query_sales", description, schema,
                ToolDefinition.ToolType.TOOL, this::executeQuerySales);
    }

    private String executeQuerySales(ObjectNode arguments) {
        String metric = arguments.path("metric").asText("").trim().toLowerCase(Locale.ROOT);
        String range = arguments.path("time_range").asText("30d").trim();
        Double total = MockData.METRICS.get(metric);
        if (total == null) {
            return error("不支持的 metric: " + metric + "，可选值 " + MockData.METRICS.keySet());
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("metric", metric);
        result.put("range", range);
        result.put("total", total);
        return result.toString();
    }

    private ToolDefinition runSql() {
        ObjectNode schema = objectSchema();
        stringProperty(propertiesOf(schema), "sql", "只读 SQL，数据源固定为 sales_order(channel, order_amount)",
                null);
        require(schema, "sql");
        String description = "对电商订单明细表执行只读 SQL 并取回结果行。表名固定为 sales_order，"
                + "可用字段 channel（渠道）与 order_amount（订单金额）。聚合查询（含 group by）返回各渠道明细，"
                + "否则返回全局汇总。用于需要看明细而不是单个数字的场景。";
        return new ToolDefinition("run_sql", description, schema,
                ToolDefinition.ToolType.TOOL, this::executeRunSql);
    }

    private String executeRunSql(ObjectNode arguments) {
        String sql = arguments.path("sql").asText("").trim();
        if (sql.isEmpty()) {
            return error("sql 不能为空");
        }
        String lowered = sql.toLowerCase(Locale.ROOT);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode columns = result.putArray("columns");
        ArrayNode rows = result.putArray("rows");
        if (lowered.contains("channel")) {
            columns.add("channel").add("gmv");
            for (MockData.ChannelGmv row : MockData.CHANNEL_GMV) {
                ArrayNode values = rows.addArray();
                values.add(row.channel()).add(row.gmv());
            }
        } else {
            columns.add("gmv");
            rows.addArray().add(MockData.GMV_30D);
        }
        result.put("rowCount", rows.size());
        return result.toString();
    }

    private ToolDefinition gmvReport() {
        ObjectNode schema = objectSchema();
        stringProperty(propertiesOf(schema), "group_by", "报表拆分维度", "channel", "region", "category");
        require(schema, "group_by");
        String description = "技能：生成 GMV 日报。内部会按指定维度聚合、计算占比并成文，"
                + "返回可直接引用的报表摘要。适用于用户要「出一份报表 / 日报 / 渠道拆解」的场景。";
        return new ToolDefinition("gmv_report", description, schema,
                ToolDefinition.ToolType.SKILL, this::executeGmvReport);
    }

    private String executeGmvReport(ObjectNode arguments) {
        String groupBy = arguments.path("group_by").asText("channel").trim();
        ObjectNode result = mapper.createObjectNode();
        result.put("reportId", "rpt-01");
        result.put("groupBy", groupBy);
        result.put("summary", "近30天GMV " + wan(MockData.GMV_30D) + " 万");
        ArrayNode breakdown = result.putArray("breakdown");
        for (MockData.ChannelGmv row : MockData.CHANNEL_GMV) {
            ObjectNode item = breakdown.addObject();
            item.put("channel", row.channel());
            item.put("gmv", row.gmv());
            item.put("share", percent(row.gmv() / MockData.GMV_30D));
        }
        return result.toString();
    }

    private ToolDefinition cohortAnalysis() {
        ObjectNode schema = objectSchema();
        stringProperty(propertiesOf(schema), "weeks",
                "要分析的注册周，逗号分隔（如 2026-W31,2026-W32）；留空表示最近两周", null);
        String description = "技能：分群留存分析。按注册周切分群，计算各群后续每周的留存率，"
                + "用于回答「留存怎么样」「新用户掉得快不快」这类问题。";
        return new ToolDefinition("cohort_analysis", description, schema,
                ToolDefinition.ToolType.SKILL, this::executeCohortAnalysis);
    }

    private String executeCohortAnalysis(ObjectNode arguments) {
        String weeks = arguments.path("weeks").asText("").trim();
        List<MockData.Cohort> cohorts = weeks.isEmpty()
                ? MockData.COHORTS
                : MockData.COHORTS.stream().filter(cohort -> weeks.contains(cohort.week())).toList();
        ObjectNode result = mapper.createObjectNode();
        result.put("cohortCount", cohorts.size());
        ArrayNode items = result.putArray("cohorts");
        for (MockData.Cohort cohort : cohorts) {
            ObjectNode item = items.addObject();
            item.put("week", cohort.week());
            ArrayNode retention = item.putArray("retention");
            cohort.retention().forEach(retention::add);
        }
        return result.toString();
    }

    // ------------------------------------------------------------------ 通用

    private ToolDefinition searchMemory() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = propertiesOf(schema);
        stringProperty(properties, "query", "要检索的记忆内容，用自然语言描述", null);
        stringProperty(properties, "scopes", "检索范围：USER / AGENT / BOTH", "USER", "AGENT", "BOTH");
        require(schema, "query");
        String description = "检索本租户的长期记忆。USER 侧是用户画像、偏好与业务背景，"
                + "AGENT 侧是历史工具调用经验、SQL 模板与数据源特性。"
                + "适用于需要确认「用户以前是怎么要求的」「上次这个指标是怎么算的」的场景。";
        return new ToolDefinition("search_memory", description, schema,
                ToolDefinition.ToolType.TOOL, this::executeSearchMemory);
    }

    private String executeSearchMemory(ObjectNode arguments) {
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) {
            return error("query 不能为空");
        }
        List<MemoryScope> scopes = switch (arguments.path("scopes").asText("BOTH")
                .trim().toUpperCase(Locale.ROOT)) {
            case "USER" -> List.of(MemoryScope.USER);
            case "AGENT" -> List.of(MemoryScope.AGENT);
            default -> List.of(MemoryScope.USER, MemoryScope.AGENT);
        };
        TenantContext context = TenantContext.current();
        RetrievalResult retrieval = retrievalEngine.retrieve(context, scopes, query, 5);

        ObjectNode result = mapper.createObjectNode();
        result.put("query", query);
        result.put("count", retrieval.memories().size());
        ArrayNode items = result.putArray("items");
        for (RetrievalResult.ScoredMemory scored : retrieval.memories()) {
            ObjectNode item = items.addObject();
            item.put("scope", scored.item().scope().name());
            item.put("type", scored.item().type().name());
            item.put("content", scored.item().content());
            item.put("score", round(scored.score(), 4));
            ArrayNode channels = item.putArray("channels");
            scored.channels().forEach(channels::add);
        }
        return result.toString();
    }

    private ToolDefinition getCurrentTime() {
        ObjectNode schema = objectSchema();
        String description = "获取服务端当前时间。当用户说「上周」「最近30天」「这个月」时，"
                + "先用它换算出具体日期区间再查询，避免时间口径歧义。";
        return new ToolDefinition("get_current_time", description, schema,
                ToolDefinition.ToolType.TOOL, this::executeGetCurrentTime);
    }

    private String executeGetCurrentTime(ObjectNode arguments) {
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate date = now.toLocalDate();
        ObjectNode result = mapper.createObjectNode();
        result.put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        result.put("date", date.format(DATE));
        result.put("weekday", WEEKDAYS[date.getDayOfWeek().getValue() - 1]);
        return result.toString();
    }

    private ToolDefinition calculator() {
        ObjectNode schema = objectSchema();
        stringProperty(propertiesOf(schema), "expression",
                "四则运算表达式，支持 + - * / 与括号，如 (1284300.5-1100000)/1100000*100", null);
        require(schema, "expression");
        String description = "计算四则运算表达式。用于环比、占比、人均这类换算——"
                + "模型直接口算容易出错，交给它算更可靠。";
        return new ToolDefinition("calculator", description, schema,
                ToolDefinition.ToolType.TOOL, this::executeCalculator);
    }

    private String executeCalculator(ObjectNode arguments) {
        String expression = arguments.path("expression").asText("").trim();
        if (expression.isEmpty()) {
            return error("expression 不能为空");
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("expression", expression);
        result.put("result", round(Arithmetic.evaluate(expression), 6));
        return result.toString();
    }

    private ToolDefinition weeklySummary() {
        ObjectNode schema = objectSchema();
        stringProperty(propertiesOf(schema), "metrics",
                "要汇总的指标，逗号分隔，如 gmv,orders", null);
        require(schema, "metrics");
        String description = "技能：汇总生成增长周报。内部会拉取指标、计算环比并输出要点，"
                + "适用于用户说「给我一份周报」「总结一下这周」的场景。";
        return new ToolDefinition("weekly_summary", description, schema,
                ToolDefinition.ToolType.SKILL, this::executeWeeklySummary);
    }

    private String executeWeeklySummary(ObjectNode arguments) {
        String metrics = arguments.path("metrics").asText("gmv").trim();
        ObjectNode result = mapper.createObjectNode();
        result.put("title", "增长周报");
        result.put("metrics", metrics);
        ArrayNode highlights = result.putArray("highlights");
        MockData.WEEKLY_HIGHLIGHTS.forEach(highlights::add);
        return result.toString();
    }

    // ------------------------------------------------------------------ 联网

    private ToolDefinition getWeather() {
        ObjectNode schema = objectSchema();
        stringProperty(propertiesOf(schema), "city",
                "城市名，中文即可，如 郑州、上海、San Francisco；不要带「市」以外的行政区后缀", null);
        require(schema, "city");
        String description = "查询某个城市当前的真实天气与未来三天预报。当用户问「今天天气怎么样」"
                + "「明天要带伞吗」「那边冷不冷」这类问题时调用。返回结果里带一句可以直接引用的中文摘要。";
        return new ToolDefinition("get_weather", description, schema,
                ToolDefinition.ToolType.TOOL, webTools::weather);
    }

    private ToolDefinition searchWeb() {
        ObjectNode schema = objectSchema();
        stringProperty(propertiesOf(schema), "query", "搜索关键词，用自然语言即可", null);
        require(schema, "query");
        String description = "联网搜索网页，返回标题、链接与摘要。用于训练数据里没有的实时信息："
                + "新闻、价格、政策、赛事、人物近况等。结果仅供你组织答案，不要在正文里堆原始链接。";
        return new ToolDefinition("search_web", description, schema,
                ToolDefinition.ToolType.TOOL, webTools::search);
    }

    // ------------------------------------------------------------------ 构造辅助

    private ObjectNode objectSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        return schema;
    }

    private static ObjectNode propertiesOf(ObjectNode schema) {
        return (ObjectNode) schema.path("properties");
    }

    private void stringProperty(ObjectNode properties, String name, String description, String... enumValues) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
        if (enumValues != null && enumValues.length > 0) {
            ArrayNode values = property.putArray("enum");
            for (String value : enumValues) {
                values.add(value);
            }
        }
    }

    private void require(ObjectNode schema, String... names) {
        ArrayNode required = schema.putArray("required");
        for (String name : names) {
            required.add(name);
        }
    }

    /** 统一的失败返回格式；包级可见是为了让 {@link WebTools} 也能复用同一套转义。 */
    static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    private static String quote(String raw) {
        String escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static String wan(double value) {
        return String.format(Locale.ROOT, "%.1f", value / 10000d);
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100d);
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10d, scale);
        return Math.round(value * factor) / factor;
    }

    /**
     * 极简四则运算求值器：递归下降，只认数字、+ - * / 与括号。
     *
     * <p>刻意不用脚本引擎——那等于把任意代码执行权交给模型输出。
     * 顺手把模型常写的全角符号与千分位逗号归一化，减少无谓的"参数错误"往返。
     */
    private static final class Arithmetic {

        private final String text;
        private int pos;

        private Arithmetic(String text) {
            this.text = text;
        }

        static double evaluate(String expression) {
            String normalized = expression
                    .replace('×', '*').replace('÷', '/')
                    .replace('（', '(').replace('）', ')')
                    .replace(",", "").replace("，", "").replace(" ", "");
            Arithmetic arithmetic = new Arithmetic(normalized);
            double value = arithmetic.expression();
            if (arithmetic.pos < normalized.length()) {
                throw new IllegalArgumentException("表达式存在无法解析的字符: '"
                        + normalized.charAt(arithmetic.pos) + "'");
            }
            return value;
        }

        private double expression() {
            double value = term();
            while (pos < text.length()) {
                if (consume('+')) {
                    value += term();
                } else if (consume('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
            return value;
        }

        private double term() {
            double value = factor();
            while (pos < text.length()) {
                if (consume('*')) {
                    value *= factor();
                } else if (consume('/')) {
                    double divisor = factor();
                    if (divisor == 0d) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
            return value;
        }

        private double factor() {
            if (consume('(')) {
                double value = expression();
                if (!consume(')')) {
                    throw new IllegalArgumentException("缺少右括号");
                }
                return value;
            }
            if (consume('-')) {
                return -factor();
            }
            if (consume('+')) {
                return factor();
            }
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("位置 " + pos + " 处缺少数字");
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        private boolean consume(char expected) {
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }
    }
}