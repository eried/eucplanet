#!/usr/bin/env python3
"""Injects the Amazfit (Zepp OS) watch strings into every locale file.

Run from repo root: `python tools/inject-amazfit-translations.py`
Safe to re-run, every change is idempotent on the key name and content.

Four keys are touched:
  watch_paired_kind_amazfit           added if missing (brand name, literal everywhere)
  watch_paired_none_desc              replaced with a translation that names all three watch families
  watch_hardware_button_1_subtitle    gets " · Amazfit: Select" appended if not already mentioning Amazfit
  watch_hardware_button_2_subtitle    gets " · Amazfit: <Down>" appended, with a per-locale word for Down
"""

import re
import sys
from pathlib import Path

KIND_AMAZFIT = "Amazfit (Zepp OS)"

# locale -> (watch_paired_none_desc, word for the Down key)
TRANSLATIONS = {
    "es": ("Instala la aplicación complementaria EUC Planet en un reloj Wear OS, Garmin o Amazfit para empezar a recibir el dial aquí", "Abajo"),
    "b+es+419": ("Instalá la app complementaria EUC Planet en un reloj Wear OS, Garmin o Amazfit para empezar a recibir el dial acá", "Abajo"),
    "no": ("Installer EUC Planet-følgeappen på en Wear OS-, Garmin- eller Amazfit-klokke for å begynne å motta urskiven her", "Ned"),
    "de": ("Installiere die EUC Planet-Begleit-App auf einer Wear OS-, Garmin- oder Amazfit-Uhr, um das Zifferblatt hier zu empfangen", "Unten"),
    "cs": ("Nainstaluj doplňkovou aplikaci EUC Planet na hodinky Wear OS, Garmin nebo Amazfit a ciferník se začne zobrazovat zde", "Dolů"),
    "da": ("Installer EUC Planet-følgeappen på et Wear OS-, Garmin- eller Amazfit-ur for at begynde at modtage urskiven her", "Ned"),
    "fi": ("Asenna EUC Planet -kumppanisovellus Wear OS-, Garmin- tai Amazfit-kelloon, niin kellotaulu alkaa näkyä täällä", "Alas"),
    "fr": ("Installez l\\'application compagnon EUC Planet sur une montre Wear OS, Garmin ou Amazfit pour recevoir le cadran ici", "Bas"),
    "hu": ("Telepítsd az EUC Planet kísérőalkalmazást egy Wear OS, Garmin vagy Amazfit órára, hogy itt megjelenjen a számlap", "Le"),
    "it": ("Installa l\\'app companion EUC Planet su un orologio Wear OS, Garmin o Amazfit per iniziare a ricevere il quadrante qui", "Giù"),
    "ja": ("Wear OS、Garmin、または Amazfit のウォッチに EUC Planet コンパニオンアプリをインストールすると、ここでダイヤルを受信できます", "下"),
    "ko": ("Wear OS, Garmin 또는 Amazfit 워치에 EUC Planet 컴패니언 앱을 설치하면 여기에서 다이얼을 받을 수 있습니다", "아래"),
    "nl": ("Installeer de EUC Planet-companion-app op een Wear OS-, Garmin- of Amazfit-horloge om hier de wijzerplaat te ontvangen", "Omlaag"),
    "pl": ("Zainstaluj aplikację towarzyszącą EUC Planet na zegarku Wear OS, Garmin lub Amazfit, aby zacząć odbierać tarczę tutaj", "Dół"),
    "pt-rBR": ("Instale o app complementar EUC Planet em um relógio Wear OS, Garmin ou Amazfit para começar a receber o mostrador aqui", "Baixo"),
    "ro": ("Instalează aplicația însoțitoare EUC Planet pe un ceas Wear OS, Garmin sau Amazfit pentru a primi cadranul aici", "Jos"),
    "ru": ("Установите приложение-компаньон EUC Planet на часы Wear OS, Garmin или Amazfit, чтобы получать циферблат здесь", "Вниз"),
    "sv": ("Installera EUC Planet-följeslagarappen på en Wear OS-, Garmin- eller Amazfit-klocka för att börja ta emot urtavlan här", "Ned"),
    "tr": ("Kadranı burada almaya başlamak için EUC Planet yardımcı uygulamasını bir Wear OS, Garmin veya Amazfit saate yükle", "Aşağı"),
    "uk": ("Установіть застосунок-компаньйон EUC Planet на годинник Wear OS, Garmin або Amazfit, щоб отримувати циферблат тут", "Вниз"),
    "zh": ("在 Wear OS、Garmin 或 Amazfit 手表上安装 EUC Planet 伴侣应用，即可在此接收表盘", "下键"),
    "zh-rTW": ("在 Wear OS、Garmin 或 Amazfit 手錶上安裝 EUC Planet 夥伴應用程式，即可在此接收錶盤", "下鍵"),
}


def string_re(key: str) -> re.Pattern:
    return re.compile(rf'(<string name="{re.escape(key)}">)(.*?)(</string>)', re.S)


def set_string(text: str, key: str, value: str) -> tuple[str, bool]:
    """Replace the value of an existing key. Returns (text, changed)."""
    m = string_re(key).search(text)
    if not m:
        return text, False
    if m.group(2) == value:
        return text, False
    return text[: m.start(2)] + value + text[m.end(2):], True


def append_suffix(text: str, key: str, suffix: str) -> tuple[str, bool]:
    m = string_re(key).search(text)
    if not m or "Amazfit" in m.group(2):
        return text, False
    return text[: m.end(2)] + suffix + text[m.end(2):], True


def add_after(text: str, anchor_key: str, key: str, value: str) -> tuple[str, bool]:
    if string_re(key).search(text):
        return text, False
    m = string_re(anchor_key).search(text)
    line = f'    <string name="{key}">{value}</string>'
    if m:
        end = text.index("\n", m.end())
        return text[: end + 1] + line + "\n" + text[end + 1:], True
    return text.replace("</resources>", line + "\n</resources>", 1), True


def inject_locale(locale: str, none_desc: str, down_word: str) -> int:
    path = Path(f"app/src/main/res/values-{locale}/strings.xml")
    if not path.exists():
        print(f"!! missing {path}", file=sys.stderr)
        return 0
    text = path.read_text(encoding="utf-8")
    changes = 0
    text, c = add_after(text, "watch_paired_kind_garmin", "watch_paired_kind_amazfit", KIND_AMAZFIT); changes += c
    text, c = set_string(text, "watch_paired_none_desc", none_desc); changes += c
    # French typography puts a space before the colon, matching the existing text.
    colon = " :" if locale == "fr" else ":"
    text, c = append_suffix(text, "watch_hardware_button_1_subtitle", f" · Amazfit{colon} Select"); changes += c
    text, c = append_suffix(text, "watch_hardware_button_2_subtitle", f" · Amazfit{colon} {down_word}"); changes += c
    if changes:
        path.write_text(text, encoding="utf-8")
    print(f"{'OK' if changes else '--'} {locale}: {changes} change(s)")
    return changes


def main():
    total = sum(inject_locale(loc, desc, down) for loc, (desc, down) in TRANSLATIONS.items())
    print(f"\nTotal changes: {total} across {len(TRANSLATIONS)} locales")


if __name__ == "__main__":
    main()
