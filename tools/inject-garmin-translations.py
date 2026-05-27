#!/usr/bin/env python3
"""Injects the 20 Garmin-branch UI strings into every locale file.

Run from repo root: `python tools/inject-garmin-translations.py`
Safe to re-run — each insertion is idempotent on the key name.
Universal strings (Wear OS, Garmin Connect IQ, %1$.1f Hz) are written
verbatim across all locales.
"""

import re
import sys
from pathlib import Path

# Brand-name + format-string keys that stay literal everywhere.
UNIVERSAL = {
    "watch_paired_kind_wear":   "Wear OS",
    "watch_paired_kind_garmin": "Garmin Connect IQ",
    "watch_paired_rate_hz":     "%1$.1f Hz",
}

# Per-locale translations for the 17 narrative strings. English is the
# source of truth; each locale has all 17 keys + the 3 universals merged
# in below. Keep insertion order matching the source file so diffs read
# cleanly.
TRANSLATIONS = {
    "es": {
        "section_watch_device":               "Dispositivos",
        "section_watch_paired":               "Dispositivos vinculados",
        "watch_paired_none":                  "Ningún reloj vinculado",
        "watch_paired_none_desc":             "Instala la aplicación complementaria EUC Planet en un reloj Wear OS o Garmin para empezar a recibir el dial aquí",
        "watch_paired_active":                "En vivo",
        "watch_paired_idle":                  "Inactivo",
        "watch_paired_garmin_limits_title":   "Limitaciones de Garmin",
        "watch_paired_garmin_limit_launch":   "Abre la aplicación del reloj manualmente al comienzo de cada viaje. Garmin no permite que las aplicaciones del teléfono lancen automáticamente la app complementaria.",
        "watch_paired_garmin_limit_rate":     "La telemetría se actualiza más lento que en Wear OS. El SDK móvil de Connect IQ limita la frecuencia de actualización.",
        "watch_paired_garmin_limit_rotation": "La rotación del dial no está disponible. Connect IQ no tiene una matriz de transformación nativa para rotar el dial renderizado.",
        "section_watch_hardware_buttons":     "Botones físicos",
        "watch_hardware_button_1":            "Botón 1",
        "watch_hardware_button_2":            "Botón 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: botón de Acción naranja",
        "watch_hardware_button_2_subtitle":   "Garmin: Arriba (mantener) · Galaxy Watch Ultra: botón lateral inferior",
        "section_watch_screen_buttons_desc":  "Áreas táctiles dibujadas sobre el dial del reloj",
        "section_watch_hardware_buttons_desc": "Botones laterales del reloj",
    },
    "b+es+419": {
        "section_watch_device":               "Dispositivos",
        "section_watch_paired":               "Dispositivos vinculados",
        "watch_paired_none":                  "Ningún reloj vinculado",
        "watch_paired_none_desc":             "Instalá la app complementaria EUC Planet en un reloj Wear OS o Garmin para empezar a recibir el dial acá",
        "watch_paired_active":                "En vivo",
        "watch_paired_idle":                  "Inactivo",
        "watch_paired_garmin_limits_title":   "Limitaciones de Garmin",
        "watch_paired_garmin_limit_launch":   "Abrí la app del reloj manualmente al comienzo de cada viaje. Garmin no permite que las apps del teléfono lancen automáticamente la app complementaria.",
        "watch_paired_garmin_limit_rate":     "La telemetría se actualiza más lento que en Wear OS. El SDK móvil de Connect IQ limita la frecuencia de actualización.",
        "watch_paired_garmin_limit_rotation": "La rotación del dial no está disponible. Connect IQ no tiene una matriz de transformación nativa para rotar el dial renderizado.",
        "section_watch_hardware_buttons":     "Botones físicos",
        "watch_hardware_button_1":            "Botón 1",
        "watch_hardware_button_2":            "Botón 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: botón de Acción naranja",
        "watch_hardware_button_2_subtitle":   "Garmin: Arriba (mantener) · Galaxy Watch Ultra: botón lateral inferior",
        "section_watch_screen_buttons_desc":  "Áreas táctiles dibujadas sobre el dial del reloj",
        "section_watch_hardware_buttons_desc": "Botones laterales del reloj",
    },
    "no": {
        "section_watch_device":               "Enheter",
        "section_watch_paired":               "Sammenkoblede enheter",
        "watch_paired_none":                  "Ingen klokker sammenkoblet",
        "watch_paired_none_desc":             "Installer EUC Planet-følgeappen på en Wear OS- eller Garmin-klokke for å begynne å motta urskiven her",
        "watch_paired_active":                "Live",
        "watch_paired_idle":                  "Inaktiv",
        "watch_paired_garmin_limits_title":   "Garmin-begrensninger",
        "watch_paired_garmin_limit_launch":   "Åpne klokkeappen manuelt før hver tur. Garmin tillater ikke at telefonapper auto-starter følgeappen.",
        "watch_paired_garmin_limit_rate":     "Telemetri oppdateres tregere enn på Wear OS. Connect IQ Mobile SDK begrenser oppdateringsraten.",
        "watch_paired_garmin_limit_rotation": "Rotasjon av urskiven er ikke tilgjengelig. Connect IQ har ingen innebygd transformasjonsmatrise for å rotere den tegnede urskiven.",
        "section_watch_hardware_buttons":     "Fysiske knapper",
        "watch_hardware_button_1":            "Knapp 1",
        "watch_hardware_button_2":            "Knapp 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: oransje Action-knapp",
        "watch_hardware_button_2_subtitle":   "Garmin: Opp (lang trykk) · Galaxy Watch Ultra: nedre sideknapp",
        "section_watch_screen_buttons_desc":  "Berøringsfelt tegnet på urskiven",
        "section_watch_hardware_buttons_desc": "Sideknapper på klokken",
    },
    "de": {
        "section_watch_device":               "Geräte",
        "section_watch_paired":               "Gekoppelte Geräte",
        "watch_paired_none":                  "Keine Uhren gekoppelt",
        "watch_paired_none_desc":             "Installiere die EUC Planet-Begleit-App auf einer Wear OS- oder Garmin-Uhr, um das Zifferblatt hier zu empfangen",
        "watch_paired_active":                "Live",
        "watch_paired_idle":                  "Inaktiv",
        "watch_paired_garmin_limits_title":   "Garmin-Einschränkungen",
        "watch_paired_garmin_limit_launch":   "Öffne die Uhren-App manuell zu Beginn jeder Fahrt. Garmin erlaubt es Telefon-Apps nicht, die Begleit-App automatisch zu starten.",
        "watch_paired_garmin_limit_rate":     "Die Telemetrie aktualisiert sich langsamer als bei Wear OS. Das Connect IQ Mobile SDK begrenzt die Aktualisierungsrate.",
        "watch_paired_garmin_limit_rotation": "Drehung des Zifferblatts ist nicht verfügbar. Connect IQ hat keine native Transformationsmatrix für das gerenderte Zifferblatt.",
        "section_watch_hardware_buttons":     "Hardware-Tasten",
        "watch_hardware_button_1":            "Taste 1",
        "watch_hardware_button_2":            "Taste 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: orange Action-Taste",
        "watch_hardware_button_2_subtitle":   "Garmin: Oben (lang drücken) · Galaxy Watch Ultra: untere Seitentaste",
        "section_watch_screen_buttons_desc":  "Tippflächen auf dem Zifferblatt der Uhr",
        "section_watch_hardware_buttons_desc": "Seitentasten der Uhr",
    },
    "fr": {
        "section_watch_device":               "Appareils",
        "section_watch_paired":               "Appareils appairés",
        "watch_paired_none":                  "Aucune montre appairée",
        "watch_paired_none_desc":             "Installez l\\'application compagnon EUC Planet sur une montre Wear OS ou Garmin pour commencer à recevoir le cadran ici",
        "watch_paired_active":                "En direct",
        "watch_paired_idle":                  "Inactif",
        "watch_paired_garmin_limits_title":   "Limitations de Garmin",
        "watch_paired_garmin_limit_launch":   "Ouvrez l\\'application de la montre manuellement au début de chaque trajet. Garmin n\\'autorise pas les applications téléphoniques à lancer automatiquement l\\'application compagnon.",
        "watch_paired_garmin_limit_rate":     "La télémétrie se rafraîchit plus lentement que sur Wear OS. Le SDK Mobile Connect IQ limite la fréquence de mise à jour.",
        "watch_paired_garmin_limit_rotation": "La rotation du cadran n\\'est pas disponible. Connect IQ ne dispose pas de matrice de transformation native pour faire pivoter le cadran rendu.",
        "section_watch_hardware_buttons":     "Boutons physiques",
        "watch_hardware_button_1":            "Bouton 1",
        "watch_hardware_button_2":            "Bouton 2",
        "watch_hardware_button_1_subtitle":   "Garmin : Start · Galaxy Watch Ultra : bouton Action orange",
        "watch_hardware_button_2_subtitle":   "Garmin : Haut (appui long) · Galaxy Watch Ultra : bouton latéral inférieur",
        "section_watch_screen_buttons_desc":  "Zones tactiles dessinées sur le cadran de la montre",
        "section_watch_hardware_buttons_desc": "Boutons latéraux de la montre",
    },
    "it": {
        "section_watch_device":               "Dispositivi",
        "section_watch_paired":               "Dispositivi accoppiati",
        "watch_paired_none":                  "Nessun orologio accoppiato",
        "watch_paired_none_desc":             "Installa l\\'app companion EUC Planet su un orologio Wear OS o Garmin per iniziare a ricevere il quadrante qui",
        "watch_paired_active":                "Live",
        "watch_paired_idle":                  "Inattivo",
        "watch_paired_garmin_limits_title":   "Limitazioni Garmin",
        "watch_paired_garmin_limit_launch":   "Apri manualmente l\\'app dell\\'orologio all\\'inizio di ogni viaggio. Garmin non consente alle app del telefono di avviare automaticamente l\\'app companion.",
        "watch_paired_garmin_limit_rate":     "La telemetria si aggiorna più lentamente rispetto a Wear OS. Il SDK Mobile di Connect IQ limita la frequenza di aggiornamento.",
        "watch_paired_garmin_limit_rotation": "La rotazione del quadrante non è disponibile. Connect IQ non ha una matrice di trasformazione nativa per ruotare il quadrante renderizzato.",
        "section_watch_hardware_buttons":     "Pulsanti hardware",
        "watch_hardware_button_1":            "Pulsante 1",
        "watch_hardware_button_2":            "Pulsante 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: pulsante Action arancione",
        "watch_hardware_button_2_subtitle":   "Garmin: Su (pressione lunga) · Galaxy Watch Ultra: pulsante laterale inferiore",
        "section_watch_screen_buttons_desc":  "Aree tattili disegnate sul quadrante dell\\'orologio",
        "section_watch_hardware_buttons_desc": "Pulsanti laterali dell\\'orologio",
    },
    "nl": {
        "section_watch_device":               "Apparaten",
        "section_watch_paired":               "Gekoppelde apparaten",
        "watch_paired_none":                  "Geen horloges gekoppeld",
        "watch_paired_none_desc":             "Installeer de EUC Planet-begeleidingsapp op een Wear OS- of Garmin-horloge om hier de wijzerplaat te ontvangen",
        "watch_paired_active":                "Live",
        "watch_paired_idle":                  "Inactief",
        "watch_paired_garmin_limits_title":   "Garmin-beperkingen",
        "watch_paired_garmin_limit_launch":   "Open de horloge-app handmatig aan het begin van elke rit. Garmin staat telefoon-apps niet toe om de begeleidingsapp automatisch te starten.",
        "watch_paired_garmin_limit_rate":     "Telemetrie wordt langzamer ververst dan op Wear OS. De Connect IQ Mobile SDK beperkt de updatefrequentie.",
        "watch_paired_garmin_limit_rotation": "Rotatie van de wijzerplaat is niet beschikbaar. Connect IQ heeft geen native transformatiematrix om de wijzerplaat te draaien.",
        "section_watch_hardware_buttons":     "Hardware-knoppen",
        "watch_hardware_button_1":            "Knop 1",
        "watch_hardware_button_2":            "Knop 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: oranje Action-knop",
        "watch_hardware_button_2_subtitle":   "Garmin: Omhoog (lang indrukken) · Galaxy Watch Ultra: onderste zijknop",
        "section_watch_screen_buttons_desc":  "Aanraakgebieden op de wijzerplaat",
        "section_watch_hardware_buttons_desc": "Zijknoppen van het horloge",
    },
    "da": {
        "section_watch_device":               "Enheder",
        "section_watch_paired":               "Parrede enheder",
        "watch_paired_none":                  "Ingen ure parret",
        "watch_paired_none_desc":             "Installer EUC Planet-følgeappen på et Wear OS- eller Garmin-ur for at begynde at modtage urskiven her",
        "watch_paired_active":                "Live",
        "watch_paired_idle":                  "Inaktiv",
        "watch_paired_garmin_limits_title":   "Garmin-begrænsninger",
        "watch_paired_garmin_limit_launch":   "Åbn ur-appen manuelt i starten af hver tur. Garmin tillader ikke, at telefonapps auto-starter følgeappen.",
        "watch_paired_garmin_limit_rate":     "Telemetri opdateres langsommere end på Wear OS. Connect IQ Mobile SDK begrænser opdateringsfrekvensen.",
        "watch_paired_garmin_limit_rotation": "Rotation af urskiven er ikke tilgængelig. Connect IQ har ingen indbygget transformationsmatrix til at rotere urskiven.",
        "section_watch_hardware_buttons":     "Fysiske knapper",
        "watch_hardware_button_1":            "Knap 1",
        "watch_hardware_button_2":            "Knap 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: orange Action-knap",
        "watch_hardware_button_2_subtitle":   "Garmin: Op (langt tryk) · Galaxy Watch Ultra: nederste sideknap",
        "section_watch_screen_buttons_desc":  "Tryk-områder tegnet på urskiven",
        "section_watch_hardware_buttons_desc": "Sideknapper på uret",
    },
    "pl": {
        "section_watch_device":               "Urządzenia",
        "section_watch_paired":               "Sparowane urządzenia",
        "watch_paired_none":                  "Brak sparowanych zegarków",
        "watch_paired_none_desc":             "Zainstaluj aplikację towarzyszącą EUC Planet na zegarku Wear OS lub Garmin, aby zacząć odbierać tutaj tarczę",
        "watch_paired_active":                "Na żywo",
        "watch_paired_idle":                  "Nieaktywny",
        "watch_paired_garmin_limits_title":   "Ograniczenia Garmin",
        "watch_paired_garmin_limit_launch":   "Otwórz aplikację na zegarku ręcznie na początku każdej jazdy. Garmin nie pozwala aplikacjom z telefonu automatycznie uruchamiać aplikacji towarzyszącej.",
        "watch_paired_garmin_limit_rate":     "Telemetria odświeża się wolniej niż w Wear OS. SDK Connect IQ Mobile ogranicza częstotliwość aktualizacji.",
        "watch_paired_garmin_limit_rotation": "Obrót tarczy nie jest dostępny. Connect IQ nie ma natywnej macierzy transformacji do obracania renderowanej tarczy.",
        "section_watch_hardware_buttons":     "Przyciski sprzętowe",
        "watch_hardware_button_1":            "Przycisk 1",
        "watch_hardware_button_2":            "Przycisk 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: pomarańczowy przycisk Action",
        "watch_hardware_button_2_subtitle":   "Garmin: W górę (długie naciśnięcie) · Galaxy Watch Ultra: dolny przycisk boczny",
        "section_watch_screen_buttons_desc":  "Obszary dotykowe narysowane na tarczy zegarka",
        "section_watch_hardware_buttons_desc": "Boczne przyciski zegarka",
    },
    "pt-rBR": {
        "section_watch_device":               "Dispositivos",
        "section_watch_paired":               "Dispositivos pareados",
        "watch_paired_none":                  "Nenhum relógio pareado",
        "watch_paired_none_desc":             "Instale o aplicativo complementar EUC Planet em um relógio Wear OS ou Garmin para começar a receber o mostrador aqui",
        "watch_paired_active":                "Ao vivo",
        "watch_paired_idle":                  "Inativo",
        "watch_paired_garmin_limits_title":   "Limitações do Garmin",
        "watch_paired_garmin_limit_launch":   "Abra o aplicativo do relógio manualmente no início de cada viagem. O Garmin não permite que aplicativos do telefone iniciem automaticamente o aplicativo complementar.",
        "watch_paired_garmin_limit_rate":     "A telemetria atualiza mais devagar do que no Wear OS. O SDK Mobile do Connect IQ limita a taxa de atualização.",
        "watch_paired_garmin_limit_rotation": "A rotação do mostrador não está disponível. O Connect IQ não tem uma matriz de transformação nativa para girar o mostrador renderizado.",
        "section_watch_hardware_buttons":     "Botões físicos",
        "watch_hardware_button_1":            "Botão 1",
        "watch_hardware_button_2":            "Botão 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: botão de Ação laranja",
        "watch_hardware_button_2_subtitle":   "Garmin: Cima (pressionar longo) · Galaxy Watch Ultra: botão lateral inferior",
        "section_watch_screen_buttons_desc":  "Áreas de toque desenhadas no mostrador do relógio",
        "section_watch_hardware_buttons_desc": "Botões laterais do relógio",
    },
    "ru": {
        "section_watch_device":               "Устройства",
        "section_watch_paired":               "Подключённые устройства",
        "watch_paired_none":                  "Часы не подключены",
        "watch_paired_none_desc":             "Установите приложение-компаньон EUC Planet на часы Wear OS или Garmin, чтобы циферблат начал отображаться здесь",
        "watch_paired_active":                "В эфире",
        "watch_paired_idle":                  "Бездействует",
        "watch_paired_garmin_limits_title":   "Ограничения Garmin",
        "watch_paired_garmin_limit_launch":   "Открывайте приложение на часах вручную в начале каждой поездки. Garmin не позволяет телефонным приложениям автоматически запускать приложение-компаньон.",
        "watch_paired_garmin_limit_rate":     "Телеметрия обновляется медленнее, чем на Wear OS. SDK Connect IQ Mobile ограничивает частоту обновления.",
        "watch_paired_garmin_limit_rotation": "Поворот циферблата недоступен. Connect IQ не имеет встроенной матрицы преобразования для поворота отрисованного циферблата.",
        "section_watch_hardware_buttons":     "Физические кнопки",
        "watch_hardware_button_1":            "Кнопка 1",
        "watch_hardware_button_2":            "Кнопка 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: оранжевая кнопка Action",
        "watch_hardware_button_2_subtitle":   "Garmin: Вверх (долгое нажатие) · Galaxy Watch Ultra: нижняя боковая кнопка",
        "section_watch_screen_buttons_desc":  "Сенсорные области, отрисованные на циферблате",
        "section_watch_hardware_buttons_desc": "Боковые кнопки часов",
    },
    "sv": {
        "section_watch_device":               "Enheter",
        "section_watch_paired":               "Parade enheter",
        "watch_paired_none":                  "Inga klockor parade",
        "watch_paired_none_desc":             "Installera EUC Planet-följeappen på en Wear OS- eller Garmin-klocka för att börja ta emot urtavlan här",
        "watch_paired_active":                "Live",
        "watch_paired_idle":                  "Inaktiv",
        "watch_paired_garmin_limits_title":   "Garmin-begränsningar",
        "watch_paired_garmin_limit_launch":   "Öppna klockappen manuellt i början av varje åktur. Garmin tillåter inte att telefonappar auto-startar följeappen.",
        "watch_paired_garmin_limit_rate":     "Telemetri uppdateras långsammare än på Wear OS. Connect IQ Mobile SDK begränsar uppdateringsfrekvensen.",
        "watch_paired_garmin_limit_rotation": "Rotation av urtavlan är inte tillgänglig. Connect IQ saknar inbyggd transformationsmatris för att rotera den renderade urtavlan.",
        "section_watch_hardware_buttons":     "Fysiska knappar",
        "watch_hardware_button_1":            "Knapp 1",
        "watch_hardware_button_2":            "Knapp 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: orange Action-knapp",
        "watch_hardware_button_2_subtitle":   "Garmin: Upp (lång tryckning) · Galaxy Watch Ultra: nedre sidoknapp",
        "section_watch_screen_buttons_desc":  "Tryckytor ritade på urtavlan",
        "section_watch_hardware_buttons_desc": "Sidoknappar på klockan",
    },
    "uk": {
        "section_watch_device":               "Пристрої",
        "section_watch_paired":               "Парні пристрої",
        "watch_paired_none":                  "Годинники не з\\'єднано",
        "watch_paired_none_desc":             "Установіть супутній застосунок EUC Planet на годинник Wear OS або Garmin, щоб циферблат почав відображатися тут",
        "watch_paired_active":                "У ефірі",
        "watch_paired_idle":                  "Не активно",
        "watch_paired_garmin_limits_title":   "Обмеження Garmin",
        "watch_paired_garmin_limit_launch":   "Відкривайте застосунок на годиннику вручну на початку кожної поїздки. Garmin не дозволяє застосункам з телефону автоматично запускати супутній застосунок.",
        "watch_paired_garmin_limit_rate":     "Телеметрія оновлюється повільніше, ніж на Wear OS. SDK Connect IQ Mobile обмежує частоту оновлення.",
        "watch_paired_garmin_limit_rotation": "Обертання циферблату недоступне. Connect IQ не має вбудованої матриці перетворення для обертання намальованого циферблату.",
        "section_watch_hardware_buttons":     "Фізичні кнопки",
        "watch_hardware_button_1":            "Кнопка 1",
        "watch_hardware_button_2":            "Кнопка 2",
        "watch_hardware_button_1_subtitle":   "Garmin: Start · Galaxy Watch Ultra: помаранчева кнопка Action",
        "watch_hardware_button_2_subtitle":   "Garmin: Угору (довге натискання) · Galaxy Watch Ultra: нижня бічна кнопка",
        "section_watch_screen_buttons_desc":  "Сенсорні зони, намальовані на циферблаті",
        "section_watch_hardware_buttons_desc": "Бічні кнопки годинника",
    },
    "zh": {
        "section_watch_device":               "设备",
        "section_watch_paired":               "已配对设备",
        "watch_paired_none":                  "未配对手表",
        "watch_paired_none_desc":             "在 Wear OS 或 Garmin 手表上安装 EUC Planet 配套应用，即可在此处接收表盘",
        "watch_paired_active":                "实时",
        "watch_paired_idle":                  "空闲",
        "watch_paired_garmin_limits_title":   "Garmin 限制",
        "watch_paired_garmin_limit_launch":   "每次骑行开始时手动打开手表应用。Garmin 不允许手机应用自动启动配套应用。",
        "watch_paired_garmin_limit_rate":     "遥测数据的刷新速度比 Wear OS 慢。Connect IQ 移动 SDK 限制了更新速率。",
        "watch_paired_garmin_limit_rotation": "表盘旋转不可用。Connect IQ 没有用于旋转渲染表盘的原生变换矩阵。",
        "section_watch_hardware_buttons":     "硬件按键",
        "watch_hardware_button_1":            "按键 1",
        "watch_hardware_button_2":            "按键 2",
        "watch_hardware_button_1_subtitle":   "Garmin：Start · Galaxy Watch Ultra：橙色 Action 按键",
        "watch_hardware_button_2_subtitle":   "Garmin：上（长按）· Galaxy Watch Ultra：下方侧键",
        "section_watch_screen_buttons_desc":  "绘制在手表表盘上的点击区域",
        "section_watch_hardware_buttons_desc": "手表侧面按键",
    },
}

