# gnfinder on pensoft / dev (mode A, match OVERLAP)

Date: 2026-11-15

Documents: 100

Finder: gnfinder 1.4.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

- The JSON keeps the first 500 error samples of each kind: 500 of 5000 recorded false positives, 500 of 1998 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 25118 | 13213 | 1998 | 0.655 | 0.926 | 0.768 |
| &nbsp;&nbsp;block:ABSTRACT | 858 | 220 | 63 | 0.796 | 0.932 | 0.858 |
| &nbsp;&nbsp;block:CAPTION | 1790 | 778 | 380 | 0.697 | 0.825 | 0.756 |
| &nbsp;&nbsp;block:FOOTNOTE | 69 | 106 | 0 | 0.394 | 1.000 | 0.566 |
| &nbsp;&nbsp;block:HEADING | 782 | 352 | 18 | 0.690 | 0.978 | 0.809 |
| &nbsp;&nbsp;block:PARAGRAPH | 13611 | 8345 | 1134 | 0.620 | 0.923 | 0.742 |
| &nbsp;&nbsp;block:TABLE_CELL | 7718 | 3347 | 396 | 0.698 | 0.951 | 0.805 |
| &nbsp;&nbsp;block:TITLE | 290 | 65 | 7 | 0.817 | 0.976 | 0.890 |
| &nbsp;&nbsp;kind:ABBREVIATED | 6941 | 7 | 885 | 0.999 | 0.887 | 0.940 |
| &nbsp;&nbsp;kind:BINOMIAL | 6755 | 528 | 241 | 0.928 | 0.966 | 0.946 |
| &nbsp;&nbsp;kind:TRINOMIAL | 406 | 29 | 10 | 0.933 | 0.976 | 0.954 |
| &nbsp;&nbsp;kind:UNINOMIAL | 11016 | 12649 | 862 | 0.465 | 0.927 | 0.620 |
| &nbsp;&nbsp;lang:en | 19248 | 10114 | 1719 | 0.656 | 0.918 | 0.765 |
| &nbsp;&nbsp;lang:und | 5870 | 3099 | 279 | 0.654 | 0.955 | 0.777 |
| acts | 86 | 203 | 79 | 0.298 | 0.521 | 0.379 |
| contexts | 0 | 0 | 1296 | 0.000 | 0.000 | 0.000 |
| main subject | 155 | 433 | 86 | 0.264 | 0.643 | 0.374 |

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 13106 | 20720 | 0.633 |
| &nbsp;&nbsp;kind:ABBREVIATED | 0 | 6941 | 0.000 |
| &nbsp;&nbsp;kind:BINOMIAL | 6299 | 6725 | 0.937 |
| &nbsp;&nbsp;kind:TRINOMIAL | 334 | 406 | 0.823 |
| &nbsp;&nbsp;kind:UNINOMIAL | 6473 | 6648 | 0.974 |

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

## False negatives (30 of 1998 recorded)

- `pensoft:mycokeys.128.172807` [ABBREVIATED] e sp. nov., H. ryvardenii sp. nov., and «H. salcedoae» sp. nov., which are described in this p
- `pensoft:phytokeys.275.192014` [ABBREVIATED]  Orobanche caryophyllacea (H’ = 3.606), «O. alba» (H' = 3.370), O. centaurina (H' = 3.318
- `pensoft:zookeys.1251.145353` [ABBREVIATED] ata sp. nov., E. spiralis sp. nov., and «E. zhangorum» sp. nov. New combinations are proposed 
- `pensoft:mycokeys.113.140932` [ABBREVIATED] ested into the genus Xenasma. The taxon «X. bisterigmatae» has a close relationship with X. rimico
- `pensoft:zookeys.1273.176856` [ABBREVIATED] dified female caudal seta II that place «S. picrca» sp. nov. within the longipes-group. Mol
- `pensoft:zookeys.1217.125780` [ABBREVIATED] t al. (1980) described two new species, «B. fascia» Huang & Tian, 1980 and B. nigromarginat
- `pensoft:bdj.12.e130385` [ABBREVIATED] cular markers has hampered the study of «D. mun»’s genetic diversity and population stru
- `pensoft:zookeys.1254.168429` [UNINOMIAL] «Amazoboea», a new genus of Darwin wasps (Hymenopte
- `pensoft:zookeys.1218.126535` [UNINOMIAL] Deer «mice», Peromyscus, thrive in diverse environm
- `pensoft:phytokeys.247.131353` [UNINOMIAL] ). By analogy with Ex. 11, the epithets «baicalensis» and baikalensis should be treated as ho
- `pensoft:phytokeys.263.163351` [UNINOMIAL] xonomically (PPG I 2016). The broadened «Dryopteris» has been shown to be a monophyletic gen
- `pensoft:mycokeys.141.209886` [UNINOMIAL] «Macadamia»-growing regions provide favorable habit
- `pensoft:zookeys.1293.199475` [UNINOMIAL] Although genetic divergence between the «zonata» population of E. dasytoides and E. qinl
- `pensoft:zookeys.1219.134970` [ABBREVIATED] ated from P. armata, P. mossambica, and «P. poseidon» by its two suborbital spines and indist
- `pensoft:mycokeys.123.161540` [ABBREVIATED] oposed genus, Phyllozyma. These species—«P. coprosmicola», P. corallina, P. dimennae, P. linderae
- `pensoft:zookeys.1232.141693` [ABBREVIATED] «N. iku» sp. nov.
- `pensoft:mycokeys.126.174148` [ABBREVIATED] f these new taxa are provided. Notably, «H. qixianlingensis» represents the first record of Helicotu
- `pensoft:zookeys.1257.165617` [ABBREVIATED]  a close phylogenetic relationship with «A. d. dafangensis» and is differentiated from the nominal 
- `pensoft:bdj.12.e72511` [ABBREVIATED] «C. hylas» Macfie, 1940. Putumayo: Puerto Leguízam
- `pensoft:zookeys.1260.167263` [ABBREVIATED] 5, matching the original description of «P. cactus», and KAUST SA 231, P. explanulata, from
- `pensoft:mycokeys.136.188385` [UNINOMIAL] hylogeny and taxonomy of Lycoperdaceae: «Longipediperdon» gen. nov., a new genus from China based
- `pensoft:zookeys.1276.183701` [UNINOMIAL] (Apachyus and Dendroiketes Burr, 1909), «Gonolabiidae» Popham & Brindle, 1966b (Gonolabina Ver
- `pensoft:zookeys.1283.169111` [UNINOMIAL]  and established as the type species of «Chelitrapezura» gen. nov., the latter affiliated with t
- `pensoft:mycokeys.127.173361` [ABBREVIATED] ttae, O. unilateralis, O. rhizoidea and «O. peltate» with O. blattae designated as the type 
- `pensoft:zookeys.1264.140174` [ABBREVIATED] ruens exhibit a turbine shape, while in «H. salina» they display a simple aperture. Phyloge
- `pensoft:zookeys.1223.138220` [UNINOMIAL] totype: Male, “Australia ? Adelaide 984 «guttatus»”, “Lectotype”, “Sharp Coll. 1905-313.”,
- `pensoft:zookeys.1291.189894` [UNINOMIAL] miptera, Coreidae) reveals a new genus: «Gemenesis» gen. nov.
- `pensoft:zookeys.1293.201884` [UNINOMIAL] fically addressing relationships within «Nipponomyia» has been performed.
- `pensoft:zookeys.1204.123909` [ABBREVIATED] P. («S.») ussuriensis Belokobylskij, 1988
- `pensoft:zookeys.1201.123141` [UNINOMIAL] «Pectapalcohrus»
