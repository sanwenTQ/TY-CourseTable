package com.dsh.coursetable;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从教务系统导出的“课表 PDF”中抽取课程。
 *
 * 做法：取出每个字形的位置（PDFBox 的 xDirAdj / yDirAdj 已是“阅读方向”坐标），
 * 聚合成行、按星期列分簇，再从单元格文字里解析节次 / 周次 / 教师 / 场地。
 */
public class CourseParser {

    public static class Result {
        public final List<Course> courses = new ArrayList<Course>();
        public String semester = "";
        public int maxWeek = 0;
        public String note = "";
    }

    /** 一行文字片段 */
    static class Line {
        String text = "";
        float x0, x1, y, size;
        int page;
        @Override public String toString() {
            return String.format("p%d y=%.1f x=%.1f sz=%.1f |%s|", page, y, x0, size, text);
        }
    }

    private static final Pattern ENTRY = Pattern.compile(
            "[（(]?\\s*第?\\s*(\\d{1,2})\\s*(?:[-–—~～至]\\s*(\\d{1,2}))?\\s*节\\s*[)）]?\\s*"
            + "([0-9][0-9,，、\\-–—~～至\\s]*)\\s*周");
    private static final Pattern ENTRY_LOOSE = Pattern.compile(
            "(?:第\\s*)?(\\d{1,2})\\s*[-–—~～至]\\s*(\\d{1,2})\\s*节");
    private static final Pattern SEMESTER = Pattern.compile(
            "(\\d{4}\\s*[-–—]\\s*\\d{4}\\s*学年\\s*第?\\s*[一二两三四五六七八九\\d]+\\s*学期)");
    private static final Pattern TEACHER = Pattern.compile("教师\\s*[:：]\\s*([^/;；:：]*)");
    private static final Pattern ROOM = Pattern.compile("场地\\s*[:：]\\s*([^/;；:：]*)");
    private static final Pattern CAMPUS = Pattern.compile("校区\\s*[:：]\\s*([^/;；:：]*)");
    private static final Pattern CLASSNAME = Pattern.compile("教学班\\s*[:：]\\s*([^/;；()（）\\s]{2,20})");
    private static final Pattern EXAM = Pattern.compile("考核方式\\s*[:：]\\s*([^/;；:：]*)");
    private static final Pattern NOTE = Pattern.compile("选课备注\\s*[:：]\\s*([^/;；:：]*)");

    // ------------------------------------------------------------------ 入口

    public static Result parse(File f) throws IOException {
        PDDocument doc = PDDocument.load(f);
        try { return parse(doc); } finally { close(doc); }
    }

    public static Result parse(InputStream in) throws IOException {
        PDDocument doc = PDDocument.load(in);
        try { return parse(doc); } finally { close(doc); }
    }

    private static void close(PDDocument d) { try { d.close(); } catch (Exception ignore) {} }

    public static Result parse(PDDocument doc) throws IOException {
        final List<List<TextPosition>> pagePos = new ArrayList<List<TextPosition>>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override protected void processTextPosition(TextPosition t) {
                int p = getCurrentPageNo() - 1;
                if (p < 0) return;
                while (pagePos.size() <= p) pagePos.add(new ArrayList<TextPosition>());
                pagePos.get(p).add(t);
            }
        };
        stripper.setSortByPosition(false);
        stripper.getText(doc);

        Result res = new Result();
        List<List<Line>> pages = new ArrayList<List<Line>>();
        for (int p = 0; p < pagePos.size(); p++) pages.add(buildLines(pagePos.get(p), p));

        // 学期
        outer:
        for (List<Line> lines : pages) {
            for (Line l : lines) {
                Matcher m = SEMESTER.matcher(l.text);
                if (m.find()) { res.semester = m.group(1).replaceAll("\\s+", ""); break outer; }
            }
        }

