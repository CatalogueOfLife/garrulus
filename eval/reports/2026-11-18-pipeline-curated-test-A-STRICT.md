# pipeline on curated / test (mode A, match STRICT)

Date: 2026-11-18

Documents: 80

Finder: pipeline 0.1.0-SNAPSHOT, config `f40ea7de5c5fe60938dc0db79cbcd9eb91aa0479b28ea95db4bf138f1e185192`, dictionary `316691`

OTU mentions (BOLD BINs and UNITE SHs, not scored as names): 0

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 571 | 94 | 77 | 0.859 | 0.881 | 0.870 |
| &nbsp;&nbsp;block:ABSTRACT | 147 | 24 | 14 | 0.860 | 0.913 | 0.886 |
| &nbsp;&nbsp;block:CAPTION | 186 | 6 | 57 | 0.969 | 0.765 | 0.855 |
| &nbsp;&nbsp;block:HEADING | 20 | 0 | 1 | 1.000 | 0.952 | 0.976 |
| &nbsp;&nbsp;block:PARAGRAPH | 185 | 41 | 3 | 0.819 | 0.984 | 0.894 |
| &nbsp;&nbsp;block:TABLE_CELL | 9 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| &nbsp;&nbsp;block:TITLE | 24 | 23 | 2 | 0.511 | 0.923 | 0.658 |
| &nbsp;&nbsp;kind:ABBREVIATED | 163 | 0 | 3 | 1.000 | 0.982 | 0.991 |
| &nbsp;&nbsp;kind:BINOMIAL | 175 | 5 | 4 | 0.972 | 0.978 | 0.975 |
| &nbsp;&nbsp;kind:TRINOMIAL | 6 | 0 | 3 | 1.000 | 0.667 | 0.800 |
| &nbsp;&nbsp;kind:UNINOMIAL | 227 | 89 | 67 | 0.718 | 0.772 | 0.744 |
| &nbsp;&nbsp;lang:de | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| &nbsp;&nbsp;lang:en | 491 | 68 | 73 | 0.878 | 0.871 | 0.874 |
| &nbsp;&nbsp;lang:es | 17 | 9 | 4 | 0.654 | 0.810 | 0.723 |
| &nbsp;&nbsp;lang:fr | 7 | 2 | 0 | 0.778 | 1.000 | 0.875 |
| &nbsp;&nbsp;lang:pt | 14 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| &nbsp;&nbsp;lang:und | 40 | 15 | 0 | 0.727 | 1.000 | 0.842 |
| &nbsp;&nbsp;stratum:ACTS | 221 | 41 | 70 | 0.844 | 0.759 | 0.799 |
| &nbsp;&nbsp;stratum:BROAD_EN | 55 | 7 | 3 | 0.887 | 0.948 | 0.917 |
| &nbsp;&nbsp;stratum:NON_EN | 25 | 5 | 1 | 0.833 | 0.962 | 0.893 |
| &nbsp;&nbsp;stratum:PENSOFT | 270 | 41 | 3 | 0.868 | 0.989 | 0.925 |
| acts | 49 | 2 | 41 | 0.961 | 0.544 | 0.695 |
| contexts | 15 | 20 | 18 | 0.429 | 0.455 | 0.441 |
| main subject | 7 | 86 | 5 | 0.075 | 0.583 | 0.133 |
| authorship | 59 | 15 | 66 | 0.797 | 0.472 | 0.593 |

Authorship: on the matched spans of curated documents, the gold authorship against the mention's, whitespace ignored (a wrong one counts as FP and FN).

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 0 | 0 | 0.000 |

## False positives (30 of 94 recorded)

