#!/usr/bin/env python3
# Regenerates carabus-note.pdf, the GrobidIT fixture: python3 make-fixture.py carabus-note.pdf
# The text was written for this fixture (no third-party content); the PDF is dedicated to the public domain (CC0).
"""Writes a one-page PDF (PDF 1.4, Helvetica) with a title, an author line, an abstract and two body paragraphs.
The text is written for this test fixture; no third-party content."""
import sys

title = "A new ground beetle of the genus Carabus (Coleoptera: Carabidae)"
author = "A. Tester"
blocks = [
    ("Abstract", [
        "Carabus nigrus sp. nov. is described from a mountain forest. It differs",
        "from Carabus violaceus Linnaeus, 1758 in the shape of the pronotum.",
    ]),
    ("Introduction", [
        "The genus Carabus Linnaeus, 1758 holds more than nine hundred species of",
        "ground beetles. Species of Carabus are flightless predators of snails,",
        "earthworms and caterpillars, and several of them are used as indicators",
        "of forest quality. Pterostichus niger and Abax parallelepipedus were found",
        "together with the new species in pitfall traps.",
    ]),
    ("Material and methods", [
        "Specimens were collected by hand and in pitfall traps between May and",
        "September. All specimens are kept in the collection of the author.",
    ]),
]

def esc(s):
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

ops = ["BT", "/F2 14 Tf", "72 760 Td", "(%s) Tj" % esc(title), "ET",
       "BT", "/F1 11 Tf", "72 738 Td", "(%s) Tj" % esc(author), "ET"]
y = 706
for head, lines in blocks:
    ops += ["BT", "/F2 11 Tf", "72 %d Td" % y, "(%s) Tj" % esc(head), "ET"]
    y -= 16
    ops += ["BT", "/F1 10 Tf", "13 TL", "72 %d Td" % y]
    for i, line in enumerate(lines):
        ops.append(("(%s) Tj" if i == 0 else "T* (%s) Tj") % esc(line))
    ops.append("ET")
    y -= 13 * len(lines) + 14
content = "\n".join(ops).encode("latin-1")

objs = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>",
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
    b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream",
    b"<< /Title (A new ground beetle of the genus Carabus) /Author (A. Tester) >>",
]
out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
offsets = []
for i, o in enumerate(objs, 1):
    offsets.append(len(out))
    out += b"%d 0 obj\n" % i + o + b"\nendobj\n"
xref = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
for off in offsets:
    out += b"%010d 00000 n \n" % off
out += b"trailer\n<< /Size %d /Root 1 0 R /Info 7 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
open(sys.argv[1], "wb").write(out)
print(len(out), "bytes")
