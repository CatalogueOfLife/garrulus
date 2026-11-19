# gnfinder on curated / test (mode A, match STRICT)

Date: 2026-11-19

Documents: 80

Finder: gnfinder 1.5.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

- The JSON keeps the first 500 error samples of each kind: 500 of 538 recorded false positives, 65 of 65 false negatives. All counts are complete.

OTU mentions (BOLD BINs and UNITE SHs, not scored as names): 0

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 583 | 538 | 65 | 0.520 | 0.900 | 0.659 |
| &nbsp;&nbsp;block:ABSTRACT | 144 | 155 | 17 | 0.482 | 0.894 | 0.626 |
| &nbsp;&nbsp;block:CAPTION | 207 | 99 | 36 | 0.676 | 0.852 | 0.754 |
| &nbsp;&nbsp;block:HEADING | 21 | 2 | 0 | 0.913 | 1.000 | 0.955 |
| &nbsp;&nbsp;block:PARAGRAPH | 181 | 226 | 7 | 0.445 | 0.963 | 0.608 |
| &nbsp;&nbsp;block:TABLE_CELL | 9 | 3 | 0 | 0.750 | 1.000 | 0.857 |
| &nbsp;&nbsp;block:TITLE | 21 | 53 | 5 | 0.284 | 0.808 | 0.420 |
| &nbsp;&nbsp;kind:ABBREVIATED | 156 | 5 | 10 | 0.969 | 0.940 | 0.954 |
| &nbsp;&nbsp;kind:BINOMIAL | 168 | 13 | 11 | 0.928 | 0.939 | 0.933 |
| &nbsp;&nbsp;kind:TRINOMIAL | 6 | 8 | 3 | 0.429 | 0.667 | 0.522 |
| &nbsp;&nbsp;kind:UNINOMIAL | 253 | 512 | 41 | 0.331 | 0.861 | 0.478 |
| &nbsp;&nbsp;lang:de | 2 | 9 | 0 | 0.182 | 1.000 | 0.308 |
| &nbsp;&nbsp;lang:en | 510 | 440 | 54 | 0.537 | 0.904 | 0.674 |
| &nbsp;&nbsp;lang:es | 18 | 30 | 3 | 0.375 | 0.857 | 0.522 |
| &nbsp;&nbsp;lang:fr | 1 | 11 | 6 | 0.083 | 0.143 | 0.105 |
| &nbsp;&nbsp;lang:pt | 14 | 2 | 0 | 0.875 | 1.000 | 0.933 |
| &nbsp;&nbsp;lang:und | 38 | 46 | 2 | 0.452 | 0.950 | 0.613 |
| &nbsp;&nbsp;stratum:ACTS | 246 | 148 | 45 | 0.624 | 0.845 | 0.718 |
| &nbsp;&nbsp;stratum:BROAD_EN | 51 | 108 | 7 | 0.321 | 0.879 | 0.470 |
| &nbsp;&nbsp;stratum:NON_EN | 19 | 33 | 7 | 0.365 | 0.731 | 0.487 |
| &nbsp;&nbsp;stratum:PENSOFT | 267 | 249 | 6 | 0.517 | 0.978 | 0.677 |
| acts | 32 | 6 | 58 | 0.842 | 0.356 | 0.500 |
| contexts | 0 | 0 | 33 | 0.000 | 0.000 | 0.000 |
| main subject | 6 | 104 | 6 | 0.055 | 0.500 | 0.098 |
| authorship | 0 | 0 | 141 | 0.000 | 0.000 | 0.000 |

Authorship: on the matched spans of curated documents, the gold authorship against the mention's, whitespace ignored (a wrong one counts as FP and FN).

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 0 | 0 | 0.000 |

## False positives (30 of 538 recorded)

