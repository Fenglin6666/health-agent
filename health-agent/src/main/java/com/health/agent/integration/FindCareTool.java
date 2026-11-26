package com.health.agent.integration;

import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolResult;
import com.health.agent.knowledge.DepartmentIndex;
import com.health.agent.knowledge.ReferralIndex;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 就医路径：挂什么科 + 附近去哪。
 *
 * <p>把"该挂哪个科"和"附近有什么医院"放在一个工具里，因为它们在实际对话中是一次
 * 完整的动作——用户问"我该去哪看"，不会先问科室再问医院。
 *
 * <p>科室建议来自参考表，输出里明确标注"通常"；模型判断是急症时可以直接推翻。
 */
@Component
public class FindCareTool implements Tool {

    private final AmapClient amap;
    private final DepartmentIndex departments;
    private final ReferralIndex referrals;

    public FindCareTool(AmapClient amap, DepartmentIndex departments, ReferralIndex referrals) {
        this.amap = amap;
        this.departments = departments;
        this.referrals = referrals;
    }

    @Override
    public String name() {
        return "find_care";
    }

    @Override
    public String description() {
        return "查就医路径：根据症状建议挂什么科，并检索附近的医院或药店（名称、地址、距离、营业时间、电话）。"
                + "只返回「有哪些地方」，没有挂号、号源、库存信息。"
                + "用户问「挂什么科」「去哪看」「附近有没有药店」时用这个。";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object",
                 "properties":{
                   "symptom":{"type":"string","description":"用户的症状或病名，用来建议科室；只找药店时可以不填"},
                   "kind":{"type":"string","enum":["hospital","pharmacy"],"description":"找医院还是药店，默认 hospital"},
                   "address":{"type":"string","description":"用户给的文字地址，如「商丘市睢阳区神火大道南段」。会话里已有定位时不用填"},
                   "severity":{"type":"string","enum":["mild","severe"],"description":"重症（肿瘤、心脑血管事件、需要手术的）传 severe，会额外给强势科室参考"},
                   "keywords":{"type":"string","description":"可选，如「24小时」「急诊」「三甲」"}
                 },
                 "required":[]}
                """;
    }

    @Override
    public String check(Map<String, Object> args) {
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        long t0 = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();

        String symptom = args.get("symptom") == null ? "" : String.valueOf(args.get("symptom"));
        departments.suggest(symptom).ifPresent(dept ->
                sb.append("【建议科室】").append(dept)
                        .append("（这是常见症状的通用对应关系，最终以医院分诊为准）\n"));

        // 重症先给强势科室参考：离得近不等于看得好，这条必须在距离之前说
        if ("severe".equals(String.valueOf(args.get("severity")))) {
            referrals.match(symptom).ifPresent(r ->
                    sb.append("【强势科室参考】").append(r.condition()).append(" 方向公认较强的是：")
                            .append(String.join("、", r.hospitals()))
                            .append("\n（按公开常识整理，非官方排名；实际以医院官方和挂号平台为准）\n"));
        }

        String kind = String.valueOf(args.getOrDefault("kind", "hospital"));
        String keywords = args.get("keywords") == null ? "" : String.valueOf(args.get("keywords"));

        if (!amap.available()) {
            sb.append("（地图服务未配置，查不到附近地点）");
            return ToolResult.ok(name(), sb.toString().trim(), System.currentTimeMillis() - t0);
        }

        // 坐标优先：用户设过定位就不该再问地址
        Double lat = toDouble(args.get("_lat"));
        Double lng = toDouble(args.get("_lng"));
        String address = args.get("address") == null ? "" : String.valueOf(args.get("address")).trim();
        if ((lat == null || lng == null) && !address.isBlank()) {
            var geo = amap.geocode(address);
            if (geo.isPresent()) {
                lat = geo.get().lat();
                lng = geo.get().lng();
                sb.append("【定位】按「").append(geo.get().formattedAddress()).append("」查\n");
            } else {
                sb.append("（没能识别这个地址：").append(address).append("，可以让用户说得更具体一些，比如加上城市和区）\n");
                return ToolResult.ok(name(), sb.toString().trim(), System.currentTimeMillis() - t0);
            }
        }
        if (lat == null || lng == null) {
            sb.append("（没有位置信息。可以问用户所在的城市或区域，例如「朝阳区望京」）");
            return ToolResult.ok(name(), sb.toString().trim(), System.currentTimeMillis() - t0);
        }

        String type = "pharmacy".equals(kind) ? AmapClient.TYPE_PHARMACY : AmapClient.TYPE_HOSPITAL;
        // 半径按类型分开：药店到处都有，3 公里够了；医院在地级市很稀疏，
        // 实测商丘市睢阳区神火大道南段 3 公里内一家都查不到，用户会以为"本地没有医院"。
        // 医院放宽到 10 公里，宁可多给几个远一点的，也不能给出"查不到"这种误导性结论。
        int radius = "pharmacy".equals(kind) ? 3000 : 10000;
        List<AmapClient.Poi> pois = amap.around(lat, lng, keywords, type, radius);
        if (pois.isEmpty()) {
            sb.append("（附近 ").append(radius / 1000).append(" 公里内没有找到结果。")
                    .append("这不等于当地没有，可能是地址落在路名上、周边没有已收录的地点，")
                    .append("建议直接说城市名让用户自己搜，或让用户给一个地标）");
            return ToolResult.ok(name(), sb.toString().trim(), System.currentTimeMillis() - t0);
        }

        sb.append(kind.equals("pharmacy") ? "【附近药店】\n" : "【附近医院】\n");
        for (int i = 0; i < Math.min(5, pois.size()); i++) {
            AmapClient.Poi p = pois.get(i);
            sb.append(i + 1).append(". ").append(p.name());
            if (!p.distance().isBlank()) {
                sb.append("（").append(p.distance()).append(" 米）");
            }
            sb.append('\n');
            if (!p.address().isBlank()) {
                sb.append("   地址：").append(p.address()).append('\n');
            }
            if (!p.openTime().isBlank()) {
                sb.append("   营业：").append(p.openTime()).append('\n');
            }
            if (!p.tel().isBlank()) {
                sb.append("   电话：").append(p.tel()).append('\n');
            }
        }
        sb.append("（以上只有位置和营业信息，没有号源与库存）");
        return ToolResult.ok(name(), sb.toString().trim(), System.currentTimeMillis() - t0);
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
