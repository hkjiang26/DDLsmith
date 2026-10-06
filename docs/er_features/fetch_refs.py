import sys, io, os, re
import urllib.request, ssl
from pypdf import PdfReader

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
targets = {
  "elmasri_ch07": "http://tinman.cs.gsu.edu/~raj/4710/f11/Ch07.pdf",
  "elmasri_ch08": "http://tinman.cs.gsu.edu/~raj/4710/f11/Ch08.pdf",
  "elmasri_ch09": "http://tinman.cs.gsu.edu/~raj/4710/f11/Ch09.pdf",
  "chen1976_jhu": "https://www.cs.jhu.edu/~misha/ReadingPapers/Chen1976.pdf",
  "chen1976_uh": "https://www2.cs.uh.edu/~ceick/6340/chen-76.pdf",
  "silber_ch7_uva": "https://ivi.fnwi.uva.nl/fcn/teaching/2013-db-ik/silberslides/ch7.pdf",
  "silber_notes_hartig": "http://olafhartig.de/CS640/2-ermodel-handout-notes.pdf",
  "elmasri_ch07_6ed_chico": "http://www.ecst.csuchico.edu/~melody/courses/Fall2009CINS370/Chap7_6ed.pdf",
  "elmasri_ch08_6ed_chico": "http://www.ecst.csuchico.edu/~melody/courses/Fall2009CINS370/Chap8_6ed.pdf",
}

outdir = os.path.join(os.environ.get("TEMP", "/tmp"), "erresearch")
os.makedirs(outdir, exist_ok=True)
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

for name, url in targets.items():
    pdf_path = os.path.join(outdir, name + ".pdf")
    txt_path = os.path.join(outdir, name + ".txt")
    try:
        if not os.path.exists(pdf_path):
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
                data = r.read()
            with open(pdf_path, "wb") as f:
                f.write(data)
            print(f"DOWNLOAD {name}: {len(data)} bytes from {url}")
        else:
            print(f"SKIP-DL {name} (cached)")
        if not os.path.exists(txt_path):
            reader = PdfReader(pdf_path)
            pages = []
            for i, p in enumerate(reader.pages):
                try:
                    pages.append(f"--- PAGE {i+1} ---\n" + (p.extract_text() or ""))
                except Exception as e:
                    pages.append(f"--- PAGE {i+1} ERROR {e}")
            text = "\n".join(pages)
            with open(txt_path, "w", encoding="utf-8", errors="replace") as f:
                f.write(text)
            print(f"EXTRACT {name}: {len(reader.pages)} pages, {len(text)} chars -> {txt_path}")
    except Exception as e:
        print(f"FAIL {name} ({url}): {e}")
