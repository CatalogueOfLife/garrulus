# pipeline on pensoft / test (mode A, match OVERLAP)

Date: 2026-11-15

Documents: 200

Finder: pipeline 0.1.0-SNAPSHOT, config `799364566a46508c6817796ff29edb32d09b6343a8dc9a8a8e476f1f20d93153`, dictionary `316691`

- The JSON keeps the first 500 error samples of each kind: 500 of 2976 recorded false positives, 500 of 1682 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 37513 | 2976 | 1682 | 0.926 | 0.957 | 0.942 |
| &nbsp;&nbsp;block:ABSTRACT | 1505 | 54 | 55 | 0.965 | 0.965 | 0.965 |
| &nbsp;&nbsp;block:CAPTION | 3144 | 180 | 84 | 0.946 | 0.974 | 0.960 |
| &nbsp;&nbsp;block:FOOTNOTE | 17 | 17 | 0 | 0.500 | 1.000 | 0.667 |
| &nbsp;&nbsp;block:HEADING | 1005 | 65 | 50 | 0.939 | 0.953 | 0.946 |
| &nbsp;&nbsp;block:PARAGRAPH | 21027 | 2330 | 1007 | 0.900 | 0.954 | 0.926 |
| &nbsp;&nbsp;block:TABLE_CELL | 10284 | 310 | 454 | 0.971 | 0.958 | 0.964 |
| &nbsp;&nbsp;block:TITLE | 531 | 20 | 32 | 0.964 | 0.943 | 0.953 |
| &nbsp;&nbsp;kind:ABBREVIATED | 13535 | 33 | 506 | 0.998 | 0.964 | 0.980 |
| &nbsp;&nbsp;kind:BINOMIAL | 12369 | 139 | 75 | 0.989 | 0.994 | 0.991 |
| &nbsp;&nbsp;kind:INFRAGENERIC | 0 | 2 | 0 | 0.000 | 0.000 | 0.000 |
| &nbsp;&nbsp;kind:TRINOMIAL | 536 | 3 | 6 | 0.994 | 0.989 | 0.992 |
| &nbsp;&nbsp;kind:UNINOMIAL | 11073 | 2799 | 1095 | 0.798 | 0.910 | 0.850 |
| &nbsp;&nbsp;lang:en | 32407 | 2559 | 1547 | 0.927 | 0.954 | 0.940 |
| &nbsp;&nbsp;lang:und | 5106 | 417 | 135 | 0.924 | 0.974 | 0.949 |
| acts | 300 | 145 | 11 | 0.674 | 0.965 | 0.794 |
| contexts | 284 | 870 | 1328 | 0.246 | 0.176 | 0.205 |
| main subject | 423 | 498 | 30 | 0.459 | 0.934 | 0.616 |

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 32331 | 33855 | 0.955 |
| &nbsp;&nbsp;kind:ABBREVIATED | 12635 | 13535 | 0.934 |
| &nbsp;&nbsp;kind:BINOMIAL | 12031 | 12298 | 0.978 |
| &nbsp;&nbsp;kind:TRINOMIAL | 296 | 530 | 0.558 |
| &nbsp;&nbsp;kind:UNINOMIAL | 7369 | 7492 | 0.984 |

