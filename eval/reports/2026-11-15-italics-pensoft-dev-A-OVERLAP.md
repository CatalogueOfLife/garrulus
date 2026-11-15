# italics on pensoft / dev (mode A, match OVERLAP)

Date: 2026-11-15

Documents: 100

Finder: italics 0.1, config `italics-1-3-words`

- The JSON keeps the first 500 error samples of each kind: 500 of 2389 recorded false positives, 500 of 5000 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 19490 | 2389 | 7626 | 0.891 | 0.719 | 0.796 |
| &nbsp;&nbsp;block:ABSTRACT | 706 | 14 | 215 | 0.981 | 0.767 | 0.860 |
| &nbsp;&nbsp;block:CAPTION | 1789 | 61 | 381 | 0.967 | 0.824 | 0.890 |
| &nbsp;&nbsp;block:FOOTNOTE | 66 | 0 | 3 | 1.000 | 0.957 | 0.978 |
| &nbsp;&nbsp;block:HEADING | 98 | 1 | 702 | 0.990 | 0.123 | 0.218 |
| &nbsp;&nbsp;block:PARAGRAPH | 11006 | 1903 | 3739 | 0.853 | 0.746 | 0.796 |
| &nbsp;&nbsp;block:TABLE_CELL | 5724 | 410 | 2390 | 0.933 | 0.705 | 0.803 |
| &nbsp;&nbsp;block:TITLE | 101 | 0 | 196 | 1.000 | 0.340 | 0.508 |
| &nbsp;&nbsp;kind:ABBREVIATED | 7571 | 44 | 255 | 0.994 | 0.967 | 0.981 |
| &nbsp;&nbsp;kind:BINOMIAL | 6455 | 674 | 541 | 0.905 | 0.923 | 0.914 |
| &nbsp;&nbsp;kind:TRINOMIAL | 258 | 183 | 158 | 0.585 | 0.620 | 0.602 |
| &nbsp;&nbsp;kind:UNINOMIAL | 5206 | 1488 | 6672 | 0.778 | 0.438 | 0.561 |
| &nbsp;&nbsp;lang:en | 15409 | 1895 | 5558 | 0.890 | 0.735 | 0.805 |
| &nbsp;&nbsp;lang:und | 4081 | 494 | 2068 | 0.892 | 0.664 | 0.761 |
| acts | 0 | 0 | 165 | 0.000 | 0.000 | 0.000 |
| contexts | 0 | 0 | 1296 | 0.000 | 0.000 | 0.000 |
| main subject | 91 | 8 | 150 | 0.919 | 0.378 | 0.535 |

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 11796 | 19489 | 0.605 |
| &nbsp;&nbsp;kind:ABBREVIATED | 0 | 7571 | 0.000 |
| &nbsp;&nbsp;kind:BINOMIAL | 6403 | 6455 | 0.992 |
| &nbsp;&nbsp;kind:TRINOMIAL | 249 | 258 | 0.965 |
| &nbsp;&nbsp;kind:UNINOMIAL | 5144 | 5205 | 0.988 |

## False positives (30 of 2389 recorded)

