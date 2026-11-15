# gnfinder on pensoft / test (mode B, match STRICT, document level)

Date: 2026-11-15

Documents: 196

Finder: gnfinder 1.4.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

- Mode B: 196 of 200 documents converted from PDF by GROBID and scored; 4 skipped (download or conversion failed): pensoft:zookeys.1294.205145, pensoft:bdj.14.e191625, pensoft:zookeys.1294.205518, pensoft:mycokeys.141.209682.
- PDF sources: 196 from the PMC Article Datasets on AWS (copies identical in spot checks (MD5)), 0 from pensoft.net.
- The JSON keeps the first 500 error samples of each kind: 500 of 5000 recorded false positives, 500 of 3953 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 7254 | 10643 | 3953 | 0.405 | 0.647 | 0.498 |
| &nbsp;&nbsp;kind:ABBREVIATED | 719 | 3378 | 2325 | 0.175 | 0.236 | 0.201 |
| &nbsp;&nbsp;kind:BINOMIAL | 3745 | 1373 | 1015 | 0.732 | 0.787 | 0.758 |
| &nbsp;&nbsp;kind:TRINOMIAL | 116 | 194 | 116 | 0.374 | 0.500 | 0.428 |
| &nbsp;&nbsp;kind:UNINOMIAL | 2674 | 5698 | 497 | 0.319 | 0.843 | 0.463 |
| &nbsp;&nbsp;lang:en | 6262 | 8988 | 3478 | 0.411 | 0.643 | 0.501 |
| &nbsp;&nbsp;lang:und | 992 | 1655 | 475 | 0.375 | 0.676 | 0.482 |
| acts | 171 | 271 | 134 | 0.387 | 0.561 | 0.458 |
| contexts | 0 | 0 | 1541 | 0.000 | 0.000 | 0.000 |
| main subject | 297 | 770 | 148 | 0.278 | 0.667 | 0.393 |

## False positives (30 of 5000 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] Accepted
- `pensoft:bdj.14.e161845` [UNINOMIAL] Accepted
- `pensoft:bdj.12.e118010` [UNINOMIAL] Inbio
- `pensoft:bdj.14.e191590` [UNINOMIAL] Linnaeus
- `pensoft:zookeys.1196.117947` [UNINOMIAL] Accepted
- `pensoft:mycokeys.110.134132` [UNINOMIAL] China
- `pensoft:bdj.14.e186195` [UNINOMIAL] Amphipod
- `pensoft:zookeys.1279.188050` [UNINOMIAL] Accepted
- `pensoft:phytokeys.279.207098` [UNINOMIAL] Brazil
- `pensoft:bdj.12.e134521` [UNINOMIAL] Korean
- `pensoft:mycokeys.124.172775` [UNINOMIAL] China
- `pensoft:bdj.14.e180342` [UNINOMIAL] Flora
- `pensoft:zookeys.1244.146861` [UNINOMIAL] Accepted
- `pensoft:mycokeys.125.168474` [UNINOMIAL] China
- `pensoft:zookeys.1236.145914` [UNINOMIAL] China
- `pensoft:zookeys.1225.125030` [UNINOMIAL] Accepted
- `pensoft:zookeys.1268.173464` [UNINOMIAL] Taxonomic
- `pensoft:zookeys.1214.132950` [UNINOMIAL] Mexico
- `pensoft:mycokeys.123.160960` [UNINOMIAL] China
- `pensoft:phytokeys.251.135126` [UNINOMIAL] China
- `pensoft:mycokeys.133.187172` [UNINOMIAL] China
- `pensoft:mycokeys.107.122026` [UNINOMIAL] China
- `pensoft:zookeys.1275.178507` [UNINOMIAL] Nepal
- `pensoft:phytokeys.251.137015` [UNINOMIAL] China
- `pensoft:zookeys.1265.168108` [UNINOMIAL] Accepted
- `pensoft:phytokeys.257.153202` [UNINOMIAL] Taxonomic
- `pensoft:zookeys.1232.142699` [UNINOMIAL] Accepted
- `pensoft:zookeys.1258.168452` [UNINOMIAL] Accepted
- `pensoft:mycokeys.128.178090` [UNINOMIAL] Taxonomic
- `pensoft:phytokeys.270.174879` [UNINOMIAL] China

## False negatives (30 of 3953 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] Heliocosmidae
- `pensoft:bdj.12.e118010` [BINOMIAL] Ephippigerida rosae
- `pensoft:zookeys.1196.117947` [UNINOMIAL] ornata
- `pensoft:mycokeys.110.134132` [BINOMIAL] Paradingleyomyces lepidopterorum
- `pensoft:bdj.14.e186195` [UNINOMIAL] crustacea
- `pensoft:zookeys.1279.188050` [ABBREVIATED] Sphenomorphus tamchucensis
- `pensoft:phytokeys.279.207098` [ABBREVIATED] Imperata minutiflora
- `pensoft:bdj.12.e134521` [ABBREVIATED] Ischnochiton manazuruensis
- `pensoft:mycokeys.124.172775` [UNINOMIAL] (Nidulantes)
- `pensoft:zookeys.1244.146861` [BINOMIAL] Anevrina thoracica
- `pensoft:mycokeys.125.168474` [ABBREVIATED] Linnemannia longigemmata
- `pensoft:zookeys.1236.145914` [UNINOMIAL] Metrodolrinae
- `pensoft:zookeys.1225.125030` [UNINOMIAL] (Armadillidium)
- `pensoft:zookeys.1268.173464` [UNINOMIAL] Cristariina
- `pensoft:zookeys.1214.132950` [UNINOMIAL] Harpaticoida
- `pensoft:mycokeys.123.160960` [ABBREVIATED] Daldinia bakeri
- `pensoft:phytokeys.251.135126` [ABBREVIATED] Primulina davidioides
- `pensoft:mycokeys.133.187172` [ABBREVIATED] Trichoderma fulviclavum
- `pensoft:mycokeys.107.122026` [UNINOMIAL] Pestalotioid
- `pensoft:zookeys.1275.178507` [BINOMIAL] Cyrtodactylus (Geckoella) nebulosus
- `pensoft:phytokeys.251.137015` [ABBREVIATED] Agapetes hosseana
- `pensoft:zookeys.1265.168108` [ABBREVIATED] Paragomphus sinaiticus
- `pensoft:phytokeys.257.153202` [ABBREVIATED] Silene sect. Longitubulosae
- `pensoft:zookeys.1232.142699` [ABBREVIATED] Sperchon glandulosus
- `pensoft:zookeys.1258.168452` [ABBREVIATED] Monomicrocaryon euglenivorum euglenivorum
- `pensoft:mycokeys.128.178090` [BINOMIAL] Arecophila gulubiicola
- `pensoft:phytokeys.270.174879` [ABBREVIATED] Impatiens recurvicornis
- `pensoft:bdj.12.e116039` [UNINOMIAL] Cnidaria
- `pensoft:mycokeys.134.187344` [UNINOMIAL] Durabilispora
- `pensoft:zookeys.1210.125611` [ABBREVIATED] Amemboa lyra
