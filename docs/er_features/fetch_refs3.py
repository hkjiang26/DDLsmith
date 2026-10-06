import os, ssl, urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
targets = {
  "elmasri_ch07_gsu": ["https://tinman.cs.gsu.edu/~raj/4710/f11/Ch07.pdf", "http://tinman.cs.gsu.edu/~raj/4710/f11/Ch07.pdf"],
  "elmasri_ch08_gsu": ["https://tinman.cs.gsu.edu/~raj/4710/f11/Ch08.pdf", "http://tinman.cs.gsu.edu/~raj/4710/f11/Ch08.pdf"],
  "elmasri_ch09_gsu": ["https://tinman.cs.gsu.edu/~raj/4710/f11/Ch09.pdf", "http://tinman.cs.gsu.edu/~raj/4710/f11/Ch09.pdf"],
  "nyu_er_slides": ["https://cs.nyu.edu/~jcf/classes/CSCI-GA.2433-001_fa11/slides/session3/EnterpriseDataModelingUsingTheEntity-RelationshipModel.pdf"],
  "uzh_er_slides": ["https://files.ifi.uzh.ch/dbtg/dbs/FS13/sl06-2x2.pdf"],
  "kocw_elmasri_ch7": ["http://contents2.kocw.or.kr/KOCW/document/2015/chungbuk/chowansup/6-2.pdf", "https://contents2.kocw.or.kr/KOCW/document/2015/chungbuk/chowansup/6-2.pdf"],
}
outdir = os.path.join(os.environ.get("TEMP", "/tmp"), "erresearch")
os.makedirs(outdir, exist_ok=True)
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
for name, urls in targets.items():
    pdf_path = os.path.join(outdir, name + ".pdf")
    if os.path.exists(pdf_path):
        print(f"SKIP {name} (cached)"); continue
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=180, context=ctx) as r:
                data = r.read()
            with open(pdf_path, "wb") as f: f.write(data)
            print(f"DOWNLOAD {name}: {len(data)} bytes from {url}")
            break
        except Exception as e:
            print(f"FAIL {name} ({url}): {e}")
    else:
        print(f"GIVEUP {name}")