- `openalex:W4410991980` [UNINOMIAL] sa ferganaensis Sheraliev & Peng, 2021 («Teleostei»: Nemacheilidae)
- `openalex:W7117713044` [UNINOMIAL] FILLET QUALITY DIFFERENCE OF «MICROPTERUS» SALMOIDES CULTURED UNDER DIFFERENT MODE
- `openalex:W4414511084` [UNINOMIAL] epresentatives of Pilumnus Leach, 1816 («Decapoda», Brachyura, Pilumnidae) based on molecu
- `openalex:W4413210974` [BINOMIAL] a producir híbridos interespecíficos de «Elaeis oleifera» × Elaeis guineensis (O×G), presentan va
- `openalex:W4416959617` [UNINOMIAL] lax dendrophila Belle, 1984, en Guyane («Odonata» : Libellulidae)
- `pensoft:zookeys.1229.138920` [UNINOMIAL] «Mesosoma». Mesoscutum slightly swollen, straight 
- `pensoft:zookeys.1285.182943` [UNINOMIAL] 19"W), at about 1050 m elevation in the «Atacama» Desert, Arica Province, northern Chile.
- `pensoft:bdj.13.e146229` [UNINOMIAL] observed on a leaf of Pueraria montana («Fabaceae») growing on a sunny, grassy hill (Fig. 
- `pensoft:bdj.14.e190624` [UNINOMIAL] ientificName: Cervaphis quercus; class: «Insecta»; order: Hemiptera; family: Aphididae; g
- `pensoft:zookeys.1280.182768` [UNINOMIAL] was represented by individuals from the «Chamba» region in the northwestern Himalaya of 
- `pensoft:phytokeys.271.181141` [UNINOMIAL] oquia Río Verde, Bosque Protector Cerro «Candelaria» (Fundación EcoMinga), upper Pastaza wat
- `pensoft:mycokeys.130.183564` [UNINOMIAL] itary and scattered, black, unilocular. «Peridium» 20–35 μm wide, composed of two layers, 
- `pensoft:bdj.12.e123575` [UNINOMIAL] gates atratulus (Schenck, 1852); order: «Hymenoptera»; family: Formicidae; taxonRank: species
- `pensoft:zookeys.1264.165241` [UNINOMIAL] n County, Hunan Province. Suizhou City, «Hubei» Province. T. kaijiangensis sp. nov.: Da
- `pensoft:mycokeys.110.136844` [UNINOMIAL] ard the substrate, sulcate or glabrous. «Ostiolum» periphysate. Ascomatal wall leathery to
- `pensoft:phytokeys.274.183749` [UNINOMIAL] lous hairs and sparse, glandular hairs. «Calyx» narrowly campanulate, 5.0–8.0 mm long, 
- `pensoft:zookeys.1223.137184` [UNINOMIAL]  Aug. 2024; Shuo Liu leg.; KIZ 2024083. «Paratype». China • ♀; same locality; 24 Aug. 2024
- `pensoft:phytokeys.248.123248` [UNINOMIAL] Ecuador. «Imbabura»: Ibarra. Parroquia: Lita. Unpaved road 
- `pensoft:mycokeys.124.171541` [UNINOMIAL] Bayesian inference analysis of «Entoloma» subg. Cubospora based on ITS, LSU, rpb2
- `pensoft:zookeys.1274.141366` [UNINOMIAL] e World (MEOW), and size range of world «Syrrhoe» species.
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] 5. Nitrospinia** cl. n. Cavalier-Smith («Nitrospinaceae»: Nitrospina) Subphylum 2. Acidobacteria
- `doi:10.1007/s13225-020-00459-1` [UNINOMIAL]  DNA-barcodes of species of C. subgenus «Telamonia» in the public sequence databases. This 
- `openalex:W4394689792` [UNINOMIAL] Gyrosigma dongtingense sp. nov. («Bacillariophyta») from Dongting Lake, China
- `openalex:W4403772617` [UNINOMIAL] ter, 1838, and Dictyocotes Kéler, 1940 («Phthiraptera»: Ischnocera), with descriptions of four
- `openalex:W4403399836` [UNINOMIAL] f the genus Schizotetranychus Trägårdh («Prostigmata»: Tetranychidae) from Saudi Arabia with 
- `openalex:W4405773338` [UNINOMIAL] Revision of the genus Amicula («Mollusca»: Polyplacophora: Mopaliidae)
- `openalex:W4391116097` [UNINOMIAL] mplexes of Western Australian Isopogon («Proteaceae») using RADseq
- `openalex:W4403485334` [UNINOMIAL] Columnea conopurpurea («Gesneriaceae»), a New Pendulous Epiphytic Species fro
- `openalex:W4392694265` [UNINOMIAL] ee new species of Protoancylodiscoides («Monogenea»: Dactylogyridae) and biogeography of th
- `openalex:W4415449747` [UNINOMIAL] Cinco especies nuevas de Aniba («Lauraceae») de Colombia

## False negatives (30 of 77 recorded)

