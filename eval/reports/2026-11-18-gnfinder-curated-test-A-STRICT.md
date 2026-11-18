# gnfinder on curated / test (mode A, match STRICT)

Date: 2026-11-18

Documents: 80

Finder: gnfinder 1.4.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

OTU mentions (BOLD BINs and UNITE SHs, not scored as names): 0

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 563 | 484 | 85 | 0.538 | 0.869 | 0.664 |
| &nbsp;&nbsp;block:ABSTRACT | 142 | 130 | 19 | 0.522 | 0.882 | 0.656 |
| &nbsp;&nbsp;block:CAPTION | 200 | 89 | 43 | 0.692 | 0.823 | 0.752 |
| &nbsp;&nbsp;block:HEADING | 18 | 2 | 3 | 0.900 | 0.857 | 0.878 |
| &nbsp;&nbsp;block:PARAGRAPH | 173 | 211 | 15 | 0.451 | 0.920 | 0.605 |
| &nbsp;&nbsp;block:TABLE_CELL | 9 | 2 | 0 | 0.818 | 1.000 | 0.900 |
| &nbsp;&nbsp;block:TITLE | 21 | 50 | 5 | 0.296 | 0.808 | 0.433 |
| &nbsp;&nbsp;kind:ABBREVIATED | 152 | 4 | 14 | 0.974 | 0.916 | 0.944 |
| &nbsp;&nbsp;kind:BINOMIAL | 162 | 13 | 17 | 0.926 | 0.905 | 0.915 |
| &nbsp;&nbsp;kind:TRINOMIAL | 6 | 8 | 3 | 0.429 | 0.667 | 0.522 |
| &nbsp;&nbsp;kind:UNINOMIAL | 243 | 459 | 51 | 0.346 | 0.827 | 0.488 |
| &nbsp;&nbsp;lang:de | 2 | 6 | 0 | 0.250 | 1.000 | 0.400 |
| &nbsp;&nbsp;lang:en | 491 | 391 | 73 | 0.557 | 0.871 | 0.679 |
| &nbsp;&nbsp;lang:es | 16 | 32 | 5 | 0.333 | 0.762 | 0.464 |
| &nbsp;&nbsp;lang:fr | 1 | 10 | 6 | 0.091 | 0.143 | 0.111 |
| &nbsp;&nbsp;lang:pt | 14 | 2 | 0 | 0.875 | 1.000 | 0.933 |
| &nbsp;&nbsp;lang:und | 39 | 43 | 1 | 0.476 | 0.975 | 0.639 |
| &nbsp;&nbsp;stratum:ACTS | 242 | 133 | 49 | 0.645 | 0.832 | 0.727 |
| &nbsp;&nbsp;stratum:BROAD_EN | 51 | 90 | 7 | 0.362 | 0.879 | 0.513 |
| &nbsp;&nbsp;stratum:NON_EN | 19 | 31 | 7 | 0.380 | 0.731 | 0.500 |
| &nbsp;&nbsp;stratum:PENSOFT | 251 | 230 | 22 | 0.522 | 0.919 | 0.666 |
| acts | 27 | 7 | 63 | 0.794 | 0.300 | 0.435 |
| contexts | 0 | 0 | 33 | 0.000 | 0.000 | 0.000 |
| main subject | 6 | 97 | 6 | 0.058 | 0.500 | 0.104 |
| authorship | 0 | 0 | 135 | 0.000 | 0.000 | 0.000 |

Authorship: on the matched spans of curated documents, the gold authorship against the mention's, whitespace ignored (a wrong one counts as FP and FN).

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 0 | 0 | 0.000 |

## False positives (30 of 484 recorded)

