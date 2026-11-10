#!/usr/bin/env python3
"""生成真实的 docx/xlsx/pptx 样例，验证解析器兼容性。"""
import os
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill
from docx import Document
from docx.shared import Pt, Inches, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from pptx import Presentation
from pptx.util import Inches as PInches, Pt as PPt
from pptx.dml.color import RGBColor as PRGB
from pptx.enum.text import PP_ALIGN

out = "/tmp/office_samples"
os.makedirs(out, exist_ok=True)

# ---------- 1x1 红色 PNG（无外部依赖手写） ----------
png_path = os.path.join(out, "red.png")
import zlib, struct
def make_png(w, h, rgb):
    raw = b""
    for _ in range(h):
        raw += b"\x00" + bytes(rgb) * w
    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        c += struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
        return c
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
open(png_path, "wb").write(make_png(64, 48, (220, 40, 40)))

# ---------- docx ----------
d = Document()
d.add_heading("项目报告", level=1)
d.add_heading("第一章 概述", level=2)
p = d.add_paragraph()
p.add_run("普通文字，")
r = p.add_run("加粗文字")
r.bold = True
r2 = p.add_run("斜体文字")
r2.italic = True
r3 = p.add_run("红色下划线")
r3.font.underline = True
r3.font.color.rgb = RGBColor(0xCC, 0x00, 0x00)
d.add_paragraph("无序列表项 A", style="List Bullet")
d.add_paragraph("无序列表项 B", style="List Bullet")
c = d.add_paragraph()
c.alignment = WD_ALIGN_PARAGRAPH.CENTER
c.add_run("居中段落")
link_p = d.add_paragraph()
hl = link_p.add_hyperlink if hasattr(link_p, "add_hyperlink") else None
# 手工构造超链接
from docx.oxml import OxmlElement
part = d.part
rId = part.relate_to("https://example.com/docs",
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
    is_external=True)
h_el = OxmlElement("w:hyperlink"); h_el.set(qn("r:id"), rId)
r_el = OxmlElement("w:r"); t_el = OxmlElement("w:t"); t_el.text = "官方文档"
r_el.append(t_el); h_el.append(r_el)
p_el = OxmlElement("w:p"); link_p._p.addnext(p_el)
lp = d.paragraphs[-1] if False else None
link_p._p.append(h_el)
tbl = d.add_table(rows=3, cols=3)
tbl.style = "Table Grid"
tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
tbl.cell(0, 0).merge(tbl.cell(0, 1))
tbl.cell(0, 0).text = "合并两列"
tbl.cell(0, 2).text = "C0"
tbl.cell(1, 0).text = "纵向合并"
tbl.cell(1, 0).merge(tbl.cell(2, 0))
tbl.cell(1, 1).text = "11"; tbl.cell(1, 2).text = "12"
tbl.cell(2, 1).text = "21"; tbl.cell(2, 2).text = "22"
d.add_picture(png_path, width=Inches(1.0))
d.save(os.path.join(out, "sample.docx"))

# ---------- xlsx ----------
wb = Workbook()
ws = wb.active
ws.title = "销售表"
ws.append(["品名", "数量", "单价", "金额", "日期"])
rows = [
    ["苹果", 120, 5.5, 660.0, "2024-01-15"],
    ["香蕉", 80, 3.2, 256.0, "2024-02-29"],
]
for row in rows:
    ws.append(row)
for i in range(2, 4):
    ws.cell(row=i, column=5).number_format = "yyyy/m/d"
ws["A6"] = "合计"
ws.merge_cells("A7:B7")
ws["A7"] = "合并文本"
ws["C4"] = -42.75
ws["D4"] = 0.5
ws["E2"] = 45366  # 2024-03-01 序列日期
ws["F1"] = "备注列"
ws["F2"] = "第一行备注"
wb.create_sheet("空表")
wb.save(os.path.join(out, "sample.xlsx"))

# ---------- pptx ----------
prs = Presentation()
prs.slide_width = PInches(13.333)
prs.slide_height = PInches(7.5)
blank = prs.slide_layouts[6]

s1 = prs.slides.add_slide(prs.slide_layouts[0])  # 标题版式（占位符）
s1.shapes.title.text = "季度总结"
s1.placeholders[1].text = "副标题：2026 年第一季度"

s2 = prs.slides.add_slide(blank)
tb = s2.shapes.add_textbox(PInches(1), PInches(1), PInches(6), PInches(3))
tf = tb.text_frame
tf.word_wrap = True
para = tf.paragraphs[0]
para.text = "正文第一段"
para.runs[0].font.size = PPt(20)
para2 = tf.add_paragraph()
para2.text = "加粗第二段"
para2.runs[0].font.bold = True
para2.runs[0].font.size = PPt(18)
para2.runs[0].font.color.rgb = PRGB(0x1A, 0x6B, 0xFF)
pic = s2.shapes.add_picture(png_path, PInches(8), PInches(1), PInches(2), PInches(1.5))
tbl_shape = s2.shapes.add_table(2, 3, PInches(1), PInches(4.5), PInches(8), PInches(1.5))
t = tbl_shape.table
t.cell(0, 0).text = "指标"; t.cell(0, 1).text = "Q4"; t.cell(0, 2).text = "Q1"
t.cell(1, 0).text = "营收"; t.cell(1, 1).text = "1.2亿"; t.cell(1, 2).text = "1.5亿"
for shp in s2.shapes:
    if shp.has_table:
        pass
s3 = prs.slides.add_slide(blank)
b = s3.shapes.add_textbox(PInches(0.5), PInches(0.5), PInches(9), PInches(2))
btf = b.text_frame
btf.text = "项目符号一"
btf.paragraphs[0].level = 0
p3 = btf.add_paragraph(); p3.text = "项目符号二（二级）"; p3.level = 1
prs.save(os.path.join(out, "sample.pptx"))

print("generated:", os.listdir(out))
