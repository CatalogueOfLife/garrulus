# gnfinder on pensoft / dev (mode A, match STRICT)

Date: 2026-11-14

Finder: gnfinder 1.4.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 24304 | 14183 | 2812 | 0.631 | 0.896 | 0.741 |
| &nbsp;&nbsp;block:ABSTRACT | 757 | 282 | 164 | 0.729 | 0.822 | 0.772 |
| &nbsp;&nbsp;block:CAPTION | 1646 | 1128 | 524 | 0.593 | 0.759 | 0.666 |
| &nbsp;&nbsp;block:FOOTNOTE | 62 | 111 | 7 | 0.358 | 0.899 | 0.512 |
| &nbsp;&nbsp;block:HEADING | 754 | 380 | 46 | 0.665 | 0.943 | 0.780 |
| &nbsp;&nbsp;block:PARAGRAPH | 13149 | 8799 | 1596 | 0.599 | 0.892 | 0.717 |
| &nbsp;&nbsp;block:TABLE_CELL | 7647 | 3417 | 467 | 0.691 | 0.942 | 0.797 |
| &nbsp;&nbsp;block:TITLE | 289 | 66 | 8 | 0.814 | 0.973 | 0.887 |
| &nbsp;&nbsp;kind:ABBREVIATED | 6708 | 179 | 1118 | 0.974 | 0.857 | 0.912 |
| &nbsp;&nbsp;kind:BINOMIAL | 6386 | 875 | 610 | 0.879 | 0.913 | 0.896 |
| &nbsp;&nbsp;kind:TRINOMIAL | 334 | 164 | 82 | 0.671 | 0.803 | 0.731 |
| &nbsp;&nbsp;kind:UNINOMIAL | 10876 | 12965 | 1002 | 0.456 | 0.916 | 0.609 |
| &nbsp;&nbsp;lang:en | 18500 | 11019 | 2467 | 0.627 | 0.882 | 0.733 |
| &nbsp;&nbsp;lang:und | 5804 | 3164 | 345 | 0.647 | 0.944 | 0.768 |
| acts | 80 | 186 | 83 | 0.301 | 0.491 | 0.373 |
| contexts | 0 | 0 | 1296 | 0.000 | 0.000 | 0.000 |
| main subject | 150 | 422 | 89 | 0.262 | 0.628 | 0.370 |

## False positives (30 of 5000 recorded)

