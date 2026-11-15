# gnfinder on pensoft / dev (mode B, match STRICT, document level)

Date: 2026-11-15

Documents: 97

Finder: gnfinder 1.4.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

- Mode B: 97 of 100 documents converted from PDF by GROBID and scored; 3 skipped (download or conversion failed): pensoft:mycokeys.141.209886, pensoft:zookeys.1293.199475, pensoft:zookeys.1293.201884.
- PDF sources: 94 from the PMC Article Datasets on AWS (copies identical in spot checks (MD5)), 3 from pensoft.net.
- The JSON keeps the first 500 error samples of each kind: 500 of 5000 recorded false positives, 500 of 2070 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 5659 | 5678 | 2070 | 0.499 | 0.732 | 0.594 |
| &nbsp;&nbsp;kind:ABBREVIATED | 357 | 1928 | 1401 | 0.156 | 0.203 | 0.177 |
| &nbsp;&nbsp;kind:BINOMIAL | 2707 | 517 | 357 | 0.840 | 0.883 | 0.861 |
| &nbsp;&nbsp;kind:TRINOMIAL | 232 | 147 | 55 | 0.612 | 0.808 | 0.697 |
| &nbsp;&nbsp;kind:UNINOMIAL | 2363 | 3086 | 257 | 0.434 | 0.902 | 0.586 |
| &nbsp;&nbsp;lang:en | 3848 | 4463 | 1599 | 0.463 | 0.706 | 0.559 |
| &nbsp;&nbsp;lang:und | 1811 | 1215 | 471 | 0.598 | 0.794 | 0.682 |
| acts | 80 | 201 | 76 | 0.285 | 0.513 | 0.366 |
| contexts | 0 | 0 | 1269 | 0.000 | 0.000 | 0.000 |
| main subject | 145 | 420 | 84 | 0.257 | 0.633 | 0.365 |

## False positives (30 of 5000 recorded)

- `pensoft:mycokeys.128.172807` [UNINOMIAL] Accepted
- `pensoft:mycokeys.104.117872` [UNINOMIAL] China
- `pensoft:phytokeys.275.192014` [UNINOMIAL] Accepted
- `pensoft:zookeys.1251.145353` [UNINOMIAL] China
- `pensoft:mycokeys.113.140932` [UNINOMIAL] China
- `pensoft:zookeys.1273.176856` [UNINOMIAL] Accepted
- `pensoft:zookeys.1195.115889` [UNINOMIAL] China
- `pensoft:zookeys.1217.125780` [UNINOMIAL] China
- `pensoft:bdj.12.e130385` [UNINOMIAL] Accepted
- `pensoft:zookeys.1254.168429` [UNINOMIAL] Amazonia
- `pensoft:bdj.12.e132915` [UNINOMIAL] Overview
- `pensoft:zookeys.1218.126535` [UNINOMIAL] Taxonomic
- `pensoft:phytokeys.247.131353` [UNINOMIAL] Accepted
- `pensoft:phytokeys.263.163351` [UNINOMIAL] Accepted
- `pensoft:zookeys.1219.134970` [UNINOMIAL] Accepted
- `pensoft:mycokeys.123.161540` [UNINOMIAL] China
- `pensoft:zookeys.1232.141693` [UNINOMIAL] Accepted
- `pensoft:mycokeys.126.174148` [UNINOMIAL] China
- `pensoft:zookeys.1257.165617` [UNINOMIAL] China
- `pensoft:bdj.12.e72511` [UNINOMIAL] Latreille
- `pensoft:zookeys.1260.167263` [UNINOMIAL] Accepted
- `pensoft:mycokeys.136.188385` [UNINOMIAL] Accepted
- `pensoft:phytokeys.252.134081` [UNINOMIAL] Nepal
- `pensoft:zookeys.1276.183701` [UNINOMIAL] Accepted
- `pensoft:zookeys.1283.169111` [UNINOMIAL] Accepted
- `pensoft:bdj.12.e115464` [UNINOMIAL] Macaronesia
- `pensoft:mycokeys.127.173361` [UNINOMIAL] China
- `pensoft:zookeys.1264.140174` [UNINOMIAL] Accepted
- `pensoft:phytokeys.252.135155` [UNINOMIAL] China
- `pensoft:bdj.12.e141640` [UNINOMIAL] Hebei

## False negatives (30 of 2070 recorded)

- `pensoft:mycokeys.128.172807` [ABBREVIATED] Hyphodermella salcedoae
- `pensoft:mycokeys.104.117872` [ABBREVIATED] Arecophila gulubiicola
- `pensoft:phytokeys.275.192014` [ABBREVIATED] Orobanche centaurina
- `pensoft:zookeys.1251.145353` [ABBREVIATED] Eversmannia atromarginata
- `pensoft:mycokeys.113.140932` [BINOMIAL] Xenasma bisterigmatae
- `pensoft:zookeys.1273.176856` [BINOMIAL] Scottolana picrca
- `pensoft:zookeys.1195.115889` [ABBREVIATED] Leptogenys cyanicatena
- `pensoft:zookeys.1217.125780` [ABBREVIATED] Bambusiphaga fascia
- `pensoft:zookeys.1254.168429` [UNINOMIAL] Amazoboea
- `pensoft:bdj.12.e132915` [UNINOMIAL] APOMYRMINAE
- `pensoft:zookeys.1218.126535` [UNINOMIAL] mice
- `pensoft:phytokeys.247.131353` [ABBREVIATED] Cocconeis nanoburyatica
- `pensoft:phytokeys.263.163351` [TRINOMIAL] Dryopteris subg. (Dryopteris)
- `pensoft:zookeys.1232.141693` [BINOMIAL] Nesomyrmex konina
- `pensoft:mycokeys.126.174148` [BINOMIAL] Helicotubeufia qixianlingensis
- `pensoft:zookeys.1257.165617` [TRINOMIAL] Amolops dafangensis wumengmontis
- `pensoft:bdj.12.e72511` [ABBREVIATED] Culicoides insignis
- `pensoft:zookeys.1260.167263` [UNINOMIAL] Fimbriaphyllia
- `pensoft:mycokeys.136.188385` [UNINOMIAL] Longipediperdon
- `pensoft:phytokeys.252.134081` [ABBREVIATED] Typhonium roxburghii
- `pensoft:zookeys.1276.183701` [BINOMIAL] Metisolabis punctata
- `pensoft:zookeys.1283.169111` [UNINOMIAL] Chelitrapezura
- `pensoft:bdj.12.e115464` [TRINOMIAL] Devade indistincta
- `pensoft:mycokeys.127.173361` [ABBREVIATED] Ophiocordyceps blattae
- `pensoft:zookeys.1264.140174` [ABBREVIATED] Heterocypris vandouwei
- `pensoft:phytokeys.252.135155` [ABBREVIATED] Lilium lophophorum
- `pensoft:bdj.12.e141640` [ABBREVIATED] Pholcus guani
- `pensoft:zookeys.1223.138220` [TRINOMIAL] Sandracottus insignis ornatus
- `pensoft:zookeys.1291.189894` [UNINOMIAL] Gemenesis
- `pensoft:zookeys.1204.123909` [UNINOMIAL] Paradelius (Sculptomyriola)
