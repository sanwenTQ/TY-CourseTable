package com.dsh.coursetable;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 读取 Excel 风格的课表：支持
 *  1) .xlsx（zip + xml）
 *  2) .xls（OLE2 复合文档 + BIFF5/8）
 *  3) 伪装的 xls（很多教务系统导出的是 HTML 表格，只是后缀叫 .xls）
 * 输出统一的二维字符串网格。
 */
public class Xls {

    public static class Grid {
        public final List<List<String>> rows = new ArrayList<List<String>>();

        public String at(int r, int c) {
            if (r < 0 || r >= rows.size()) return "";
            List<String> row = rows.get(r);
            if (c < 0 || c >= row.size()) return "";
            String s = row.get(c);
            return s == null ? "" : s.trim();
        }

        public int rowCount() { return rows.size(); }

        public int colCount() {
            int n = 0;
            for (List<String> r : rows) n = Math.max(n, r.size());
            return n;
        }

        public void put(int r, int c, String v) {
            while (rows.size() <= r) rows.add(new ArrayList<String>());
            List<String> row = rows.get(r);
            while (row.size() <= c) row.add("");
            if (v == null) v = "";
            if (row.get(c) == null || row.get(c).length() == 0) row.set(c, v);
            else if (!row.get(c).contains(v)) row.set(c, row.get(c) + " " + v);
        }

        public String dump() {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < rows.size() && r < 40; r++) {
                sb.append(r).append(": ");
                List<String> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String v = row.get(c);
                    if (v != null && v.length() > 0) sb.append('[').append(c).append(']').append(v).append(' ');
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 入口

    public static Grid read(File f) throws IOException {
        byte[] head = head(f, 8);
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K') {
            return readXlsx(f);
        }
        if (head.length >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0) {
            return readXls(f);
        }
        // 其它一律按文本/HTML 试
        byte[] all = readAll(f);
        String text = decode(all);
        String low = text.toLowerCase();
        if (low.contains("<table") || low.contains("<html") || low.contains("<tr")) {
            return readHtml(text);
        }
        throw new IOException("不是可识别的 Excel/PDF 表格文件（也没有 HTML 表格）");
    }