- `pensoft:mycokeys.128.172807` [UNINOMIAL] Corresponding author: «Margarita» Dueñas (mduenas@rjb.csic.es)
- `pensoft:mycokeys.104.117872` [UNINOMIAL] is spp. nov. in Cainiaceae from Yunnan, «China»
- `pensoft:phytokeys.275.192014` [UNINOMIAL] Corresponding author: «Renata» Piwowarczyk (renata.piwowarczyk@ujk.edu
- `pensoft:zookeys.1251.145353` [UNINOMIAL] ger, 1871 (Lepidoptera, Uraniidae) from «China», with descriptions of three new species
- `pensoft:mycokeys.113.140932` [UNINOMIAL] omycota) in the Yunnan-Guizhou Plateau, «China»
- `pensoft:zookeys.1273.176856` [UNINOMIAL] Academic editor: «Maria» Cristina Bruno
- `pensoft:zookeys.1195.115889` [UNINOMIAL]  (Hymenoptera, Formicidae) from Hainan, «China», with a key to the known Chinese specie
- `pensoft:zookeys.1217.125780` [UNINOMIAL] ea, Delphacidae, Tropidocephalini) from «China», with an updated checklist and key to s
- `pensoft:bdj.12.e130385` [UNINOMIAL] derate levels of genetic diversity with «PIC» = 0.77, NA = 3.9, NE = 2.8, Ho = 0.56 a
- `pensoft:zookeys.1254.168429` [UNINOMIAL] ymenoptera, Ichneumonidae) from western «Amazonia»
- `pensoft:bdj.12.e132915` [UNINOMIAL] «Overview» of myrmecological studies and a checkli
- `pensoft:zookeys.1218.126535` [UNINOMIAL] A «Taxonomic» Odyssey: An annotated checklist of Pero
- `pensoft:phytokeys.247.131353` [UNINOMIAL] 24) Nomenclature and systematics of two «Cocconeis» species (Bacillariophyta) from Lake Bai
- `pensoft:phytokeys.263.163351` [UNINOMIAL] Academic editor: «Blanca» León
- `pensoft:mycokeys.141.209886` [UNINOMIAL] microfungi associated with Macadamia in «China» and Thailand and their lignocellulitic 
- `pensoft:zookeys.1293.199475` [UNINOMIAL] lattodea, Corydioidea, Corydiidae) from «China», with notes on female genitalia
- `pensoft:zookeys.1219.134970` [UNINOMIAL] The known range of the species includes «Taiwan», the Philippines, Micronesia, Indonesia
- `pensoft:mycokeys.123.161540` [UNINOMIAL] th description of four new species from «China»
- `pensoft:zookeys.1232.141693` [UNINOMIAL] biting the tropical dry forest (TDF) of «Colombia» are reviewed. Three new species of this
- `pensoft:mycokeys.126.174148` [UNINOMIAL] errestrial habitats in Hainan Province, «China»
- `pensoft:zookeys.1257.165617` [UNINOMIAL] ura, Ranidae) from northeastern Yunnan, «China»
- `pensoft:bdj.12.e72511` [UNINOMIAL] Culicoides «Latreille» (Diptera, Ceratopogonidae) of Colombia:
- `pensoft:zookeys.1260.167263` [UNINOMIAL] Corresponding author: «Francesca» Benzoni (francesca.benzoni@kaust.edu.sa
- `pensoft:mycokeys.136.188385` [UNINOMIAL] Academic editor: Yupeng «Ge»
- `pensoft:phytokeys.252.134081` [UNINOMIAL] ies from the tropical forest of Eastern «Nepal»
- `pensoft:zookeys.1276.183701` [UNINOMIAL] ae (Bormans, 1894), Hypurgus humeralis («Kirby», 1891), and Timomenus bicuspis (Stål, 1
- `pensoft:zookeys.1283.169111` [UNINOMIAL] s during the PACOTILLES-2 cruise to the «Lesser» Antilles, together with additional samp
- `pensoft:bdj.12.e115464` [UNINOMIAL] ries of spiders (Arachnida, Araneae) of «Macaronesia» III: dry habitats of Cabo Verde Archipe
- `pensoft:mycokeys.127.173361` [UNINOMIAL]  (Cordycipitaceae), in Hypocreales from «China»
- `pensoft:zookeys.1264.140174` [BINOMIAL] r N, Dulias K, Wang J, Schwalb A (2025) «Heterocypris exodonta» sp. nov. (Ostracoda, Cyprididae), morph

## False negatives (30 of 2812 recorded)

- `pensoft:mycokeys.128.172807` [UNINOMIAL] six clades and one singleton within the «Hyphodermella» core; three of these correspond to spec
- `pensoft:mycokeys.104.117872` [ABBREVIATED] comprise a novel genus Paramphibambusa («P. bambusicola»sp. nov.) and two new species, Arecophil
- `pensoft:phytokeys.275.192014` [UNINOMIAL] uthern Africa, Madagascar, and Comoros. «Hyobanche» has eight species and is endemic to sou
- `pensoft:zookeys.1251.145353` [ABBREVIATED] e species are described as new species: «E. atromarginata»sp. nov., E. spiralissp. nov., and E. zh
- `pensoft:mycokeys.113.140932` [BINOMIAL]  bambusae (Porotheleaceae, Agaricales), «Xenasma bisterigmatae», and X. guttulata (Xenasmataceae, Russu
- `pensoft:zookeys.1273.176856` [BINOMIAL] ical descriptions and illustrations for «Elanella jejuensis»sp. nov., Brianola coreanasp. nov., and 
- `pensoft:zookeys.1195.115889` [BINOMIAL] erine ants from Hainan Province, China, «Leptogenys hainanensis»sp. nov. and L. zhouisp. nov., are delin
- `pensoft:zookeys.1217.125780` [ABBREVIATED] t al. (1980) described two new species, «B. fascia» Huang & Tian, 1980 and B. nigromarginat
- `pensoft:bdj.12.e130385` [ABBREVIATED] cular markers has hampered the study of «D. mun»’s genetic diversity and population stru
- `pensoft:zookeys.1254.168429` [UNINOMIAL] «Amazoboea», a new genus of Darwin wasps (Hymenopte
- `pensoft:bdj.12.e132915` [TRINOMIAL] «Dorylus kohli indocili»s Santschi, 1933
- `pensoft:zookeys.1218.126535` [UNINOMIAL] Deer «mice», Peromyscus, thrive in diverse environm
- `pensoft:phytokeys.247.131353` [TRINOMIAL] The variety «Cocconeis placentula Ehrenberg var. baicalensis» B.V. Skvortzov et K.I. Meyer was descri
- `pensoft:phytokeys.263.163351` [UNINOMIAL] xonomically (PPG I 2016). The broadened «Dryopteris» has been shown to be a monophyletic gen
- `pensoft:mycokeys.141.209886` [UNINOMIAL] «Macadamia»-growing regions provide favorable habit
- `pensoft:zookeys.1293.199475` [UNINOMIAL] Although genetic divergence between the «zonata» population of E. dasytoides and E. qinl
- `pensoft:zookeys.1219.134970` [ABBREVIATED] ands. Based on these published reports, «P. moultoni» has been considered as widely distribut
- `pensoft:mycokeys.123.161540` [ABBREVIATED] fied as four new species of Phyllozyma: «P. aucubae»sp. nov. (holotype CICC 33627T), P. came
- `pensoft:zookeys.1232.141693` [BINOMIAL] iewed. Three new species of this genus, «Nesomyrmex iku»sp. nov., Nesomyrmex koninasp. nov., and
- `pensoft:mycokeys.126.174148` [BINOMIAL] cal characteristics, two novel species, «Helicotubeufia qixianlingensis» and Tubeufia diaoluoshanensis, are intr
- `pensoft:zookeys.1257.165617` [TRINOMIAL]  Ke, Cheng, & Wang, 2024, designated as «Amolops dafangensis wumengmontis»ssp. nov., is described from Mt. Wumeng,
- `pensoft:bdj.12.e72511` [UNINOMIAL] ca (Suárez et al. 2008). The species of «Culicoides» can also transmit to humans the nematod
- `pensoft:zookeys.1260.167263` [BINOMIAL] heir taxonomy has long been overlooked. «Pavona giannii»sp. nov. is here described based on newl
- `pensoft:mycokeys.136.188385` [UNINOMIAL] hylogeny and taxonomy of Lycoperdaceae: «Longipediperdon» gen. nov., a new genus from China based
- `pensoft:phytokeys.252.134081` [ABBREVIATED] key differences presented in diagnosis, «T. morangense» can be distinguished from T. inopinatum
- `pensoft:zookeys.1276.183701` [BINOMIAL] Kirby (1903: 61): Singapore (as «Diplatys rideleyi»)
- `pensoft:zookeys.1283.169111` [UNINOMIAL] nclusion of the genera Bermudamysis and «Platyops» based on segmental numbers and the non-
- `pensoft:bdj.12.e115464` [TRINOMIAL] e Archipelago, to which the citation of «Devade cf. indistincta» is added after a survey in the Island o
- `pensoft:mycokeys.127.173361` [BINOMIAL] The phylogenetic analysis revealed that «Ophiocordyceps ramosa»sp. nov. is closely related to Hirsutell
- `pensoft:zookeys.1264.140174` [ABBREVIATED] ruens exhibit a turbine shape, while in «H. salina» they display a simple aperture. Phyloge