# Order matches the order in values/strings.xml so diffs line up.
KEY_ORDER = [
    "section_watch_device",
    "section_watch_paired",
    "watch_paired_none",
    "watch_paired_none_desc",
    "watch_paired_kind_wear",
    "watch_paired_kind_garmin",
    "watch_paired_active",
    "watch_paired_idle",
    "watch_paired_rate_hz",
    "watch_paired_garmin_limits_title",
    "watch_paired_garmin_limit_launch",
    "watch_paired_garmin_limit_rate",
    "watch_paired_garmin_limit_rotation",
    "section_watch_hardware_buttons",
    "watch_hardware_button_1",
    "watch_hardware_button_2",
    "watch_hardware_button_1_subtitle",
    "watch_hardware_button_2_subtitle",
    "section_watch_screen_buttons_desc",
    "section_watch_hardware_buttons_desc",
]

KEY_RE = re.compile(r'<string\s+name="([^"]+)"')

def existing_keys(text: str) -> set[str]:
    return set(KEY_RE.findall(text))

def render_block(translations: dict[str, str], have: set[str]) -> str:
    """Render the missing-keys block for a locale, preserving KEY_ORDER."""
    out = []
    for key in KEY_ORDER:
        if key in have:
            continue
        # Universal keys (Wear OS, Garmin Connect IQ, %1$.1f Hz) come
        # from UNIVERSAL; per-locale strings from `translations`.
        value = translations.get(key) or UNIVERSAL.get(key)
        if value is None:
            print(f"!! missing translation for key {key}", file=sys.stderr)
            continue
        # XML-escape ampersands. Single-quote escaping is already in the
        # source string (\\'), apostrophes go through verbatim.
        value = value.replace("&", "&amp;")
        out.append(f'    <string name="{key}">{value}</string>')
    return "\n".join(out)

def inject_locale(locale: str, translations: dict[str, str]) -> bool:
    path = Path(f"app/src/main/res/values-{locale}/strings.xml")
    if not path.exists():
        print(f"!! missing {path}", file=sys.stderr)
        return False
    text = path.read_text(encoding="utf-8")
    have = existing_keys(text)
    block = render_block(translations, have)
    if not block:
        print(f"-- {locale}: nothing to add")
        return False
    if "</resources>" not in text:
        print(f"!! {locale}: no </resources> tag", file=sys.stderr)
        return False
    new_text = text.replace("</resources>", block + "\n</resources>", 1)
    path.write_text(new_text, encoding="utf-8")
    added = block.count('<string ')
    print(f"OK {locale}: added {added} strings")
    return True

def main():
    total = 0
    for locale, tr in TRANSLATIONS.items():
        if inject_locale(locale, tr):
            total += 1
    print(f"\nTotal locales updated: {total} / {len(TRANSLATIONS)}")

if __name__ == "__main__":
    main()
