package com.example.myskills.ui.view;

import android.graphics.Path;

/**
 * 极简 SVG path 解析：把 hanzi-writer 的笔画轮廓转成 android.graphics.Path。
 *
 * 只解析到数据坐标系（1024x1024、y 轴向上），到屏幕坐标的变换由调用方统一做一次 Matrix。
 * 数据实测只用到 M/L/Q/C/Z，其余命令为容错保留。
 */
final class SvgPathParser {

    private SvgPathParser() {
    }

    /** 把 path 描述解析进 out（追加，不清空 out） */
    static void parse(String d, Path out) {
        if (d == null || out == null) return;
        new SvgPathParser().doParse(d, out);
    }

    private String src;
    private int pos;

    private void doParse(String d, Path out) {
        src = d;
        pos = 0;

        float curX = 0, curY = 0;        // 当前点
        float startX = 0, startY = 0;    // 子路径起点
        float lastCx = 0, lastCy = 0;    // 上一段三次贝塞尔的控制点（S 用）
        float lastQx = 0, lastQy = 0;    // 上一段二次贝塞尔的控制点（T 用）
        char lastCmd = 0;
        char cmd = 0;

        while (true) {
            skipSeparators();
            if (pos >= src.length()) break;
            char c = src.charAt(pos);
            if (isCommand(c)) {
                cmd = c;
                pos++;
            } else if (cmd == 0) {
                break;  // 数字出现在任何命令之前，数据非法
            }

            // 一条命令可以跟多组参数，例如 "C x1 y1 x2 y2 x y x1 y1 x2 y2 x y"
            do {
                int need = argc(cmd);
                if (need < 0) return;   // 未知命令，参数个数无法推断，放弃剩余数据
                if (need == 0) {
                    // Z / z：闭合子路径，没有参数也不会重复
                    out.close();
                    curX = startX;
                    curY = startY;
                    lastCmd = cmd;
                    break;
                }
                float[] a = readArgs(need);
                if (a == null) return;  // 参数不足，数据被截断

                switch (cmd) {
                    case 'M':
                        curX = a[0];
                        curY = a[1];
                        out.moveTo(curX, curY);
                        startX = curX;
                        startY = curY;
                        break;
                    case 'm':
                        curX += a[0];
                        curY += a[1];
                        out.moveTo(curX, curY);
                        startX = curX;
                        startY = curY;
                        break;
                    case 'L':
                        curX = a[0];
                        curY = a[1];
                        out.lineTo(curX, curY);
                        break;
                    case 'l':
                        curX += a[0];
                        curY += a[1];
                        out.lineTo(curX, curY);
                        break;
                    case 'H':
                        curX = a[0];
                        out.lineTo(curX, curY);
                        break;
                    case 'h':
                        curX += a[0];
                        out.lineTo(curX, curY);
                        break;
                    case 'V':
                        curY = a[0];
                        out.lineTo(curX, curY);
                        break;
                    case 'v':
                        curY += a[0];
                        out.lineTo(curX, curY);
                        break;
                    case 'C':
                        out.cubicTo(a[0], a[1], a[2], a[3], a[4], a[5]);
                        lastCx = a[2];
                        lastCy = a[3];
                        curX = a[4];
                        curY = a[5];
                        break;
                    case 'c': {
                        float x1 = curX + a[0], y1 = curY + a[1];
                        float x2 = curX + a[2], y2 = curY + a[3];
                        float x = curX + a[4], y = curY + a[5];
                        out.cubicTo(x1, y1, x2, y2, x, y);
                        lastCx = x2;
                        lastCy = y2;
                        curX = x;
                        curY = y;
                        break;
                    }
                    case 'S': {
                        // 上一段是三次贝塞尔时，第一控制点取上一点关于当前点的对称点
                        float x1 = curX, y1 = curY;
                        if (isCubic(lastCmd)) {
                            x1 = 2 * curX - lastCx;
                            y1 = 2 * curY - lastCy;
                        }
                        out.cubicTo(x1, y1, a[0], a[1], a[2], a[3]);
                        lastCx = a[0];
                        lastCy = a[1];
                        curX = a[2];
                        curY = a[3];
                        break;
                    }
                    case 's': {
                        float x1 = curX, y1 = curY;
                        if (isCubic(lastCmd)) {
                            x1 = 2 * curX - lastCx;
                            y1 = 2 * curY - lastCy;
                        }
                        float x2 = curX + a[0], y2 = curY + a[1];
                        float x = curX + a[2], y = curY + a[3];
                        out.cubicTo(x1, y1, x2, y2, x, y);
                        lastCx = x2;
                        lastCy = y2;
                        curX = x;
                        curY = y;
                        break;
                    }
                    case 'Q':
                        out.quadTo(a[0], a[1], a[2], a[3]);
                        lastQx = a[0];
                        lastQy = a[1];
                        curX = a[2];
                        curY = a[3];
                        break;
                    case 'q': {
                        float qx = curX + a[0], qy = curY + a[1];
                        float x = curX + a[2], y = curY + a[3];
                        out.quadTo(qx, qy, x, y);
                        lastQx = qx;
                        lastQy = qy;
                        curX = x;
                        curY = y;
                        break;
                    }
                    case 'T': {
                        float qx = curX, qy = curY;
                        if (isQuad(lastCmd)) {
                            qx = 2 * curX - lastQx;
                            qy = 2 * curY - lastQy;
                        }
                        out.quadTo(qx, qy, a[0], a[1]);
                        lastQx = qx;
                        lastQy = qy;
                        curX = a[0];
                        curY = a[1];
                        break;
                    }
                    case 't': {
                        float qx = curX, qy = curY;
                        if (isQuad(lastCmd)) {
                            qx = 2 * curX - lastQx;
                            qy = 2 * curY - lastQy;
                        }
                        float x = curX + a[0], y = curY + a[1];
                        out.quadTo(qx, qy, x, y);
                        lastQx = qx;
                        lastQy = qy;
                        curX = x;
                        curY = y;
                        break;
                    }
                    default:
                        return;
                }

                lastCmd = cmd;
                // M/m 之后紧跟的坐标组按 L/l 处理（SVG 规范）
                if (cmd == 'M') {
                    cmd = 'L';
                } else if (cmd == 'm') {
                    cmd = 'l';
                }
            } while (hasNumber());
        }
    }

