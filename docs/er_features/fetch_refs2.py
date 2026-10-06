import os, ssl, urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
targets = {
  "elmasri_ch07_6ed_chico": ["http://www.ecst.csuchico.edu/~melody/courses/Fall2009CINS370/Chap7_6ed.pdf",
                              "https://www.ecst.csuchico.edu/~melody/courses/Fall2009CINS370/Chap7_6ed.pdf"],
  "elmasri_ch09_6ed_chico": ["http://www.ecst.csuchico.edu/~melody/courses/Fall2009CINS370/Chap9_6ed.pdf",
                              "https://www.ecst.csuchico.edu/~melody/courses/Fall2009CINS370/Chap9_6ed.pdf"],
  "chen1976_uh": ["https://www2.cs.uh.edu/~ceick/6340/chen-76.pdf"],
  "chen1976_uh2": ["http://www2.cs.uh.edu/~ceick/cosc6340/chen-76.pdf"],
}
outdir = os.path.join(os.environ.get("TEMP", "/tmp"), "erresearch")
os.makedirs(outdir, exist_ok=True)
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
for name, urls in targets.items():
    pdf_path = os.path.join(outdir, name + ".pdf")
    if os.path.exists(pdf_path):
        print(f"SKIP {name} (cached)"); continue
    ok = False
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90, context=ctx) as r:
                data = r.read()
            with open(pdf_path, "wb") as f: f.write(data)
            print(f"DOWNLOAD {name}: {len(data)} bytes from {url}")
            ok = True; break
        except Exception as e:
            print(f"FAIL {name} ({url}): {e}")
    if not ok: print(f"GIVEUP {name}")
