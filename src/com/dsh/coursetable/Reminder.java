package com.dsh.coursetable;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 上课提醒：按课表算出「下一批要提醒的课」，用 AlarmManager 定时，到点由
 * {@link ReminderReceiver} 弹出通知（左时间 / 右课程名）。
 */
public class Reminder {

    /** 一次待提醒的课 */
    public static class Item {
        public long triggerAt;   // 提醒时刻（毫秒）
        public long classAt;     // 上课时刻
        public long classEndAt;  // 下课时刻（Android 16 实时更新的时间轴要用）
        public String course;
        public int startPeriod;
        public int endPeriod;
        public String timeText;  // 14:00~14:45

        public String key() { return triggerAt + "|" + course; }
    }

    private static final int LOOKAHEAD_DAYS = 8;
    private static final int MAX_ALARMS = 24;

    /** 算出未来几天内需要提醒的课（已按时间排序） */
    public static List<Item> upcoming(Context ctx, Settings s, List<Course> courses) {
        List<Item> out = new ArrayList<Item>();
        if (s == null || courses == null || courses.isEmpty()) return out;

        Calendar now = Calendar.getInstance();
        long nowMs = now.getTimeInMillis();
        long weekStart = s.weekStartMillis();

        for (int offset = 0; offset < LOOKAHEAD_DAYS && out.size() < MAX_ALARMS; offset++) {
            Calendar day = Calendar.getInstance();
            day.setTimeInMillis(nowMs);
            day.add(Calendar.DAY_OF_MONTH, offset);
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);

            long diff = day.getTimeInMillis() - weekStart;
            int dayIndex = (int) Math.floor(diff / 86400000.0);
            if (dayIndex < 0) continue;
            int week = dayIndex / 7 + 1;
            int dow = dayIndex % 7 + 1;          // 1=周一

            for (Course c : courses) {
                if (c.isOther() || c.day != dow || c.startPeriod <= 0) continue;
                if (!c.weeks.contains(week)) continue;
                Settings.Period p = s.period(c.startPeriod);
                if (p == null) continue;

                Calendar start = (Calendar) day.clone();
                start.set(Calendar.HOUR_OF_DAY, p.start / 60);
                start.set(Calendar.MINUTE, p.start % 60);
                long classAt = start.getTimeInMillis();
                long triggerAt = classAt - s.remindMin * 60_000L;
                if (triggerAt < nowMs + 5_000L) continue;     // 已经过了（或马上过）

                Item it = new Item();
                it.triggerAt = triggerAt;
                it.classAt = classAt;
                it.course = c.name;
                it.startPeriod = c.startPeriod;
                it.endPeriod = Math.max(c.endPeriod, c.startPeriod);
                it.timeText = Settings.fmt(p.start) + "~" + Settings.fmt(p.end);

                // 下课时刻：按末节的结束时间算（跨休息段也没关系，差值就是总时长）
                Settings.Period pe = s.period(it.endPeriod);
                if (pe != null && pe.end > p.start) {
                    it.classEndAt = classAt + (pe.end - p.start) * 60_000L;
                } else {
                    it.classEndAt = classAt + 45L * 60_000L;
                }
                out.add(it);
            }
        }

        // 按时间排序（简单插入排序，量很小）
        for (int i = 1; i < out.size(); i++) {
            Item key = out.get(i);
            int j = i - 1;
            while (j >= 0 && out.get(j).triggerAt > key.triggerAt) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, key);
        }
        return out;
    }

    /** 重新安排全部提醒（关闭提醒时清空） */
    public static void reschedule(Context ctx) {
        Settings s = Settings.load(ctx);
        List<Course> courses = courses(ctx);
        cancelAll(ctx);
        if (!s.remind || courses.isEmpty()) return;

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { exact = am.canScheduleExactAlarms(); } catch (Throwable ignore) {}
        }
        List<Item> items = upcoming(ctx, s, courses);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            PendingIntent pi = pending(ctx, it, i);
            if (pi == null) continue;
            try {
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.triggerAt, pi);
                } else {
                    // 没有精确闹钟权限时退化成窗口闹钟，最多晚几分钟
                    am.setWindow(AlarmManager.RTC_WAKEUP, it.triggerAt, 60_000L, pi);
                }
            } catch (Throwable ignore) {}
        }
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < MAX_ALARMS; i++) {
            Intent in = new Intent(ctx, ReminderReceiver.class).setAction(ReminderReceiver.ACTION_REMIND);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1000 + i, in,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
    }

    private static PendingIntent pending(Context ctx, Item it, int index) {
        Intent in = new Intent(ctx, ReminderReceiver.class)
                .setAction(ReminderReceiver.ACTION_REMIND)
                .putExtra("course", it.course)
                .putExtra("time", it.timeText)
                .putExtra("period", it.startPeriod)
                .putExtra("classAt", it.classAt)
                .putExtra("classEnd", it.classEndAt);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, 1000 + index, in, flags);
    }

    /** 读取已保存的课表 */
    public static List<Course> courses(Context ctx) {
        Store.Data d = Store.load(ctx);
        return d == null ? new ArrayList<Course>() : d.courses;
    }
}
