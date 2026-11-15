# gnfinder on pensoft / test (mode A, match STRICT)

Date: 2026-11-15

Documents: 200

Finder: gnfinder 1.4.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

- The JSON keeps the first 500 error samples of each kind: 500 of 5000 recorded false positives, 500 of 3873 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 35322 | 21401 | 3873 | 0.623 | 0.901 | 0.737 |
| &nbsp;&nbsp;block:ABSTRACT | 1387 | 499 | 173 | 0.735 | 0.889 | 0.805 |
| &nbsp;&nbsp;block:CAPTION | 2812 | 1348 | 416 | 0.676 | 0.871 | 0.761 |
| &nbsp;&nbsp;block:FOOTNOTE | 17 | 161 | 0 | 0.096 | 1.000 | 0.174 |
| &nbsp;&nbsp;block:HEADING | 964 | 732 | 91 | 0.568 | 0.914 | 0.701 |
| &nbsp;&nbsp;block:PARAGRAPH | 19623 | 14897 | 2411 | 0.568 | 0.891 | 0.694 |
| &nbsp;&nbsp;block:TABLE_CELL | 9970 | 3591 | 768 | 0.735 | 0.928 | 0.821 |
| &nbsp;&nbsp;block:TITLE | 549 | 173 | 14 | 0.760 | 0.975 | 0.854 |
| &nbsp;&nbsp;kind:ABBREVIATED | 12128 | 343 | 1913 | 0.972 | 0.864 | 0.915 |
| &nbsp;&nbsp;kind:BINOMIAL | 11794 | 481 | 650 | 0.961 | 0.948 | 0.954 |
| &nbsp;&nbsp;kind:TRINOMIAL | 300 | 158 | 242 | 0.655 | 0.554 | 0.600 |
| &nbsp;&nbsp;kind:UNINOMIAL | 11100 | 20419 | 1068 | 0.352 | 0.912 | 0.508 |
| &nbsp;&nbsp;lang:en | 30334 | 18185 | 3620 | 0.625 | 0.893 | 0.736 |
| &nbsp;&nbsp;lang:und | 4988 | 3216 | 253 | 0.608 | 0.952 | 0.742 |
| acts | 168 | 270 | 143 | 0.384 | 0.540 | 0.449 |
| contexts | 0 | 0 | 1612 | 0.000 | 0.000 | 0.000 |
| main subject | 299 | 783 | 154 | 0.276 | 0.660 | 0.390 |

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 18800 | 31639 | 0.594 |
| &nbsp;&nbsp;kind:ABBREVIATED | 0 | 12128 | 0.000 |
| &nbsp;&nbsp;kind:BINOMIAL | 11113 | 11725 | 0.948 |
| &nbsp;&nbsp;kind:TRINOMIAL | 284 | 294 | 0.966 |
| &nbsp;&nbsp;kind:UNINOMIAL | 7403 | 7492 | 0.988 |

