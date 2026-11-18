# gnfinder on curated / dev (mode A, match STRICT)

Date: 2026-11-18

Documents: 31

Finder: gnfinder 1.4.0, config `chunk=100000;ambiguous=true;odds=true;lang=detect`

OTU mentions (BOLD BINs and UNITE SHs, not scored as names): 0

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 179 | 164 | 33 | 0.522 | 0.844 | 0.645 |
| &nbsp;&nbsp;block:ABSTRACT | 56 | 51 | 6 | 0.523 | 0.903 | 0.663 |
| &nbsp;&nbsp;block:CAPTION | 13 | 18 | 18 | 0.419 | 0.419 | 0.419 |
| &nbsp;&nbsp;block:HEADING | 7 | 3 | 2 | 0.700 | 0.778 | 0.737 |
| &nbsp;&nbsp;block:PARAGRAPH | 91 | 77 | 6 | 0.542 | 0.938 | 0.687 |
| &nbsp;&nbsp;block:TABLE_CELL | 2 | 1 | 0 | 0.667 | 1.000 | 0.800 |
| &nbsp;&nbsp;block:TITLE | 10 | 14 | 1 | 0.417 | 0.909 | 0.571 |
| &nbsp;&nbsp;kind:ABBREVIATED | 61 | 1 | 16 | 0.984 | 0.792 | 0.878 |
| &nbsp;&nbsp;kind:BINOMIAL | 70 | 1 | 13 | 0.986 | 0.843 | 0.909 |
| &nbsp;&nbsp;kind:UNINOMIAL | 48 | 162 | 4 | 0.229 | 0.923 | 0.366 |
| &nbsp;&nbsp;lang:de | 0 | 3 | 0 | 0.000 | 0.000 | 0.000 |
| &nbsp;&nbsp;lang:en | 123 | 136 | 30 | 0.475 | 0.804 | 0.597 |
| &nbsp;&nbsp;lang:es | 3 | 3 | 0 | 0.500 | 1.000 | 0.667 |
| &nbsp;&nbsp;lang:fr | 19 | 6 | 0 | 0.760 | 1.000 | 0.864 |
| &nbsp;&nbsp;lang:und | 34 | 16 | 3 | 0.680 | 0.919 | 0.782 |
| &nbsp;&nbsp;stratum:ACTS | 5 | 6 | 1 | 0.455 | 0.833 | 0.588 |
| &nbsp;&nbsp;stratum:BROAD_EN | 39 | 47 | 6 | 0.453 | 0.867 | 0.595 |
| &nbsp;&nbsp;stratum:NON_EN | 22 | 12 | 0 | 0.647 | 1.000 | 0.786 |
| &nbsp;&nbsp;stratum:PENSOFT | 113 | 99 | 26 | 0.533 | 0.813 | 0.644 |
| acts | 3 | 1 | 3 | 0.750 | 0.500 | 0.600 |
| contexts | 0 | 0 | 2 | 0.000 | 0.000 | 0.000 |
| main subject | 4 | 24 | 5 | 0.143 | 0.444 | 0.216 |
| authorship | 0 | 0 | 24 | 0.000 | 0.000 | 0.000 |

Authorship: on the matched spans of curated documents, the gold authorship against the mention's, whitespace ignored (a wrong one counts as FP and FN).

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 0 | 0 | 0.000 |

## False positives (30 of 164 recorded)