- `pensoft:mycokeys.128.172807` [UNINOMIAL]  phylogenetic analyses of the ITS, LSU, «RPB1», and RPB2 regions. The results of the c
- `pensoft:mycokeys.104.117872` [BINOMIAL] , Al-Rejaie S, Tanaka K, Dai D-Q (2024) «Paramphibambusa bambusicola» gen. et. sp. nov., Arecophila xishuangb
- `pensoft:phytokeys.275.192014` [UNINOMIAL] y two holoparasitic plants were listed: «Mystropetalum» from Balanophoraceae (Lengyel et al. 20
- `pensoft:zookeys.1251.145353` [UNINOMIAL]  (2025) A taxonomic review of the genus «Eversmannia» Staudinger, 1871 (Lepidoptera, Uraniida
- `pensoft:mycokeys.113.140932` [UNINOMIAL] «Wumengshanensis» (Lat.) refers to the locality “Wumengsh
- `pensoft:zookeys.1273.176856` [UNINOMIAL] «Holotype» • 1♀ (MABIK CR00259503) dissected on fi
- `pensoft:zookeys.1195.115889` [UNINOMIAL] (2024) Two new ant species of the genus «Leptogenys» (Hymenoptera, Formicidae) from Hainan, 
- `pensoft:zookeys.1217.125780` [UNINOMIAL] S (2024) Three new species of the genus «Bambusiphaga» Huang & Ding, 1979 (Hemiptera, Fulgoroi
- `pensoft:bdj.12.e130385` [BINOMIAL] ,588,615,700 base pairs were generated. «De novo» assembly indicated that 91,134 unigenes
- `pensoft:zookeys.1254.168429` [UNINOMIAL] inen A, Österman EM, Kaunisto KM (2025) «Amazoboea», a new genus of Darwin wasps (Hymenopte
- `pensoft:zookeys.1218.126535` [UNINOMIAL] omic Odyssey: An annotated checklist of «Peromyscus» (Cricetidae, Rodentia) in Honduras. Zoo
- `pensoft:phytokeys.247.131353` [UNINOMIAL] 24) Nomenclature and systematics of two «Cocconeis» species (Bacillariophyta) from Lake Bai
- `pensoft:phytokeys.263.163351` [BINOMIAL] X-J, Zhou L-X, Zuo Z-Y, Dong S-Y (2025) «Dryopteris pycnolepis», a new fern species from northern Vietn
- `pensoft:mycokeys.141.209886` [UNINOMIAL] .000000; proportion of invariable sites «I» = 0.642117; gamma distribution shape pa
- `pensoft:zookeys.1293.199475` [UNINOMIAL] nomic approach combining morphology and «COI» sequences, with ASAP and GMYC methods: 
- `pensoft:zookeys.1219.134970` [BINOMIAL] o K, Motomura H (2024) Redescription of «Parascorpaena moultoni» (Whitley, 1961) (Actinopterygii, Scorpa
- `pensoft:mycokeys.123.161540` [UNINOMIAL] d taxa. Based on multi-locus (ITS, LSU, «TEF1», and RBP1) and single-locus (ITS) phylo
- `pensoft:zookeys.1232.141693` [UNINOMIAL] do BS, Guerrero RJ (2025) The ant genus «Nesomyrmex» Wheeler (Formicidae, Myrmicinae) from t
- `pensoft:mycokeys.126.174148` [UNINOMIAL] «Saprobic» on decaying wood in a terrestrial habit
- `pensoft:zookeys.1257.165617` [UNINOMIAL] ylogenetic analysis of the 16S rRNA and «CO1» genes, as well as morphological examina
- `pensoft:zookeys.1260.167263` [BINOMIAL] oni F (2025) A new reef-dwelling coral, «Pavona giannii» sp. nov. (Scleractinia, Agariciidae), w
- `pensoft:mycokeys.136.188385` [UNINOMIAL] nies inferred from ITS, nLSU, rpb2, and «TEF1»-α datasets have provided a robust tempo
- `pensoft:phytokeys.252.134081` [BINOMIAL] Ojha R, Rai S, Schneider H (2025) «Typhonium morangense» (Araceae), a new species from the tropi
- `pensoft:zookeys.1276.183701` [ABBREVIATED] Burr (1902: 479) as Anisolabis stali: “«A. Ståli» Dohrn. – Singapore (Biró)”
- `pensoft:zookeys.1283.169111` [UNINOMIAL] «Cephalothorax» (Fig. 13). Basal segment of the 2- to 4
- `pensoft:bdj.12.e115464` [BINOMIAL] m, Sonchus daltonii, Tolpis farinulosa, «Umbilicus schmidtii»). Other mountainous regions, such as Pi
- `pensoft:mycokeys.127.173361` [UNINOMIAL] of the former Verticillium Nees section «Albo-erecta», established the genus Leptobacillium. 
- `pensoft:zookeys.1264.140174` [UNINOMIAL] «Cc»
- `pensoft:phytokeys.252.135155` [BINOMIAL] Wang T, Yuan Y, Zhou T-H, Gao Y (2025) «Lilium huanglongense» (Liliaceae): a newly-discovered species
- `pensoft:zookeys.1223.138220` [UNINOMIAL] al and Australasian diving beetle genus «Sandracottus» Sharp, 1882 (Coleoptera, Dytiscidae, Dy

## False negatives (30 of 5000 recorded)

- `pensoft:mycokeys.128.172807` [UNINOMIAL] Revisiting the genus Hyphodermella («Basidiomycota», Polyporales) with descriptions of thre
- `pensoft:mycokeys.104.117872` [UNINOMIAL] ensis and A. zhaotongensis spp. nov. in «Cainiaceae» from Yunnan, China
- `pensoft:phytokeys.275.192014` [UNINOMIAL] ionary trajectories among holoparasitic «Orobanchaceae» and their animal associates: a global p
- `pensoft:zookeys.1251.145353` [UNINOMIAL] the genus Eversmannia Staudinger, 1871 («Lepidoptera», Uraniidae) from China, with descriptio
- `pensoft:mycokeys.113.140932` [UNINOMIAL] tic analyses revealed four new species («Basidiomycota») in the Yunnan-Guizhou Plateau, China
- `pensoft:zookeys.1273.176856` [UNINOMIAL] Three new species of the family «Canuellidae» Lang, 1944 (Copepoda, Canuelloida) from
- `pensoft:zookeys.1195.115889` [UNINOMIAL] ew ant species of the genus Leptogenys («Hymenoptera», Formicidae) from Hainan, China, with a
- `pensoft:zookeys.1217.125780` [UNINOMIAL]  genus Bambusiphaga Huang & Ding, 1979 («Hemiptera», Fulgoroidea, Delphacidae, Tropidocepha
- `pensoft:bdj.12.e130385` [UNINOMIAL] is of Diospyros mun A.Chev. ex Lecomte («Ebenaceae») based on EST-SSR markers derived from 
- `pensoft:zookeys.1254.168429` [UNINOMIAL] Amazoboea, a new genus of Darwin wasps («Hymenoptera», Ichneumonidae) from western Amazonia
- `pensoft:bdj.12.e132915` [UNINOMIAL] al studies and a checklist of the ants («Hymenoptera», Formicidae) of the Democratic Republic
- `pensoft:zookeys.1218.126535` [UNINOMIAL] : An annotated checklist of Peromyscus («Cricetidae», Rodentia) in Honduras
- `pensoft:phytokeys.247.131353` [UNINOMIAL] d systematics of two Cocconeis species («Bacillariophyta») from Lake Baikal: Cocconeis baicalensi
- `pensoft:phytokeys.263.163351` [UNINOMIAL]  notes on Dryopteris sect. Splendentes («Dryopteridaceae»)
- `pensoft:mycokeys.141.209886` [UNINOMIAL] «Macadamia»-growing regions provide favorable habit
- `pensoft:zookeys.1293.199475` [UNINOMIAL]  new species of Eucorydia Hebard, 1929 («Blattodea», Corydioidea, Corydiidae) from China, w
- `pensoft:zookeys.1219.134970` [UNINOMIAL] Parascorpaena moultoni (Whitley, 1961) («Actinopterygii», Scorpaenidae), with new distribution r
- `pensoft:mycokeys.123.161540` [UNINOMIAL] Phylogeny of Phyllozyma («Spiculogloeaceae», Spiculogloeales) with description of f
- `pensoft:zookeys.1232.141693` [UNINOMIAL] The ant genus Nesomyrmex Wheeler («Formicidae», Myrmicinae) from the threatened Colomb
- `pensoft:mycokeys.126.174148` [UNINOMIAL] Novel Helicotubeufia and Tubeufia («Tubeufiaceae», Tubeufiales) species from terrestrial 
- `pensoft:zookeys.1257.165617` [UNINOMIAL] a new subspecies of Amolops Cope, 1865 («Anura», Ranidae) from northeastern Yunnan, Chi
- `pensoft:bdj.12.e72511` [UNINOMIAL] Culicoides Latreille («Diptera», Ceratopogonidae) of Colombia: records 
- `pensoft:zookeys.1260.167263` [UNINOMIAL] welling coral, Pavona giannii sp. nov. («Scleractinia», Agariciidae), with an overview of the 
- `pensoft:mycokeys.136.188385` [UNINOMIAL] «Fungi», encompassing mushrooms, molds, lichens
- `pensoft:phytokeys.252.134081` [UNINOMIAL] Typhonium morangense («Araceae»), a new species from the tropical fores
- `pensoft:zookeys.1276.183701` [UNINOMIAL] Annotated checklist of «Dermaptera» and Zoraptera (Insecta) of Singapore, w
- `pensoft:zookeys.1283.169111` [UNINOMIAL] ry, taxonomy and keys to the species of «Mysidae» (Crustacea, Mysida) from marine caves o
- `pensoft:bdj.12.e115464` [UNINOMIAL] Standardised inventories of spiders («Arachnida», Araneae) of Macaronesia III: dry habit
- `pensoft:mycokeys.127.173361` [UNINOMIAL] pecies, Ophiocordyceps ramosa sp. nov. («Ophiocordycipitaceae») and Leptobacillium hepiali sp. nov. (C
- `pensoft:zookeys.1264.140174` [UNINOMIAL] Heterocypris exodonta sp. nov. («Ostracoda», Cyprididae), morphological and molecul
