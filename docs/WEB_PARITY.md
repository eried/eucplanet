# Parity between the app, eucstats and the Trip Viewer

One rider uses all three: the EUC Planet app, [eucstats](https://eucstats.ried.no) and the
[Trip Viewer](https://eucviewer.ried.no). This page is the single place that says what they
agree on for languages and units, and what the app still has to do to match the two web
sites. Agreed on 2026-11-18. Change it here first, then change the three.

## What the app still has to do

1. **Add British English.** The web has `en-GB` as a separate choice; plain English is
   American spelling. Add `values-en-rGB` holding only the strings whose spelling differs
   (colour, grey, metres and so on). On eucstats that was 17 of about 620 strings.
2. **Name language variants the same way.** In the app's language picker use the display
   names in the table below: `English (US)` and `English (UK)`, `Español (LA)` and
   `Español (ES)`, `Português (BR)` and `Português (PT)`.
3. **Altitude is its own unit.** On the web, altitude (m or ft) is a third switch that no
   other switch moves. In the app it follows distance. Under the Custom preset, let altitude
   be chosen separately.
4. **Default units for the UK.** The web gives the UK, the Crown Dependencies and Myanmar
   miles with Celsius and metres. The app has no UK case and gives Myanmar full imperial.
5. **`share_scan_is_crewpass` names a menu that does not exist.** It says "Scan it from
   Settings, under Crews". The entry is `crews_pass_action` ("Crew pass") under
   `section_online_stats` ("Online leaderboards"). Reword it in every locale with that
   locale's own section label.
6. **`values-es`, `crews_scan_hint`** says "dale a Crews". The site's button in Spanish
   (Spain) is "Cuadrillas", the word the rest of `values-es` uses.
7. **A deleted trip is re-requested forever.** `EucStatsApi.getTripStatus()` returns `null`
   for a 404, the same as a network failure, so the recheck never stops. Treat 404 as
   final. Keep retrying on 5xx, 429 and timeouts.
8. **Uploads for an unregistered rider.** Twice in November one device sent 60 trips that
   were each answered `400 rider_not_registered`, then kept retrying into
   `429 rate_limited:trip_per_rider` (about once a second for 22 minutes). A 400 with that
   detail should stop the queue and register the rider; a 429 should back off.

## Languages

All three carry the same 30 languages, plus British English on the web (item 1 above).

### Browser or system language to our code

The web sites map a browser language to a code with these rules, first match wins. The app
resolves through Android resources and should land on the same language.

| Input | Code |
|---|---|
| `nb`, `nn`, `no` | `no` |
| `pt` with region PT, AO, MZ, CV, GW, ST, TL | `pt-PT` |
| any other `pt` | `pt-BR` |
| `zh` with TW, HK, MO or Hant | `zh-Hant` |
| any other `zh` | `zh` |
| `es` with region 419, MX, AR, CO, CL, PE, VE, EC, GT, CU, BO, DO, HN, PY, SV, NI, CR, PA, UY, PR, US | `es-419` |
| any other `es`, including a bare `es` | `es` (Spain) |
| `en` with region GB, UK, IE, AU, NZ, ZA, IN | `en-GB` |
| any other `en` | `en` |
| `in`, `id` | `id` |

### The shared language cookie

The two web sites share the rider's choice through a cookie, `euc_lang`, on `ried.no`
(path `/`, one year, `samesite=lax`). It is read before each site's own storage and written
on every change. A code the reading site does not have is ignored. The app is not part of
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
| `en-GB` | English (UK) | `values-en-rGB (to add)` |
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
| `zh-Hant` | 中文 (繁體) | `values-zh-rTW` |

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
| `zh-Hant` | 獨輪車 | 騎士 | 車隊 | 格 | 塊 | 行程 | 車隊通行證 | 車隊通行證 | 線上排行榜 |
