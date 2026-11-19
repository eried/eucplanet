# Parity between the app, eucstats and the Trip Viewer

One rider uses all three: the EUC Planet app, [eucstats](https://eucstats.ried.no) and the
[Trip Viewer](https://eucviewer.ried.no). This page is the single place that says what they
agree on for languages and units. Agreed on 2026-11-18. Change it here first, then change the three.

## Where each side stands

Checked on 2026-11-19 against `next-experimental` and the live web sites.

- **The app** has all of it. `WebParityTest` holds it there.
- **eucstats** has all of it. `tests/test_parity.py` holds it there.
- **The Trip Viewer** has all of it. `node tools/parity_check.js` holds it there.

All three checks read the same rules file: eucstats `tests/parity_rules.json`, copied to
`app/src/test/resources/web_parity_rules.json` here and to `tools/parity_rules.json` in the
viewer. When a rule changes, change this page and that file in all three.

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

EUC Planet, Trip Viewer, Trips Explorer (the viewer's tab, Trip Explorer until 2026-11-19), Forensics, Dropbox (Polish declines it, "w Dropboksie", as the app does), DarknessBot, euc.world, GitHub,
PWM, and every unit symbol. On eucstats "powered by EUC Planet" is also left in English.

## Language codes and display names

The display name is what the language picker shows. It is the same on all three, and so is the order: English (US) and English (UK) first, then by code, which puts Español (ES) before (LA) and Simplified before Traditional Chinese.

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

Core rider terms as eucstats uses them, beside the app's own labels for the crew pass and the settings tab it lives in. Refreshed on 2026-11-19. That day, 19 languages moved the patch word from a stain or piece word to a zone or area word, and Turkish took the app's Ekip kartı for the crew pass.

| Code | Wheel | Riders | Crews | Square | Patch | Trips | Crew pass (site) | Crew pass (app) | Settings tab (app) |
|---|---|---|---|---|---|---|---|---|---|
| `en` | wheel | Riders | Crews | square | patch | Trips | Crew Pass | Crew pass | Backups & Leaderboards |
| `en-GB` | wheel | Riders | Crews | square | patch | Trips | Crew Pass | Crew pass | Backups & Leaderboards |
| `bg` | моноколело | Ездачи | Екипи | квадрат | зона | Пътувания | Пропуск за екип | Пропуск за екип | Архиви и Leaderboards |
| `cs` | monokolo | Jezdci | Crews | čtverec | oblast | Jízdy | Průkaz crew | Průkaz crew | Zálohy a Leaderboards |
| `da` | hjul | Ryttere | Crews | felt | område | Ture | Crew-pas | Crew-pas | Backup og Leaderboards |
| `de` | Wheel | Fahrer | Crews | Feld | Zone | Fahrten | Crew-Pass | Crew-Pass | Backup & Leaderboards |
| `el` | μονότροχο | Αναβάτες | Ομάδες | τετράγωνο | ζώνη | Διαδρομές | Πάσο ομάδας | Πάσο ομάδας | Αντίγραφα & Leaderboards |
| `es` | rueda | Riders | Cuadrillas | casilla | zona | Viajes | Pase de cuadrilla | Pase de cuadrilla | Copias de seguridad y Leaderboards |
| `es-419` | rueda | Riders | Crews | casilla | zona | Viajes | Pase de crew | Pase de crew | Respaldos y Leaderboards |
| `fi` | yksipyöräinen | Ajajat | Crewit | ruutu | alue | Matkat | Crew-passi | Crew-passi | Varmuuskopiot ja Leaderboards |
| `fr` | roue | Riders | Crews | case | zone | Trajets | Pass crew | Pass crew | Sauvegardes et Leaderboards |
| `hu` | egykerekű | Egykerekűsök | Crews | négyzet | zóna | Menetek | Crew-belépő | Crew-belépő | Biztonsági mentések és Leaderboards |
| `id` | EUC | Pengendara | Kru | kotak | kawasan | Perjalanan | Pass Kru | Pass kru | Cadangan & Leaderboards |
| `it` | ruota | Rider | Squadre | casella | zona | Viaggi | Pass squadra | Pass squadra | Backup e Leaderboards |
| `ja` | ホイール | ライダー | クルー | マス | エリア | トリップ | クルーパス | クルーパス | バックアップ & Leaderboards |
| `ko` | 휠 | 라이더 | 크루 | 칸 | 영역 개 | 주행 | 크루 패스 | 크루 패스 | 백업 및 Leaderboards |
| `nl` | wiel | Rijders | Crews | vakje | gebied | Ritten | Crewpas | Crewpas | Back-ups & Leaderboards |
| `no` | hjul | Kjørere | Crews | rute | område | Turer | Crew-pass | Crew-pass | Sikkerhetskopi og Leaderboards |
| `pl` | koło | Riderzy | Ekipy | pole | strefa | Przejazdy | Przepustka ekipy | Przepustka ekipy | Kopie zapasowe i Leaderboards |
| `pt-BR` | roda | Pilotos | Turmas | quadrado | área | Viagens | Passe da turma | Passe da turma | Backups e Leaderboards |
| `pt-PT` | roda | Pilotos | Equipas | quadrado | zona | Percursos | Passe de equipa | Passe de equipa | Cópias e Leaderboards |
| `ro` | monociclu | Monocicliști | Crews | pătrat | zonă | Curse | Acces crew | Acces crew | Copii de rezervă și Leaderboards |
| `ru` | колесо | Райдеры | Кланы | клетка | зона | Поездки | Пропуск клана | Пропуск клана | Резервные копии и Leaderboards |
| `sk` | koleso | Jazdci | Posádky | štvorec | zóna | Jazdy | Preukaz posádky | Preukaz posádky | Zálohy a Leaderboards |
| `sv` | hjul | Åkare | Crews | ruta | område | Turer | Crew-pass | Crew-pass | Säkerhetskopior och Leaderboards |
| `th` | ล้อ | ผู้ขี่ | ครูว์ | ช่อง | ผืน | ทริป | บัตรผ่านครูว์ | บัตรผ่านครูว์ | สำรองข้อมูลและ Leaderboards |
| `tr` | teker | Sürücüler | Ekipler | kare | bölge | Sürüşler | Ekip kartı | Ekip kartı | Yedekler ve Leaderboards |
| `uk` | колесо | Райдери | Клани | квадрат | зона | Поїздки | Пропуск клану | Пропуск клану | Копії та Leaderboards |
| `vi` | xe | Tay lái | Đội | ô | mảng | Chuyến đi | Thẻ đội | Thẻ đội | Sao lưu & Leaderboards |
| `zh` | 独轮车 | 骑友 | 战队 | 格 | 块 | 行程 | 战队通行证 | 战队通行证 | 备份与 Leaderboards |
| `zh-TW` | 獨輪車 | 騎士 | 車隊 | 格 | 塊 | 行程 | 車隊通行證 | 車隊通行證 | 備份與 Leaderboards |
