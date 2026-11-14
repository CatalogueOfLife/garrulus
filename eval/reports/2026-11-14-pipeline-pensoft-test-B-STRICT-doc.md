# pipeline on pensoft / test (mode B, match STRICT, document level)

Date: 2026-11-14

Documents: 196

Finder: pipeline 0.1.0-SNAPSHOT, config `9e194ba0f1db4c4a2a1c71f674ee52e231f4367e22b8127a023397b5ed4fc420`, dictionary `316691`

- Mode B: 196 of 200 documents converted from PDF by GROBID and scored; 4 skipped (download or conversion failed): pensoft:zookeys.1294.205145, pensoft:bdj.14.e191625, pensoft:zookeys.1294.205518, pensoft:mycokeys.141.209682.
- PDF sources: 196 from the PMC Article Datasets on AWS (byte-identical copies), 0 from pensoft.net.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 8764 | 2852 | 2443 | 0.754 | 0.782 | 0.768 |
| &nbsp;&nbsp;kind:ABBREVIATED | 2224 | 766 | 820 | 0.744 | 0.731 | 0.737 |
| &nbsp;&nbsp;kind:BINOMIAL | 3839 | 1160 | 921 | 0.768 | 0.807 | 0.787 |
| &nbsp;&nbsp;kind:INFRAGENERIC | 0 | 8 | 0 | 0.000 | 0.000 | 0.000 |
| &nbsp;&nbsp;kind:TRINOMIAL | 124 | 97 | 108 | 0.561 | 0.534 | 0.547 |
| &nbsp;&nbsp;kind:UNINOMIAL | 2577 | 821 | 594 | 0.758 | 0.813 | 0.785 |
| &nbsp;&nbsp;lang:en | 7665 | 2396 | 2075 | 0.762 | 0.787 | 0.774 |
| &nbsp;&nbsp;lang:und | 1099 | 456 | 368 | 0.707 | 0.749 | 0.727 |
| acts | 277 | 155 | 27 | 0.641 | 0.911 | 0.753 |
| contexts | 266 | 469 | 1275 | 0.362 | 0.173 | 0.234 |
| main subject | 399 | 511 | 45 | 0.438 | 0.899 | 0.589 |

## False positives (30 of 2852 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] Talavera
- `pensoft:bdj.14.e161845` [UNINOMIAL] Atacama
- `pensoft:bdj.12.e118010` [BINOMIAL] Orthoptera families
- `pensoft:bdj.14.e191590` [UNINOMIAL] Canis
- `pensoft:zookeys.1196.117947` [UNINOMIAL] Katha
- `pensoft:mycokeys.110.134132` [UNINOMIAL] Yunnan
- `pensoft:bdj.14.e186195` [UNINOMIAL] Canaria
- `pensoft:zookeys.1279.188050` [UNINOMIAL] Trimeresurus
- `pensoft:phytokeys.279.207098` [UNINOMIAL] Taquara
- `pensoft:bdj.12.e134521` [UNINOMIAL] Takara
- `pensoft:mycokeys.124.172775` [ABBREVIATED] A. cavernicola
- `pensoft:bdj.14.e180342` [UNINOMIAL] Lazarina
- `pensoft:zookeys.1244.146861` [UNINOMIAL] Zetterstedt
- `pensoft:mycokeys.125.168474` [UNINOMIAL] Ozimek
- `pensoft:zookeys.1236.145914` [ABBREVIATED] M. badagongshanensis
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

## False negatives (30 of 2443 recorded)

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
- `pensoft:zookeys.1244.146861` [UNINOMIAL] Cyclorrhapha
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
