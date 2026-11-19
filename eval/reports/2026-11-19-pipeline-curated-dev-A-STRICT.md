# pipeline on curated / dev (mode A, match STRICT)

Date: 2026-11-19

Documents: 31

Finder: pipeline 0.1.0-SNAPSHOT, config `f40ea7de5c5fe60938dc0db79cbcd9eb91aa0479b28ea95db4bf138f1e185192`, dictionary `316691`

OTU mentions (BOLD BINs and UNITE SHs, not scored as names): 0

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 191 | 14 | 21 | 0.932 | 0.901 | 0.916 |
| &nbsp;&nbsp;block:ABSTRACT | 57 | 3 | 5 | 0.950 | 0.919 | 0.934 |
| &nbsp;&nbsp;block:CAPTION | 21 | 0 | 10 | 1.000 | 0.677 | 0.808 |
| &nbsp;&nbsp;block:HEADING | 8 | 0 | 1 | 1.000 | 0.889 | 0.941 |
| &nbsp;&nbsp;block:PARAGRAPH | 95 | 6 | 2 | 0.941 | 0.979 | 0.960 |
| &nbsp;&nbsp;block:TABLE_CELL | 2 | 1 | 0 | 0.667 | 1.000 | 0.800 |
| &nbsp;&nbsp;block:TITLE | 8 | 4 | 3 | 0.667 | 0.727 | 0.696 |
| &nbsp;&nbsp;kind:ABBREVIATED | 66 | 1 | 11 | 0.985 | 0.857 | 0.917 |
| &nbsp;&nbsp;kind:BINOMIAL | 78 | 1 | 5 | 0.987 | 0.940 | 0.963 |
| &nbsp;&nbsp;kind:UNINOMIAL | 47 | 12 | 5 | 0.797 | 0.904 | 0.847 |
| &nbsp;&nbsp;lang:en | 132 | 14 | 21 | 0.904 | 0.863 | 0.883 |
| &nbsp;&nbsp;lang:es | 3 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| &nbsp;&nbsp;lang:fr | 19 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| &nbsp;&nbsp;lang:und | 37 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| &nbsp;&nbsp;stratum:ACTS | 5 | 6 | 1 | 0.455 | 0.833 | 0.588 |
| &nbsp;&nbsp;stratum:BROAD_EN | 38 | 1 | 7 | 0.974 | 0.844 | 0.905 |
| &nbsp;&nbsp;stratum:NON_EN | 22 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| &nbsp;&nbsp;stratum:PENSOFT | 126 | 7 | 13 | 0.947 | 0.906 | 0.926 |
| acts | 6 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| contexts | 2 | 8 | 0 | 0.200 | 1.000 | 0.333 |
| main subject | 4 | 13 | 5 | 0.235 | 0.444 | 0.308 |
| authorship | 15 | 6 | 9 | 0.714 | 0.625 | 0.667 |

Authorship: on the matched spans of curated documents, the gold authorship against the mention's, whitespace ignored (a wrong one counts as FP and FN).

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 0 | 0 | 0.000 |

## False positives (14 of 14 recorded)

