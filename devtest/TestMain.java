import com.dsh.coursetable.*;
import java.io.File;
import java.util.*;

public class TestMain {
    public static void main(String[] a) throws Exception {
        CourseParser.Result r = CourseParser.parse(new File(a[0]));
        System.out.println("学期: " + r.semester + "  最大周: " + r.maxWeek + "  课程条目: " + r.courses.size());
        List<Course> cs = new ArrayList<>(r.courses);
        Collections.sort(cs, new Comparator<Course>() {
            public int compare(Course x, Course y) {
                if (x.day != y.day) return x.day - y.day;
                if (x.startPeriod != y.startPeriod) return x.startPeriod - y.startPeriod;
                return x.name.compareTo(y.name);
            }
        });
        for (Course c : cs) {
            System.out.printf("%-4s %-10s %-16s 周次[%s] 教师[%s] 地点[%s] 校区[%s] 班[%s]%n",
                c.dayName(), c.periodText(), c.name, c.weeksSummary(), c.teacher, c.room, c.campus, c.className);
        }
    }
}