## False positives (30 of 2976 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] Alucita hexadactyla «Linnaeus», 1758
- `pensoft:bdj.14.e161845` [UNINOMIAL] g authors: Jeremías Benjamín Norambuena «Molina» (jenorambuena@udec.cl), Laura Tavera Ma
- `pensoft:bdj.12.e118010` [UNINOMIAL] or focusing on certain Orthoptera taxa («Pina» et al. 2017). Although some of these mo
- `pensoft:bdj.14.e191590` [UNINOMIAL] We are grateful to Familia «Nikola» Ltd. (Sofia), a Bulgarian muesli-produc
- `pensoft:zookeys.1196.117947` [BINOMIAL] Agarwal I (2024) Two new species of the «Cnemaspis galaxia» complex (Squamata, Gekkonidae) from the
- `pensoft:mycokeys.110.134132` [BINOMIAL] -C (2024) Interesting mycoparasites and «Paradingleyomyces lepidopterorum» gen. et sp. nov. (Hypocreales, Polyceph
- `pensoft:bdj.14.e186195` [UNINOMIAL] «Antonina» Badalucco, Sabrina Lo Brutto, Jean-Clau
- `pensoft:zookeys.1279.188050` [BINOMIAL]  AV, Le MD, Ziegler T, Nguyen TQ (2026) «Sphenomorphus puhoatensis» (Squamata, Scincidae), a new skink from
- `pensoft:phytokeys.279.207098` [UNINOMIAL] ve recovered the genus as sister to the «Apocopis»–Germainia–Pogonatherum clade, supportin
- `pensoft:bdj.12.e134521` [UNINOMIAL] PCR) was conducted using TaKaRa Ex Taq («Takara» Bio, Shiga, Japan) with the universal p
- `pensoft:mycokeys.124.172775` [UNINOMIAL] ang W-Y, Wang X-C (2025) New species of «Aspergillus» in sections Cavernicolarum and Nigri fr
- `pensoft:bdj.14.e180342` [UNINOMIAL] and regionally (Kagiampaki et al. 2011, «Lazarina» et al. 2019, Panitsa et al. 2021).
- `pensoft:zookeys.1244.146861` [UNINOMIAL] storek P, Oboňa J (2025) Scuttle flies («Diptera», Phoridae) collected by mosquito trap f
- `pensoft:mycokeys.125.168474` [UNINOMIAL] 025) Unveiling species diversity within «Mortierellomycota» from China X: Three new species in Linn
- `pensoft:zookeys.1236.145914` [UNINOMIAL] al phylogenomics of pygmy grasshoppers («Orthoptera», Tetrigidae, Metrodorinae): description
- `pensoft:zookeys.1225.125030` [UNINOMIAL] ering behaviour of terrestrial isopods («Isopoda», Oniscidea): a field experiment. In: Tu
- `pensoft:zookeys.1268.173464` [UNINOMIAL] freshwater mussel subtribe Cristariina («Bivalvia», Unionidae) in China: description of a 
- `pensoft:zookeys.1214.132950` [BINOMIAL] z J (2024) Complementary description of «Peltidium nayarit» (Copepoda, Harpaticoida, Peltidiidae) a
- `pensoft:mycokeys.123.160960` [UNINOMIAL] ification of new species and records of «Daldinia» (Hypoxylaceae, Xylariales) from Guizhou
- `pensoft:phytokeys.251.135126` [BINOMIAL] , He S-T, Wen F, Bai X-X, Li M-J (2025) «Primulina xingyiensis» (Gesneriaceae), a new species in the ka
- `pensoft:mycokeys.133.187172` [UNINOMIAL] gibrachiatum, Harzianum, Psychrophilum, «Semiorbis», and Brevicompactum clades. The discove
- `pensoft:mycokeys.107.122026` [UNINOMIAL]  L, Zhang X (2024) Three new species of «Pestalotiopsis» (Amphisphaeriales, Sporocadaceae) were 
- `pensoft:zookeys.1275.178507` [BINOMIAL]  Nepal revealed by a new species of the «Cyrtodactylus (Geckoella) nebulosus» complex (Reptilia, Squamata) from Nepal
- `pensoft:phytokeys.251.137015` [BINOMIAL] Y, Wang B-M, Huang Y-S, Tong Y-H (2025) «Agapetes hongheensis» (Ericaceae), a new species from Yunnan,
- `pensoft:zookeys.1265.168108` [BINOMIAL]  B, Abdalla A, Masri M, Elfaki E (2025) «Paragomphus alami» sp. nov. (Odonata, Gomphidae): a new dr
- `pensoft:phytokeys.257.153202` [BINOMIAL] g H-C (2025) Taxonomic clarification of «Silene dawoensis» (Caryophyllaceae): synonymization with 
- `pensoft:zookeys.1232.142699` [UNINOMIAL] outhwestern Anatolia (mainly Burdur and «Isparta» provinces) in 2024. As a result of this
- `pensoft:zookeys.1258.168452` [BINOMIAL] ) Morphology and molecular phylogeny of «Monomicrocaryon trimarginalis» sp. nov. (Ciliophora, Hypotricha), a ne
- `pensoft:mycokeys.128.178090` [UNINOMIAL] across various locations in Sichuan and «Yunnan» Provinces. Morphological characteristic
- `pensoft:phytokeys.270.174879` [BINOMIAL] n Y, Yang H, Liu C, Song X, Ma S (2026) «Impatiens longcanggouensis» (Balsaminaceae): a new species from Sic

