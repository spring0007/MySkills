package com.example.myskills.data;

/**
 * 新华字典中的一条汉字记录，与 xinhuazidian.db 的 xhzd_surnfu 表字段一一对应。
 */
public class HanziEntry {

    /** 主键，字典内部编号 */
    private long id;
    /** 汉字本身，全表唯一 */
    private String zi;
    /** 无调号拼音，如 zhong，可含多音，以逗号分隔 */
    private String py;
    /** 五笔编码，大量生僻字为空 */
    private String wubi;
    /** 部首，部分生僻字为「难检字」 */
    private String bushou;
    /** 笔画数 */
    private int bihua;
    /** 带调号拼音，如 zhòng,zhōng */
    private String pinyin;
    /** 笔顺编号，如 3121534，即每一笔的笔画类型代号，约 57 条源数据缺失 */
    private String bishun;
    /** 简要释义（已是纯文本，换行代替了原来的 <br>） */
    private String jijie;
    /** 详细解释，约六成记录为空 */
    private String xiangjie;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getZi() {
        return zi;
    }

    public void setZi(String zi) {
        this.zi = zi;
    }

    public String getPy() {
        return py;
    }

    public void setPy(String py) {
        this.py = py;
    }

    public String getWubi() {
        return wubi;
    }

    public void setWubi(String wubi) {
        this.wubi = wubi;
    }

    public String getBushou() {
        return bushou;
    }

    public void setBushou(String bushou) {
        this.bushou = bushou;
    }

    public int getBihua() {
        return bihua;
    }

    public void setBihua(int bihua) {
        this.bihua = bihua;
    }

    public String getPinyin() {
        return pinyin;
    }

    public void setPinyin(String pinyin) {
        this.pinyin = pinyin;
    }

    public String getBishun() {
        return bishun;
    }

    public void setBishun(String bishun) {
        this.bishun = bishun;
    }

    public String getJijie() {
        return jijie;
    }

    public void setJijie(String jijie) {
        this.jijie = jijie;
    }

    public String getXiangjie() {
        return xiangjie;
    }

    public void setXiangjie(String xiangjie) {
        this.xiangjie = xiangjie;
    }

    /**
     * 拼装成信息面板直接显示的纯文本。
     *
     * jijie 正文里本来就重复内嵌了拼音、笔画数、部首、笔顺编号，
     * 所以这里先用结构化列给出关键信息，再在下面附上完整释义原文。
     */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        sb.append(zi);
        if (hasText(pinyin)) {
            sb.append("    ").append(pinyin);
        } else if (hasText(py)) {
            sb.append("    ").append(py);
        }

        StringBuilder meta = new StringBuilder();
        appendLine(meta, "部首：" + bushou);
        appendLine(meta, bihua > 0 ? "笔画：" + bihua : null);
        appendLine(meta, hasText(wubi) ? "五笔：" + wubi : null);
        appendLine(meta, hasText(bishun) ? "笔顺：" + bishun : null);
        if (meta.length() > 0) {
            sb.append('\n').append(meta);
        }

        if (hasText(jijie)) {
            sb.append("\n\n释义\n").append(jijie);
        }
        if (hasText(xiangjie) && !xiangjie.equals(jijie)) {
            sb.append("\n\n详解\n").append(xiangjie);
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (!hasText(line)) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(line);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "HanziEntry{" + zi + ", " + pinyin + ", " + bushou + ", " + bihua + "}";
    }
}