- `openalex:W4410859805` [UNINOMIAL] na maxima ‘Rediscovered’ in the Eastern «Indian» Ocean
- `openalex:W4416401120` [UNINOMIAL] d environmental adaptability, while the «Spot» model can effectively buffer the interf
- `openalex:W4411162763` [UNINOMIAL] Characteristics «Associated» With Disseminated Gonococcal Infections
- `openalex:W4408219078` [UNINOMIAL]  to forest-to-pasture conversion in the «Amazon»
- `openalex:W4407170428` [UNINOMIAL] locations (Marion Bay, King Island, and «Maria» Island). Stranding location accounted f
- `openalex:W4415775354` [UNINOMIAL] t Mapping Approach to Setting Protected «Areas» Using Satellite Indices and Deep Learni
- `openalex:W4409210802` [UNINOMIAL] ion) dispersal model for Lizard Island, «Australia», with the aim to predict the effect of 
- `openalex:W4410991980` [UNINOMIAL] of Triplophysa ferganaensis Sheraliev & «Peng», 2021 (Teleostei: Nemacheilidae)
- `openalex:W4414742815` [UNINOMIAL] ed to mitigate ocelot road mortality on «Texas» state highway
- `openalex:W4415064949` [UNINOMIAL] nt of Different Feeds to Produce Animal «Products» to Use in Agricultural Policy Studies i
- `openalex:W4411158793` [UNINOMIAL] itions toward regenerative agriculture («RA») supports carbon offset initiatives ali
- `openalex:W4406944451` [UNINOMIAL] ovider Diagnostic Practices in Houston, «Texas»
- `openalex:W4406702371` [UNINOMIAL] ity, and Phyco-Pigment Contents of Some «Microalgae» Extracts
- `openalex:W7117713044` [UNINOMIAL] FILLET QUALITY DIFFERENCE OF «MICROPTERUS» SALMOIDES CULTURED UNDER DIFFERENT MODE
- `openalex:W4412873380` [UNINOMIAL] abian and Egyptian reefs, respectively. «As» a result of this mass bleaching event, 
- `openalex:W4411159455` [UNINOMIAL] he Immune Code: IL-5 Dynamics in BALB/c «Mice» Spleen Post-Exposure to Klebsiella pneu
- `openalex:W4413245474` [UNINOMIAL] neral composition of sargassum from the «Atlantic» Ocean coast in the Benin Republic for f
- `openalex:W4414511084` [UNINOMIAL] hylogenetic relationships among western «Atlantic» representatives of Pilumnus Leach, 1816
- `openalex:W4410980449` [UNINOMIAL] «Light» tuned to the avian eye elicits early de
- `openalex:W4406900892` [UNINOMIAL] Abstract The 5th International «Conference» on the Effects of Climate Change on the
- `openalex:W4412135165` [TRINOMIAL] Environmental drivers of «Atrina zelandica habitat» suitability in Tauranga Harbour
- `openalex:W4410515205` [UNINOMIAL] Abstract Giant «Panda» (hereafter, panda) habitat in the Mount
- `openalex:W4406972437` [UNINOMIAL]  Instability Index in Output, Harvested «Area», and Yield of Oil Palm Fruit in Nigeria
- `openalex:W7117575966` [UNINOMIAL] Investigation of Cryptosporidium «Parasite» Contamination in Broiler Chickens in Kh
- `openalex:W4408296502` [UNINOMIAL]  extant subpopulations in north-eastern «Poland» could form a viable metapopulation and 
- `openalex:W7161772517` [UNINOMIAL] «Les» praticiens rapportent depuis quelques a
- `openalex:W4414736270` [UNINOMIAL] es útiles que sirven como guía general. «La» mejor manera de descubrir cómo atraer a
- `openalex:W4412642615` [TRINOMIAL] taille-poids et facteur de condition de «Chrysichthys nigrodigitatus dans» deux hydrosystèmes du nord de la Côte d
- `openalex:W4413210974` [BINOMIAL] a producir híbridos interespecíficos de «Elaeis oleifera» × Elaeis guineensis (O×G), presentan va
- `openalex:W7216840899` [UNINOMIAL] a. Contribuições sociais e ambientais - «As» conclusões do estudo fornecem subsídios

## False negatives (30 of 85 recorded)

