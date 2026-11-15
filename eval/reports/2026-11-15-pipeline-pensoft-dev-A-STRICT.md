# pipeline on pensoft / dev (mode A, match STRICT)

Date: 2026-11-15

Documents: 100

Finder: pipeline 0.1.0-SNAPSHOT, config `799364566a46508c6817796ff29edb32d09b6343a8dc9a8a8e476f1f20d93153`, dictionary `316691`

- The JSON keeps the first 500 error samples of each kind: 500 of 2175 recorded false positives, 500 of 1273 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 25843 | 2175 | 1273 | 0.922 | 0.953 | 0.937 |
| &nbsp;&nbsp;block:ABSTRACT | 884 | 17 | 37 | 0.981 | 0.960 | 0.970 |
| &nbsp;&nbsp;block:CAPTION | 2070 | 79 | 100 | 0.963 | 0.954 | 0.959 |
| &nbsp;&nbsp;block:FOOTNOTE | 68 | 12 | 1 | 0.850 | 0.986 | 0.913 |
| &nbsp;&nbsp;block:HEADING | 775 | 35 | 25 | 0.957 | 0.969 | 0.963 |
| &nbsp;&nbsp;block:PARAGRAPH | 14042 | 1347 | 703 | 0.912 | 0.952 | 0.932 |
| &nbsp;&nbsp;block:TABLE_CELL | 7720 | 677 | 394 | 0.919 | 0.951 | 0.935 |
| &nbsp;&nbsp;block:TITLE | 284 | 8 | 13 | 0.973 | 0.956 | 0.964 |
| &nbsp;&nbsp;kind:ABBREVIATED | 7609 | 38 | 217 | 0.995 | 0.972 | 0.984 |
| &nbsp;&nbsp;kind:BINOMIAL | 6936 | 507 | 60 | 0.932 | 0.991 | 0.961 |
| &nbsp;&nbsp;kind:INFRAGENERIC | 0 | 3 | 0 | 0.000 | 0.000 | 0.000 |
| &nbsp;&nbsp;kind:TRINOMIAL | 337 | 23 | 79 | 0.936 | 0.810 | 0.869 |
| &nbsp;&nbsp;kind:UNINOMIAL | 10961 | 1604 | 917 | 0.872 | 0.923 | 0.897 |
| &nbsp;&nbsp;lang:en | 19889 | 1356 | 1078 | 0.936 | 0.949 | 0.942 |
| &nbsp;&nbsp;lang:und | 5954 | 819 | 195 | 0.879 | 0.968 | 0.922 |
| acts | 156 | 133 | 9 | 0.540 | 0.945 | 0.687 |
| contexts | 190 | 464 | 1106 | 0.291 | 0.147 | 0.195 |
| main subject | 217 | 286 | 24 | 0.431 | 0.900 | 0.583 |

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 20639 | 21413 | 0.964 |
| &nbsp;&nbsp;kind:ABBREVIATED | 7016 | 7609 | 0.922 |
| &nbsp;&nbsp;kind:BINOMIAL | 6787 | 6906 | 0.983 |
| &nbsp;&nbsp;kind:TRINOMIAL | 336 | 337 | 0.997 |
| &nbsp;&nbsp;kind:UNINOMIAL | 6500 | 6561 | 0.991 |

## False positives (30 of 2175 recorded)

