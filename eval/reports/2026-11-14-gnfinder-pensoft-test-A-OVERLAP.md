# gnfinder on pensoft / test (mode A, match OVERLAP)

Date: 2026-11-14

Documents: 200

Finder: gnfinder 1.4.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 36530 | 20113 | 2665 | 0.645 | 0.932 | 0.762 |
| &nbsp;&nbsp;block:ABSTRACT | 1390 | 440 | 170 | 0.760 | 0.891 | 0.820 |
| &nbsp;&nbsp;block:CAPTION | 2941 | 1180 | 287 | 0.714 | 0.911 | 0.800 |
| &nbsp;&nbsp;block:FOOTNOTE | 17 | 161 | 0 | 0.096 | 1.000 | 0.174 |
| &nbsp;&nbsp;block:HEADING | 1032 | 664 | 23 | 0.608 | 0.978 | 0.750 |
| &nbsp;&nbsp;block:PARAGRAPH | 20529 | 14013 | 1505 | 0.594 | 0.932 | 0.726 |
| &nbsp;&nbsp;block:TABLE_CELL | 10064 | 3490 | 674 | 0.743 | 0.937 | 0.829 |
| &nbsp;&nbsp;block:TITLE | 557 | 165 | 6 | 0.771 | 0.989 | 0.867 |
| &nbsp;&nbsp;kind:ABBREVIATED | 12425 | 23 | 1616 | 0.998 | 0.885 | 0.938 |
| &nbsp;&nbsp;kind:BINOMIAL | 12283 | 269 | 161 | 0.979 | 0.987 | 0.983 |
| &nbsp;&nbsp;kind:TRINOMIAL | 537 | 34 | 5 | 0.940 | 0.991 | 0.965 |
| &nbsp;&nbsp;kind:UNINOMIAL | 11285 | 19787 | 883 | 0.363 | 0.927 | 0.522 |
| &nbsp;&nbsp;lang:en | 31463 | 16981 | 2491 | 0.649 | 0.927 | 0.764 |
| &nbsp;&nbsp;lang:und | 5067 | 3132 | 174 | 0.618 | 0.967 | 0.754 |
| acts | 156 | 289 | 154 | 0.351 | 0.503 | 0.413 |
| contexts | 0 | 0 | 1612 | 0.000 | 0.000 | 0.000 |
| main subject | 289 | 810 | 163 | 0.263 | 0.639 | 0.373 |

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

## False negatives (30 of 2665 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] ased on these findings, we propose that «Zygaenoidea»sensu stricto should include only the ni
- `pensoft:bdj.12.e118010` [UNINOMIAL] «Animalia»
- `pensoft:zookeys.1196.117947` [UNINOMIAL] ia. Both new species are members of the «ornata» subclade within the beddomei clade. The
- `pensoft:mycokeys.110.134132` [BINOMIAL] Interesting mycoparasites and «Paradingleyomyces lepidopterorum» gen. et sp. nov. (Hypocreales, Polyceph
- `pensoft:bdj.14.e186195` [UNINOMIAL] he keywords “amphipod*”, “peracarid*”, “«crustacea»*”, “checklist" in combination with the 
- `pensoft:zookeys.1279.188050` [ABBREVIATED] ecies is recovered as a sister taxon to «S. tamchucensis» and genetically at least 6.9% and up to
- `pensoft:phytokeys.279.207098` [ABBREVIATED] zil (I. brasiliensis, I. contracta, and «I. tenuis»), whereas the occurrence of I. minutifl
- `pensoft:mycokeys.124.172775` [ABBREVIATED] stes, and soy sauce (Bennett 2009), and «A. niger» Tiegh. is used for fermentations of bai
- `pensoft:zookeys.1244.146861` [UNINOMIAL] onging to the families Pleurotaceae and «Plutaceae» (Disney 1991).
- `pensoft:zookeys.1236.145914` [UNINOMIAL] tly, a new genus and two new species of «Metrodolrinae» from China are described and illustrate
- `pensoft:zookeys.1225.125030` [ABBREVIATED] gare, which is considered comparable to «A. versicolor» (Csonka et al. 2013), was evaluated to 
- `pensoft:zookeys.1268.173464` [UNINOMIAL] ision of the freshwater mussel subtribe «Cristariina» (Bivalvia, Unionidae) in China: descrip
- `pensoft:zookeys.1214.132950` [UNINOMIAL] ription of Peltidium nayarit (Copepoda, «Harpaticoida», Peltidiidae) and a new record at Revil
- `pensoft:mycokeys.123.160960` [ABBREVIATED] n addition, a comprehensive analysis of «D. eschscholtzii»’s worldwide host distribution revealed 
- `pensoft:phytokeys.251.135126` [ABBREVIATED] ulina xingyiensis, P. malingheensis and «P. davidioides»AP. xingyiensis of Jingnan Town BP. xing
- `pensoft:mycokeys.133.187172` [ABBREVIATED] China. As a result, five novel species, «T. flavobritdaniae», T. foliatum, T. fulviclavum, T. liaoni
- `pensoft:mycokeys.107.122026` [ABBREVIATED] estalotiopsis aporosae-dioicaesp. nov., «P. nannuoensis»sp. nov. and P. rhaphiolepidissp. nov.
- `pensoft:zookeys.1275.178507` [ABBREVIATED]  from the Eastern Ghats. Members of the «C. albofasciatus»- C. deccanensis complex are distributed
- `pensoft:zookeys.1265.168108` [ABBREVIATED] rs revealed unique diagnostic traits in «P. alami»sp. nov., including short, thick cerci e
- `pensoft:zookeys.1232.142699` [ABBREVIATED] ) grouped together the COI sequences of «S. thienemanni»-like mites belonging to the following B
- `pensoft:zookeys.1258.168452` [ABBREVIATED] ules. Phylogenetic analyses showed that «M. trimarginalis»sp. nov. is placed within a clade contai
- `pensoft:mycokeys.128.178090` [BINOMIAL]  aquatica, Am. aureae, Am. bambusicola, «Am. cerosissimae», Am. guangxiensis, Am. hongheensis and 
- `pensoft:phytokeys.270.174879` [ABBREVIATED] as I. perforata, I. aciformis, I. zhui, «I. kuntsunii» and I. cordibracteata (Huang and Liu 20
- `pensoft:bdj.12.e116039` [UNINOMIAL] hat prey on specific organisms, such as «Cnidaria», exhibit morphological modifications in
- `pensoft:mycokeys.134.187344` [UNINOMIAL] A new genus («Durabilispora») and two new species (D. carpatica, Dom
- `pensoft:zookeys.1210.125611` [ABBREVIATED] l. (2006) reported the distribution of “«A. lyra» (Paiva, 1918)” in Yunnan. However, Zett
- `pensoft:phytokeys.280.206680` [BINOMIAL]  on coral rag with Combretum schumannii–«Gyrocarpus americanus», 28 May 1985, K. Gerhardt & M. Steiner 
- `pensoft:bdj.12.e119481` [ABBREVIATED] ca from Brunei Darussalam is described, «C. mataikanensis» Otani et al., sp. nov. This is the seco
- `pensoft:zookeys.1216.128080` [UNINOMIAL] «Paraterga» declivous; tergal setae very long, abou
- `pensoft:zookeys.1240.151021` [ABBREVIATED] nd. Nitocrella grandicaudissp. nov. and «N. thailandensis»sp. nov. show the peculiar shapes of the
