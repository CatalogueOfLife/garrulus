# pipeline on pensoft / test (mode B, match STRICT, document level)

Date: 2026-11-15

Documents: 196

Finder: pipeline 0.1.0-SNAPSHOT, config `799364566a46508c6817796ff29edb32d09b6343a8dc9a8a8e476f1f20d93153`, dictionary `316691`

- Mode B: 196 of 200 documents converted from PDF by GROBID and scored; 4 skipped (download or conversion failed): pensoft:zookeys.1294.205145, pensoft:bdj.14.e191625, pensoft:zookeys.1294.205518, pensoft:mycokeys.141.209682.
- PDF sources: 196 from the PMC Article Datasets on AWS (copies identical in spot checks (MD5)), 0 from pensoft.net.
- The JSON keeps the first 500 error samples of each kind: 500 of 2553 recorded false positives, 500 of 2381 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 8826 | 2553 | 2381 | 0.776 | 0.788 | 0.782 |
| &nbsp;&nbsp;kind:ABBREVIATED | 2275 | 419 | 769 | 0.844 | 0.747 | 0.793 |
| &nbsp;&nbsp;kind:BINOMIAL | 3845 | 1187 | 915 | 0.764 | 0.808 | 0.785 |
| &nbsp;&nbsp;kind:INFRAGENERIC | 0 | 6 | 0 | 0.000 | 0.000 | 0.000 |
| &nbsp;&nbsp;kind:TRINOMIAL | 123 | 99 | 109 | 0.554 | 0.530 | 0.542 |
| &nbsp;&nbsp;kind:UNINOMIAL | 2583 | 842 | 588 | 0.754 | 0.815 | 0.783 |
| &nbsp;&nbsp;lang:en | 7687 | 2155 | 2053 | 0.781 | 0.789 | 0.785 |
| &nbsp;&nbsp;lang:und | 1139 | 398 | 328 | 0.741 | 0.776 | 0.758 |
| acts | 277 | 149 | 28 | 0.650 | 0.908 | 0.758 |
| contexts | 266 | 465 | 1275 | 0.364 | 0.173 | 0.234 |
| main subject | 399 | 492 | 46 | 0.448 | 0.897 | 0.597 |

## False positives (30 of 2553 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] Talavera
- `pensoft:bdj.14.e161845` [UNINOMIAL] Atacama
- `pensoft:bdj.12.e118010` [BINOMIAL] Orthoptera families
- `pensoft:bdj.14.e191590` [UNINOMIAL] Canis
- `pensoft:zookeys.1196.117947` [UNINOMIAL] Katha
- `pensoft:mycokeys.110.134132` [BINOMIAL] Wang tris
- `pensoft:bdj.14.e186195` [UNINOMIAL] Canaria
- `pensoft:zookeys.1279.188050` [UNINOMIAL] Trimeresurus
- `pensoft:phytokeys.279.207098` [UNINOMIAL] Taquara
- `pensoft:bdj.12.e134521` [UNINOMIAL] Takara
- `pensoft:mycokeys.124.172775` [BINOMIAL] Hainan sandy
- `pensoft:bdj.14.e180342` [UNINOMIAL] Lazarina
- `pensoft:zookeys.1244.146861` [UNINOMIAL] Zetterstedt
- `pensoft:mycokeys.125.168474` [UNINOMIAL] Ozimek
- `pensoft:zookeys.1236.145914` [UNINOMIAL] Hainan
- `pensoft:zookeys.1225.125030` [UNINOMIAL] Latreille
- `pensoft:zookeys.1268.173464` [UNINOMIAL] Mongolia
- `pensoft:mycokeys.123.160960` [UNINOMIAL] Notaris
- `pensoft:phytokeys.251.135126` [UNINOMIAL] Trichosporeae
- `pensoft:mycokeys.133.187172` [UNINOMIAL] Semiorbis
- `pensoft:mycokeys.107.122026` [UNINOMIAL] Canace
- `pensoft:zookeys.1275.178507` [UNINOMIAL] Odisha
- `pensoft:phytokeys.251.137015` [UNINOMIAL] Xia
- `pensoft:zookeys.1265.168108` [ABBREVIATED] Paragomphus lacustris cerci
- `pensoft:phytokeys.257.153202` [BINOMIAL] Melandrium batangense
- `pensoft:zookeys.1232.142699` [UNINOMIAL] Isparta
- `pensoft:zookeys.1258.168452` [ABBREVIATED] Monomicrocaryon euglenivorum
- `pensoft:mycokeys.128.178090` [UNINOMIAL] Yunnan
- `pensoft:phytokeys.270.174879` [UNINOMIAL] Yunnan
- `pensoft:bdj.12.e116039` [UNINOMIAL] Murina

## False negatives (30 of 2381 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] Heliocosmidae
- `pensoft:bdj.14.e161845` [BINOMIAL] Nutallochiton martiali
- `pensoft:bdj.12.e118010` [BINOMIAL] Ephippigerida rosae
- `pensoft:zookeys.1196.117947` [UNINOMIAL] ornata
- `pensoft:mycokeys.110.134132` [UNINOMIAL] Hypocreales
- `pensoft:bdj.14.e186195` [UNINOMIAL] crustacea
- `pensoft:zookeys.1279.188050` [BINOMIAL] Achalinus quangi
- `pensoft:bdj.12.e134521` [UNINOMIAL] Chiton
- `pensoft:mycokeys.124.172775` [UNINOMIAL] Nigri
- `pensoft:bdj.14.e180342` [UNINOMIAL] Allium
- `pensoft:zookeys.1244.146861` [BINOMIAL] Anevrina thoracica
- `pensoft:mycokeys.125.168474` [UNINOMIAL] Podia
- `pensoft:zookeys.1236.145914` [UNINOMIAL] Metrodolrinae
- `pensoft:zookeys.1225.125030` [UNINOMIAL] (Armadillidium)
- `pensoft:zookeys.1268.173464` [UNINOMIAL] Cristariina
- `pensoft:zookeys.1214.132950` [UNINOMIAL] Copepoda
- `pensoft:mycokeys.123.160960` [ABBREVIATED] Daldinia kretschmaroides
- `pensoft:mycokeys.133.187172` [UNINOMIAL] Hypocreales
- `pensoft:mycokeys.107.122026` [UNINOMIAL] Pestalotioid
- `pensoft:zookeys.1275.178507` [BINOMIAL] Cyrtodactylus (Geckoella) nebulosus
- `pensoft:phytokeys.251.137015` [TRINOMIAL] Agapetes ser. Longifiles
- `pensoft:zookeys.1265.168108` [UNINOMIAL] elpidius
- `pensoft:phytokeys.257.153202` [ABBREVIATED] Silene sect. Longitubulosae
- `pensoft:zookeys.1232.142699` [UNINOMIAL] Acari
- `pensoft:zookeys.1258.168452` [UNINOMIAL] Hypotricha
- `pensoft:mycokeys.128.178090` [BINOMIAL] Amphibambusa cerosissimae
- `pensoft:phytokeys.270.174879` [BINOMIAL] Impatiens nushanensis
- `pensoft:bdj.12.e116039` [UNINOMIAL] Cnidaria
- `pensoft:mycokeys.134.187344` [UNINOMIAL] sensu
- `pensoft:zookeys.1210.125611` [UNINOMIAL] hainanica
