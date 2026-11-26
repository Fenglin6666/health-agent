package com.health.agent.integration;

import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 日常上下文：日期、星期、节气、天气。
 *
 * <p><b>为什么合成一个工具而不是三个</b>：科普 agent 每次回答几乎都要同时用到"今天几号"
 * 和"什么节气"——"处暑了，早晚温差大"这种话，缺一个就说不成。拆成三个工具意味着三次
 * 调用往返，每次几百毫秒，而它们的数据是一起用的。**按使用方式合并，不是按数据来源合并。**
 *
 * <p>天气要城市。优先用会话里的坐标反查（用户设过定位就不该再问），没有坐标才要模型传城市名。
 *
 * <p>节气用通用寿星公式推算，1900-2100 年内除个别年份修正日外准确。这里只用来判断
 * "现在处于哪个节气"，差一天不影响任何健康建议，所以不引入外部数据源。
 */
@Component
public class DailyContextTool implements Tool {

    private final AmapClient amap;

    /** 24 节气名称，按公历顺序，每月两个。 */
    private static final String[] TERMS = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
            "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
    };

    /** 21 世纪各节气对应的 C 值（寿星公式）。下标与 TERMS 对齐。 */
    private static final double[] C = {
            5.4055, 20.12, 3.87, 18.73, 5.63, 20.646, 4.81, 20.1,
            5.52, 21.04, 5.678, 21.37, 7.108, 22.83, 7.5, 23.13,
            7.646, 23.042, 8.318, 23.438, 7.438, 22.36, 7.18, 21.94
    };

    private static final String[] WEEKDAY = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    public DailyContextTool(AmapClient amap) {
        this.amap = amap;
    }

    @Override
    public String name() {
        return "daily_context";
    }

    @Override
    public String description() {
        return "查今天的日期、星期、节气和当地天气。回答日常健康提醒（穿衣、作息、过敏、心脑血管注意事项）前先调它，"
                + "不要凭训练数据里的日期和天气作答。";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object",
                 "properties":{
                   "city":{"type":"string","description":"城市名，如「商丘」。会话里已有定位时可以留空"}
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
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder();

        sb.append("【日期】").append(today.format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
                .append(" ").append(WEEKDAY[today.getDayOfWeek().getValue() - 1]).append('\n');
        sb.append("【节气】").append(solarTerm(today)).append('\n');

        String weather = weather(args);
        sb.append(weather);

        return ToolResult.ok(name(), sb.toString().trim(), System.currentTimeMillis() - t0);
    }

    /** 当前所处节气：本年度已经过去的最后一个节气。 */
    static String solarTerm(LocalDate date) {
        int year = date.getYear();
        LocalDate current = null;
        String name = null;
        for (int i = 0; i < TERMS.length; i++) {
            LocalDate d = termDate(year, i);
            if (!d.isAfter(date)) {
                current = d;
                name = TERMS[i];
            }
        }
        if (name == null) {
            // 1 月 1 日到小寒之间，仍属上一年冬至
            return "冬至（" + TERMS[23] + " 之后，小寒未至）";
        }
        return name + "（" + current.getMonthValue() + "月" + current.getDayOfMonth() + "日起）";
    }

    static LocalDate termDate(int year, int index) {
        int y = year % 100;
        int month = index / 2 + 1;
        int day = (int) Math.floor(y * 0.2422 + C[index]) - (int) Math.floor((y - 1) / 4.0);
        day = Math.max(1, Math.min(day, 31));
        return LocalDate.of(year, month, day);
    }

    private String weather(Map<String, Object> args) {
        if (!amap.available()) {
            return "【天气】（未配置地图服务，查不到天气）";
        }
        String city = args.get("city") == null ? "" : String.valueOf(args.get("city")).trim();
        if (city.isEmpty()) {
            Double lat = toDouble(args.get("_lat"));
            Double lng = toDouble(args.get("_lng"));
            if (lat != null && lng != null) {
                city = amap.cityOf(lat, lng).orElse("");
            }
        }
        if (city.isEmpty()) {
            return "【天气】（不知道用户在哪个城市。可以直接问一句「你在哪个城市」，不要编天气）";
        }
        List<AmapClient.Weather> list = amap.weather(city);
        if (list.isEmpty()) {
            return "【天气】（查不到「" + city + "」的天气）";
        }
        StringBuilder sb = new StringBuilder("【天气】").append(city).append('\n');
        for (AmapClient.Weather w : list) {
            sb.append("  ").append(w.date().isBlank() ? "今天" : w.date())
                    .append(" ").append(w.dayWeather());
            if (!w.nightWeather().isBlank() && !w.nightWeather().equals(w.dayWeather())) {
                sb.append("转").append(w.nightWeather());
            }
            sb.append(" ").append(w.dayTemp()).append("~").append(w.nightTemp()).append("℃");
            if (!w.wind().isBlank()) {
                sb.append(" ").append(w.wind());
            }
            sb.append('\n');
        }
        return sb.toString();
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
