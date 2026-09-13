package com.dsh.coursetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把 Excel/HTML 表格网格解析成课程。优先按“星期列 × 节次行”的表格式课表解析。 */
public class ExcelParser {

    private static final Pattern SEMESTER = Pattern.compile(
            "(\\d{4}\\s*[-–—]\\s*\\d{4}\\s*学年\\s*第?\\s*[一二两三四五六七八九\\d]+\\s*学期)");
    private static final Pattern HEADER_HINT = Pattern.compile("课程|课名|科目");
    private static final Pattern EXAM = Pattern.compile("考核|考试方式");

    public static CourseParser.Result parse(Xls.Grid g) {
        CourseParser.Result r = new CourseParser.Result();
        if (g == null) return r;

        // 学期
        outer:
        for (int row = 0; row < g.rowCount(); row++) {
            for (int col = 0; col < g.colCount(); col++) {
                Matcher m = SEMESTER.matcher(g.at(row, col));
                if (m.find()) {
                    r.semester = m.group(1).replaceAll("\\s+", "");
                    break outer;
                }
            }
        }

        // ---------- 1) 横排“星期”表头：最常见的教务系统课表
        int dayRow = -1;
        Map<Integer, Integer> dayCols = new HashMap<Integer, Integer>();
        for (int row = 0; row < g.rowCount(); row++) {
            Map<Integer, Integer> tmp = new LinkedHashMap<Integer, Integer>();
            for (int col = 0; col < g.colCount(); col++) {
                int d = CourseParser.dayOf(g.at(row, col));
                if (d > 0) tmp.put(col, d);
            }
            if (tmp.size() > dayCols.size()) {
                dayCols = tmp;
                dayRow = row;
            }
        }
        if (dayCols.size() >= 2) {
            for (int row = dayRow + 1; row < g.rowCount(); row++) {
                for (Map.Entry<Integer, Integer> e : dayCols.entrySet()) {
                    String text = g.at(row, e.getKey());
                    if (text.isEmpty()) continue;
                    Course c = CourseParser.cellToCourse(text, e.getValue());
                    if (c != null) r.courses.add(c);
                }
            }
            if (!r.courses.isEmpty()) {
                finish(r);
                return r;
            }
        }

        // ---------- 2) 竖排“星期”表头（星期在某一列，往下排）
        int dayCol = -1;
        Map<Integer, Integer> dayRows = new LinkedHashMap<Integer, Integer>();
        for (int col = 0; col < g.colCount(); col++) {
            Map<Integer, Integer> tmp = new LinkedHashMap<Integer, Integer>();
            for (int row = 0; row < g.rowCount(); row++) {
                int d = CourseParser.dayOf(g.at(row, col));
                if (d > 0) tmp.put(row, d);
            }
            if (tmp.size() > dayRows.size()) {
                dayRows = tmp;
                dayCol = col;
            }
        }
        if (dayRows.size() >= 2) {
            for (Map.Entry<Integer, Integer> e : dayRows.entrySet()) {
                int row = e.getKey();
                for (int col = 0; col < g.colCount(); col++) {
                    if (col == dayCol) continue;
                    String text = g.at(row, col);
                    if (text.isEmpty()) continue;
                    Course c = CourseParser.cellToCourse(text, e.getValue());
                    if (c != null) r.courses.add(c);
                }
            }
            if (!r.courses.isEmpty()) {
                finish(r);
                return r;
            }
        }

        // ---------- 3) 一行一门课的“列式”课表：找含“课程/教师/星期/节次/周次”的表头行
        int headRow = -1;
        Map<String, Integer> colOf = new HashMap<String, Integer>();
        for (int row = 0; row < g.rowCount(); row++) {
            Map<String, Integer> tmp = new HashMap<String, Integer>();
            for (int col = 0; col < g.colCount(); col++) {
                String v = g.at(row, col);
                if (v.isEmpty()) continue;
                if (v.contains("课程") || v.contains("课名") || v.contains("科目")) tmp.put("name", col);
                if (v.contains("教师") || v.contains("老师") || v.contains("授课")) tmp.put("teacher", col);
                if (v.contains("星期") || v.contains("周几")) tmp.put("day", col);
                if (v.contains("节次") || v.contains("节")) tmp.put("period", col);
                if (v.contains("周次") || v.contains("周数") || v.equals("周")) tmp.put("weeks", col);
                if (v.contains("教室") || v.contains("地点") || v.contains("场地")) tmp.put("room", col);
                if (v.contains("校区")) tmp.put("campus", col);
                if (EXAM.matcher(v).find()) tmp.put("exam", col);
            }
            if (tmp.containsKey("name") && (tmp.containsKey("day") || tmp.containsKey("period") || tmp.containsKey("weeks"))) {
                headRow = row;
                colOf = tmp;
                break;
            }
        }
        if (headRow >= 0) {
            for (int row = headRow + 1; row < g.rowCount(); row++) {
                Course c = new Course();
                c.day = colOf.containsKey("day") ? CourseParser.dayOf(g.at(row, colOf.get("day"))) : 0;
                c.name = colOf.containsKey("name") ? g.at(row, colOf.get("name")) : "";
                c.teacher = colOf.containsKey("teacher") ? g.at(row, colOf.get("teacher")) : "";
                c.room = colOf.containsKey("room") ? g.at(row, colOf.get("room")) : "";
                c.campus = colOf.containsKey("campus") ? g.at(row, colOf.get("campus")) : "";
                c.examType = colOf.containsKey("exam") ? g.at(row, colOf.get("exam")) : "";
                String period = colOf.containsKey("period") ? g.at(row, colOf.get("period")) : "";
                String weeks = colOf.containsKey("weeks") ? g.at(row, colOf.get("weeks")) : "";
                c.raw = (c.name + " " + period + " " + weeks + " " + c.teacher + " " + c.room).trim();
                Matcher pm = Pattern.compile("(\\d{1,2})\\s*[-–—~～至]\\s*(\\d{1,2})\\s*节").matcher(period);
                if (pm.find()) {
                    c.startPeriod = CourseParser.parseInt(pm.group(1));
                    c.endPeriod = CourseParser.parseInt(pm.group(2));
                } else {
                    Matcher pm2 = Pattern.compile("(\\d{1,2})\\s*节").matcher(period);
                    if (pm2.find()) {
                        c.startPeriod = CourseParser.parseInt(pm2.group(1));
                        c.endPeriod = c.startPeriod;
                    }
                }
                Matcher wm = Pattern.compile("([0-9][0-9,，、\\-–—~～至\\s]*)\\s*周").matcher(weeks.isEmpty() ? c.raw : weeks);
                if (wm.find()) {
                    c.weeksText = wm.group(1).replaceAll("\\s", "");
                    for (int w : CourseParser.parseWeeks(wm.group(1))) c.weeks.add(w);
                }
                c.name = c.name.replaceAll("[★☆●○◇◆*]+\\s*$", "").trim();
                if (c.name.isEmpty()) continue;
                if (c.weeks.isEmpty() && c.startPeriod <= 0) continue;
                r.courses.add(c);
            }
            if (!r.courses.isEmpty()) {
                finish(r);
                return r;
            }
        }

        // ---------- 4) 兜底：任何含“x-y节 / x-y周”的单元格都当课程
        for (int row = 0; row < g.rowCount(); row++) {
            int day = 0;
            if (dayRow >= 0) {
                for (Map.Entry<Integer, Integer> e : dayCols.entrySet()) {
                    if (e.getKey() <= 0) day = e.getValue();
                }
            }
            for (int col = 0; col < g.colCount(); col++) {
                String text = g.at(row, col);
                if (text.isEmpty()) continue;
                Course c = CourseParser.cellToCourse(text, day);
                if (c != null) r.courses.add(c);
            }
        }
        finish(r);
        return r;
    }

    private static void finish(CourseParser.Result r) {
        // 去重
        Map<String, Course> uniq = new LinkedHashMap<String, Course>();
        for (Course c : r.courses) {
            if (c.name.isEmpty()) continue;
            String k = c.signature() + "|" + c.weeksSummary();
            if (!uniq.containsKey(k)) uniq.put(k, c);
        }
        r.courses.clear();
        r.courses.addAll(uniq.values());
        for (Course c : r.courses) for (int w : c.weeks) r.maxWeek = Math.max(r.maxWeek, w);
        if (r.maxWeek == 0) r.maxWeek = 16;
    }
}
