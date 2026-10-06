import urllib.request, urllib.parse, json, ssl

ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

def sparql(query):
    url = "https://dbpedia.org/sparql?default-graph-uri=http%3A%2F%2Fdbpedia.org&query=" + urllib.parse.quote(query) + "&format=application%2Fsparql-results%2Bjson&timeout=30000"
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
    with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
        return json.loads(r.read().decode("utf-8"))

# validate pipeline with a simple resource
try:
    res = sparql('SELECT ?a WHERE { <http://dbpedia.org/resource/Database> <http://dbpedia.org/ontology/abstract> ?a . FILTER(lang(?a)="en") }')
    print("VALIDATION ok, abstract chars:", len(res["results"]["bindings"][0]["a"]["value"]) if res["results"]["bindings"] else 0)
except Exception as e:
    print("VALIDATION-FAIL:", e)

# proper IRIs with unicode en dash U+2013
resources = {
  "er_model": "http://dbpedia.org/resource/Entity\u2013relationship_model",
  "eer_model": "http://dbpedia.org/resource/Enhanced_entity\u2013relationship_model",
  "min_max": "http://dbpedia.org/resource/Min-Max-Notation",
  "weak_entity": "http://dbpedia.org/resource/Weak_entity",
  "associative_entity": "http://dbpedia.org/resource/Associative_entity",
  "cardinality": "http://dbpedia.org/resource/Cardinality_(data_modeling)",
  "look_across": "http://dbpedia.org/resource/Look-across_cardinality",
  "crowfoot": "http://dbpedia.org/resource/Crow's_foot_notation",
}
out_lines = []
for label, iri in resources.items():
    try:
        q = f'SELECT ?a WHERE {{ <{iri}> <http://dbpedia.org/ontology/abstract> ?a . FILTER(lang(?a)="en") }}'
        res = sparql(q)
        vals = res["results"]["bindings"]
        if vals:
            abstract = vals[0]["a"]["value"]
            print(f"\n===== {label} =====\n{abstract}\n")
            out_lines.append(f"===== {label} ({iri}) =====\n{abstract}")
        else:
            print(f"\n===== {label} ===== (no abstract)")
            out_lines.append(f"===== {label} ({iri}) =====\n(no abstract)")
    except Exception as e:
        print(f"SPARQL-FAIL {label}: {e}")
        out_lines.append(f"===== {label} ({iri}) =====\nFAILED: {e}")

import os
outdir = os.path.join(os.environ.get("TEMP", "/tmp"), "erresearch")
with open(os.path.join(outdir, "dbpedia_abstracts.txt"), "w", encoding="utf-8") as f:
    f.write("\n\n".join(out_lines))
print("saved to", os.path.join(outdir, "dbpedia_abstracts.txt"))