    /** 命令的参数个数；未知命令返回 -1，Z/z 返回 0 */
    private static int argc(char cmd) {
        switch (cmd) {
            case 'Z':
            case 'z':
                return 0;
            case 'H':
            case 'h':
            case 'V':
            case 'v':
                return 1;
            case 'M':
            case 'm':
            case 'L':
            case 'l':
            case 'T':
            case 't':
                return 2;
            case 'S':
            case 's':
            case 'Q':
            case 'q':
                return 4;
            case 'C':
            case 'c':
                return 6;
            default:
                return -1;
        }
    }

    /** 连续读 n 个数字；剩余数量不足返回 null */
    private float[] readArgs(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            if (!hasNumber()) return null;
            a[i] = readNumber();
        }
        return a;
    }

    private boolean hasNumber() {
        skipSeparators();
        if (pos >= src.length()) return false;
        char c = src.charAt(pos);
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
    }

    /**
     * 读一个数字。数据里存在无分隔的紧邻写法（如 "... 316 556Q315 556..."），
     * 所以不能按分隔符切分，只能逐字符扫描。
     */
    private float readNumber() {
        skipSeparators();
        int start = pos;
        if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) pos++;
        while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            int mark = pos;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) pos++;
            if (pos < src.length() && isDigit(src.charAt(pos))) {
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
            } else {
                pos = mark;  // 不是合法指数，回退
            }
        }
        if (pos == start) return 0f;
        try {
            return Float.parseFloat(src.substring(start, pos));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private void skipSeparators() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isCommand(char c) {
        return "MmLlHhVvCcSsQqTtZz".indexOf(c) >= 0;
    }

    private static boolean isCubic(char c) {
        return c == 'C' || c == 'c' || c == 'S' || c == 's';
    }

    private static boolean isQuad(char c) {
        return c == 'Q' || c == 'q' || c == 'T' || c == 't';
    }
}
