// Languages for the site's pages: which one to speak, the cogwheel that
// changes it, and the lookup. The same languages as the EUC Planet app, the
// trip viewer and eucstats, named the way eucstats names them: English and
// Spanish each come in two kinds, told apart by region.
//
// The English stays in the page. Each translatable piece carries an id:
//   data-i18n="id"                       the element's text
//   data-i18n-html="id"                  the element's markup, links and all
//   data-i18n-attrs data-i18n-attr-alt="id"   that attribute (alt, title, aria-label)
// One dictionary per language lives in i18n/<code>.js, id to translation, and
// only the one in use is fetched. A piece the dictionary lacks stays English,
// so a missing entry is never a blank. British English (en-GB) holds only
// the few pieces spelled differently. i18n/en.json is the source list
// (tools read it; the pages do not).
//
// Resolution order: ?lang= in the address, the cookie shared with the trip
// viewer and eucstats, this site's own saved choice, then the browser's
// language list. The cookie is set for all of ried.no, so a language picked
// on any of the three sites follows the rider to the others. Its name and
// the codes must match theirs.
(function () {
  "use strict";

  var STORAGE_KEY = "eucplanet-lang";
  var COOKIE = "euc_lang";
  // Bump when any dictionary changes, so nobody keeps a stale one.
  var DICT_VERSION = "1";

  // Native names, never translated.
  var LANGS = {
    "en": "English (US)",
    "en-GB": "English (UK)",
    "bg": "Български",
    "cs": "Čeština",
    "da": "Dansk",
    "de": "Deutsch",
    "el": "Ελληνικά",
    "es-419": "Español (LA)",
    "es": "Español (ES)",
    "fi": "Suomi",
    "fr": "Français",
    "hu": "Magyar",
    "id": "Bahasa Indonesia",
    "it": "Italiano",
    "ja": "日本語",
    "ko": "한국어",
    "nl": "Nederlands",
    "no": "Norsk",
    "pl": "Polski",
    "pt-BR": "Português (BR)",
    "pt-PT": "Português (PT)",
    "ro": "Română",
    "ru": "Русский",
    "sk": "Slovenčina",
    "sv": "Svenska",
    "th": "ไทย",
    "tr": "Türkçe",
    "uk": "Українська",
    "vi": "Tiếng Việt",
    "zh": "中文 (简体)",
    "zh-Hant": "中文 (繁體)"
  };
  function has(code) { return Object.prototype.hasOwnProperty.call(LANGS, code); }

  // Both lists are the ones the app uses.
  var LATAM = ["419", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO",
    "DO", "HN", "PY", "SV", "NI", "CR", "PA", "UY", "PR", "US"];
  var PT_PT = ["PT", "AO", "MZ", "CV", "GW", "ST", "TL"];
  // Where English is the British kind, the list eucstats uses.
  var EN_GB = ["GB", "UK", "IE", "AU", "NZ", "ZA", "IN"];

  // A browser tag ("es-MX", "nb", "zh-TW") to one of ours, or null.
  function mapLang(raw) {
    if (!raw) return null;
    var lc = String(raw).toLowerCase();
    var base = lc.split("-")[0];
    var region = (lc.split("-")[1] || "").toUpperCase();
    if (base === "en") return EN_GB.indexOf(region) >= 0 ? "en-GB" : "en";
    if (base === "pt") return PT_PT.indexOf(region) >= 0 ? "pt-PT" : "pt-BR";
    if (base === "zh") return /(^|-)(tw|hk|mo|hant)(-|$)/.test(lc) ? "zh-Hant" : "zh";
    if (base === "nb" || base === "nn" || base === "no") return "no";
    if (base === "in") return "id";
    if (base === "es") return LATAM.indexOf(region) >= 0 ? "es-419" : "es";
    return has(base) ? base : null;
  }
  // A stored or shared code: exact first, then as a browser tag.
  function known(v) { return typeof v === "string" && has(v) ? v : mapLang(v); }

  function readCookie() {
    try {
      var m = document.cookie.match(new RegExp("(?:^|;\\s*)" + COOKIE + "=([^;]*)"));
      return m ? decodeURIComponent(m[1]) : null;
    } catch (_) { return null; }
  }
  function writeCookie(v) {
    try {
      var shared = /(^|\.)ried\.no$/.test(location.hostname) ? "; domain=ried.no" : "";
      document.cookie = COOKIE + "=" + encodeURIComponent(v) + shared +
        "; path=/; max-age=31536000; samesite=lax";
    } catch (_) {}
  }

  function detect() {
    try {
      var force = known(new URLSearchParams(location.search).get("lang"));
      if (force) return force;
    } catch (_) {}
    var shared = known(readCookie());
    if (shared) return shared;
    try {
      var stored = known(localStorage.getItem(STORAGE_KEY));
      if (stored) return stored;
    } catch (_) {}
    try {
      var navs = (navigator.languages && navigator.languages.length)
        ? navigator.languages : [navigator.language || "en"];
      for (var i = 0; i < navs.length; i++) {
        var m = mapLang(navs[i]);
        if (m) return m;
      }
    } catch (_) {}
    return "en";
  }

  var lang = detect();
  try { document.documentElement.lang = lang; } catch (_) {}

  // The translation for an id, or the English the caller holds.
  function t(id, english) {
    var dict = window.EUC_I18N_DICT;
    var s = dict && dict[id];
    return typeof s === "string" && s ? s : english;
  }

  // A list of English words, each translated by its place: word.0, word.1.
  function words(list) {
    return list.map(function (w, i) { return t("word." + i, w); });
  }

  // Translate the marked markup under root. What the dictionary lacks is
  // left as the page wrote it.
  function apply(root) {
    root = root || document;
    var dict = window.EUC_I18N_DICT;
    if (!dict) return;
    function find(id) { return typeof dict[id] === "string" && dict[id] ? dict[id] : null; }
    root.querySelectorAll("[data-i18n]").forEach(function (el) {
      var s = find(el.getAttribute("data-i18n"));
      if (s != null) el.textContent = s;
    });
    root.querySelectorAll("[data-i18n-html]").forEach(function (el) {
      var s = find(el.getAttribute("data-i18n-html"));
      if (s != null) el.innerHTML = s;
    });
    root.querySelectorAll("[data-i18n-attrs]").forEach(function (el) {
      Array.prototype.slice.call(el.attributes).forEach(function (a) {
        if (a.name.indexOf("data-i18n-attr-") !== 0) return;
        var s = find(a.value);
        if (s != null) el.setAttribute(a.name.slice("data-i18n-attr-".length), s);
      });
    });
  }

  function set(next) {
    if (!has(next)) return;
    try { localStorage.setItem(STORAGE_KEY, next); } catch (_) {}
    writeCookie(next);
  }

  // The cogwheel: a button in the top corner that opens the language list.
  // Picking one saves it and reloads, the way the trip viewer does.
  function cogwheel() {
    var style = document.createElement("style");
    style.textContent =
      ".site-cog{position:fixed;top:14px;right:14px;z-index:60;font:14px/1.3 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}" +
      ".site-cog button{width:38px;height:38px;border-radius:50%;display:grid;place-items:center;cursor:pointer;" +
      "color:#e6edf3;background:rgba(22,27,34,.78);border:1px solid rgba(255,255,255,.16);" +
      "-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);transition:transform .25s ease,border-color .2s ease}" +
      ".site-cog button:hover,.site-cog.open button{border-color:rgba(88,166,255,.7)}" +
      ".site-cog.open button{transform:rotate(60deg)}" +
      ".site-cog button:focus-visible{outline:2px solid #58a6ff;outline-offset:2px}" +
      ".site-cog .panel{display:none;position:absolute;top:46px;right:0;min-width:200px;padding:12px;border-radius:12px;" +
      "color:#e6edf3;background:rgba(13,17,23,.96);border:1px solid rgba(255,255,255,.16);box-shadow:0 12px 32px rgba(0,0,0,.5)}" +
      ".site-cog.open .panel{display:block}" +
      ".site-cog label{display:block;font-size:12px;color:#9aa4b2;margin-bottom:6px}" +
      ".site-cog select{width:100%;padding:8px 10px;border-radius:8px;font:inherit;color:#e6edf3;" +
      "background:#161b22;border:1px solid rgba(255,255,255,.16)}";
    document.head.appendChild(style);

    var box = document.createElement("div");
    box.className = "site-cog";
    var settings = t("ui.settings", "Settings");
    box.innerHTML =
      '<button type="button" aria-haspopup="true" aria-expanded="false"></button>' +
      '<div class="panel"><label for="lang-select"></label><select id="lang-select"></select></div>';
    var btn = box.querySelector("button");
    btn.setAttribute("aria-label", settings);
    btn.title = settings;
    btn.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="3"/>' +
      '<path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 ' +
      '1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83' +
      'l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82' +
      'l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 ' +
      '1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09' +
      'a1.65 1.65 0 0 0-1.51 1z"/></svg>';
    box.querySelector("label").textContent = t("ui.language", "Language");

    var sel = box.querySelector("select");
    Object.keys(LANGS).forEach(function (code) {
      var o = document.createElement("option");
      o.value = code;
      o.textContent = LANGS[code];
      sel.appendChild(o);
    });
    sel.value = lang;
    sel.addEventListener("change", function () {
      set(sel.value);
      // A ?lang= left in the address would win over the choice just made.
      try {
        var url = new URL(location.href);
        if (url.searchParams.has("lang")) {
          url.searchParams.delete("lang");
          location.replace(url.toString());
          return;
        }
      } catch (_) {}
      location.reload();
    });

    function open(yes) {
      box.classList.toggle("open", yes);
      btn.setAttribute("aria-expanded", yes ? "true" : "false");
    }
    btn.addEventListener("click", function (ev) {
      ev.stopPropagation();
      open(!box.classList.contains("open"));
    });
    document.addEventListener("click", function (ev) {
      if (!box.contains(ev.target)) open(false);
    });
    document.addEventListener("keydown", function (ev) {
      if (ev.key === "Escape") open(false);
    });
    document.body.appendChild(box);
  }

  window.EucI18n = { lang: lang, LANGS: LANGS, t: t, words: words, apply: apply, mapLang: mapLang, set: set };

  // The dictionary has to be in before the page's own script runs. A
  // parser-inserted script is the one way to load a file and block on it
  // without a build step; the one after it translates the markup above and
  // draws the cogwheel with its label in the right language.
  window.EucI18nReady = function () {
    apply(document);
    cogwheel();
  };
  var ready = "<script>window.EucI18nReady();<\/script>";
  if (lang !== "en") {
    try {
      var me = document.currentScript && document.currentScript.src;
      var base = me ? me.replace(/js\/i18n\.js.*$/, "i18n/") : "i18n/";
      document.write('<script src="' + base + lang + ".js?v=" + DICT_VERSION + '"><\/script>' + ready);
    } catch (_) { cogwheel(); }
  } else {
    cogwheel();
  }
})();