- `openalex:W7114795836` [ABBREVIATED] probiotic candidates L. acidophilus and «L. delbrueckii» spp. bulgaricus, along with assessments
- `pensoft:zookeys.1260.128800` [UNINOMIAL]  than the head and body (Reid and Gόmez «Zamora» 2022 and literature therein).
- `pensoft:zookeys.1253.163155` [UNINOMIAL] Tetrastichodes «Ashmead»
- `pensoft:phytokeys.238.118031` [UNINOMIAL] Crimea. “Ex «Tauria»”, P.S. Pallas in Herb. Bieberstein (lec
- `pensoft:zookeys.1243.152263` [UNINOMIAL]  2 females (11.4 × 9.6, 11.0 × 9.1 mm); «Ogasawara» Islands, Chichi-jima Island, Futami, Ki
- `openalex:W4405013555` [UNINOMIAL] angensis, a new species of the subgenus «Culiciomyia» Theobald of the genus Culex Linnaeus (D
- `pensoft:zookeys.1253.163155` [UNINOMIAL]  on each side, each row with 5–6 setae. «Scutellum» 1.4 × as broad as long, anterior pair o
- `pensoft:phytokeys.238.118031` [UNINOMIAL] Crimea. “Ex Tauria”, P.S. «Pallas» in Herb. Bieberstein (lectotype LE01043
- `pensoft:zookeys.1243.152263` [BINOMIAL] ng, Dasi; 16 Aug. 2016; NCHUZOOL 16034. «Ptychognathus lipkei» NK Ng, 2010: Philippines • 4 males (par
- `openalex:W4405013555` [UNINOMIAL] Culiciomyia Theobald of the genus Culex «Linnaeus» (Diptera: Culicidae) from Bhutan
- `openalex:W4405013555` [UNINOMIAL] a Theobald of the genus Culex Linnaeus («Diptera»: Culicidae) from Bhutan
- `openalex:W4405013555` [UNINOMIAL] d of the genus Culex Linnaeus (Diptera: «Culicidae») from Bhutan
- `openalex:W4405013555` [UNINOMIAL] Culiciomyia Theobald of the genus Culex «Linnaeus» is described from larvae found in a str
- `openalex:W4405013555` [UNINOMIAL] sp. nov. The species is a member of the «Fragilis» Group. The fourth-instar larva is descr

## False negatives (21 of 21 recorded)

- `openalex:W7114795836` [ABBREVIATED] probiotic candidates L. acidophilus and «L. delbrueckii spp. bulgaricus», along with assessments of the prebioti
- `openalex:W4414865928` [UNINOMIAL] the WHO-IWGE (Informal Working Group on «Echinococcosis») CE1-CE3 hydatid cysts. Differentials i
- `openalex:W4408298287` [UNINOMIAL] ANTIBODIES TO «BESNOITIA» SPP. IN THREE SPECIES OF FREERANGING OP
- `openalex:W4413897306` [UNINOMIAL] espit edilen etkenler Escherichia coli («E.coli») (%35,7), koagülaz negatif stafilokokla
- `pensoft:zookeys.1260.128800` [UNINOMIAL] Order «Didelphimorpha» Gill, 1872
- `pensoft:phytokeys.238.118031` [ABBREVIATED] ces in the Cytisus ratisbonensis group A«C. borysthenicus»BC. cinereusCC. kreczetovicziiDC. lithua
- `pensoft:zookeys.1243.152263` [BINOMIAL] 7 Jul. 1990; ZRC 2008.0035 (labeled as “«Cognatus benokiensis»” in NK Ng 2006’s dissertation). Taiwan 
- `openalex:W4405013555` [BINOMIAL] ulex sarpangensis, a new species of the «subgenus Culiciomyia» Theobald of the genus Culex Linnaeus (D
- `openalex:W4408298287` [UNINOMIAL] N THREE SPECIES OF FREERANGING OPOSSUM («DIDELPHIS» SPP.) IN FLORIDA, USA, AND BRAZIL
- `pensoft:phytokeys.238.118031` [ABBREVIATED] s ratisbonensis group AC. borysthenicusB«C. cinereus»CC. kreczetovicziiDC. lithuanicusEC. pol
- `pensoft:zookeys.1243.152263` [BINOMIAL] ng, Dasi; 16 Aug. 2016; NCHUZOOL 16034. «Ptychognathus lipkei »NK Ng, 2010: Philippines • 4 males (para
- `openalex:W4408298287` [BINOMIAL] om three opossum species, including the «Virginia opossum» (Didelphis virginiana, n = 33) from urb
- `pensoft:phytokeys.238.118031` [ABBREVIATED] sis group AC. borysthenicusBC. cinereusC«C. kreczetoviczii»DC. lithuanicusEC. polonicusFC. ratisbon
- `openalex:W4408298287` [BINOMIAL] ibodies. While 3/33 (9.1%) samples from «Virginia opossum» showed positive reactivity by both meth
- `pensoft:phytokeys.238.118031` [ABBREVIATED] sthenicusBC. cinereusCC. kreczetovicziiD«C. lithuanicus»EC. polonicusFC. ratisbonensisGC. ruthen
- `pensoft:phytokeys.238.118031` [ABBREVIATED] nereusCC. kreczetovicziiDC. lithuanicusE«C. polonicus»FC. ratisbonensisGC. ruthenicusHC. semer
- `pensoft:phytokeys.238.118031` [ABBREVIATED] czetovicziiDC. lithuanicusEC. polonicusF«C. ratisbonensis»GC. ruthenicusHC. semerenkoanusIC. elong
- `pensoft:phytokeys.238.118031` [ABBREVIATED] thuanicusEC. polonicusFC. ratisbonensisG«C. ruthenicus»HC. semerenkoanusIC. elongatusJC. wulffi
- `pensoft:phytokeys.238.118031` [ABBREVIATED] olonicusFC. ratisbonensisGC. ruthenicusH«C. semerenkoanus»IC. elongatusJC. wulffii. Scale bars: 1 
- `pensoft:phytokeys.238.118031` [ABBREVIATED] bonensisGC. ruthenicusHC. semerenkoanusI«C. elongatus»JC. wulffii. Scale bars: 1 mm.
- `pensoft:phytokeys.238.118031` [ABBREVIATED] uthenicusHC. semerenkoanusIC. elongatusJ«C. wulffii». Scale bars: 1 mm.