- `pensoft:mycokeys.128.172807` [UNINOMIAL] Telleria MT (2026) Revisiting the genus «Hyphodermella» (Basidiomycota, Polyporales) with descr
- `pensoft:mycokeys.104.117872` [BINOMIAL] , Al-Rejaie S, Tanaka K, Dai D-Q (2024) «Paramphibambusa bambusicola» gen. et. sp. nov., Arecophila xishuangb
- `pensoft:phytokeys.275.192014` [UNINOMIAL] Niedzwetzki-Taubert 2022; Nishimura and «Takayama» 2022). Moreover, little information on 
- `pensoft:zookeys.1251.145353` [UNINOMIAL]  (2025) A taxonomic review of the genus «Eversmannia» Staudinger, 1871 (Lepidoptera, Uraniida
- `pensoft:mycokeys.113.140932` [ABBREVIATED] X. guttulata sp. nov. is clustered with «X. pruinosum». The morphology and multi-gene phylogen
- `pensoft:zookeys.1273.176856` [UNINOMIAL] alau government permit (RE-19-09) and a «Koror» state government permit (T19-10750, Per
- `pensoft:zookeys.1195.115889` [UNINOMIAL] (2024) Two new ant species of the genus «Leptogenys» (Hymenoptera, Formicidae) from Hainan, 
- `pensoft:zookeys.1217.125780` [UNINOMIAL] S (2024) Three new species of the genus «Bambusiphaga» Huang & Ding, 1979 (Hemiptera, Fulgoroi
- `pensoft:bdj.12.e130385` [UNINOMIAL] 2018, Li et al. 2019, Wang et al. 2020, «Samarina» et al. 2021, Li et al. 2023), date-plum
- `pensoft:zookeys.1254.168429` [UNINOMIAL] inen A, Österman EM, Kaunisto KM (2025) «Amazoboea», a new genus of Darwin wasps (Hymenopte
- `pensoft:bdj.12.e132915` [UNINOMIAL] 100 years ago: Odontomachus haematodus («Linnaeus», 1758), native to South America; Oecoph
- `pensoft:zookeys.1218.126535` [UNINOMIAL] omic Odyssey: An annotated checklist of «Peromyscus» (Cricetidae, Rodentia) in Honduras. Zoo
- `pensoft:phytokeys.247.131353` [UNINOMIAL] 24) Nomenclature and systematics of two «Cocconeis» species (Bacillariophyta) from Lake Bai
- `pensoft:phytokeys.263.163351` [BINOMIAL] X-J, Zhou L-X, Zuo Z-Y, Dong S-Y (2025) «Dryopteris pycnolepis», a new fern species from northern Vietn
- `pensoft:mycokeys.141.209886` [UNINOMIAL]  of saprobic microfungi associated with «Macadamia» in China and Thailand and their lignoce
- `pensoft:zookeys.1293.199475` [UNINOMIAL] -Q, Che Y-L (2026) Three new species of «Eucorydia» Hebard, 1929 (Blattodea, Corydioidea, C
- `pensoft:zookeys.1219.134970` [BINOMIAL] o K, Motomura H (2024) Redescription of «Parascorpaena moultoni» (Whitley, 1961) (Actinopterygii, Scorpa
- `pensoft:mycokeys.123.161540` [UNINOMIAL] Y, Niu Q-H, Hui F-L (2025) Phylogeny of «Phyllozyma» (Spiculogloeaceae, Spiculogloeales) wit
- `pensoft:zookeys.1232.141693` [UNINOMIAL] do BS, Guerrero RJ (2025) The ant genus «Nesomyrmex» Wheeler (Formicidae, Myrmicinae) from t
- `pensoft:mycokeys.126.174148` [UNINOMIAL] o N-N, Qiu N, Zhou M, Ma J (2025) Novel «Helicotubeufia» and Tubeufia (Tubeufiaceae, Tubeufiales
- `pensoft:zookeys.1257.165617` [UNINOMIAL] ed evidence reveals a new subspecies of «Amolops» Cope, 1865 (Anura, Ranidae) from northe
- `pensoft:bdj.12.e72511` [UNINOMIAL] Culicoides «Latreille» (Diptera, Ceratopogonidae) of Colombia:
- `pensoft:zookeys.1260.167263` [UNINOMIAL] s than half a corallite diameter apart. «Corallites» arrangement is thamnasteroid and series
- `pensoft:mycokeys.136.188385` [UNINOMIAL] re. Endoperidium papery and membranous. «Gleba» cottony when young, pulverulent when ma
- `pensoft:phytokeys.252.134081` [BINOMIAL] Ojha R, Rai S, Schneider H (2025) «Typhonium morangense» (Araceae), a new species from the tropi
- `pensoft:zookeys.1276.183701` [UNINOMIAL] Order «DERMAPTERA» de Geer, 1773
- `pensoft:zookeys.1283.169111` [UNINOMIAL] ry, taxonomy and keys to the species of «Mysidae» (Crustacea, Mysida) from marine caves o
- `pensoft:bdj.12.e115464` [UNINOMIAL] ries of spiders (Arachnida, Araneae) of «Macaronesia» III: dry habitats of Cabo Verde Archipe
- `pensoft:mycokeys.127.173361` [BINOMIAL]  and morphology reveal two new species, «Ophiocordyceps ramosa» sp. nov. (Ophiocordycipitaceae) and Lep
- `pensoft:zookeys.1264.140174` [BINOMIAL] r N, Dulias K, Wang J, Schwalb A (2025) «Heterocypris exodonta» sp. nov. (Ostracoda, Cyprididae), morph

## False negatives (30 of 1273 recorded)

- `pensoft:mycokeys.128.172807` [UNINOMIAL] six clades and one singleton within the «Hyphodermella» core; three of these correspond to spec
- `pensoft:phytokeys.275.192014` [UNINOMIAL]  J.L. Contr., Gleadovia Gamble & Prain, «Kopsiopsis» (Beck) Beck, Mannagettaea Harry Sm., Or
- `pensoft:zookeys.1251.145353` [UNINOMIAL] ake and transferred it to the new genus «Eversmannia.» The distribution of Eversmannia is main
- `pensoft:mycokeys.113.140932` [ABBREVIATED] X. guttulata sp. nov. is clustered with «X. pruinosum.» The morphology and multi-gene phylogene
- `pensoft:zookeys.1273.176856` [UNINOMIAL] s of the family Canuellidae Lang, 1944 («Copepoda», Canuelloida) from the Western Pacific 
- `pensoft:zookeys.1195.115889` [UNINOMIAL] Key to species of «Leptogenys» found in China based on the worker cast
- `pensoft:zookeys.1217.125780` [UNINOMIAL] es name is derived from the Latin word “«striola»”, referring to forewing with a dark bro
- `pensoft:bdj.12.e130385` [ABBREVIATED] cular markers has hampered the study of «D. mun»’s genetic diversity and population stru
- `pensoft:zookeys.1254.168429` [UNINOMIAL] «Amazoboea» Broad & Sääksjärvi gen. nov.
- `pensoft:bdj.12.e132915` [UNINOMIAL] «APOMYRMINAE»
- `pensoft:zookeys.1218.126535` [UNINOMIAL] Deer «mice», Peromyscus, thrive in diverse environm
- `pensoft:phytokeys.247.131353` [UNINOMIAL] s paper aims to typify the names of two «Cocconeis» taxa and to study their taxonomy and mo
- `pensoft:phytokeys.263.163351` [UNINOMIAL] miscellaneous notes on Dryopteris sect. «Splendentes» (Dryopteridaceae)
- `pensoft:mycokeys.141.209886` [UNINOMIAL] «Macadamia»-growing regions provide favorable habit
- `pensoft:zookeys.1293.199475` [UNINOMIAL] Although genetic divergence between the «zonata» population of E. dasytoides and E. qinl
- `pensoft:zookeys.1232.141693` [BINOMIAL] «Nesomyrmex konina» sp. nov.
- `pensoft:mycokeys.126.174148` [UNINOMIAL] Hainan Province, China. Thus, the genus «Helicotubeufia» now encapsulates four recognised specie
- `pensoft:zookeys.1257.165617` [ABBREVIATED]  a close phylogenetic relationship with «A. d. dafangensis» and is differentiated from the nominal 
- `pensoft:bdj.12.e72511` [UNINOMIAL] «Forcipomyia (Lasiohelea)» sp. Meta: Vistahermosa (n = 1); Guaviar
- `pensoft:zookeys.1260.167263` [UNINOMIAL] ce. Despite the ecological relevance of «Pavona» corals, a taxonomic revision of the gen
- `pensoft:mycokeys.136.188385` [UNINOMIAL] hylogeny and taxonomy of Lycoperdaceae: «Longipediperdon» gen. nov., a new genus from China based
- `pensoft:zookeys.1276.183701` [UNINOMIAL] ra form a monophyletic clade within the «Polyneoptera» (Jarvis et al. 2005; Wipfler et al. 202
- `pensoft:zookeys.1283.169111` [ABBREVIATED] Iliffe (1986a) with some reservation as «H.» (Olivaemysis) aff. guitarti from sponge
- `pensoft:bdj.12.e115464` [TRINOMIAL] e Archipelago, to which the citation of «Devade cf. indistincta» is added after a survey in the Island o
- `pensoft:mycokeys.127.173361` [UNINOMIAL]  hepiali sp. nov. (Cordycipitaceae), in «Hypocreales» from China
- `pensoft:zookeys.1264.140174` [TRINOMIAL]  to specimens previously referred to as «Heterocypris aff. salina» by Vences et al. (2024) and as Heterocy
- `pensoft:zookeys.1223.138220` [TRINOMIAL] = «Sandracottus rotundus ab. reductus» Zimmermann, 1926
- `pensoft:zookeys.1291.189894` [UNINOMIAL] «Gemenesis» Jiang & Bu gen. nov.
- `pensoft:zookeys.1293.201884` [UNINOMIAL] examination of many museum collections, «Nipponomyia» crane flies from the Korean Peninsula w
- `pensoft:zookeys.1204.123909` [UNINOMIAL] cans He & Chen, 2000 are transferred to «Paradelius (Sinadelius)» (comb. nov.). A key for determination o