## False positives (30 of 5000 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] Corresponding author: Aihui «Yin» (keyanlaodong@163.com)
- `pensoft:bdj.14.e161845` [UNINOMIAL] g authors: Jeremías Benjamín Norambuena «Molina» (jenorambuena@udec.cl), Laura Tavera Ma
- `pensoft:bdj.12.e118010` [UNINOMIAL] The «InBIO» Barcoding Initiative Database: DNA barc
- `pensoft:bdj.14.e191590` [UNINOMIAL]  a resident Golden jackal (Canis aureus «Linnaeus», 1758) family group
- `pensoft:zookeys.1196.117947` [UNINOMIAL] of the south Western Ghats, Tamil Nadu, «India». Both new species are members of the or
- `pensoft:mycokeys.110.134132` [UNINOMIAL] ephalomycetaceae) from Yunnan Province, «China»
- `pensoft:bdj.14.e186195` [UNINOMIAL] «Amphipod» species in European and nearby seas
- `pensoft:zookeys.1279.188050` [UNINOMIAL] Pham AV, Pham CT, Ong AV, Le MD, «Ziegler» T, Nguyen TQ (2026) Sphenomorphus puhoa
- `pensoft:phytokeys.279.207098` [UNINOMIAL]  Imperata (Poaceae, Andropogoneae) from «Brazil» and their taxonomic significance
- `pensoft:bdj.12.e134521` [UNINOMIAL] Revisiting the taxonomy of «Korean» Ischnochiton species (Polyplacophora, I
- `pensoft:mycokeys.124.172775` [UNINOMIAL] nd Nigri from terrestrial ecosystems of «China» (Eurotiales, Aspergillaceae)
- `pensoft:bdj.14.e180342` [UNINOMIAL] nctional Traits Dataset for the Endemic «Flora» of Greece: morphology, ecology and ecos
- `pensoft:zookeys.1244.146861` [UNINOMIAL] Corresponding author: «Laura» Mlynárová (laura.mlynarova@smail.unipo.
- `pensoft:mycokeys.125.168474` [UNINOMIAL] diversity within Mortierellomycota from «China» X: Three new species in Linnemannia and
- `pensoft:zookeys.1236.145914` [UNINOMIAL]  two new species, and new synonyms from «China»
- `pensoft:zookeys.1225.125030` [UNINOMIAL] ering behaviour of terrestrial isopods («Isopoda», Oniscidea): a field experiment. In: Tu
- `pensoft:zookeys.1268.173464` [UNINOMIAL] «Taxonomic» revision of the freshwater mussel subtr
- `pensoft:zookeys.1214.132950` [UNINOMIAL] ew record at Revillagigedo Archipelago, «Mexico»
- `pensoft:mycokeys.123.160960` [UNINOMIAL] eae, Xylariales) from Guizhou Province, «China»
- `pensoft:phytokeys.251.135126` [UNINOMIAL] he karst landforms of Guizhou Province, «China»
- `pensoft:mycokeys.133.187172` [UNINOMIAL]  species isolated from decaying wood in «China»
- `pensoft:mycokeys.107.122026` [UNINOMIAL] igene phylogeny from Hainan and Yunnan, «China»
- `pensoft:zookeys.1275.178507` [UNINOMIAL] nectivity between the Eastern Ghats and «Nepal» revealed by a new species of the Cyrtod
- `pensoft:phytokeys.251.137015` [UNINOMIAL] (Ericaceae), a new species from Yunnan, «China»
- `pensoft:zookeys.1265.168108` [UNINOMIAL] copy compared to congeners P. lacustris «Karsch», 1890 and P. elpidius Ris, 1921. DNA ba
- `pensoft:phytokeys.257.153202` [UNINOMIAL] «Taxonomic» clarification of Silene dawoensis (Cary
- `pensoft:zookeys.1232.142699` [UNINOMIAL] Corresponding author: «Vladimir» Pešić (vladopesic@gmail.com)
- `pensoft:zookeys.1258.168452` [UNINOMIAL] ik Min (mingisik@inha.ac.kr); Kyung-Min «Park» (kmpark315@korea.kr)
- `pensoft:mycokeys.128.178090` [UNINOMIAL] «Taxonomic» novelties and phylogenetic insights int
- `pensoft:phytokeys.270.174879` [UNINOMIAL] aminaceae): a new species from Sichuan, «China»

## False negatives (30 of 3873 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] rysian families of uncertain placement («Heliocosmidae» and Millieriidae), while three glossata
- `pensoft:bdj.12.e118010` [UNINOMIAL] f 420 specimens covering all the eleven «Orthoptera» families occurring in Portugal. Specime
- `pensoft:zookeys.1196.117947` [UNINOMIAL] ia. Both new species are members of the «ornata» subclade within the beddomei clade. The
- `pensoft:mycokeys.110.134132` [BINOMIAL] Interesting mycoparasites and «Paradingleyomyces lepidopterorum» gen. et sp. nov. (Hypocreales, Polyceph
- `pensoft:bdj.14.e186195` [UNINOMIAL] he keywords “amphipod*”, “peracarid*”, “«crustacea»*”, “checklist" in combination with the 
- `pensoft:zookeys.1279.188050` [ABBREVIATED] ecies is recovered as a sister taxon to «S. tamchucensis» and genetically at least 6.9% and up to
- `pensoft:phytokeys.279.207098` [ABBREVIATED] zil (I. brasiliensis, I. contracta, and «I. tenuis»), whereas the occurrence of I. minutifl
- `pensoft:bdj.12.e134521` [ABBREVIATED] ture of its perinotum scales (Table 2). «I. boninensis» has small perinotum scales sculpted wit
- `pensoft:mycokeys.124.172775` [ABBREVIATED] stes, and soy sauce (Bennett 2009), and «A. niger» Tiegh. is used for fermentations of bai
- `pensoft:zookeys.1244.146861` [UNINOMIAL] onging to the families Pleurotaceae and «Plutaceae» (Disney 1991).
- `pensoft:mycokeys.125.168474` [UNINOMIAL] elihood phylogenetic tree for the genus «Linnemannia» based on combined ITS, LSU, SSU, RPB1, 
- `pensoft:zookeys.1236.145914` [UNINOMIAL] tly, a new genus and two new species of «Metrodolrinae» from China are described and illustrate
- `pensoft:zookeys.1225.125030` [ABBREVIATED] gare, which is considered comparable to «A. versicolor» (Csonka et al. 2013), was evaluated to 
- `pensoft:zookeys.1268.173464` [UNINOMIAL] ision of the freshwater mussel subtribe «Cristariina» (Bivalvia, Unionidae) in China: descrip
- `pensoft:zookeys.1214.132950` [UNINOMIAL] ription of Peltidium nayarit (Copepoda, «Harpaticoida», Peltidiidae) and a new record at Revil
- `pensoft:mycokeys.123.160960` [ABBREVIATED] n addition, a comprehensive analysis of «D. eschscholtzii»’s worldwide host distribution revealed 
- `pensoft:phytokeys.251.135126` [BINOMIAL] cs between the three (Table 2, Fig. 4). «Primulina xingyiensis» can be distinguished from P. davidioide
- `pensoft:mycokeys.133.187172` [ABBREVIATED] China. As a result, five novel species, «T. flavobritdaniae», T. foliatum, T. fulviclavum, T. liaoni
- `pensoft:mycokeys.107.122026` [UNINOMIAL] ungorum.org/, accessed on 26 Jun 2024). «Pestalotioid» fungi are a cosmopolitan group of fungi
- `pensoft:zookeys.1275.178507` [UNINOMIAL] s of ground-dwelling gecko of the genus «Cyrtodactylus (Geckoella)» is described from the low elevation Ter
- `pensoft:phytokeys.251.137015` [TRINOMIAL] Agapetes hongheensis is assigned to «Agapetes ser. Longifiles» Airy Shaw (1935: 25), which is characte
- `pensoft:zookeys.1265.168108` [UNINOMIAL] raits with P. elpidius Ris, 1921 as the «elpidius»-group. This informal grouping does not 
- `pensoft:phytokeys.257.153202` [ABBREVIATED] g (1996) classified this species within «S. sect. Longitubulosae» C. L. Tang, which according to Jafari e
- `pensoft:zookeys.1232.142699` [ABBREVIATED] cular analysis match the description of «P. vietsi.» The latter species, originally describe
- `pensoft:zookeys.1258.168452` [ABBREVIATED] ules. Phylogenetic analyses showed that «M. trimarginalis» sp. nov. is placed within a clade conta
- `pensoft:mycokeys.128.178090` [BINOMIAL]  aquatica, Am. aureae, Am. bambusicola, «Am. cerosissimae», Am. guangxiensis, Am. hongheensis and 
- `pensoft:phytokeys.270.174879` [ABBREVIATED] as I. perforata, I. aciformis, I. zhui, «I. kuntsunii» and I. cordibracteata (Huang and Liu 20
- `pensoft:bdj.12.e116039` [ABBREVIATED] sity of about 14,640 individuals m-2 of «P. ophiocephala» can consume a massive quantity of amphi
- `pensoft:mycokeys.134.187344` [UNINOMIAL] A new genus («Durabilispora») and two new species (D. carpatica, Dom
- `pensoft:zookeys.1210.125611` [ABBREVIATED] l. (2006) reported the distribution of “«A. lyra» (Paiva, 1918)” in Yunnan. However, Zett
