package com.health.agent.platform.api;

import com.health.agent.integration.AmapClient;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地点查询入口。
 *
 * <p>两个端点分工：`/geocode` 把地址转坐标，`/nearby` 按坐标找店。
 * Agent 走的是工具（place_search），这两个端点是给前端直接用的。
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final AmapClient amap;

    public PlaceController(AmapClient amap) {
        this.amap = amap;
    }

    @GetMapping("/nearby")
    public Map<String, Object> nearby(@RequestParam double lat,
                                      @RequestParam double lng,
                                      @RequestParam(defaultValue = "pharmacy") String kind,
                                      @RequestParam(defaultValue = "3000") int radius) {
        String type = "hospital".equalsIgnoreCase(kind)
                ? AmapClient.TYPE_HOSPITAL
                : AmapClient.TYPE_PHARMACY;

        List<AmapClient.Poi> pois = amap.around(lat, lng, "", type, radius);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("count", pois.size());
        out.put("items", pois.stream().limit(10).map(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", p.name());
            item.put("address", p.address());
            item.put("distance_m", p.distance());
            item.put("tel", p.tel());
            item.put("open_time", p.openTime());
            item.put("lat", p.lat());
            item.put("lng", p.lng());
            return item;
        }).toList());
        out.put("note", "只含位置与营业信息，不含库存与挂号");
        return out;
    }

    @GetMapping("/geocode")
    public Map<String, Object> geocode(@RequestParam String address) {
        Map<String, Object> out = new LinkedHashMap<>();
        var geo = amap.geocode(address);
        if (geo.isEmpty()) {
            out.put("found", false);
            out.put("message", "没有解析出坐标，换一个更具体的地址试试（如「朝阳区望京」）");
            return out;
        }
        out.put("found", true);
        out.put("lat", geo.get().lat());
        out.put("lng", geo.get().lng());
        out.put("formatted_address", geo.get().formattedAddress());
        out.put("city", geo.get().city());
        return out;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("configured", amap.available());
    }
}
