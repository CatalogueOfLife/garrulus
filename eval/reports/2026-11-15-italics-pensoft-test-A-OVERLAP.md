# italics on pensoft / test (mode A, match OVERLAP)

Date: 2026-11-15

Documents: 200

Finder: italics 0.1, config `italics-1-3-words`

- The JSON keeps the first 500 error samples of each kind: 500 of 4313 recorded false positives, 500 of 5000 false negatives. All counts are complete.

| Metric | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| names | 31541 | 4313 | 7654 | 0.880 | 0.805 | 0.841 |
| &nbsp;&nbsp;block:ABSTRACT | 1270 | 16 | 290 | 0.988 | 0.814 | 0.892 |
| &nbsp;&nbsp;block:CAPTION | 2922 | 186 | 306 | 0.940 | 0.905 | 0.922 |
| &nbsp;&nbsp;block:FOOTNOTE | 16 | 0 | 1 | 1.000 | 0.941 | 0.970 |
| &nbsp;&nbsp;block:HEADING | 150 | 3 | 905 | 0.980 | 0.142 | 0.248 |
| &nbsp;&nbsp;block:PARAGRAPH | 18098 | 3795 | 3936 | 0.827 | 0.821 | 0.824 |
| &nbsp;&nbsp;block:TABLE_CELL | 8899 | 310 | 1839 | 0.966 | 0.829 | 0.892 |
| &nbsp;&nbsp;block:TITLE | 186 | 3 | 377 | 0.984 | 0.330 | 0.495 |
| &nbsp;&nbsp;kind:ABBREVIATED | 13420 | 808 | 621 | 0.943 | 0.956 | 0.949 |
| &nbsp;&nbsp;kind:BINOMIAL | 11421 | 675 | 1023 | 0.944 | 0.918 | 0.931 |
| &nbsp;&nbsp;kind:TRINOMIAL | 128 | 299 | 414 | 0.300 | 0.236 | 0.264 |
| &nbsp;&nbsp;kind:UNINOMIAL | 6572 | 2531 | 5596 | 0.722 | 0.540 | 0.618 |
| &nbsp;&nbsp;lang:en | 27979 | 4189 | 5975 | 0.870 | 0.824 | 0.846 |
| &nbsp;&nbsp;lang:und | 3562 | 124 | 1679 | 0.966 | 0.680 | 0.798 |
| acts | 0 | 0 | 311 | 0.000 | 0.000 | 0.000 |
| contexts | 0 | 0 | 1612 | 0.000 | 0.000 | 0.000 |
| main subject | 158 | 30 | 295 | 0.840 | 0.349 | 0.493 |

Name correct: of the matched spans whose gold has a full name, how many mentions carry that name (whitespace normalised). It does not change the name P/R/F1 above.

| Name correct | Correct | Matched | Rate |
|---|---:|---:|---:|
| names | 17604 | 31540 | 0.558 |
| &nbsp;&nbsp;kind:ABBREVIATED | 0 | 13420 | 0.000 |
| &nbsp;&nbsp;kind:BINOMIAL | 11001 | 11421 | 0.963 |
| &nbsp;&nbsp;kind:TRINOMIAL | 96 | 128 | 0.750 |
| &nbsp;&nbsp;kind:UNINOMIAL | 6507 | 6571 | 0.990 |