    private static byte[] head(File f, int n) {
        try {
            InputStream in = new FileInputStream(f);
            byte[] b = new byte[n];
            int r = in.read(b);
            in.close();
            if (r <= 0) return new byte[0];
            if (r < n) {
                byte[] t = new byte[r];
                System.arraycopy(b, 0, t, 0, r);
                return t;
            }
            return b;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] readAll(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static String decode(byte[] b) {
        try {
            String s = new String(b, "UTF-8");
            if (s.indexOf('\uFFFD') < 0) return s;
        } catch (Exception ignore) {}
        try {
            return new String(b, "GBK");
        } catch (Exception e) {
            return new String(b);
        }
    }

    // ---------------------------------------------------------------- xlsx

    private static Grid readXlsx(File f) throws IOException {
        Grid g = new Grid();
        ZipFile zip = new ZipFile(f);
        try {
            List<String> shared = new ArrayList<String>();
            ZipEntry ss = zip.getEntry("xl/sharedStrings.xml");
            if (ss != null) {
                InputStream in = zip.getInputStream(ss);
                parseSharedStrings(in, shared);
                in.close();
            }
            ZipEntry sheet = null;
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String nm = e.getName();
                if (nm.startsWith("xl/worksheets/sheet") && nm.endsWith(".xml")) {
                    if (sheet == null || nm.compareTo(sheet.getName()) < 0) sheet = e;
                }
            }
            if (sheet == null) throw new IOException("xlsx 里没有工作表");
            InputStream in = zip.getInputStream(sheet);
            parseSheet(in, shared, g);
            in.close();
        } finally {
            try { zip.close(); } catch (Exception ignore) {}
        }
        return g;
    }

    private static void parseSharedStrings(InputStream in, final List<String> out) {
        try {
            SAXParser p = SAXParserFactory.newInstance().newSAXParser();
            p.parse(new InputSource(in), new DefaultHandler() {
                StringBuilder cur;
                boolean inT;
                private String nm(String local, String q) {
                    return (local != null && local.length() > 0) ? local : q;
                }
                @Override public void startElement(String uri, String local, String q, Attributes a) {
                    String n = nm(local, q);
                    if ("si".equals(n)) cur = new StringBuilder();
                    else if ("t".equals(n)) inT = true;
                }
                @Override public void characters(char[] ch, int st, int len) {
                    if (inT && cur != null) cur.append(ch, st, len);
                }
                @Override public void endElement(String uri, String local, String q) {
                    String n = nm(local, q);
                    if ("t".equals(n)) inT = false;
                    else if ("si".equals(n) && cur != null) {
                        out.add(cur.toString());
                        cur = null;
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void parseSheet(InputStream in, final List<String> shared, final Grid g) {
        try {
            SAXParser p = SAXParserFactory.newInstance().newSAXParser();
            p.parse(new InputSource(in), new DefaultHandler() {
                int row = -1, col = -1;
                String type;
                StringBuilder val;
                boolean inV, inT;
                private String nm(String local, String q) {
                    return (local != null && local.length() > 0) ? local : q;
                }
                @Override public void startElement(String uri, String local, String q, Attributes a) {
                    String n = nm(local, q);
                    if ("row".equals(n)) {
                        String r = a.getValue("r");
                        row = (r == null) ? row + 1 : parseInt(r) - 1;
                    } else if ("c".equals(n)) {
                        String ref = a.getValue("r");
                        col = (ref == null) ? col + 1 : colOf(ref);
                        type = a.getValue("t");
                        val = new StringBuilder();
                    } else if ("v".equals(n)) inV = true;
                    else if ("t".equals(n)) inT = true;
                }
                @Override public void characters(char[] ch, int st, int len) {
                    if ((inV || inT) && val != null) val.append(ch, st, len);
                }
                @Override public void endElement(String uri, String local, String q) {
                    String n = nm(local, q);
                    if ("v".equals(n)) inV = false;
                    else if ("t".equals(n)) inT = false;
                    else if ("c".equals(n) && val != null) {
                        String text = val.toString();
                        if ("s".equals(type)) {
                            int idx = parseInt(text);
                            if (idx >= 0 && idx < shared.size()) text = shared.get(idx);
                        }
                        if (row >= 0 && col >= 0) g.put(row, col, text);
                        val = null;
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int colOf(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
            else break;
        }
        return c - 1;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    // ---------------------------------------------------------------- HTML

    private static Grid readHtml(String html) {
        Grid g = new Grid();
        int row = -1;
        int i = 0;
        String low = html.toLowerCase();
        while (true) {
            int tr = low.indexOf("<tr", i);
            if (tr < 0) break;
            int trEnd = low.indexOf('>', tr);
            if (trEnd < 0) break;
            int trClose = low.indexOf("</tr", trEnd);
            if (trClose < 0) trClose = html.length();
            row++;
            String block = html.substring(trEnd + 1, trClose);
            String blockLow = block.toLowerCase();
            int col = -1;
            int j = 0;
            while (true) {
                int td = blockLow.indexOf("<td", j);
                int th = blockLow.indexOf("<th", j);
                int start = (td < 0) ? th : (th < 0 ? td : Math.min(td, th));
                if (start < 0) break;
                int open = blockLow.indexOf('>', start);
                if (open < 0) break;
                int close = blockLow.indexOf("</t", open);
                if (close < 0) close = block.length();
                col++;
                String cell = block.substring(open + 1, close);
                cell = cell.replaceAll("<[^>]+>", " ");
                cell = cell.replace("&nbsp;", " ").replace("&amp;", "&")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
                cell = cell.replaceAll("\\s+", " ").trim();
                if (cell.length() > 0) g.put(row, col, cell);
                j = close + 2;
            }
            i = trClose + 3;
        }
        return g;
    }

    // ---------------------------------------------------------------- xls (OLE2 + BIFF)

    private static Grid readXls(File f) throws IOException {
        byte[] d = readAll(f);
        int secShift = u16(d, 0x1E);
        int miniShift = u16(d, 0x20);
        int secSize = 1 << secShift;
        int miniSize = 1 << miniShift;
        int numFat = i32(d, 0x2C);
        int dirStart = i32(d, 0x30);
        int miniCutoff = i32(d, 0x38);
        int miniFatStart = i32(d, 0x3C);
        int numMiniFat = i32(d, 0x40);
        int difatStart = i32(d, 0x44);
        int numDifat = i32(d, 0x48);

        // DIFAT → FAT 扇区列表
        List<Integer> fatSectors = new ArrayList<Integer>();
        for (int i = 0; i < 109 && fatSectors.size() < numFat; i++) {
            int v = i32(d, 0x4C + i * 4);
            if (v >= 0) fatSectors.add(v);
        }
        int dif = difatStart;
        for (int k = 0; k < numDifat && dif >= 0; k++) {
            int base = secOff(d, dif, secSize);
            int per = secSize / 4;
            for (int i = 0; i < per - 1 && fatSectors.size() < numFat; i++) {
                int v = i32(d, base + i * 4);
                if (v >= 0) fatSectors.add(v);
            }
            dif = i32(d, base + (per - 1) * 4);
        }
        // FAT
        int fatLen = fatSectors.size() * (secSize / 4);
        int[] fat = new int[fatLen];
        int p = 0;
        for (int s : fatSectors) {
            int base = secOff(d, s, secSize);
            for (int i = 0; i < secSize / 4 && p < fatLen; i++) fat[p++] = i32(d, base + i * 4);
        }
        // MiniFAT
        int[] miniFat = new int[Math.max(0, numMiniFat) * (secSize / 4)];
        int mp = 0;
        int ms = miniFatStart;
        for (int k = 0; k < numMiniFat && ms >= 0 && mp < miniFat.length; k++) {
            int base = secOff(d, ms, secSize);
            for (int i = 0; i < secSize / 4 && mp < miniFat.length; i++) miniFat[mp++] = i32(d, base + i * 4);
            ms = (ms < fat.length) ? fat[ms] : -2;
        }

        // 目录
        List<String> names = new ArrayList<String>();
        List<Integer> starts = new ArrayList<Integer>();
        List<Integer> sizes = new ArrayList<Integer>();
        int dir = dirStart;
        while (dir >= 0) {
            int base = secOff(d, dir, secSize);
            for (int i = 0; i + 128 <= secSize; i += 128) {
                int off = base + i;
                int nameLen = u16(d, off + 0x40);
                StringBuilder nm = new StringBuilder();
                for (int c = 0; c + 1 < nameLen && c < 64; c += 2) {
                    char ch = (char) u16(d, off + c);
                    if (ch == 0) break;
                    nm.append(ch);
                }
                int type = d[off + 0x42] & 0xFF;
                int start = i32(d, off + 0x74);
                int size = i32(d, off + 0x78);
                if (type == 2 || type == 5) {
                    names.add(nm.toString());
                    starts.add(start);
                    sizes.add(size);
                }
            }
            dir = (dir < fat.length) ? fat[dir] : -2;
        }

        // 找 Workbook / Book 流 与 root（mini stream）
        int rootStart = -1, rootSize = 0;
        for (int i = 0; i < names.size(); i++) {
            if ("Root Entry".equalsIgnoreCase(names.get(i))) {
                rootStart = starts.get(i);
                rootSize = sizes.get(i);
            }
        }
        byte[] miniStream = null;
        for (int i = 0; i < names.size(); i++) {
            String nm = names.get(i).toLowerCase();
            if (nm.equals("workbook") || nm.equals("book")) {
                int start = starts.get(i);
                int size = sizes.get(i);
                if (size < miniCutoff) {
                    if (miniStream == null) miniStream = readChain(d, fat, rootStart, rootSize, secSize, false, null, 0);
                    return readBiff(readChain(d, null, start, size, miniSize, true, miniFat, 0), miniStream);
                }
                byte[] stream = readChain(d, fat, start, size, secSize, false, null, 0);
                return readBiff(stream, null);
            }
        }
        throw new IOException("这个 .xls 里没找到 Workbook 流");
    }

    private static int u16(byte[] d, int off) {
        if (off + 2 > d.length) return 0;
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] d, int off) {
        if (off + 4 > d.length) return -1;
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8) | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static int secOff(byte[] d, int sec, int secSize) { return 512 + sec * secSize; }

    /** 顺着链表把流读出来（普通扇区或 mini 扇区） */
    private static byte[] readChain(byte[] d, int[] fat, int start, int size, int secSize,
                                    boolean mini, int[] miniFat, int miniStreamStart) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int cur = start;
        int guard = 0;
        while (cur >= 0 && guard++ < 100000) {
            int base = mini ? (miniStreamStart + cur * secSize) : secOff(d, cur, secSize);
            int len = Math.min(secSize, d.length - base);
            if (len <= 0) break;
            bos.write(d, base, len);
            cur = mini ? ((cur < miniFat.length) ? miniFat[cur] : -2) : ((cur < fat.length) ? fat[cur] : -2);
        }
        byte[] all = bos.toByteArray();
        if (size > 0 && size < all.length) {
            byte[] t = new byte[size];
            System.arraycopy(all, 0, t, 0, size);
            return t;
        }
        return all;
    }

    // BIFF 记录解析
    private static Grid readBiff(byte[] s, byte[] miniStreamUnused) {
        Grid g = new Grid();
        if (s == null || s.length < 8) return g;
        List<String> sst = new ArrayList<String>();
        Map<Integer, String> strings = new HashMap<Integer, String>();
        int pos = 0;
        int sheetSeen = 0;
        boolean inFirstSheet = false;
        boolean sawCells = false;
        String pendingString = null;
        int pendingRow = -1, pendingCol = -1;
        while (pos + 4 <= s.length) {
            int type = u16(s, pos);
            int len = u16(s, pos + 2);
            int dataOff = pos + 4;
            if (dataOff + len > s.length) break;
            if (type == 0x0809) {           // BOF
                int dt = u16(s, dataOff + 2);
                if (dt == 0x0010) {          // worksheet
                    sheetSeen++;
                    if (sheetSeen == 1) inFirstSheet = true;
                    else if (sawCells) break;   // 只取第一个有内容的工作表
                }
            } else if (type == 0x0085) {     // BOUNDSHEET
                // 忽略
            } else if (type == 0x00FC) {     // SST
                parseSst(s, pos, len, sst);
            } else if (type == 0x00FD) {     // LABELSST
                int row = u16(s, dataOff);
                int col = u16(s, dataOff + 2);
                int idx = i32(s, dataOff + 6);
                if (idx >= 0 && idx < sst.size()) g.put(row, col, sst.get(idx));
                sawCells = true;
            } else if (type == 0x0204) {     // LABEL
                int row = u16(s, dataOff);
                int col = u16(s, dataOff + 2);
                int[] cpos = new int[]{dataOff + 6};
                String v = readUnicodeString(s, cpos, len - 6);
                g.put(row, col, v);
                sawCells = true;
            } else if (type == 0x027E) {     // RK
                int row = u16(s, dataOff);
                int col = u16(s, dataOff + 2);
                g.put(row, col, fmtNum(rk(i32(s, dataOff + 6))));
                sawCells = true;
            } else if (type == 0x00BD) {     // MULRK
                int row = u16(s, dataOff);
                int c1 = u16(s, dataOff + 2);
                int cnt = (len - 6) / 6;
                for (int i = 0; i < cnt; i++) {
                    int rkOff = dataOff + 4 + i * 6 + 2;
                    g.put(row, c1 + i, fmtNum(rk(i32(s, rkOff))));
                }
                sawCells = true;
            } else if (type == 0x0203) {     // NUMBER
                int row = u16(s, dataOff);
                int col = u16(s, dataOff + 2);
                long bits = 0;
                for (int i = 7; i >= 0; i--) bits = (bits << 8) | (s[dataOff + 6 + i] & 0xFFL);
                g.put(row, col, fmtNum(Double.longBitsToDouble(bits)));
                sawCells = true;
            } else if (type == 0x0006) {     // FORMULA（字符串结果在后面的 STRING 记录里）
                pendingRow = u16(s, dataOff);
                pendingCol = u16(s, dataOff + 2);
                pendingString = "";
            } else if (type == 0x0207) {     // STRING
                int[] cpos = new int[]{dataOff};
                String v = readUnicodeString(s, cpos, len);
                if (pendingRow >= 0) {
                    g.put(pendingRow, pendingCol, v);
                    sawCells = true;
                }
                pendingRow = -1;
            }
            pos = dataOff + len;
            if (!inFirstSheet && sawCells) break;
        }
        return g;
    }

    /** SST 可能被 CONTINUE 切开，这里按段处理，段首重新读压缩标志 */
    private static void parseSst(byte[] s, int recPos, int recLen, List<String> out) {
        List<int[]> chunks = new ArrayList<int[]>();   // {offset, length}
        chunks.add(new int[]{recPos + 4 + 8, recLen - 8});
        int pos = recPos + 4 + recLen;
        while (pos + 4 <= s.length && u16(s, pos) == 0x003C) {
            int len = u16(s, pos + 2);
            chunks.add(new int[]{pos + 4, len});
            pos = pos + 4 + len;
        }
        // 把各段拼起来，但记录每段的边界（跨段时压缩标志会重置，这里按“每段独立解析”处理）
        int total = 0;
        for (int[] c : chunks) total += c[1];
        byte[] buf = new byte[total];
        int[] bounds = new int[chunks.size() + 1];
        int p = 0, bi = 0;
        for (int[] c : chunks) {
            bounds[bi++] = p;
            System.arraycopy(s, c[0], buf, p, Math.max(0, Math.min(c[1], s.length - c[0])));
            p += c[1];
        }
        bounds[bi] = p;

        int idx = 0;
        int seg = 0;
        while (idx + 3 <= buf.length && out.size() < 200000) {
            while (seg + 1 < bounds.length && idx >= bounds[seg + 1]) seg++;
            int cch = (buf[idx] & 0xFF) | ((buf[idx + 1] & 0xFF) << 8);
            int flags = buf[idx + 2] & 0xFF;
            idx += 3;
            boolean wide = (flags & 0x01) != 0;
            boolean rich = (flags & 0x08) != 0;
            boolean farEast = (flags & 0x04) != 0;
            int richRuns = 0, extLen = 0;
            if (rich) {
                if (idx + 2 > buf.length) break;
                richRuns = (buf[idx] & 0xFF) | ((buf[idx + 1] & 0xFF) << 8);
                idx += 2;
            }
            if (farEast) {
                if (idx + 4 > buf.length) break;
                extLen = i32(buf, idx);
                idx += 4;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cch; i++) {
                // 跨段时压缩标志会在段首重来
                while (seg + 1 < bounds.length && idx >= bounds[seg + 1] && idx < bounds[seg + 1] + 1) {
                    seg++;
                    if (idx < buf.length) {
                        flags = buf[idx] & 0xFF;
                        wide = (flags & 0x01) != 0;
                        idx++;
                    }
                }
                if (wide) {
                    if (idx + 2 > buf.length) break;
                    sb.append((char) ((buf[idx] & 0xFF) | ((buf[idx + 1] & 0xFF) << 8)));
                    idx += 2;
                } else {
                    if (idx + 1 > buf.length) break;
                    sb.append((char) (buf[idx] & 0xFF));
                    idx += 1;
                }
            }
            idx += richRuns * 4;
            idx += extLen;
            out.add(sb.toString());
        }
    }

    /** BIFF8 Unicode 字符串（用于 LABEL / STRING 记录） */
    private static String readUnicodeString(byte[] s, int[] pos, int maxLen) {
        int p = pos[0];
        if (p + 3 > s.length) return "";
        int cch = u16(s, p);
        int flags = s[p + 2] & 0xFF;
        p += 3;
        boolean wide = (flags & 0x01) != 0;
        boolean rich = (flags & 0x08) != 0;
        int runs = 0;
        if (rich) { runs = u16(s, p); p += 2; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cch; i++) {
            if (wide) {
                if (p + 2 > s.length) break;
                sb.append((char) ((s[p] & 0xFF) | ((s[p + 1] & 0xFF) << 8)));
                p += 2;
            } else {
                if (p + 1 > s.length) break;
                sb.append((char) (s[p] & 0xFF));
                p += 1;
            }
        }
        p += runs * 4;
        pos[0] = p;
        return sb.toString();
    }

    private static double rk(int rk) {
        boolean isInt = (rk & 0x02) != 0;
        boolean div100 = (rk & 0x01) != 0;
        double v;
        if (isInt) {
            v = (double) (rk >> 2);
        } else {
            long bits = ((long) (rk & 0xFFFFFFFC)) << 32;
            v = Double.longBitsToDouble(bits);
        }
        if (div100) v /= 100.0;
        return v;
    }

    private static String fmtNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) v);
        String s = String.valueOf(v);
        return s;
    }
}
