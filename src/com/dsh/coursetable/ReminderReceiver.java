package com.dsh.coursetable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * 上课提醒的接收器：到点弹提醒，并自动排下一批。
 *
 * 显示：应用内自定义布局是「左边上课时间 / 右边课程名」，单击直接进入课表。
 *
 * 灵动岛 / 实况通知：Android 16（API 36）起这类能力有了公开 API（实时更新，
 * {@code Notification.ProgressStyle}），实现在 {@link LiveUpdate}。
 * 系统版本低于 16、用户关掉了实时更新开关、或机型未适配时，系统就按普通通知横幅显示，
 * 应用侧不需要分情况处理；以前的"往 extras 里塞各家厂商私有字段"的做法已经删掉
 * （那些字段系统并不认，见交接文档 SESSION-7.12 第 5.1 节）。
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_REMIND = "com.dsh.coursetable.REMIND";
    private static final String CHANNEL = "class_reminder";
    private static final int NOTIFY_ID = 3001;
    /** 拿不到下课时间时的兜底时长 */
    private static final long DEFAULT_CLASS_MS = 45L * 60_000L;
    /** 提醒通知沿用原来的主题蓝（与 Settings 无关，只作时间轴配色） */
    private static final int ACCENT = 0xFF66CCFF;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Reminder.reschedule(ctx);      // 开机/更新后重新排
            return;
        }
        if (!ACTION_REMIND.equals(action)) return;

        String course = intent.getStringExtra("course");
        String time = intent.getStringExtra("time");
        int period = intent.getIntExtra("period", 0);
        long classAt = intent.getLongExtra("classAt", 0L);
        long classEnd = intent.getLongExtra("classEnd", 0L);

        if (course == null) course = "上课提醒";
        if (time == null) time = "";
        long now = System.currentTimeMillis();
        if (classAt <= 0L) classAt = now;
        if (classEnd <= classAt) classEnd = classAt + DEFAULT_CLASS_MS;

        notify(ctx, course, time, period, classAt, classEnd);
        Reminder.reschedule(ctx);          // 排下一批（提醒一般提前 10 分钟，来得及）
    }

    private void notify(Context ctx, String course, String time, int period,
                        long classAt, long classEnd) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "上课提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("上课前提醒；Android 16 及以上会显示为实时更新");
            ch.enableVibration(true);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }

        String hint = period > 0 ? ("第 " + period + " 节 · 马上上课") : "马上上课";

        // 应用内自定义布局：左时间 / 右课程名
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.notify_reminder);
        rv.setTextViewText(R.id.nt_time, shortTime(time));
        rv.setTextViewText(R.id.nt_course, course);
        rv.setTextViewText(R.id.nt_hint, hint);
        b.setCustomContentView(rv)
                .setCustomBigContentView(rv)
                .setSmallIcon(R.drawable.ic_clock)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(ACCENT)
                // 系统模板（实时更新卡片、锁屏、读屏）用的文字；自定义布局下不显示
                .setContentTitle(course)
                .setContentText(time.length() > 0 ? (time + " · " + hint) : hint);

        // 单击进入课表
        Intent open = new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        b.setContentIntent(PendingIntent.getActivity(ctx, 2001, open, flags));

        // Android 16 的「实时更新」（状态栏胶囊 / 锁屏卡片 + 倒计时到上课 + 下课后自动收走）。
        // 设备不支持时这一步什么都不做，照常按普通通知横幅显示。
        LiveUpdate.apply(b, shortTime(time), ACCENT, classAt, classEnd);

        try {
            nm.notify(NOTIFY_ID, b.build());
        } catch (Throwable ignore) {}
    }

    private String shortTime(String range) {
        if (range == null) return "";
        int i = range.indexOf('~');
        return i > 0 ? range.substring(0, i) : range;
    }
}
