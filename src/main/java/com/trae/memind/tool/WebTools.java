package com.trae.memind.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网类工具：让 Agent 能回答「郑州今天天气怎么样」这类训练数据里没有的实时问题。
 *
 * <p>刻意不接需要密钥的商业搜索/天气 API：本项目的工具是用来演示记忆与编排的，
 * 引入 key 只会让这个模块在别人机器上跑不起来。因此两个实现都走公开端点：
 * <ul>
 *   <li>天气：Open-Meteo（geocoding + forecast），无需 key，返回结构化 JSON</li>
 *   <li>搜索：360 搜索（{@code www.so.com}）的结果页 HTML 解析</li>
 * </ul>
 *
 * <p>搜索源的选择是实测出来的，不是偏好：DuckDuckGo / Wikipedia 的公开接口在部分网络下直接超时，
 * 百度与搜狗对无会话的自动请求只回一个几 KB 的壳页，而 cn.bing.com 虽然能连上，
 * 却会对中文查询返回一堆与关键词完全无关的条目（结果是「健身动作」「阿萨姆神庙」之类），
 * 疑似其反爬降级策略。360 搜索在同样条件下返回的是正常 SERP，故取之。
 *
 * <p>所有失败都返回 {@code {"error":"..."}} 而不是抛异常：工具层一旦抛异常，
 * 整条 SSE 流就断了，用户看到的是「聊天失败」而不是「天气没查到」。
 */
