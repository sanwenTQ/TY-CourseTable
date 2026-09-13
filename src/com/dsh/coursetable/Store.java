package com.dsh.coursetable;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 课表数据的本地存取（JSON，放在应用私有目录）。 */
public class Store {

    private static final String FILE = "timetable.json";

    public static class Data {
        public String semester = "";
        public String source = "";
        public long importedAt = 0;
        public int maxWeek = 16;
        public final List<Course> courses = new ArrayList<Course>();
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    public static void save(Context ctx, Data d) {
        try {
            JSONObject root = new JSONObject();
            root.put("semester", d.semester);
            root.put("source", d.source);
            root.put("importedAt", d.importedAt);
            root.put("maxWeek", d.maxWeek);
            JSONArray arr = new JSONArray();
            for (Course c : d.courses) {
                JSONObject o = new JSONObject();
                o.put("name", c.name);
                o.put("teacher", c.teacher);
                o.put("room", c.room);
                o.put("campus", c.campus);
                o.put("className", c.className);
                o.put("examType", c.examType);
                o.put("note", c.note);
                o.put("weeksText", c.weeksText);
                o.put("day", c.day);
                o.put("sp", c.startPeriod);
                o.put("ep", c.endPeriod);
                o.put("weeks", c.weeksSummary());
                o.put("raw", c.raw);
                arr.put(o);
            }
            root.put("courses", arr);
            FileOutputStream fos = new FileOutputStream(file(ctx));
            fos.write(root.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Data load(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            Data d = new Data();
            d.semester = root.optString("semester", "");
            d.source = root.optString("source", "");
            d.importedAt = root.optLong("importedAt", 0);
            d.maxWeek = root.optInt("maxWeek", 16);
            JSONArray arr = root.optJSONArray("courses");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Course c = new Course();
                    c.name = o.optString("name", "");
                    c.teacher = o.optString("teacher", "");
                    c.room = o.optString("room", "");
                    c.campus = o.optString("campus", "");
                    c.className = o.optString("className", "");
                    c.examType = o.optString("examType", "");
                    c.note = o.optString("note", "");
                    c.weeksText = o.optString("weeksText", "");
                    c.day = o.optInt("day", 0);
                    c.startPeriod = o.optInt("sp", 0);
                    c.endPeriod = o.optInt("ep", 0);
                    c.raw = o.optString("raw", "");
                    for (int w : CourseParser.parseWeeks(o.optString("weeks", ""))) c.weeks.add(w);
                    d.courses.add(c);
                }
            }
            return d;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void clear(Context ctx) {
        File f = file(ctx);
        if (f.exists()) f.delete();
    }
}