- `openalex:W4410859805` [ABBREVIATED] e to Ashmore Reef. Phylogeographically, «T. maxima» did not group with conspecifics from th
- `openalex:W7117713044` [BINOMIAL] FILLET QUALITY DIFFERENCE OF «MICROPTERUS SALMOIDES» CULTURED UNDER DIFFERENT MODES
- `openalex:W4411159455` [ABBREVIATED] mising vaccine candidate for preventing «K. pneumoniae» infections through stimulation of humor
- `openalex:W4414511084` [ABBREVIATED] ssed specimen phylogenetically close to «P. lacteus» may represent an undescribed species. T
- `openalex:W4412135165` [BINOMIAL] Environmental drivers of «Atrina zelandica» habitat suitability in Tauranga Harbour
- `openalex:W7161772517` [BINOMIAL] croissante, avec un rôle prédominant de «Mannheimia haemolytica» dans les formes aiguës. Le diagnostic r
- `openalex:W4412642615` [BINOMIAL] taille-poids et facteur de condition de «Chrysichthys nigrodigitatus» dans deux hydrosystèmes du nord de la C
- `openalex:W4413210974` [TRINOMIAL] a producir híbridos interespecíficos de «Elaeis oleifera × Elaeis guineensis» (O×G), presentan variabilidad genética 
- `openalex:W4409591877` [BINOMIAL] Séroprévalence de «Toxoplasma gondii» chez le poulet de la région de Marrakec
- `openalex:W4416959617` [BINOMIAL] ues années, de nouveaux recensements de «Nothodiplax dendrophila» sur le territoire guyanais permettent d
- `pensoft:zookeys.1229.138920` [BINOMIAL] «Acerophagus albuscorpus» Xi & Hu, sp. nov., holotype female A do
- `pensoft:zookeys.1285.182943` [BINOMIAL] ayesian majority-rule consensus tree of «Azaptilia azapensis» Vargas, gen. nov. et sp. nov. (bold) an
- `pensoft:zookeys.1256.162903` [ABBREVIATED] ana group, composed of A. sumatrana and «A. jacobi». Other species were not classified into
- `pensoft:phytokeys.271.181141` [ABBREVIATED] ters differentiating Glossoloma jostii, «G. puroanum», G. recaldeorum, and G. wiehleri.
- `pensoft:mycokeys.130.183564` [ABBREVIATED] In considering the conidial morphology, «D. fusiformis» has oblong to ellipsoidal, straight or 
- `pensoft:bdj.12.e123575` [BINOMIAL] s workless inquiline species in Europe. «Anergates atratulus» has a native range that covers the west
- `pensoft:zookeys.1264.165241` [ABBREVIATED] Province. Suizhou City, Hubei Province. «T. kaijiangensis» sp. nov.: Dazhou city, Sichuang Provinc
- `pensoft:zookeys.1223.137184` [BINOMIAL] «Cyrtodactylus nangunhe» sp. nov.
- `pensoft:mycokeys.124.171541` [BINOMIAL] «Entoloma acutiflavum» Z.W. Liu, Y.Y. Cui & Zhu L. Yang sp. no
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL]  subphyl. n. Cavalier-Smith. Sole class «Gemmatimonadia» cl. n. Cavalier-Smith (Gemmatimonas, Lo
- `doi:10.1007/s13225-020-00459-1` [ABBREVIATED]  of reliable DNA-barcodes of species of «C. subgenus Telamonia» in the public sequence databases. This 
- `openalex:W4394689792` [BINOMIAL] ma dongtingense sp. nov. belongs to the «section Strigiles» sensu H. Peragallo and it is characteri
- `openalex:W4415449747` [ABBREVIATED] al grupo “affinis” (A. aceroi sp. nov., «A. anuliglandulosa» sp. nov., A. cardenasiana sp. nov.), gr
- `openalex:W4411159455` [BINOMIAL] Objectives«Klebsiella pneumoniae» is a Gram-negative pathogen responsible
- `openalex:W4414511084` [TRINOMIAL] epresentatives of Pilumnus Leach, 1816 («Decapoda, Brachyura, Pilumnidae») based on molecular markers, with comme
- `openalex:W4412642615` [BINOMIAL] teur de condition ont été analysés pour «Chrysichthys nigrodigitatus» sur ces lacs.L'objectif de cette étude 
- `pensoft:zookeys.1285.182943` [UNINOMIAL] n of the Greek word “πτίλον” (plumage). «Azaptilia» is considered feminine in gender.
- `pensoft:phytokeys.271.181141` [ABBREVIATED]  adjacent lobe and folded lengthwise in «G. puroanum» and G. recaldeorum).
- `pensoft:zookeys.1264.165241` [BINOMIAL] «Thereuopoda kaijiangensis» sp. nov., paratype female. Scale bars: 
- `pensoft:mycokeys.124.171541` [TRINOMIAL] Bayesian inference analysis of «Entoloma subg. Cubospora» based on ITS, LSU, rpb2, and tef1 seque
