# -*- coding: utf-8 -*-
"""
将 xinhuazidian.sql (MySQL dump) 转换为 Android 可直接读取的 SQLite 数据库。

用法:
    python tools/sql_to_sqlite.py [输入.sql] [输出.db]

默认:
    输入 tools/xinhuazidian/xinhuazidian.sql
    输出 app/src/main/assets/xinhuazidian/xinhuazidian.db

输出末尾会打印 行数 / 字节数 / sha256，其中字节数需要同步到
app/src/main/java/com/example/myskills/data/AssetDbInstaller.java 的 DB_SIZE_BYTES 常量。
"""
import hashlib
import html
import os
import re
import sqlite3
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SRC = os.path.join(ROOT, "tools", "xinhuazidian", "xinhuazidian.sql")
DEFAULT_DST = os.path.join(ROOT, "app", "src", "main", "assets", "xinhuazidian", "xinhuazidian.db")

# 字典库自身的 schema 版本：改表结构或重新生成数据时 +1，
# 并同步修改 AssetDbInstaller.DB_NAME 的文件名后缀，旧版本文件会被自动清理。
DB_VERSION = 1
# 源文件共 20823 条 INSERT，其中 1 条 zi 为「牙合」（源数据自带的占位符，
# 注释写明「简体中文没有收录这个字」），两个字无法作为单字查询主键，故跳过 -> 20822
EXPECTED_ROWS = 20822

TABLE = "xhzd_surnfu"
COLUMNS = ["id", "zi", "py", "wubi", "bushou", "bihua", "pinyin", "bishun", "jijie", "xiangjie"]

# INSERT INTO `xhzd_surnfu` (...) VALUES (...);
INSERT_RE = re.compile(r"^INSERT INTO\s+`?" + TABLE + r"`?\s*\([^)]*\)\s*VALUES\s*\((.*)\);\s*$",
                       re.IGNORECASE)

# 依次匹配: 单引号字符串 / NULL / 数字
#
# 注意字符串内部有两种转义写法，必须都支持：
#   1. SQL 标准的双写单引号 ''  —— 本 dump 主要用它（如 lineone''s），
#      若只按 \' 处理会在这里提前闭合字符串，导致该行后续所有列错位
#   2. MySQL 的反斜杠转义 \' \n \\ 等
VALUE_RE = re.compile(
    r"'(?:[^'\\]|\\.|'')*'|(\bNULL\b)|(-?\d+(?:\.\d+)?)",
    re.IGNORECASE,
)
# 字符串字面量整体（含首尾引号），用于取内部内容
STRING_RE = re.compile(r"'(?:[^'\\]|\\.|'')*'")

# jijie 尾部内嵌的「笔顺编号：3121534」
BISHUN_RE = re.compile(r"笔顺编号[：:]\s*(\d+)")

# MySQL 转义 -> 普通字符
UNESCAPE = {
    r"\'": "'",
    r"\"": '"',
    r"\n": "\n",
    r"\r": "\r",
    r"\t": "\t",
    r"\0": "",
    r"\\": "\\",
    r"\%": "%",
    r"\_": "_",
}
UNESCAPE_RE = re.compile(r"\\.", re.DOTALL)


def sql_unescape(text):
    """还原字符串字面量中的转义：先处理 SQL 标准的 ''，再处理 MySQL 的反斜杠转义。"""
    text = text.replace("''", "'")

    def _sub(m):
        return UNESCAPE.get(m.group(0), m.group(0)[1:])
    return UNESCAPE_RE.sub(_sub, text)


def plain_text(text):
    """把释义里的 HTML 片段转成便于 Android TextView 直接显示的纯文本。"""
    if text is None:
        return None
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"</p\s*>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    # 去掉行尾空白，折叠 3 行以上空行
    text = "\n".join(line.rstrip() for line in text.split("\n"))
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def parse_values(payload):
    """解析 VALUES (...) 中的一行数据，返回与 COLUMNS 对齐的列表。"""
    values = []
    for m in VALUE_RE.finditer(payload):
        token = m.group(0)
        if token.startswith("'"):
            values.append(sql_unescape(token[1:-1]))
        elif m.group(2) is not None:
            number = float(m.group(2))
            values.append(int(number) if number.is_integer() else number)
        else:
            values.append(None)  # NULL / null
    return values


