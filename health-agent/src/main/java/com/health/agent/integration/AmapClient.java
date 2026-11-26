package com.health.agent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Optional;

/**
 * 高德 Web 服务客户端。
 *
 * <p>放在 integration 包而不是 knowledge：knowledge 是"医学知识"的出口，
 * 这里是"地理位置"的出口，语义不同。架构规则里两者都算合法的外部出口。
 *
 * <p>能力边界（重要）：高德只给"有什么店、在哪、几点开门"，
 * **没有库存、没有挂号**。所以只能说"附近有这几家药店"，不能说"这家有货"。
 */
@Component
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

    private static final String BASE = "https://restapi.amap.com/v3";

    /** 高德 POI 类型码。 */
    public static final String TYPE_PHARMACY = "090601";
    public static final String TYPE_HOSPITAL = "090100";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String key;

    public AmapClient(ObjectMapper mapper,
                      @Value("${ha.amap.key:}") String key) {
        this.mapper = mapper;
        this.key = key;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("amap client ready: keyPresent={}", key != null && !key.isBlank());
    }

    public boolean available() {
        return key != null && !key.isBlank();
    }

    /** 周边搜索。lat/lng 是 GCJ-02 坐标（高德坐标系）。 */
    public List<Poi> around(double lat, double lng, String keywords, String type, int radius) {
        if (!available()) {
            return List.of();
        }
        String location = "%.6f,%.6f".formatted(lng, lat);
        String url = "%s/place/around?key=%s&location=%s&radius=%d&offset=10&page=1&extensions=all".formatted(
                BASE, key, location, Math.min(Math.max(radius, 500), 50000));
        if (keywords != null && !keywords.isBlank()) {
            url += "&keywords=" + URLEncoder.encode(keywords, StandardCharsets.UTF_8);
        }
        if (type != null && !type.isBlank()) {
            url += "&types=" + type;
        }

        JsonNode root = get(url);
        if (root == null) {
            return List.of();
        }
        List<Poi> pois = new ArrayList<>();
        for (JsonNode p : root.path("pois")) {
            pois.add(new Poi(
                    text(p, "name"),
                    text(p, "address"),
                    text(p, "distance"),
                    text(p, "tel"),
                    bizExt(p, "open_time"),
                    text(p, "type"),
                    coord(p, 0),
                    coord(p, 1)
            ));
        }
        return pois;
    }

    /** 地址 → 经纬度。用户说"朝阳区望京"时用。 */
    public Optional<Geo> geocode(String address) {
        if (!available() || address == null || address.isBlank()) {
            return Optional.empty();
        }
        String url = "%s/geocode/geo?key=%s&address=%s".formatted(
                BASE, key, URLEncoder.encode(address, StandardCharsets.UTF_8));
        JsonNode root = get(url);
        if (root == null || root.path("geocodes").isEmpty()) {
            return Optional.empty();
        }
        JsonNode g = root.path("geocodes").get(0);
        String loc = text(g, "location");
        String[] parts = loc.split(",");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Geo(
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[0]),
                    text(g, "formatted_address"),
                    text(g, "city")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("amap http {}", response.statusCode());
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            if (!"1".equals(root.path("status").asText())) {
                log.warn("amap error: {} {}", root.path("infocode").asText(), root.path("info").asText());
                return null;
            }
            return root;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("amap call failed: {}", e.getMessage());
            return null;
        }
    }

    /** 高德字段经常是 [] 而不是 ""，统一转成字符串。 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return "";
        }
        if (v.isArray()) {
            return v.isEmpty() ? "" : v.get(0).asText("");
        }
        return v.asText("");
    }

    /** 坐标 → 城市名。天气接口要城市，用户设过定位就不该再问他。 */
    public Optional<String> cityOf(double lat, double lng) {
        if (!available()) {
            return Optional.empty();
        }
        String location = "%.6f,%.6f".formatted(lng, lat);
        String url = "%s/geocode/regeo?key=%s&location=%s".formatted(BASE, key, location);
        JsonNode root = get(url);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode comp = root.path("regeocode").path("addressComponent");
        String city = text(comp, "city");
        if (city.isBlank()) {
            city = text(comp, "province");  // 直辖市 city 字段为空
        }
        return city.isBlank() ? Optional.empty() : Optional.of(city);
    }

    /**
     * 天气预报。city 传城市名或 adcode 都行。
     *
     * <p>用 extensions=all 拿未来几天，而不是只有实况——科普 agent 要的是"这几天怎么穿"，
     * 只给当前温度没法回答。
     */
    public List<Weather> weather(String city) {
        if (!available() || city == null || city.isBlank()) {
            return List.of();
        }
        String url = "%s/weather/weatherInfo?key=%s&city=%s&extensions=all".formatted(
                BASE, key, URLEncoder.encode(city, StandardCharsets.UTF_8));
        JsonNode root = get(url);
        if (root == null) {
            return List.of();
        }
        List<Weather> out = new ArrayList<>();
        for (JsonNode f : root.path("forecasts")) {
            for (JsonNode c : f.path("casts")) {
                out.add(new Weather(
                        text(c, "date"),
                        text(c, "dayweather"),
                        text(c, "nightweather"),
                        text(c, "daytemp"),
                        text(c, "nighttemp"),
                        text(c, "daywind") + "风" + text(c, "daypower") + "级"));
                if (out.size() >= 3) {
                    return out;
                }
            }
        }
        return out;
    }

    private static String bizExt(JsonNode node, String field) {
        JsonNode ext = node.path("biz_ext");
        if (!ext.isObject()) {
            return "";
        }
        return text(ext, field);
    }

    private static String coord(JsonNode node, int index) {
        String loc = text(node, "location");
        String[] parts = loc.split(",");
        return parts.length == 2 ? parts[index] : "";
    }

    public record Poi(String name, String address, String distance, String tel,
                      String openTime, String type, String lat, String lng) {}

    public record Geo(double lat, double lng, String formattedAddress, String city) {}

    public record Weather(String date, String dayWeather, String nightWeather,
                          String dayTemp, String nightTemp, String wind) {}
}