## False negatives (30 of 1682 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] rysian families of uncertain placement («Heliocosmidae» and Millieriidae), while three glossata
- `pensoft:bdj.14.e161845` [BINOMIAL] «Nutallochiton martiali»
- `pensoft:bdj.12.e118010` [UNINOMIAL] «Animalia»
- `pensoft:zookeys.1196.117947` [UNINOMIAL] ia. Both new species are members of the «ornata» subclade within the beddomei clade. The
- `pensoft:mycokeys.110.134132` [UNINOMIAL] omyces lepidopterorum gen. et sp. nov. («Hypocreales», Polycephalomycetaceae) from Yunnan Pro
- `pensoft:bdj.14.e186195` [UNINOMIAL] he keywords “amphipod*”, “peracarid*”, “«crustacea»*”, “checklist" in combination with the 
- `pensoft:bdj.12.e134521` [UNINOMIAL] «Chiton» species belonging to the genus Ischnoch
- `pensoft:mycokeys.124.172775` [UNINOMIAL] ergillus in sections Cavernicolarum and «Nigri» from terrestrial ecosystems of China (E
- `pensoft:zookeys.1244.146861` [UNINOMIAL] onging to the families Pleurotaceae and «Plutaceae» (Disney 1991).
- `pensoft:mycokeys.125.168474` [UNINOMIAL] , followed by Linnemannia with 24, then «Podia» and Entomortierella in equal third plac
- `pensoft:zookeys.1236.145914` [UNINOMIAL] tly, a new genus and two new species of «Metrodolrinae» from China are described and illustrate
- `pensoft:zookeys.1225.125030` [UNINOMIAL] lity of both species was similar (meanP.«scaber» = 0.54 m, meanA.versicolor = 0.57 m; pE
- `pensoft:zookeys.1268.173464` [UNINOMIAL] ision of the freshwater mussel subtribe «Cristariina» (Bivalvia, Unionidae) in China: descrip
- `pensoft:zookeys.1214.132950` [UNINOMIAL] ntary description of Peltidium nayarit («Copepoda», Harpaticoida, Peltidiidae) and a new r
- `pensoft:mycokeys.123.160960` [ABBREVIATED] n addition, a comprehensive analysis of «D. eschscholtzii»’s worldwide host distribution revealed 
- `pensoft:mycokeys.133.187172` [UNINOMIAL] f five novel Trichoderma (Hypocreaceae, «Hypocreales») species isolated from decaying wood in
- `pensoft:mycokeys.107.122026` [UNINOMIAL] «Pestalotiopsis» fungi are widely distributed all over t
- `pensoft:zookeys.1275.178507` [ABBREVIATED] Cyrtodactylus (Geckoella): blue circles «C. albofasciatus»-C. deccanensis complex; yellow circles 
- `pensoft:phytokeys.251.137015` [UNINOMIAL] her (Airy Shaw 1935, 1959). In the ser. «Longifiles», except our new species, only two other
- `pensoft:zookeys.1265.168108` [UNINOMIAL] raits with P. elpidius Ris, 1921 as the «elpidius»-group. This informal grouping does not 
- `pensoft:phytokeys.257.153202` [UNINOMIAL]  recognized and 67 species are endemic. «Silene» taxa can be found throughout the countr
- `pensoft:zookeys.1232.142699` [UNINOMIAL] ith the description of one new species («Acari», Hydrachnidia)
- `pensoft:zookeys.1258.168452` [UNINOMIAL] yon trimarginalis sp. nov. (Ciliophora, «Hypotricha»), a new soil ciliate species from South
- `pensoft:mycokeys.128.178090` [BINOMIAL]  aquatica, Am. aureae, Am. bambusicola, «Am. cerosissimae», Am. guangxiensis, Am. hongheensis and 
- `pensoft:phytokeys.270.174879` [UNINOMIAL] ult to separate and reconstruct, making «Impatiens» one of the most taxonomically challengi
- `pensoft:bdj.12.e116039` [UNINOMIAL] hat prey on specific organisms, such as «Cnidaria», exhibit morphological modifications in
- `pensoft:mycokeys.134.187344` [UNINOMIAL] ungi (AMF), that produce glomoid spores «sensu» Morton and Redecker (2001). This order 
- `pensoft:zookeys.1210.125611` [UNINOMIAL] on map are also provided for species of «Amemboa» occurring in China.
- `pensoft:phytokeys.280.206680` [UNINOMIAL]  E of Jilore forest station, alt. 60 m, «Cynometra» forest with Encephalartos hildebrandtii
- `pensoft:bdj.12.e119481` [BINOMIAL] «Clavicornaltica mataikanensis» Otani, Bertoli, Lucchini, Boin, Ellis, 