def convert(src_path, dst_path):
    if not os.path.exists(src_path):
        raise SystemExit("找不到输入文件: %s" % src_path)
    if os.path.exists(dst_path):
        os.remove(dst_path)
    os.makedirs(os.path.dirname(dst_path), exist_ok=True)

    started = time.time()
    conn = sqlite3.connect(dst_path)
    conn.execute(
        """CREATE TABLE %s (
             id       INTEGER PRIMARY KEY,
             zi       TEXT NOT NULL,
             py       TEXT,
             wubi     TEXT,
             bushou   TEXT,
             bihua    INTEGER,
             pinyin   TEXT,
             bishun   TEXT,
             jijie    TEXT,
             xiangjie TEXT
           )"""
        % TABLE
    )

    sql = "INSERT OR REPLACE INTO %s (%s) VALUES (%s)" % (
        TABLE,
        ",".join(COLUMNS),
        ",".join("?" * len(COLUMNS)),
    )

    count = 0
    skipped = 0
    seen = set()
    batch = []
    with open(src_path, encoding="utf-8") as fp:
        for line in fp:
            m = INSERT_RE.match(line.strip())
            if not m:
                continue
            values = parse_values(m.group(1))
            # 原始列序: id, zi, py, wubi, bushou, bihua, pinyin, jijie, xiangjie
            if len(values) != 9:
                skipped += 1
                continue
            zi = (values[1] or "").strip()
            # zi 是查询主键，必须正好一个汉字且不重复
            if len(zi) != 1 or zi in seen:
                skipped += 1
                continue
            seen.add(zi)

            jijie = plain_text(values[7])
            bishun_match = BISHUN_RE.search(jijie or "")

            row = [
                int(values[0]) if values[0] is not None else None,
                zi,
                values[2],
                values[3],
                values[4],
                int(values[5]) if values[5] is not None else None,
                values[6],
                bishun_match.group(1) if bishun_match else None,
                jijie,
                plain_text(values[8]),
            ]
            batch.append(row)
            count += 1
            if len(batch) >= 2000:
                conn.executemany(sql, batch)
                batch = []
    if batch:
        conn.executemany(sql, batch)

    # zi 唯一，用 UNIQUE 索引让 SQLite 也帮着守住这条约束
    conn.execute("CREATE UNIQUE INDEX idx_zi ON %s(zi)" % TABLE)
    conn.execute("CREATE INDEX idx_py ON %s(py)" % TABLE)
    conn.execute("CREATE INDEX idx_pinyin ON %s(pinyin)" % TABLE)
    conn.execute("CREATE INDEX idx_bushou ON %s(bushou)" % TABLE)
    conn.execute("CREATE INDEX idx_bihua ON %s(bihua)" % TABLE)
    conn.commit()
    conn.execute("VACUUM")
    # 只读打开 WAL 库时若 -wal/-shm 缺失或不可写会直接失败，这里显式落成 DELETE 模式
    conn.execute("PRAGMA journal_mode=DELETE")
    conn.execute("PRAGMA user_version=%d" % DB_VERSION)
    conn.commit()
    conn.close()

    with open(dst_path, "rb") as fp:
        digest = hashlib.sha256(fp.read()).hexdigest()
    size = os.path.getsize(dst_path)

    print("导入 %d 行，跳过 %d 行" % (count, skipped))
    print("输出 %s" % dst_path)
    print("  字节数 (同步到 AssetDbInstaller.DB_SIZE_BYTES): %d" % size)
    print("  sha256: %s" % digest)
    print("  耗时 %.1f 秒" % (time.time() - started))

    if count != EXPECTED_ROWS:
        raise SystemExit("行数异常：期望 %d，实际 %d" % (EXPECTED_ROWS, count))


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    dst = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_DST
    convert(src, dst)
