# Parity between the app, eucstats and the Trip Viewer

One rider uses all three: the EUC Planet app, [eucstats](https://eucstats.ried.no) and the
[Trip Viewer](https://eucviewer.ried.no). This page is the single place that says what they
agree on for languages and units. Agreed on 2026-11-18. Change it here first, then change the three.

## Where each side stands

Checked on 2026-11-19 against `next-experimental` and the live web sites.

- **The app** has all of it except one string, fixed in pull request 37: `share_scan_is_crewpass`
  named a Crews section that Settings does not have.
- **eucstats** has all of it. Its test `tests/test_parity.py` fails when the site drifts from
  the rules on this page (it reads them from `tests/parity_rules.json` in that repository).
- **The Trip Viewer** is where the unit rules came from. Its languages and names were not
  checked for this page.

Nothing checks the three against each other automatically. When a rule changes, change this
page, then each project.

## Languages

All three carry the same 31 choices: 30 languages, with British English as a separate
choice beside American. The codes are the same everywhere; Traditional Chinese is `zh-TW`
(it was `zh-Hant` on eucstats until 2026-11-19).

### Browser or system language to our code

The web sites map a browser language to a code with these rules, first match wins. The app
resolves through Android resources and should land on the same language.

| Input | Code |
|---|---|
| `nb`, `nn`, `no` | `no` |
| `pt` with region PT, AO, MZ, CV, GW, ST, TL | `pt-PT` |
| any other `pt` | `pt-BR` |
| `zh` with TW, HK, MO, or any tag containing Hant | `zh-TW` |
| any other `zh` | `zh` |
| `es` with region 419, MX, AR, CO, CL, PE, VE, EC, GT, CU, BO, DO, HN, PY, SV, NI, CR, PA, UY, PR, US | `es-419` |
| any other `es`, including a bare `es` | `es` (Spain) |
| `en` with region GB, UK, IE, AU, NZ, ZA, IN | `en-GB` |
| any other `en` | `en` |
| `in`, `id` | `id` |

### The shared language cookie

The two web sites share the rider's choice through a cookie, `euc_lang`, on `ried.no`
(path `/`, one year, `samesite=lax`). It is read before each site's own storage and written
on every change. A code the reading site does not have is ignored, except that eucstats reads an older
`zh-Hant` as `zh-TW` and rewrites the cookie. The app is not part of
this; it is listed so nobody is surprised by it.

## Units

Three switches, each independent. No presets on the web, and no switch is derived from
another.

| Switch | Choices | Also decides |
|---|---|---|
| Distance | km or mi | speed (km/h or mph) |
| Temperature | °C or °F | |
| Altitude | m or ft | |

The app keeps its extra units (m/s, knots, Norwegian mile, Kelvin, tire pressure). The web
only ever shows the three switches above.

### Default when nothing is stored

Chosen from the timezone on the web, never from the interface language.

| Region | Default |
|---|---|
| US zones and territories, Liberia | mi, °F, ft |
| UK, Belfast, Jersey, Guernsey, Isle of Man, Myanmar | mi, °C, m |
| everything else, including unknown zones | km, °C, m |

### The shared units cookie

`euc_units`, same attributes as `euc_lang`. Written as
`<distance>-<temperature>-<altitude>`, for example `km-c-m`, `mi-c-m`, `mi-f-ft`. Older
values are still read: two parts (altitude was feet with Fahrenheit), and the names
`metric`, `imperial` and `road`.

## Names that stay in English in every language

EUC Planet, Trip Viewer, Trip Explorer, Forensics, Dropbox, DarknessBot, euc.world, GitHub,
PWM, and every unit symbol. On eucstats "powered by EUC Planet" is also left in English.

## Language codes and display names

The display name is what the language picker shows. It is the same on all three.

| Code | Display name | App resource folder |
|---|---|---|
| `en` | English (US) | `values` |
| `en-GB` | English (UK) | `values-en-rGB` |
| `bg` | Български | `values-bg` |
| `cs` | Čeština | `values-cs` |
| `da` | Dansk | `values-da` |
| `de` | Deutsch | `values-de` |
| `el` | Ελληνικά | `values-el` |
| `es` | Español (ES) | `values-es` |
| `es-419` | Español (LA) | `values-b+es+419` |
| `fi` | Suomi | `values-fi` |
| `fr` | Français | `values-fr` |
| `hu` | Magyar | `values-hu` |
| `id` | Bahasa Indonesia | `values-in` |
| `it` | Italiano | `values-it` |
| `ja` | 日本語 | `values-ja` |
| `ko` | 한국어 | `values-ko` |
| `nl` | Nederlands | `values-nl` |
| `no` | Norsk | `values-no` |
| `pl` | Polski | `values-pl` |
| `pt-BR` | Português (BR) | `values-pt-rBR` |
| `pt-PT` | Português (PT) | `values-pt-rPT` |
| `ro` | Română | `values-ro` |
| `ru` | Русский | `values-ru` |
| `sk` | Slovenčina | `values-sk` |
| `sv` | Svenska | `values-sv` |
| `th` | ไทย | `values-th` |
| `tr` | Türkçe | `values-tr` |
| `uk` | Українська | `values-uk` |
| `vi` | Tiếng Việt | `values-vi` |
| `zh` | 中文 (简体) | `values-zh` |
| `zh-TW` | 中文 (繁體) | `values-zh-rTW` |

## Glossary

Core rider terms as eucstats uses them today, beside the app's own two labels for the crew pass and its settings section. The site's wording was aligned to the app's strings during a full review on 2026-11-18, so a difference in the last two columns is worth a look.

| Code | Wheel | Riders | Crews | Square | Patch | Trips | Crew pass (site) | Crew pass (app) | Settings section (app) |
|---|---|---|---|---|---|---|---|---|---|
| `en` | wheel | Riders | Crews | square | patch | Trips | Crew Pass | Crew pass | Online leaderboards |
| `en-GB` | wheel | Riders | Crews | square | patch | Trips | Crew Pass | Crew pass | Online leaderboards |
| `bg` | моноколело | Ездачи | Екипи | квадрат | петно | Пътувания | Пропуск за екип | Пропуск за екип | Онлайн класации |
| `cs` | monokolo | Jezdci | Crews | čtverec | flek | Jízdy | Průkaz crew | Průkaz crew | Online žebříčky |
| `da` | hjul | Ryttere | Crews | felt | plet | Ture | Crew-pas | Crew-pas | Online-ranglister |
| `de` | Wheel | Fahrer | Crews | Feld | Fleck | Fahrten | Crew-Pass | Crew-Pass | Online-Bestenlisten |
| `el` | μονότροχο | Αναβάτες | Ομάδες | τετράγωνο | ζώνη | Διαδρομές | Πάσο ομάδας | Πάσο ομάδας | Διαδικτυακές κατατάξεις |
| `es` | rueda | Riders | Cuadrillas | casilla | mancha | Viajes | Pase de cuadrilla | Pase de cuadrilla | Clasificaciones online |
| `es-419` | rueda | Riders | Crews | casilla | mancha | Viajes | Pase de crew | Pase de crew | Clasificaciones en línea |
| `fi` | yksipyöräinen | Ajajat | Crewit | ruutu | läntti | Matkat | Crew-passi | Crew-passi | Verkkotulostaulut |
| `fr` | roue | Riders | Crews | case | zone | Trajets | Pass crew | Pass crew | Classements en ligne |
| `hu` | egykerekű | Egykerekűsök | Crews | négyzet | folt | Menetek | Crew-belépő | Crew-belépő | Online ranglisták |
| `id` | EUC | Pengendara | Kru | kotak | kawasan | Perjalanan | Pass Kru | Pass kru | Papan peringkat online |
| `it` | ruota | Rider | Squadre | casella | macchia | Viaggi | Pass squadra | Pass squadra | Classifiche online |
| `ja` | ホイール | ライダー | クルー | マス | かたまり | トリップ | クルーパス | クルーパス | オンラインリーダーボード |
| `ko` | 휠 | 라이더 | 크루 | 칸 | 덩어리 | 주행 | 크루 패스 | 크루 패스 | 온라인 리더보드 |
| `nl` | wiel | Rijders | Crews | vakje | gebied | Ritten | Crewpas | Crewpas | Online klassementen |
| `no` | hjul | Kjørere | Crews | rute | flekk | Turer | Crew-pass | Crew-pass | Ledertavler på nett |
| `pl` | koło | Riderzy | Ekipy | pole | plama | Przejazdy | Przepustka ekipy | Przepustka ekipy | Rankingi online |
| `pt-BR` | roda | Pilotos | Turmas | quadrado | mancha | Viagens | Passe da turma | Passe da turma | Rankings online |
| `pt-PT` | roda | Pilotos | Equipas | quadrado | mancha | Percursos | Passe de equipa | Passe de equipa | Classificações online |
| `ro` | monociclu | Monocicliști | Crews | pătrat | petic | Curse | Acces crew | Acces crew | Clasamente online |
| `ru` | колесо | Райдеры | Кланы | клетка | кусок | Поездки | Пропуск клана | Пропуск клана | Онлайн-таблицы лидеров |
| `sk` | koleso | Jazdci | Posádky | štvorec | ostrov | Jazdy | Preukaz posádky | Preukaz posádky | Online rebríčky |
| `sv` | hjul | Åkare | Crews | ruta | fläck | Turer | Crew-pass | Crew-pass | Online-topplistor |
| `th` | ล้อ | ผู้ขี่ | ครูว์ | ช่อง | ผืน | ทริป | บัตรผ่านครูว์ | บัตรผ่านครูว์ | กระดานผู้นำออนไลน์ |
| `tr` | teker | Sürücüler | Ekipler | kare | parça | Sürüşler | Ekip pası | Ekip pası | Çevrimiçi lider tabloları |
| `uk` | колесо | Райдери | Клани | квадрат | шматок | Поїздки | Пропуск клану | Пропуск клану | Онлайн-таблиці лідерів |
| `vi` | xe | Tay lái | Đội | ô | mảng | Chuyến đi | Thẻ đội | Thẻ đội | Bảng xếp hạng trực tuyến |
| `zh` | 独轮车 | 骑友 | 战队 | 格 | 块 | 行程 | 战队通行证 | 战队通行证 | 在线排行榜 |
| `zh-TW` | 獨輪車 | 騎士 | 車隊 | 格 | 塊 | 行程 | 車隊通行證 | 車隊通行證 | 線上排行榜 |