- `openalex:W4414780885` [UNINOMIAL] and map accuracy across our study area. «Results» Model accuracy to predict cheatgrass pr
- `openalex:W4410859805` [UNINOMIAL] na maxima ‘Rediscovered’ in the Eastern «Indian» Ocean
- `openalex:W4416401120` [UNINOMIAL] d environmental adaptability, while the «Spot» model can effectively buffer the interf
- `openalex:W4416542429` [UNINOMIAL] «Review» of theriological studies carried out af
- `openalex:W4411162763` [UNINOMIAL] Characteristics «Associated» With Disseminated Gonococcal Infections
- `openalex:W4408219078` [UNINOMIAL]  to forest-to-pasture conversion in the «Amazon»
- `openalex:W4407170428` [UNINOMIAL] locations (Marion Bay, King Island, and «Maria» Island). Stranding location accounted f
- `openalex:W4415775354` [UNINOMIAL] t Mapping Approach to Setting Protected «Areas» Using Satellite Indices and Deep Learni
- `openalex:W4412138018` [UNINOMIAL] after nursing management were compared. «Results»: After nursing intervention, the qualif
- `openalex:W4409210802` [UNINOMIAL] ion) dispersal model for Lizard Island, «Australia», with the aim to predict the effect of 
- `openalex:W4410991980` [UNINOMIAL] of Triplophysa ferganaensis Sheraliev & «Peng», 2021 (Teleostei: Nemacheilidae)
- `openalex:W4414742815` [UNINOMIAL] ed to mitigate ocelot road mortality on «Texas» state highway
- `openalex:W4415064949` [UNINOMIAL] nt of Different Feeds to Produce Animal «Products» to Use in Agricultural Policy Studies i
- `openalex:W4411158793` [UNINOMIAL] itions toward regenerative agriculture («RA») supports carbon offset initiatives ali
- `openalex:W4406944451` [UNINOMIAL] ovider Diagnostic Practices in Houston, «Texas»
- `openalex:W4406702371` [UNINOMIAL] ity, and Phyco-Pigment Contents of Some «Microalgae» Extracts
- `openalex:W7117713044` [UNINOMIAL] FILLET QUALITY DIFFERENCE OF «MICROPTERUS» SALMOIDES CULTURED UNDER DIFFERENT MODE
- `openalex:W4412873380` [UNINOMIAL] valence at 22 and 11 sites in the Saudi «Arabian» and Egyptian Red Sea, respectively. Mea
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
- `openalex:W7114894043` [UNINOMIAL] tlichen Sinne effizienter zu gestalten. «Eine» wesentliche Herausforderung für deren U

## False negatives (30 of 65 recorded)

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
- `pensoft:zookeys.1256.162903` [UNINOMIAL] plete and comprehensive overview of the «Glossamegilla» known to the Indo-Australian Archipelag
- `pensoft:mycokeys.130.183564` [ABBREVIATED] In considering the conidial morphology, «D. fusiformis» has oblong to ellipsoidal, straight or 
- `pensoft:bdj.12.e123575` [BINOMIAL] s workless inquiline species in Europe. «Anergates atratulus» has a native range that covers the west
- `pensoft:mycokeys.124.171541` [TRINOMIAL] Bayesian inference analysis of «Entoloma subg. Cubospora» based on ITS, LSU, rpb2, and tef1 seque
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] Bacillus, Lactobacilllus) M Subclass 2. «Erysipelotrichiidae» subcl. n. Cavalier-Smith (e.g. Turiciba
- `doi:10.1007/s13225-020-00459-1` [ABBREVIATED]  of reliable DNA-barcodes of species of «C. subgenus Telamonia» in the public sequence databases. This 
- `openalex:W4394689792` [BINOMIAL] ma dongtingense sp. nov. belongs to the «section Strigiles» sensu H. Peragallo and it is characteri
- `openalex:W4415449747` [UNINOMIAL] ecímenes para el tratamiento del género «Aniba» para Colombia, se han identificado algu
- `openalex:W4411159455` [BINOMIAL] Objectives«Klebsiella pneumoniae» is a Gram-negative pathogen responsible
- `openalex:W4414511084` [TRINOMIAL] epresentatives of Pilumnus Leach, 1816 («Decapoda, Brachyura, Pilumnidae») based on molecular markers, with comme
- `openalex:W4412642615` [BINOMIAL] teur de condition ont été analysés pour «Chrysichthys nigrodigitatus» sur ces lacs.L'objectif de cette étude 
- `pensoft:zookeys.1256.162903` [ABBREVIATED] ana group, composed of A. sumatrana and «A. jacobi». Other species were not classified into
- `pensoft:bdj.12.e123575` [ABBREVIATED] complex, but also from the T. ferox and «T. chefketi» complexes (Sanetra et al. 1999, Lapeva-
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] hur bacteria and relatives) Subclass 1. «Acidithiobacillidae» (γ-proteobacteria e.g. Chromatium, Acid
- `doi:10.1007/s13225-020-00459-1` [ABBREVIATED] problems, even in difficult groups like «C. subgenus Telamonia», can be solved and consequently identif
- `openalex:W4415449747` [UNINOMIAL] ustran cinco nuevas especies del género «Aniba» para Colombia pertenecientes al grupo “
- `openalex:W4412642615` [ABBREVIATED] ontribuer à l'étude de la croissance de «C. nigrodigitatus» sur les lacs de Nangakaha et de Sologo.
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] ithiobacillus, Escherichia) Subclass 2. «Neisseriidae» Cavalier-Smith subcl. n. (β-proteobacte
- `doi:10.1007/s13225-020-00459-1` [BINOMIAL] bly of barcoded species of Cortinarius, «subgen. Telamonia» in the public sequence databases. Also 
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] er-Smith (δ-proteobacteria) Subclass 1. «Mycococcidae» subcl. n. Cavalier-Smith (e.g. Myxococc