@Component
public class WebTools {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);

    /** 伪装成普通浏览器：搜索引擎对非浏览器 UA 会降级返回无关结果，这一步不是可选项。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private static final String GEOCODING_URL =
            "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String SEARCH_URL = "https://www.so.com/s";

    private static final int MAX_RESULTS = 5;

    /** 360 结果块的标题行，例如 {@code <h3 class="res-title"><a href="...">标题</a></h3>}。 */
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    /** 摘要行，例如 {@code <p class="res-desc">…</p>}；部分卡片用 div 包裹。 */
    private static final Pattern SNIPPET_PATTERN =
            Pattern.compile("class=\"res-desc\"[^>]*>(.*?)</(?:p|div)>", Pattern.DOTALL);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY_PATTERN = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    /** WMO weather code → 中文描述（Open-Meteo 官方口径）。 */
    private static final Map<Integer, String> WEATHER_CODES = createWeatherCodes();

    private final ObjectMapper mapper;
    private final HttpClient http;

    public WebTools(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------------ 天气

    /** 查询指定城市当前天气与未来三天预报，返回可直接被模型引用的 JSON。 */
    public String weather(ObjectNode arguments) {
        String city = arguments.path("city").asText("").trim();
        if (city.isEmpty()) {
            return ToolRegistry.error("city 不能为空");
        }
        try {
            ObjectNode geocoded = geocode(city);
            if (geocoded == null) {
                return ToolRegistry.error("没有找到城市「" + city + "」，请换一个更常见的写法，如「郑州」「上海」");
            }
            double latitude = geocoded.path("latitude").asDouble();
            double longitude = geocoded.path("longitude").asDouble();

            String url = FORECAST_URL + "?latitude=" + latitude + "&longitude=" + longitude
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                    + "precipitation,weather_code,wind_speed_10m"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                    + "precipitation_probability_max"
                    + "&timezone=auto&forecast_days=3";
            JsonNode forecast = mapper.readTree(httpGet(url));

            JsonNode current = forecast.path("current");
            int currentCode = current.path("weather_code").asInt(-1);

            ObjectNode result = mapper.createObjectNode();
            result.put("city", geocoded.path("name").asText(city));
            result.put("admin", geocoded.path("admin1").asText(""));
            result.put("country", geocoded.path("country").asText(""));
            result.put("timezone", forecast.path("timezone").asText(geocoded.path("timezone").asText("")));
            result.put("source", "Open-Meteo");

            ObjectNode currentNode = result.putObject("current");
            currentNode.put("time", current.path("time").asText(""));
            currentNode.put("temperature", current.path("temperature_2m").asDouble());
            currentNode.put("feelsLike", current.path("apparent_temperature").asDouble());
            currentNode.put("humidity", current.path("relative_humidity_2m").asDouble());
            currentNode.put("precipitation", current.path("precipitation").asDouble());
            currentNode.put("windSpeed", current.path("wind_speed_10m").asDouble());
            currentNode.put("condition", describe(currentCode));

            JsonNode daily = forecast.path("daily");
            ArrayNode days = result.putArray("daily");
            int dayCount = Math.min(3, daily.path("time").size());
            for (int index = 0; index < dayCount; index++) {
                ObjectNode day = days.addObject();
                day.put("date", daily.path("time").path(index).asText(""));
                day.put("condition", describe(daily.path("weather_code").path(index).asInt(-1)));
                day.put("minTemperature", daily.path("temperature_2m_min").path(index).asDouble());
                day.put("maxTemperature", daily.path("temperature_2m_max").path(index).asDouble());
                day.put("precipitationProbability",
                        daily.path("precipitation_probability_max").path(index).asDouble());
            }
            result.put("summary", summarize(result));
            return result.toString();
        } catch (Exception ex) {
            log.warn("天气工具调用失败 city={} 原因={}", city, ex.getMessage());
            return ToolRegistry.error("调用天气服务失败：" + ex.getMessage());
        }
    }

    private ObjectNode geocode(String city) throws IOException, InterruptedException {
        String url = GEOCODING_URL + "?name=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                + "&count=1&language=zh&format=json";
        JsonNode response = mapper.readTree(httpGet(url));
        JsonNode results = response.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        return (ObjectNode) results.get(0);
    }

    /** 拼一句可直接被模型抄进正文的中文摘要，省掉模型自己做单位与字段换算的出错机会。 */
    private String summarize(ObjectNode result) {
        StringBuilder summary = new StringBuilder();
        summary.append(result.path("city").asText()).append("当前")
                .append(result.path("current").path("condition").asText())
                .append("，气温 ").append(oneDecimal(result.path("current").path("temperature").asDouble()))
                .append("℃，体感 ").append(oneDecimal(result.path("current").path("feelsLike").asDouble()))
                .append("℃，湿度 ").append((int) result.path("current").path("humidity").asDouble())
                .append("%，风速 ").append(oneDecimal(result.path("current").path("windSpeed").asDouble()))
                .append(" km/h；");
        JsonNode days = result.path("daily");
        if (!days.isEmpty()) {
            JsonNode today = days.get(0);
            summary.append("今天").append(today.path("condition").asText())
                    .append("，气温 ").append(oneDecimal(today.path("minTemperature").asDouble()))
                    .append("~").append(oneDecimal(today.path("maxTemperature").asDouble()))
                    .append("℃，降水概率 ")
                    .append((int) today.path("precipitationProbability").asDouble()).append("%。");
        }
        return summary.toString();
    }

    // ------------------------------------------------------------------ 搜索

    /** 联网搜索，返回标题 / 链接 / 摘要的条目列表。 */
    public String search(ObjectNode arguments) {
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) {
            return ToolRegistry.error("query 不能为空");
        }
        try {
            String url = SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            String html = httpGet(url);

            List<ObjectNode> results = new ArrayList<>();
            String[] blocks = html.split("<li class=\"res-list\"");
            for (String block : blocks) {
                if (results.size() >= MAX_RESULTS) {
                    break;
                }
                ObjectNode item = parseResult(block);
                if (item != null) {
                    results.add(item);
                }
            }

            ObjectNode payload = mapper.createObjectNode();
            payload.put("query", query);
            payload.put("source", "www.so.com");
            payload.put("count", results.size());
            ArrayNode items = payload.putArray("results");
            results.forEach(items::add);
            if (results.isEmpty()) {
                payload.put("hint", "没有解析到结果条目，可以换更通用的关键词再试，或直接回答你已知的内容");
            }
            return payload.toString();
        } catch (Exception ex) {
            log.warn("搜索工具调用失败 query={} 原因={}", query, ex.getMessage());
            return ToolRegistry.error("调用搜索服务失败：" + ex.getMessage());
        }
    }

    private ObjectNode parseResult(String block) {
        Matcher titleMatcher = TITLE_PATTERN.matcher(block);
        if (!titleMatcher.find()) {
            return null;
        }
        String title = clean(titleMatcher.group(2));
        if (title.isEmpty()) {
            return null;
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("title", title);
        item.put("url", normalizeUrl(titleMatcher.group(1)));

        Matcher snippetMatcher = SNIPPET_PATTERN.matcher(block);
        item.put("snippet", snippetMatcher.find() ? clean(snippetMatcher.group(1)) : "");
        return item;
    }

    /**
     * 结果链接有两种形态：直接给出目标站点，或 360 自己的 {@code /link?m=…} 跳转串。
     * 后者是加密参数，无法静态还原，也不值得为每条结果多发一次解析请求——原样返回即可。
     */
    private static String normalizeUrl(String href) {
        if (href == null || href.isBlank()) {
            return "";
        }
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        return href.startsWith("/") ? "https://www.so.com" + href : href;
    }

    private static String clean(String html) {
        String text = TAG_PATTERN.matcher(html).replaceAll("");
        Matcher entity = ENTITY_PATTERN.matcher(text);
        StringBuilder builder = new StringBuilder();
        while (entity.find()) {
            entity.appendReplacement(builder, Matcher.quoteReplacement(decodeEntity(entity.group())));
        }
        entity.appendTail(builder);
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < builder.length(); index++) {
            char ch = builder.charAt(index);
            normalized.append(ch < 0x20 ? ' ' : ch);
        }
        return normalized.toString().replaceAll("\\s{2,}", " ").trim();
    }

    private static String decodeEntity(String entity) {
        String body = entity.substring(1, entity.length() - 1);
        return switch (body.toLowerCase(java.util.Locale.ROOT)) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos", "#39" -> "'";
            case "nbsp" -> " ";
            case "ensp", "emsp", "thinsp" -> " ";
            default -> {
                try {
                    int codePoint = body.startsWith("#x") || body.startsWith("#X")
                            ? Integer.parseInt(body.substring(2), 16)
                            : body.startsWith("#")
                                    ? Integer.parseInt(body.substring(1))
                                    : -1;
                    yield codePoint < 0 ? entity : new String(Character.toChars(codePoint));
                } catch (RuntimeException ex) {
                    yield entity;
                }
            }
        };
    }

    // ------------------------------------------------------------------ HTTP

    private String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/json,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .GET()
                .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(url).getHost());
        }
        return response.body();
    }

    private static String describe(int weatherCode) {
        return WEATHER_CODES.getOrDefault(weatherCode, "未知天气(" + weatherCode + ")");
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static Map<Integer, String> createWeatherCodes() {
        Map<Integer, String> codes = new LinkedHashMap<>();
        codes.put(0, "晴");
        codes.put(1, "晴间多云");
        codes.put(2, "多云");
        codes.put(3, "阴");
        codes.put(45, "有雾");
        codes.put(48, "雾凇");
        codes.put(51, "小毛毛雨");
        codes.put(53, "毛毛雨");
        codes.put(55, "大毛毛雨");
        codes.put(56, "冻毛毛雨");
        codes.put(57, "强冻毛毛雨");
        codes.put(61, "小雨");
        codes.put(63, "中雨");
        codes.put(65, "大雨");
        codes.put(66, "冻雨");
        codes.put(67, "强冻雨");
        codes.put(71, "小雪");
        codes.put(73, "中雪");
        codes.put(75, "大雪");
        codes.put(77, "雪粒");
        codes.put(80, "小阵雨");
        codes.put(81, "阵雨");
        codes.put(82, "强阵雨");
        codes.put(85, "小阵雪");
        codes.put(86, "大阵雪");
        codes.put(95, "雷暴");
        codes.put(96, "雷暴伴小冰雹");
        codes.put(99, "雷暴伴大冰雹");
        return Map.copyOf(codes);
    }
}