## False positives (30 of 4313 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] yl phosphate synthetase domain protein («CAD»), two fragments of elongation factor 1 
- `pensoft:bdj.14.e191590` [UNINOMIAL] «Food-sharing»: when a family member of a hierarchical
- `pensoft:zookeys.1196.117947` [BINOMIAL] Agarwal I (2024) Two new species of the «Cnemaspis galaxia» complex (Squamata, Gekkonidae) from the
- `pensoft:mycokeys.110.134132` [BINOMIAL] -C (2024) Interesting mycoparasites and «Paradingleyomyces lepidopterorum» gen. et sp. nov. (Hypocreales, Polyceph
- `pensoft:zookeys.1279.188050` [BINOMIAL]  AV, Le MD, Ziegler T, Nguyen TQ (2026) «Sphenomorphus puhoatensis» (Squamata, Scincidae), a new skink from
- `pensoft:phytokeys.279.207098` [UNINOMIAL] ve recovered the genus as sister to the «Apocopis–Germainia–Pogonatherum» clade, supporting its placement in Germ
- `pensoft:mycokeys.124.172775` [UNINOMIAL] ang W-Y, Wang X-C (2025) New species of «Aspergillus» in sections Cavernicolarum and Nigri fr
- `pensoft:bdj.14.e180342` [UNINOMIAL] 2.47cm) including extreme measurements («IEM»). H varies from 0.95cm to 3,000.00cm (F
- `pensoft:mycokeys.125.168474` [UNINOMIAL]  that combined multi-locus (SSU-ITS-LSU-«RPB1»-Act) phylogenetic analyses with detaile
- `pensoft:zookeys.1236.145914` [UNINOMIAL] «Head.» Head and eyes slightly exserted above p
- `pensoft:zookeys.1268.173464` [UNINOMIAL] «Holotype»: • NCUMB_CL250901, shell length 185.21 
- `pensoft:zookeys.1214.132950` [BINOMIAL] z J (2024) Complementary description of «Peltidium nayarit» (Copepoda, Harpaticoida, Peltidiidae) a
- `pensoft:mycokeys.123.160960` [UNINOMIAL] ification of new species and records of «Daldinia» (Hypoxylaceae, Xylariales) from Guizhou
- `pensoft:phytokeys.251.135126` [BINOMIAL] , He S-T, Wen F, Bai X-X, Li M-J (2025) «Primulina xingyiensis» (Gesneriaceae), a new species in the ka
- `pensoft:mycokeys.133.187172` [UNINOMIAL] 6) Taxonomy and phylogeny of five novel «Trichoderma» (Hypocreaceae, Hypocreales) species iso
- `pensoft:mycokeys.107.122026` [UNINOMIAL]  L, Zhang X (2024) Three new species of «Pestalotiopsis» (Amphisphaeriales, Sporocadaceae) were 
- `pensoft:zookeys.1275.178507` [UNINOMIAL] «Lectotype». • (Designated herein) NHMUK 1946.9.7.5
- `pensoft:phytokeys.251.137015` [BINOMIAL] Y, Wang B-M, Huang Y-S, Tong Y-H (2025) «Agapetes hongheensis» (Ericaceae), a new species from Yunnan,
- `pensoft:zookeys.1265.168108` [UNINOMIAL] «Holotype». Sudan • 1 ♂; Khartoum State, Jebel Aul
- `pensoft:phytokeys.257.153202` [BINOMIAL] g H-C (2025) Taxonomic clarification of «Silene dawoensis» (Caryophyllaceae): synonymization with 
- `pensoft:zookeys.1232.142699` [UNINOMIAL] «Holotype» • ♂, sequenced (Voucher Id: CCDB-48498-
- `pensoft:mycokeys.128.178090` [UNINOMIAL] «Saprobic» on the surface of decaying bamboo culms
- `pensoft:phytokeys.270.174879` [BINOMIAL] n Y, Yang H, Liu C, Song X, Ma S (2026) «Impatiens longcanggouensis» (Balsaminaceae): a new species from Sic
- `pensoft:mycokeys.134.187344` [UNINOMIAL] M, Janik P, Zubek S (2026) A new genus («Durabilispora») and two new species (D. carpatica, Dom
- `pensoft:zookeys.1210.125611` [UNINOMIAL]  Jin Z, Ye Z (2024) Taxonomic review of «Amemboa» Esaki, 1925 from China, with descriptio
- `pensoft:phytokeys.280.206680` [ABBREVIATED] ict [now Lamu County], Witu, June 1957, «S. Rawlins EAH-11267» (holotype: EA!: isotype: K [K003452275]
- `pensoft:zookeys.1216.128080` [UNINOMIAL]  Cong P (2024) A new epigean species of «Trichopeltis» Pocock, 1894 from southwest China (Dipl
- `pensoft:zookeys.1240.151021` [UNINOMIAL]  Sanoamuang L (2025) Two new species of «Nitocrella» (Copepoda, Harpacticoida) from a spring
- `pensoft:phytokeys.263.163711` [BINOMIAL] Yue X, Pan X, Liu Y, Ye J (2025) «Pternopetalum wulingense» (Apiaceae), a new species from the Wuli
- `pensoft:phytokeys.276.181524` [UNINOMIAL] . 2020), concatenation being allowed as «P» = 0.12. The best substitution models, G

## False negatives (30 of 5000 recorded)

- `pensoft:zookeys.1291.204651` [UNINOMIAL] tic placement of ectoparasitic families «Cyclotornidae» and Epipyropidae: Multigene insights in
- `pensoft:bdj.14.e161845` [UNINOMIAL] The Mollusc Collection (Class «Polyplacophora») at the Museum of Zoology, University o
- `pensoft:bdj.12.e118010` [UNINOMIAL] ng Initiative Database: DNA barcodes of «Orthoptera» from Portugal
- `pensoft:zookeys.1196.117947` [UNINOMIAL] ecies of the Cnemaspis galaxia complex («Squamata», Gekkonidae) from the eastern slopes of
- `pensoft:mycokeys.110.134132` [UNINOMIAL] omyces lepidopterorum gen. et sp. nov. («Hypocreales», Polycephalomycetaceae) from Yunnan Pro
- `pensoft:bdj.14.e186195` [UNINOMIAL] al world. Here, we provide a dataset on «Amphipoda» species reported in the scientific lite
- `pensoft:zookeys.1279.188050` [UNINOMIAL] Sphenomorphus puhoatensis («Squamata», Scincidae), a new skink from Nghe An P
- `pensoft:phytokeys.279.207098` [UNINOMIAL] natomy and micromorphology of Imperata («Poaceae», Andropogoneae) from Brazil and their t
- `pensoft:bdj.12.e134521` [UNINOMIAL] axonomy of Korean Ischnochiton species («Polyplacophora», Ischnochitonidae) based on a combined 
- `pensoft:mycokeys.124.172775` [UNINOMIAL] i from terrestrial ecosystems of China («Eurotiales», Aspergillaceae)
- `pensoft:bdj.14.e180342` [UNINOMIAL] d 280 genera. The dominant families are «Asteraceae» (23% of the endemic taxa), Caryophyllac
- `pensoft:zookeys.1244.146861` [UNINOMIAL] Scuttle flies («Diptera», Phoridae) collected by mosquito trap f
- `pensoft:mycokeys.125.168474` [UNINOMIAL] Unveiling species diversity within «Mortierellomycota» from China X: Three new species in Linn
- `pensoft:zookeys.1236.145914` [UNINOMIAL] al phylogenomics of pygmy grasshoppers («Orthoptera», Tetrigidae, Metrodorinae): description
- `pensoft:zookeys.1225.125030` [UNINOMIAL] ering behaviour of terrestrial isopods («Isopoda», Oniscidea): a field experiment
- `pensoft:zookeys.1268.173464` [UNINOMIAL] ision of the freshwater mussel subtribe «Cristariina» (Bivalvia, Unionidae) in China: descrip
- `pensoft:zookeys.1214.132950` [UNINOMIAL] ntary description of Peltidium nayarit («Copepoda», Harpaticoida, Peltidiidae) and a new r
- `pensoft:mycokeys.123.160960` [UNINOMIAL] of new species and records of Daldinia («Hypoxylaceae», Xylariales) from Guizhou Province, Chi
- `pensoft:phytokeys.251.135126` [UNINOMIAL] Primulina xingyiensis («Gesneriaceae»), a new species in the karst landforms 
- `pensoft:mycokeys.133.187172` [UNINOMIAL] nd phylogeny of five novel Trichoderma («Hypocreaceae», Hypocreales) species isolated from dec
- `pensoft:mycokeys.107.122026` [UNINOMIAL] Three new species of Pestalotiopsis («Amphisphaeriales», Sporocadaceae) were identified by morp
- `pensoft:zookeys.1275.178507` [BINOMIAL]  Nepal revealed by a new species of the «Cyrtodactylus (Geckoella) nebulosus» complex (Reptilia, Squamata) from Nepal
- `pensoft:phytokeys.251.137015` [UNINOMIAL] Agapetes hongheensis («Ericaceae»), a new species from Yunnan, China
- `pensoft:zookeys.1265.168108` [UNINOMIAL] Paragomphus alami sp. nov. («Odonata», Gomphidae): a new dragonfly species de
- `pensoft:phytokeys.257.153202` [UNINOMIAL] omic clarification of Silene dawoensis («Caryophyllaceae»): synonymization with S. batangensis an
- `pensoft:zookeys.1232.142699` [UNINOMIAL] ith the description of one new species («Acari», Hydrachnidia)
- `pensoft:zookeys.1258.168452` [UNINOMIAL] Monomicrocaryon trimarginalis sp. nov. («Ciliophora», Hypotricha), a new soil ciliate specie
- `pensoft:mycokeys.128.178090` [UNINOMIAL] ovelties and phylogenetic insights into «Cainiaceae» (Xylariales, Sordariomycetes) associate
- `pensoft:phytokeys.270.174879` [UNINOMIAL] Impatiens longcanggouensis («Balsaminaceae»): a new species from Sichuan, China
- `pensoft:bdj.12.e116039` [UNINOMIAL] ate flatworm predating on a rhabdocoel («Platyhelminthes»: Proseriata and Rhabdocoela)