- `openalex:W4409207899` [UNINOMIAL] enerally do not, except for the Crowned «Eagle» Stephanoaetus coronatus, which are beli
- `openalex:W7114795836` [ABBREVIATED] probiotic candidates L. acidophilus and «L. delbrueckii» spp. bulgaricus, along with assessments
- `openalex:W4408161575` [UNINOMIAL] ks With Environmental DNA Metabarcoding «Data» in a Coastal Marine Food Web
- `openalex:W4410837196` [UNINOMIAL] «Vertebral» and Spinal Cord Hydatidosis: A Case Ser
- `openalex:W4411735138` [UNINOMIAL] n of a wide range of infectious agents («IA») in a host system. This technology allo
- `openalex:W4411326215` [UNINOMIAL] «Wild» Animal Detection and Alert System
- `openalex:W4415684239` [UNINOMIAL] f Vegetation NPP in the Yanshan-Taihang «Mountain» Ecological Conservation Zone from 2004 
- `openalex:W4408298287` [UNINOMIAL] IA SPP. IN THREE SPECIES OF FREERANGING «OPOSSUM» (DIDELPHIS SPP.) IN FLORIDA, USA, AND B
- `openalex:W4409765352` [UNINOMIAL] Sudden «Death» With Lyme Disease Myocarditis: A Case R
- `openalex:W4406679611` [UNINOMIAL]  four eastern communal conservancies in «Namibia» using questionnaire surveys administere
- `openalex:W4413897306` [UNINOMIAL] ahil edilmiştir.Tanımlayıcı analizlerde «Gram» boyama, konvansiyonel biyokimyasal yönt
- `openalex:W4417208930` [UNINOMIAL] Resumen Introducción: «La» parasitosis infantil persiste como un p
- `openalex:W7126047464` [UNINOMIAL] alis a été mis en évidence.. Résultats. «La» fréquence de l’infection à T. vaginalis
- `openalex:W7126510610` [UNINOMIAL] euen Studie die EU und ihre Fangquoten. «Die» Rede ist von systematischem Versagen in
- `openalex:W4408055545` [UNINOMIAL] AL EN MUJERES CON FALLO REPRODUCTIVO EN «ARGENTINA».
- `pensoft:zookeys.1260.128800` [UNINOMIAL]  Mogensen Wildlife Refuge, northwestern «Costa» Rica. The areas bordered by dashed line
- `pensoft:bdj.13.e144840` [UNINOMIAL] the middle elevation range (456-640 m). «Thus», all evaluations collectively suggest t
- `pensoft:zookeys.1253.163155` [UNINOMIAL]  subgenera of Aprostocetus Girault from «China» (females)
- `pensoft:phytokeys.238.118031` [UNINOMIAL] «Stems»
- `pensoft:bdj.12.e129087` [UNINOMIAL] Metarhizium puerense «Hong» Yu bis, J. M. Ma & Z.Q. Wang sp. nov.
- `pensoft:phytokeys.268.176758` [UNINOMIAL] «Scales» oval, 5.8–7.1 × 4.1–4.6 µm. A wide post
- `pensoft:zookeys.1243.152263` [UNINOMIAL] The collection sites of «Ptychognathus» dajie sp. nov. in East and Southeast As
- `pensoft:phytokeys.268.172546` [UNINOMIAL] «China» • Guangdong Province: Shaoguan City, Re
- `pensoft:phytokeys.274.184803` [UNINOMIAL]  native Selaginella species to Guizhou, «China» and their infrageneric assignments.
- `openalex:W4405013555` [UNINOMIAL] angensis, a new species of the subgenus «Culiciomyia» Theobald of the genus Culex Linnaeus (D
- `openalex:W4408161575` [UNINOMIAL] nt biotic interactions remains unclear. «Here», we tackle this challenge and compare s
- `openalex:W4410837196` [UNINOMIAL] «Vertebral» and Spinal Cord Hydatidosis is an infec
- `openalex:W4411735138` [UNINOMIAL] ned simultaneously for tens of targeted «IA» by real-time PCR. It represents a promi
- `openalex:W4411326215` [UNINOMIAL] Wild Animal Detection and Alert «System»
- `openalex:W4415684239` [UNINOMIAL] PP) is essential in the Yanshan–Taihang «Mountain» Ecological Conservation Zone (YTECZ). S

## False negatives (30 of 33 recorded)