        // 星期表头 → 坐标
        float[] dayCoord = new float[8];
        int bestPage = -1, bestCount = 0;
        for (int p = 0; p < pages.size(); p++) {
            int c = 0;
            for (Line l : pages.get(p)) if (dayOf(l.text) > 0) c++;
            if (c > bestCount) { bestCount = c; bestPage = p; }
        }
        boolean daysOnX = true;
        if (bestPage >= 0) {
            List<Line> hd = new ArrayList<Line>();
            for (Line l : pages.get(bestPage)) if (dayOf(l.text) > 0) hd.add(l);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Line l : hd) {
                minX = Math.min(minX, l.x0); maxX = Math.max(maxX, l.x0);
                minY = Math.min(minY, l.y);  maxY = Math.max(maxY, l.y);
            }
            daysOnX = (maxX - minX) >= (maxY - minY);
            for (Line l : hd) {
                int d = dayOf(l.text);
                if (d > 0) dayCoord[d] = daysOnX ? l.x0 : l.y;
            }
        }

        List<List<float[]>> pagePeriods = new ArrayList<List<float[]>>();
        for (List<Line> lines : pages) {
            List<float[]> ps = new ArrayList<float[]>();
            for (Line l : lines) {
                String t = l.text.trim();
                if (t.matches("\\d{1,2}") && l.size >= 10.0f) {
                    int v = parseInt(t);
                    if (v >= 1 && v <= 20) ps.add(new float[]{v, l.y});
                }
            }
            pagePeriods.add(ps);
        }

        for (int pi = 0; pi < pages.size(); pi++) {
            List<Line> lines = pages.get(pi);
            for (Line l : lines) if (l.text.contains("其他课程")) parseOther(l.text, res);

            List<Line> cells = new ArrayList<Line>();
            for (Line l : lines) {
                String t = l.text;
                if (t.isEmpty()) continue;
                if (dayOf(t) > 0) continue;
                if (t.contains("其他课程")) continue;
                if (t.contains("打印时间")) continue;
                if (t.contains("讲课") && t.contains("实验") && t.contains("上机")) continue;
                if (t.matches("^\\s*(时间段|节次)\\s*$")) continue;
                if (t.matches("^\\s*(上午|中午|下午|晚上|早晨)\\s*$")) continue;
                if (t.matches("^\\s*\\d{1,2}\\s*$")) continue;
                if (SEMESTER.matcher(t).find()) continue;
                cells.add(l);
            }
            for (List<Line> col : clusterByColumn(cells))
                parseColumn(col, dayCoord, daysOnX, pagePeriods.get(pi), res);
        }

        // 去重
        Map<String, Course> uniq = new LinkedHashMap<String, Course>();
        for (Course c : res.courses) {
            if (c.name.isEmpty()) continue;
            String k = c.signature() + "|" + c.weeksSummary();
            if (!uniq.containsKey(k)) uniq.put(k, c);
        }
        res.courses.clear();
        res.courses.addAll(uniq.values());
        for (Course c : res.courses) for (int w : c.weeks) res.maxWeek = Math.max(res.maxWeek, w);
        if (res.maxWeek == 0) res.maxWeek = 16;
        return res;
    }

    // ------------------------------------------------------------------ 行聚合

    static List<Line> buildLines(List<TextPosition> ps, int page) {
        List<TextPosition> list = new ArrayList<TextPosition>();
        for (TextPosition t : ps) {
            String u = t.getUnicode();
            if (u != null && !u.isEmpty()) list.add(t);
        }
        Collections.sort(list, new Comparator<TextPosition>() {
            public int compare(TextPosition a, TextPosition b) {
                int c = Float.compare(a.getYDirAdj(), b.getYDirAdj());
                return c != 0 ? c : Float.compare(a.getXDirAdj(), b.getXDirAdj());
            }
        });
        List<Line> out = new ArrayList<Line>();
        int i = 0;
        while (i < list.size()) {
            TextPosition first = list.get(i);
            float y = first.getYDirAdj();
            float tol = Math.max(2.0f, Math.abs(first.getHeightDir()) * 0.5f);
            List<TextPosition> group = new ArrayList<TextPosition>();
            int j = i;
            while (j < list.size() && Math.abs(list.get(j).getYDirAdj() - y) <= tol) {
                group.add(list.get(j));
                j++;
            }
            i = j;
            Collections.sort(group, new Comparator<TextPosition>() {
                public int compare(TextPosition a, TextPosition b) {
                    return Float.compare(a.getXDirAdj(), b.getXDirAdj());
                }
            });
            // 按 x 间隔切分成多个片段
            Line cur = newLine(page, group.get(0));
            float right = group.get(0).getXDirAdj() + Math.abs(group.get(0).getWidthDirAdj());
            StringBuilder sb = new StringBuilder();
            for (TextPosition t : group) {
                float fs = Math.max(1f, Math.abs(t.getFontSizeInPt()));
                if (sb.length() > 0 && t.getXDirAdj() - right > fs * 0.75f) {
                    cur.text = sb.toString().trim();
                    if (!cur.text.isEmpty()) out.add(cur);
                    cur = newLine(page, t);
                    sb.setLength(0);
                }
                if (sb.length() == 0) {
                    cur.x0 = t.getXDirAdj();
                    cur.y = t.getYDirAdj();
                }
                sb.append(t.getUnicode());
                cur.x1 = Math.max(cur.x1, t.getXDirAdj() + Math.abs(t.getWidthDirAdj()));
                cur.size = Math.max(cur.size, fs);
                right = t.getXDirAdj() + Math.abs(t.getWidthDirAdj());
            }
            cur.text = sb.toString().trim();
            if (!cur.text.isEmpty()) out.add(cur);
        }
        return out;
    }

    private static Line newLine(int page, TextPosition t) {
        Line l = new Line();
        l.page = page;
        l.x0 = t.getXDirAdj();
        l.y = t.getYDirAdj();
        l.x1 = l.x0;
        l.size = Math.abs(t.getFontSizeInPt());
        return l;
    }

    // ------------------------------------------------------------------ 列分簇

    static List<List<Line>> clusterByColumn(List<Line> lines) {
        List<Line> sorted = new ArrayList<Line>(lines);
        Collections.sort(sorted, new Comparator<Line>() {
            public int compare(Line a, Line b) {
                int c = Float.compare(a.x0, b.x0);
                return c != 0 ? c : Float.compare(a.y, b.y);
            }
        });
        List<List<Line>> cols = new ArrayList<List<Line>>();
        for (Line l : sorted) {
            List<Line> target = null;
            for (List<Line> c : cols) {
                if (Math.abs(l.x0 - c.get(0).x0) <= 6f) { target = c; break; }
            }
            if (target == null) { target = new ArrayList<Line>(); cols.add(target); }
            target.add(l);
        }
        for (List<Line> c : cols) {
            Collections.sort(c, new Comparator<Line>() {
                public int compare(Line a, Line b) { return Float.compare(a.y, b.y); }
            });
        }
        return cols;
    }

    // ------------------------------------------------------------------ 单元格

    static void parseColumn(List<Line> col, float[] dayCoord, boolean daysOnX,
                            List<float[]> pagePeriods, Result res) {
        int day = nearestDay(col.get(0).x0, col.get(0).y, dayCoord, daysOnX);
        Course cur = null;
        String pendingName = null;
        List<Course> made = new ArrayList<Course>();

        for (Line l : col) {
            String t = l.text;
            Matcher em = ENTRY.matcher(t);
            if (em.find() && !t.contains("周学时")) {
                int p1 = parseInt(em.group(1));
                int p2 = em.group(2) != null ? parseInt(em.group(2)) : p1;
                String rest = t.substring(em.end());
                int[] ws = parseWeeks(em.group(3));
                boolean single = rest.matches("^\\s*[（(]?\\s*单.*");
                boolean dbl = rest.matches("^\\s*[（(]?\\s*双.*");

                boolean merge = (cur != null && pendingName == null
                        && cur.startPeriod == p1 && cur.endPeriod == p2 && cur.day == day);
                Course c = merge ? cur : new Course();
                if (!merge) {
                    c.day = day;
                    c.startPeriod = p1;
                    c.endPeriod = p2;
                    c.name = pendingName != null ? cleanName(pendingName) : "";
                    c.weeksText = em.group(3).replaceAll("\\s", "");
                    c.raw = t;
                    res.courses.add(c);
                    made.add(c);
                    cur = c;
                    pendingName = null;
                } else {
                    c.raw += " || " + t;
                    if (c.weeksText.indexOf(em.group(3).trim()) < 0) c.weeksText += "," + em.group(3).trim();
                }
                for (int w : ws) {
                    if (single && w % 2 == 0) continue;
                    if (dbl && w % 2 == 1) continue;
                    c.weeks.add(w);
                }
                continue;
            }
            if (isNameLike(l, t)) {
                pendingName = t;
                cur = null;
                continue;
            }
            if (cur != null) {
                cur.raw += t;
            }
        }
        for (Course c : made) parseFields(c.raw, c);
        // 有些学校的课表单元格里不写“(1-2节)1-16周”，只有节次编号在表格行首：
        // 这种情况用几何位置兜底（节次按 y 匹配行首编号，周次用文本里的“x-y周”）。
        if (made.isEmpty() && !col.isEmpty() && !pagePeriods.isEmpty()) {
            fallbackColumn(col, day, daysOnX, pagePeriods, res);
        }
    }

    private static final Pattern LOOSE_WEEK = Pattern.compile(
            "(\\d{1,2}(?:\\s*[-–—~～至]\\s*\\d{1,2})?(?:\\s*[,，、]\\s*\\d{1,2})*)\\s*周");

    /** 没有 (x-y节) 文本时的几何兜底解析 */
    static void fallbackColumn(List<Line> col, int day, boolean daysOnX,
                               List<float[]> pagePeriods, Result res) {
        List<Course> tmp = new ArrayList<Course>();
        Course cur = null;
        for (Line l : col) {
            String t = l.text;
            if (isNameLike(l, t)) {
                cur = new Course();
                cur.name = cleanName(t);
                cur.day = day;
                cur.raw = t;
                tmp.add(cur);
                continue;
            }
            if (cur != null) cur.raw += t;
        }
        for (Course c : tmp) {
            // 节次：取落在该块 y 范围内的行首编号
            float y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (Line l : col) {
                if (c.raw.startsWith(l.text)) { y0 = Math.min(y0, l.y); y1 = Math.max(y1, l.y); }
            }
            if (day == 0) continue;
            for (float[] p : pagePeriods) {
                if (p[1] >= y0 - 8 && p[1] <= y1 + 20) {
                    if (c.startPeriod == 0 || p[0] < c.startPeriod) c.startPeriod = (int) p[0];
                    if (p[0] > c.endPeriod) c.endPeriod = (int) p[0];
                }
            }
            Matcher mw = LOOSE_WEEK.matcher(c.raw);
            if (mw.find()) {
                c.weeksText = mw.group(1).replaceAll("\\s", "");
                for (int w : parseWeeks(c.weeksText)) c.weeks.add(w);
                if (c.weeks.isEmpty()) continue;
            }
            if (c.weeks.isEmpty() && c.startPeriod == 0) continue;
            if (c.name.isEmpty()) continue;
            parseFields(c.raw, c);
            res.courses.add(c);
        }
    }

    static void parseFields(String text, Course c) {
        c.teacher = joinAll(TEACHER, text, c.teacher);
        c.room = joinAll(ROOM, text, c.room);
        c.campus = joinAll(CAMPUS, text, c.campus);
        if (c.className.isEmpty()) {
            Matcher m = CLASSNAME.matcher(text);
            if (m.find()) c.className = m.group(1).trim();
        }
        if (c.examType.isEmpty()) {
            Matcher m = EXAM.matcher(text);
            if (m.find()) c.examType = m.group(1).trim();
        }
        if (c.note.isEmpty()) {
            Matcher m = NOTE.matcher(text);
            if (m.find()) c.note = m.group(1).trim();
        }
    }

    static final String[] KEYWORDS = {"教师", "场地", "校区", "教学班", "考核", "选课", "课程学时",
            "周学时", "总学时", "学分", "地点", "教室", "上课", "时间", "备注", "组成"};

    /** 折行拼接后字段值可能粘连下一个字段名，这里截断 */
    static String cutValue(String v) {
        int end = v.length();
        for (String k : KEYWORDS) {
            int i = v.indexOf(k);
            if (i >= 0 && i < end) end = i;
        }
        return v.substring(0, end).trim();
    }

    /** 把某个字段在整段文字里出现的所有取值去重拼起来（同一门课可能有多个教师/多个场地） */
    static String joinAll(Pattern p, String text, String old) {
        StringBuilder sb = new StringBuilder();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String v = cutValue(m.group(1));
            if (v.isEmpty() || v.length() > 24) continue;
            if (sb.indexOf(v) >= 0 || old.indexOf(v) >= 0) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(v);
        }
        if (sb.length() == 0) return old;
        return old.isEmpty() ? sb.toString() : old + "/" + sb;
    }

    static int[] parseWeeks(String expr) {
        TreeSet<Integer> out = new TreeSet<Integer>();
        if (expr == null) return new int[0];
        String s = expr.replace('，', ',').replace('、', ',').replace('－', '-')
                       .replace('–', '-').replace('—', '-').replace('~', '-')
                       .replace('～', '-').replace('至', '-');
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            String[] r = part.split("-");
            try {
                int a = Integer.parseInt(r[0].trim());
                int b = r.length > 1 && !r[1].trim().isEmpty() ? Integer.parseInt(r[1].trim()) : a;
                if (b < a) { int t = a; a = b; b = t; }
                if (b - a > 40) b = a;
                for (int i = a; i <= b; i++) out.add(i);
            } catch (Exception ignore) {}
        }
        int[] arr = new int[out.size()];
        int i = 0;
        for (int w : out) arr[i++] = w;
        return arr;
    }

    // 给 Excel 单元格用的更宽松的匹配
    private static final Pattern CELL_PERIOD = Pattern.compile(
            "(\\d{1,2})\\s*[-–—~～至]\\s*(\\d{1,2})\\s*节|第\\s*(\\d{1,2})\\s*节");
    private static final Pattern CELL_WEEK = Pattern.compile(
            "([0-9][0-9,，、\\-–—~～至\\s]*)\\s*周");

    /** 把一个单元格（或一段课表文字）解析成一门课；解析不出来返回 null */
    static Course cellToCourse(String text, int day) {
        if (text == null) return null;
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() < 3) return null;
        Course c = new Course();
        c.day = day;
        c.raw = text;
        String head = text;

        Matcher em = ENTRY.matcher(text);
        if (em.find()) {
            c.startPeriod = parseInt(em.group(1));
            c.endPeriod = em.group(2) != null ? parseInt(em.group(2)) : c.startPeriod;
            c.weeksText = em.group(3).replaceAll("\\s", "");
            String rest = text.substring(em.end());
            boolean single = rest.matches("^\\s*[（(]?\\s*单.*");
            boolean dbl = rest.matches("^\\s*[（(]?\\s*双.*");
            for (int w : parseWeeks(em.group(3))) {
                if (single && w % 2 == 0) continue;
                if (dbl && w % 2 == 1) continue;
                c.weeks.add(w);
            }
            head = text.substring(0, em.start());
        } else {
            Matcher pm = CELL_PERIOD.matcher(text);
            if (pm.find()) {
                if (pm.group(3) != null) {
                    c.startPeriod = parseInt(pm.group(3));
                    c.endPeriod = c.startPeriod;
                } else {
                    c.startPeriod = parseInt(pm.group(1));
                    c.endPeriod = parseInt(pm.group(2));
                }
                head = text.substring(0, pm.start());
            }
            Matcher wm = CELL_WEEK.matcher(text);
            if (wm.find()) {
                String wexpr = wm.group(1);
                c.weeksText = wexpr.replaceAll("\\s", "");
                String after = text.substring(wm.end());
                boolean single = after.matches("^\\s*[（(]?\\s*单.*");
                boolean dbl = after.matches("^\\s*[（(]?\\s*双.*");
                for (int w : parseWeeks(wexpr)) {
                    if (single && w % 2 == 0) continue;
                    if (dbl && w % 2 == 1) continue;
                    c.weeks.add(w);
                }
            }
        }

        if (!c.weeks.isEmpty() && c.startPeriod <= 0) {
            // 有周次但没节次：从“第X节”或“X节”里再找一次
            Matcher pm2 = Pattern.compile("(\\d{1,2})\\s*节").matcher(text);
            if (pm2.find()) {
                c.startPeriod = parseInt(pm2.group(1));
                c.endPeriod = c.startPeriod;
            }
        }
        parseFields(text, c);
        c.name = cleanName(head);
        if (c.name.isEmpty()) {
            String alt = cleanName(personField(text));
            c.name = alt;
        }
        if (c.weeks.isEmpty() && c.startPeriod <= 0) return null;
        if (c.name.isEmpty() && c.teacher.isEmpty() && c.room.isEmpty()) return null;
        return c;
    }

    /** 从一段文字里猜课程名（取最前面一段不是“键:值”的内容） */
    private static String personField(String text) {
        String[] parts = text.split("/");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.contains(":") || t.contains("：")) continue;
            if (t.matches("^[（(].*")) continue;
            return t;
        }
        return "";
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    static int dayOf(String text) {
        String t = text.replaceAll("[\\s\\u00a0]", "");
        String[] pref = {"星期", "礼拜", "周"};
        for (String p : pref) {
            if (t.startsWith(p)) {
                String s = t.substring(p.length());
                if (s.length() != 1) continue;
                switch (s.charAt(0)) {
                    case '一': return 1;
                    case '二': return 2;
                    case '三': return 3;
                    case '四': return 4;
                    case '五': return 5;
                    case '六': return 6;
                    case '日': case '天': return 7;
                }
            }
        }
        return 0;
    }

    static int nearestDay(float x, float y, float[] dayCoord, boolean daysOnX) {
        float v = daysOnX ? x : y;
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int d = 1; d <= 7; d++) {
            if (dayCoord[d] == 0) continue;
            float dist = Math.abs(v - dayCoord[d]);
            if (dist < bestD) { bestD = dist; best = d; }
        }
        return bestD <= 130 ? best : 0;
    }

    static boolean isNameLike(Line l, String t) {
        if (t.length() < 2 || t.length() > 40) return false;
        if (t.contains(":") || t.contains("：")) return false;
        if (t.contains("/")) return false;
        if (t.matches("^\\d.*")) return false;
        if (t.matches("^[（(].*")) return false;
        if (t.matches(".*\\d{4}\\s*[-–—]\\s*\\d{4}.*")) return false;
        if (l.size > 0 && l.size < 7.5f) return false;
        return true;
    }

    static String cleanName(String s) {
        return s.trim().replaceAll("[★☆●○◇◆*]+\\s*$", "").trim();
    }

    // ------------------------------------------------------------------ 其他课程

    static void parseOther(String line, Result res) {
        int idx = line.indexOf("其他课程");
        if (idx < 0) return;
        String body = line.substring(idx + 4).replaceFirst("^[：:\\s]+", "");
        for (String seg : body.split("[;；]")) {
            seg = seg.trim();
            if (seg.isEmpty()) continue;
            Course c = new Course();
            c.day = 0;
            String name = seg, teacher = "";
            Matcher mk = Pattern.compile("^([^●○◇◆*]+)[●○◇◆*]").matcher(seg);
            if (mk.find()) {
                name = mk.group(1).trim();
                Matcher mt = Pattern.compile("^([^（(]*)").matcher(seg.substring(mk.end()));
                if (mt.find()) teacher = mt.group(1).trim();
            } else {
                String[] slash = seg.split("/");
                if (slash.length > 0) name = slash[0].trim();
            }
            c.name = cleanName(name);
            c.teacher = teacher;
            Matcher mw = Pattern.compile("(\\d{1,2})\\s*[-–—~～至]\\s*(\\d{1,2})\\s*周").matcher(seg);
            if (mw.find()) {
                c.weeksText = mw.group(1) + "-" + mw.group(2);
                for (int w : parseWeeks(mw.group(1) + "-" + mw.group(2))) c.weeks.add(w);
            } else {
                Matcher mw2 = Pattern.compile("(\\d{1,2})\\s*周").matcher(seg);
                if (mw2.find()) {
                    c.weeksText = mw2.group(1);
                    for (int w : parseWeeks(mw2.group(1))) c.weeks.add(w);
                }
            }
            Matcher mr = Pattern.compile("/([^/;；]*)").matcher(seg);
            String tail = "";
            while (mr.find()) tail = mr.group(1).trim();
            if (!tail.isEmpty() && !tail.equals("无")) c.room = tail;
            c.raw = seg;
            res.courses.add(c);
        }
    }
}
