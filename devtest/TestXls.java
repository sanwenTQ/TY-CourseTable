import com.dsh.coursetable.*;
import java.io.File;
import java.util.*;

public class TestXls {
    public static void main(String[] a) throws Exception {
        for (String f : a) {
            System.out.println("===== " + f + " =====");
            try {
                Xls.Grid g = Xls.read(new File(f));
                System.out.println("网格: " + g.rowCount() + " 行 x " + g.colCount() + " 列");
                CourseParser.Result r = ExcelParser.parse(g);
                System.out.println("学期: " + r.semester + "  最大周: " + r.maxWeek + "  课程数: " + r.courses.size());
                List<Course> cs = new ArrayList<>(r.courses);
                Collections.sort(cs, new Comparator<Course>() {
                    public int compare(Course x, Course y) {
                        if (x.day != y.day) return x.day - y.day;
                        return x.startPeriod - y.startPeriod;
                    }
                });
                for (Course c : cs)
                    System.out.printf("  %-4s %-10s %-18s 周[%s] 师[%s] 地[%s]%n",
                        c.dayName(), c.periodText(), c.name, c.weeksSummary(), c.teacher, c.room);
            } catch (Throwable t) {
                System.out.println("  解析失败: " + t);
            }
        }
    }
}
