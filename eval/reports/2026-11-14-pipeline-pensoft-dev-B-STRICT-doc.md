# pipeline on pensoft / dev (mode B, match STRICT, document level)

Date: 2026-11-14

Documents: 97

Finder: pipeline 0.1.0-SNAPSHOT, config `9e194ba0f1db4c4a2a1c71f674ee52e231f4367e22b8127a023397b5ed4fc420`, dictionary `316691`

- Mode B: 97 of 100 documents converted from PDF by GROBID and scored; 3 skipped (download or conversion failed): pensoft:mycokeys.141.209886, pensoft:zookeys.1293.199475, pensoft:zookeys.1293.201884.
- PDF sources: 94 from the PMC Article Datasets on AWS (byte-identical copies), 3 from pensoft.net.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 6678 | 1511 | 1051 | 0.815 | 0.864 | 0.839 |
| &nbsp;&nbsp;kind:ABBREVIATED | 1397 | 553 | 361 | 0.716 | 0.795 | 0.754 |
| &nbsp;&nbsp;kind:BINOMIAL | 2752 | 421 | 312 | 0.867 | 0.898 | 0.882 |
| &nbsp;&nbsp;kind:INFRAGENERIC | 0 | 6 | 0 | 0.000 | 0.000 | 0.000 |
| &nbsp;&nbsp;kind:TRINOMIAL | 226 | 45 | 61 | 0.834 | 0.787 | 0.810 |
| &nbsp;&nbsp;kind:UNINOMIAL | 2303 | 486 | 317 | 0.826 | 0.879 | 0.852 |
| &nbsp;&nbsp;lang:en | 4684 | 1130 | 763 | 0.806 | 0.860 | 0.832 |
| &nbsp;&nbsp;lang:und | 1994 | 381 | 288 | 0.840 | 0.874 | 0.856 |
| acts | 143 | 138 | 11 | 0.509 | 0.929 | 0.657 |
| contexts | 186 | 220 | 1083 | 0.458 | 0.147 | 0.222 |
| main subject | 201 | 321 | 26 | 0.385 | 0.885 | 0.537 |

## False positives (30 of 1511 recorded)

- `pensoft:mycokeys.128.172807` [BINOMIAL] Hyphodermella core
- `pensoft:mycokeys.104.117872` [ABBREVIATED] A. zhaotongensis
- `pensoft:phytokeys.275.192014` [UNINOMIAL] Lindenbergia
- `pensoft:zookeys.1251.145353` [TRINOMIAL] Europlema uncus triangular
- `pensoft:mycokeys.113.140932` [UNINOMIAL] Cystidia
- `pensoft:zookeys.1273.176856` [UNINOMIAL] Koror
- `pensoft:zookeys.1195.115889` [UNINOMIAL] Mesopleura
- `pensoft:zookeys.1217.125780` [UNINOMIAL] Tegula
- `pensoft:bdj.12.e130385` [UNINOMIAL] Samarina
- `pensoft:zookeys.1254.168429` [UNINOMIAL] Orellana
- `pensoft:bdj.12.e132915` [UNINOMIAL] Genus
- `pensoft:zookeys.1218.126535` [UNINOMIAL] Comayagua
- `pensoft:phytokeys.247.131353` [ABBREVIATED] Cocconeis skvortzovii
- `pensoft:phytokeys.263.163351` [INFRAGENERIC] Nothoperanema (Tagawa)
- `pensoft:zookeys.1219.134970` [UNINOMIAL] Tokara
- `pensoft:mycokeys.123.161540` [UNINOMIAL] Takashima
- `pensoft:zookeys.1232.141693` [UNINOMIAL] Colombiana
- `pensoft:mycokeys.126.174148` [ABBREVIATED] H. qixianlingensis
- `pensoft:zookeys.1257.165617` [ABBREVIATED] Anura liangshanensis
- `pensoft:bdj.12.e72511` [UNINOMIAL] Latreille
- `pensoft:zookeys.1260.167263` [UNINOMIAL] Corallites
- `pensoft:mycokeys.136.188385` [UNINOMIAL] Helicon
- `pensoft:phytokeys.252.134081` [UNINOMIAL] Murata
- `pensoft:zookeys.1276.183701` [BINOMIAL] Chaetospania infernalis
- `pensoft:zookeys.1283.169111` [ABBREVIATED] P. simonae
- `pensoft:bdj.12.e115464` [UNINOMIAL] Macaronesia
- `pensoft:mycokeys.127.173361` [BINOMIAL] Ophiocordyceps fungi
- `pensoft:zookeys.1264.140174` [UNINOMIAL] Latreille
- `pensoft:phytokeys.252.135155` [ABBREVIATED] L. fargesii
- `pensoft:bdj.12.e141640` [UNINOMIAL] Hebei

## False negatives (30 of 1051 recorded)

- `pensoft:mycokeys.128.172807` [UNINOMIAL] Fungi
- `pensoft:mycokeys.104.117872` [ABBREVIATED] Arecophila coronata
- `pensoft:phytokeys.275.192014` [UNINOMIAL] Spirobolida
- `pensoft:zookeys.1251.145353` [BINOMIAL] Dysaethria cretacae
- `pensoft:mycokeys.113.140932` [BINOMIAL] Pseudohydropus commenticius
- `pensoft:zookeys.1273.176856` [UNINOMIAL] Copepoda
- `pensoft:zookeys.1195.115889` [ABBREVIATED] Leptogenys strena
- `pensoft:zookeys.1217.125780` [BINOMIAL] Malaxa bakeri
- `pensoft:zookeys.1254.168429` [UNINOMIAL] selva
- `pensoft:bdj.12.e132915` [UNINOMIAL] APOMYRMINAE
- `pensoft:zookeys.1218.126535` [UNINOMIAL] mice
- `pensoft:phytokeys.247.131353` [TRINOMIAL] Cocconeis placentula Ehrenberg var. baicalensis
- `pensoft:phytokeys.263.163351` [UNINOMIAL] Splendentes
- `pensoft:zookeys.1232.141693` [ABBREVIATED] Nesomyrmex pulcher
- `pensoft:mycokeys.126.174148` [BINOMIAL] Tubeufia baomeilingensis
- `pensoft:bdj.12.e72511` [ABBREVIATED] Culicoides volcanensis
- `pensoft:zookeys.1260.167263` [UNINOMIAL] Fimbriaphyllia
- `pensoft:mycokeys.136.188385` [UNINOMIAL] Longipediperdon
- `pensoft:zookeys.1276.183701` [BINOMIAL] Metisolabis punctata
- `pensoft:zookeys.1283.169111` [BINOMIAL] Mysidium (Mysidium) cubanense
- `pensoft:bdj.12.e115464` [TRINOMIAL] Devade indistincta
- `pensoft:mycokeys.127.173361` [UNINOMIAL] Hypocreales
- `pensoft:zookeys.1264.140174` [BINOMIAL] Cyprinotus dubrea
- `pensoft:zookeys.1223.138220` [TRINOMIAL] Sandracottus rotundus ab. reductus
- `pensoft:zookeys.1204.123909` [UNINOMIAL] Paradelius (Sculptomyriola)
- `pensoft:zookeys.1201.123141` [UNINOMIAL] Pectapalcohrus
- `pensoft:zookeys.1229.135950` [BINOMIAL] Barbodes sellifer
- `pensoft:zookeys.1258.145290` [UNINOMIAL] (Myotis)
- `pensoft:zookeys.1230.141900` [BINOMIAL] Amauromyza (Cephalomyza) karli
- `pensoft:zookeys.1255.162247` [ABBREVIATED] Sillago sihma
