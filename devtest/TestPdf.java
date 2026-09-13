import com.dsh.coursetable.*;
import java.io.File;
public class TestPdf { public static void main(String[] a) throws Exception {
    CourseParser.Result r = CourseParser.parse(new File(a[0]));
    System.out.println("PDF 回归: 学期=" + r.semester + " 最大周=" + r.maxWeek + " 课程数=" + r.courses.size());
    int n=0; for (Course c : r.courses) if (!c.name.isEmpty()) n++;
    System.out.println("有名字的课程: " + n);
}}
