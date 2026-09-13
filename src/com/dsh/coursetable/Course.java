package com.dsh.coursetable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** 一条课程安排（同一门课的不同周次可能出现多条）。 */
public class Course {
    public String name = "";
    public String teacher = "";
    public String room = "";
    public String campus = "";
    public String className = "";
    public String examType = "";
    public String note = "";
    public String weeksText = "";
    public String raw = "";
    /** 1=周一 ... 7=周日；0 表示未知（如“其他课程”） */
    public int day = 0;
    /** 节次范围，0 表示未知 */
    public int startPeriod = 0;
    public int endPeriod = 0;
    /** 上课教学周（升序） */
    public final TreeSet<Integer> weeks = new TreeSet<Integer>();

    public boolean isOther() {
        return day <= 0;
    }

    public String dayName() {
        String[] n = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return day >= 1 && day <= 7 ? n[day] : "其他";
    }

    public String periodText() {
        if (startPeriod <= 0) return "";
        if (endPeriod <= startPeriod) return "第" + startPeriod + "节";
        return "第" + startPeriod + "-" + endPeriod + "节";
    }

    public String weeksSummary() {
        return compress(new ArrayList<Integer>(weeks));
    }

    /** 把 1,2,3,5,7,8 压成 1-3,5,7-8 */
    public static String compress(List<Integer> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int start = list.get(0), prev = start;
        for (int i = 1; i <= list.size(); i++) {
            int cur = (i < list.size()) ? list.get(i) : Integer.MIN_VALUE;
            if (cur != prev + 1) {
                if (sb.length() > 0) sb.append(',');
                if (start == prev) sb.append(start);
                else if (prev == start + 1) sb.append(start).append(',').append(prev);
                else sb.append(start).append('-').append(prev);
                start = cur;
            }
            prev = cur;
        }
        return sb.toString();
    }

    public String signature() {
        return name + "|" + day + "|" + startPeriod + "|" + endPeriod + "|" + teacher + "|" + room;
    }

    @Override
    public String toString() {
        return name + " " + dayName() + " " + periodText() + " " + weeksSummary()
                + " 师:" + teacher + " 地:" + room;
    }
}