- `openalex:W7117713044` [BINOMIAL] FILLET QUALITY DIFFERENCE OF «MICROPTERUS SALMOIDES» CULTURED UNDER DIFFERENT MODES
- `openalex:W4411159455` [BINOMIAL] Objectives«Klebsiella pneumoniae» is a Gram-negative pathogen responsible
- `openalex:W4414511084` [TRINOMIAL] epresentatives of Pilumnus Leach, 1816 («Decapoda, Brachyura, Pilumnidae») based on molecular markers, with comme
- `openalex:W4413210974` [TRINOMIAL] a producir híbridos interespecíficos de «Elaeis oleifera × Elaeis guineensis» (O×G), presentan variabilidad genética 
- `pensoft:zookeys.1285.182943` [UNINOMIAL] «Azaptilia» Vargas gen. nov.
- `pensoft:zookeys.1280.182768` [UNINOMIAL] stan, and (iii) the western and central «Himalaya» of Nepal. Notably, the Himalayan clades
- `pensoft:mycokeys.124.171541` [TRINOMIAL] Bayesian inference analysis of «Entoloma subg. Cubospora» based on ITS, LSU, rpb2, and tef1 seque
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] phyly, that many 'rDNA-phyla' belong in «Proteobacteria», and reveal robust new phyla Synthermot
- `doi:10.1007/s13225-020-00459-1` [ABBREVIATED]  of reliable DNA-barcodes of species of «C. subgenus Telamonia» in the public sequence databases. This 
- `openalex:W4394689792` [BINOMIAL] ma dongtingense sp. nov. belongs to the «section Strigiles» sensu H. Peragallo and it is characteri
- `openalex:W4415449747` [UNINOMIAL] Antecedentes y Objetivos: «Aniba» es un género de árboles y arbustos pert
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL]  Synthermota and Aquithermota. Monoderm «Posibacteria» and Mollicutes (two separate wall losse
- `doi:10.1007/s13225-020-00459-1` [ABBREVIATED] problems, even in difficult groups like «C. subgenus Telamonia», can be solved and consequently identif
- `openalex:W4415449747` [UNINOMIAL] ecímenes para el tratamiento del género «Aniba» para Colombia, se han identificado algu
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] etic: multiple outer membrane losses in «Endobacteria» occurred separately from Actinobacteria
- `doi:10.1007/s13225-020-00459-1` [BINOMIAL] bly of barcoded species of Cortinarius, «subgen. Telamonia» in the public sequence databases. Also 
- `openalex:W4415449747` [UNINOMIAL] ustran cinco nuevas especies del género «Aniba» para Colombia pertenecientes al grupo “
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] g. Rubrobacter, Conexibacter) Phylum 2. «Endobacteria» Cavalier-Smith 1998 as subphylum stat. 
- `doi:10.1007/s13225-020-00459-1` [ABBREVIATED] % of all the currently known species of «C. subgenus Telamonia». In recent years they have also include
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] diderm classes D; monoderms M) Class 1. «Halanaerobiia» cl. n. Cavalier-Smith (e.g. Halanaerobi
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] mith (the negibacterial sister group to «Endobacteria») Infrakingdom 1. Thermobacteria* infrak
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] yl. n. Cavalier-Smith 2019 Subphylum 1. «Synergistetes»** subphyl. n. Cavalier-Smith. Sole clas
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] osta 2002. (e.g. Meiothermus) Phylum 3. «Fusobacteria» Garrity and Holt 2012. Sole class Fusob
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] mith 2019 (sister group to infrakingdom «Gracilicutes») Class 1. Aquificia** cl. n. Cavalier-S
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] .g. Thermodesulfatator) Infrakingdom 2. «Gracilicutes»* Cavalier-Smith 2006 Parvkingdom 1. Pro
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] tes* Cavalier-Smith 2006 Parvkingdom 1. «Proteobacteria» parvk. n. Cavalier-Smith. Description a
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] as for phylum Proteobacteria. Phylum 1. «Proteobacteria» Stackebrandt et al. 1986 (as class) ex 
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] ier-Smith 2002 (as phylum) Subphylum 1. «Rhodobacteria» Cavalier-Smith 2002 (purple photosynthe
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] teria e.g. Caulobacter, Rhodospirillum, «Pelagibacter») Class 2. Chromatiia cl. n. Cavalier-Sm
- `doi:10.1007/s00709-019-01442-7` [UNINOMIAL] yl n. Cavalier-Smith description as for «Spirochaetae» Phylum 1. Spirochaetae Cavalier-Smith 2
