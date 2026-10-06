import os, urllib.request, json, ssl
from pypdf import PdfReader

outdir = os.path.join(os.environ.get("TEMP", "/tmp"), "erresearch")
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

# 1. Extract newly downloaded PDFs
for name in ["uzh_er_slides", "kocw_elmasri_ch7"]:
    pdf_path = os.path.join(outdir, name + ".pdf")
    txt_path = os.path.join(outdir, name + ".txt")
    if os.path.exists(pdf_path) and not os.path.exists(txt_path):
        try:
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
            print(f"EXTRACT {name}: {len(reader.pages)} pages, {len(text)} chars")
        except Exception as e:
            print(f"EXTRACT-FAIL {name}: {e}")

# 2. DBpedia SPARQL abstract queries (local shell route)
def sparql(query):
    url = "https://dbpedia.org/sparql?default-graph-uri=http%3A%2F%2Fdbpedia.org&query=" + urllib.parse.quote(query) + "&format=application%2Fsparql-results%2Bjson&timeout=30000"
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/sparql-results+json, application/json, text/json, */*"})
    with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
        return json.loads(r.read().decode("utf-8"))

queries = {
  "er_model": "Entity–relationship_model",
  "eer_model": "Enhanced_entity–relationship_model",
  "min_max": "Min-Max-Notation",
  "weak_entity": "Weak_entity",
  "associative_entity": "Associative_entity",
  "cardinality": "Cardinality_(data_modeling)",
  "look_across": "Look-across_cardinality",
}
for label, resource in queries.items():
    try:
        q = f'SELECT ?a WHERE {{ <http://dbpedia.org/resource/{resource.replace(" ", "_")}> <http://dbpedia.org/ontology/abstract> ?a . FILTER(lang(?a)="en") }}'
        res = sparql(q)
        vals = res["results"]["bindings"]
        if vals:
            abstract = vals[0]["a"]["value"]
            print(f"\n===== {label} ({resource}) =====\n{abstract}")
        else:
            print(f"\n===== {label} ({resource}) =====\n(no abstract)")
    except Exception as e:
        print(f"SPARQL-FAIL {label}: {e}")