- `openalex:W7114795836` [ABBREVIATED] probiotic candidates L. acidophilus and «L. delbrueckii spp. bulgaricus», along with assessments of the prebioti
- `openalex:W4414865928` [UNINOMIAL] the WHO-IWGE (Informal Working Group on «Echinococcosis») CE1-CE3 hydatid cysts. Differentials i
- `openalex:W4408298287` [BINOMIAL] om three opossum species, including the «Virginia opossum» (Didelphis virginiana, n = 33) from urb
- `openalex:W4413897306` [ABBREVIATED] %9,3) ve Klebsiella spp.(%9,3) olmuştur.«E. coli» colistin, meropenem ve imipeneme; Gram 
- `pensoft:zookeys.1260.128800` [BINOMIAL] t the Karen Mogensen Wildlife Refuge. A.«Dasypus novemcinctus»; B.Dicotyles tajacu; C.Cebus imitator; 
- `pensoft:phytokeys.238.118031` [ABBREVIATED] ces in the Cytisus ratisbonensis group A«C. borysthenicus»BC. cinereusCC. kreczetovicziiDC. lithua
- `pensoft:bdj.12.e129087` [ABBREVIATED] arhizium puerense is closely related to «M. album», but differs in morphological character
- `pensoft:zookeys.1243.152263` [BINOMIAL] The collection sites of «Ptychognathus dajie» sp. nov. in East and Southeast Asia. So
- `pensoft:phytokeys.268.172546` [ABBREVIATED] thes danxiaensis, S. austrosinensis and «S. japonica».
- `pensoft:phytokeys.274.184803` [UNINOMIAL] at the occurrence of cryptic species in «Selaginella» has been also frequently reported in pr
- `openalex:W4405013555` [BINOMIAL] ulex sarpangensis, a new species of the «subgenus Culiciomyia» Theobald of the genus Culex Linnaeus (D
- `openalex:W4408298287` [BINOMIAL] ibodies. While 3/33 (9.1%) samples from «Virginia opossum» showed positive reactivity by both meth
- `openalex:W4413897306` [UNINOMIAL] espit edilen etkenler Escherichia coli («E.coli») (%35,7), koagülaz negatif stafilokokla
- `pensoft:zookeys.1260.128800` [BINOMIAL] dlife Refuge. A.Dasypus novemcinctus; B.«Dicotyles tajacu»; C.Cebus imitator; D.Alouatta palliata;
- `pensoft:phytokeys.238.118031` [ABBREVIATED] s ratisbonensis group AC. borysthenicusB«C. cinereus»CC. kreczetovicziiDC. lithuanicusEC. pol
- `pensoft:bdj.12.e129087` [ABBREVIATED] 2.1 µm), which was smaller than that of «M. album» (5-8 × 2-2.5 µm). The phialides of M. p
- `pensoft:zookeys.1243.152263` [BINOMIAL] 7 Jul. 1990; ZRC 2008.0035 (labeled as “«Cognatus benokiensis»” in NK Ng 2006’s dissertation). Taiwan 
- `pensoft:zookeys.1260.128800` [BINOMIAL] pus novemcinctus; B.Dicotyles tajacu; C.«Cebus imitator»; D.Alouatta palliata; E.Nasua narica; F
- `pensoft:phytokeys.238.118031` [ABBREVIATED] sis group AC. borysthenicusBC. cinereusC«C. kreczetoviczii»DC. lithuanicusEC. polonicusFC. ratisbon
- `pensoft:bdj.12.e129087` [ABBREVIATED] 7.6 × 1.5-2.9 µm) was more slender than «M. album» (10-12.5 × 2-3.5 µm) (Michiel et al. 19
- `pensoft:zookeys.1243.152263` [BINOMIAL] «Ptychognathus dajie» sp. nov.
- `pensoft:zookeys.1260.128800` [BINOMIAL] B.Dicotyles tajacu; C.Cebus imitator; D.«Alouatta palliata»; E.Nasua narica; F.Dasyprocta punctata.
- `pensoft:phytokeys.238.118031` [ABBREVIATED] sthenicusBC. cinereusCC. kreczetovicziiD«C. lithuanicus»EC. polonicusFC. ratisbonensisGC. ruthen
- `pensoft:zookeys.1243.152263` [BINOMIAL] ng, Dasi; 16 Aug. 2016; NCHUZOOL 16034. «Ptychognathus lipkei »NK Ng, 2010: Philippines • 4 males (para
- `pensoft:zookeys.1260.128800` [BINOMIAL] .Cebus imitator; D.Alouatta palliata; E.«Nasua narica»; F.Dasyprocta punctata. Photographs by 
- `pensoft:phytokeys.238.118031` [ABBREVIATED] nereusCC. kreczetovicziiDC. lithuanicusE«C. polonicus»FC. ratisbonensisGC. ruthenicusHC. semer
- `pensoft:zookeys.1260.128800` [BINOMIAL]  D.Alouatta palliata; E.Nasua narica; F.«Dasyprocta punctata». Photographs by M. Dal Zotto.
- `pensoft:phytokeys.238.118031` [ABBREVIATED] czetovicziiDC. lithuanicusEC. polonicusF«C. ratisbonensis»GC. ruthenicusHC. semerenkoanusIC. elong
- `pensoft:zookeys.1260.128800` [UNINOMIAL] Order «Didelphimorpha» Gill, 1872
- `pensoft:phytokeys.238.118031` [ABBREVIATED] thuanicusEC. polonicusFC. ratisbonensisG«C. ruthenicus»HC. semerenkoanusIC. elongatusJC. wulffi
