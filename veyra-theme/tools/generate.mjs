#!/usr/bin/env node
// Veyra Theme generator.
//
// Builds the four files in src/main/resources/themes from JetBrains' own New UI and Islands themes, re-coloured in
// the FlightCoders palette. Starting from JetBrains' themes means every part of the IDE is covered (600+ UI keys and
// every language in the editor), and the hand-picked brand colours below always win.
//
//   node tools/generate.mjs <reference-dir> [<reference-dir> ...]
//
// Each reference dir holds files extracted from an IntelliJ IDEA install (see README, "Regenerating"); later dirs
// override earlier ones, so list the oldest IDE first:
//   expUI_dark.theme.json, expUI_light.theme.json            lib/*.jar → themes/expUI/
//   ManyIslandsDark.theme.json, ManyIslandsLight.theme.json  2025.3 and newer
//   DefaultColorSchemesManager.xml, expUI_darkScheme.xml, expUI_lightScheme.xml   lib/app-client.jar (2024.x)
//   IslandSchemeDark.xml                                     lib/intellij.platform.resources.jar (2025.3 and newer)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const OUT = path.join(path.dirname(fileURLToPath(import.meta.url)), "../src/main/resources/themes");
const refs = process.argv.slice(2);
if (!refs.length) { console.error("usage: node tools/generate.mjs <reference-dir> [...]"); process.exit(1); }

// ---------------------------------------------------------------- the FlightCoders palette
// From the f/c logo: the frame gradient amber → coral → violet on an ink tile, with indigo and lavender for "coders".
const BRAND = {
  amber: "#FFB02E", coral: "#FF4F6D", violet: "#9B3DFF",
  ink: "#0B0C16", deep: "#151733", indigo: "#5B45FF", lavender: "#B8B4FF",
};

// Hand-picked colours. UI keys use the theme.json key names; semantic names are the Islands colour roles.
const UI = {
  dark: {
    colors: {
      "accent-brand-bg": "#6A4BFF", "accent-brand-border": "#6A4BFF",
      "text-link": BRAND.lavender, "popup-completion-match-text": BRAND.amber,
      "tab-selected-border-active": BRAND.coral, "got-it-bg": BRAND.indigo, "got-it-border": BRAND.indigo,
      "presentation-assistant-bg": BRAND.indigo,
    },
    ui: {
      "Button.default": { startBackground: BRAND.coral, endBackground: BRAND.violet, startBorderColor: BRAND.coral,
        endBorderColor: BRAND.violet, focusedBorderColor: BRAND.amber, foreground: "#FFFFFF" },
      "ProgressBar": { progressColor: BRAND.coral, indeterminateStartColor: BRAND.amber, indeterminateEndColor: BRAND.violet },
      "EditorTabs": { underlineColor: BRAND.coral, underlineHeight: 3 },
      "ToolWindow.HeaderTab.underlineColor": BRAND.coral,
      "TabbedPane.underlineColor": BRAND.coral,
      "Counter": { background: BRAND.coral, foreground: "#FFFFFF" },
      "Link": { activeForeground: BRAND.lavender, hoverForeground: "#D4D1FF", visitedForeground: "#C9A6FF", pressedForeground: "#D4D1FF" },
    },
  },
  light: {
    colors: {
      "accent-brand-bg": BRAND.indigo, "accent-brand-border": BRAND.indigo,
      "text-link": "#4B36E8", "popup-completion-match-text": "#C2410C",
      "tab-selected-border-active": "#E8365A", "got-it-bg": BRAND.indigo, "got-it-border": BRAND.indigo,
      "presentation-assistant-bg": BRAND.indigo,
    },
    ui: {
      "Button.default": { startBackground: "#F23D62", endBackground: "#8A2EF0", startBorderColor: "#F23D62",
        endBorderColor: "#8A2EF0", focusedBorderColor: BRAND.amber, foreground: "#FFFFFF" },
      "ProgressBar": { progressColor: "#E8365A", indeterminateStartColor: BRAND.amber, indeterminateEndColor: BRAND.violet },
      "EditorTabs": { underlineColor: "#E8365A", underlineHeight: 3 },
      "ToolWindow.HeaderTab.underlineColor": "#E8365A",
      "TabbedPane.underlineColor": "#E8365A",
      "Counter": { background: "#E8365A", foreground: "#FFFFFF" },
      "Link": { activeForeground: "#4B36E8", hoverForeground: "#3A27C9", visitedForeground: "#7A2BD0", pressedForeground: "#3A27C9" },
    },
  },
};

// Editor syntax colours. [foreground, font] where font is 0 plain, 1 bold, 2 italic, 3 bold italic.
// Every other language's colours follow these: wherever JetBrains used the same colour as one of these defaults,
// Veyra uses the replacement below, so Kotlin, Java, Python, JS/TS, HTML, SQL… all match.
const SYNTAX = {
  dark: {
    TEXT: ["E4E2F2", 0], DEFAULT_IDENTIFIER: ["E4E2F2", 0],
    DEFAULT_KEYWORD: ["FF6F87", 0], DEFAULT_STRING: ["FFC66B", 0], DEFAULT_VALID_STRING_ESCAPE: ["FF9466", 0],
    DEFAULT_NUMBER: ["FF9466", 0], DEFAULT_LINE_COMMENT: ["7B77A6", 2], DEFAULT_BLOCK_COMMENT: ["7B77A6", 2],
    DEFAULT_DOC_COMMENT: ["8480B0", 2], DEFAULT_DOC_COMMENT_TAG: ["B8B4FF", 2], DEFAULT_DOC_COMMENT_TAG_VALUE: ["C9A6FF", 2],
    DEFAULT_FUNCTION_DECLARATION: ["B8B4FF", 0], DEFAULT_CLASS_NAME: ["CB9BFF", 0], DEFAULT_CLASS_REFERENCE: ["CB9BFF", 0],
    DEFAULT_INTERFACE_NAME: ["CB9BFF", 2], DEFAULT_INSTANCE_FIELD: ["7CC8FF", 0], DEFAULT_STATIC_FIELD: ["7CC8FF", 2],
    DEFAULT_CONSTANT: ["FF8DC7", 2], DEFAULT_METADATA: ["62E3B5", 0], DEFAULT_LABEL: ["FF8DC7", 0],
    DEFAULT_PREDEFINED_SYMBOL: ["FF8DC7", 0], DEFAULT_TAG: ["FF6F87", 0], DEFAULT_ATTRIBUTE: ["B8B4FF", 0],
    DEFAULT_ENTITY: ["FF9466", 0],
  },
  light: {
    TEXT: ["1D1A2E", 0], DEFAULT_IDENTIFIER: ["1D1A2E", 0],
    DEFAULT_KEYWORD: ["C8244A", 0], DEFAULT_STRING: ["A35C00", 0], DEFAULT_VALID_STRING_ESCAPE: ["C2410C", 0],
    DEFAULT_NUMBER: ["C2410C", 0], DEFAULT_LINE_COMMENT: ["7A7696", 2], DEFAULT_BLOCK_COMMENT: ["7A7696", 2],
    DEFAULT_DOC_COMMENT: ["7F7AA0", 2], DEFAULT_DOC_COMMENT_TAG: ["5B45FF", 2], DEFAULT_DOC_COMMENT_TAG_VALUE: ["7A2BD0", 2],
    DEFAULT_FUNCTION_DECLARATION: ["4B36E8", 0], DEFAULT_CLASS_NAME: ["7A2BD0", 0], DEFAULT_CLASS_REFERENCE: ["7A2BD0", 0],
    DEFAULT_INTERFACE_NAME: ["7A2BD0", 2], DEFAULT_INSTANCE_FIELD: ["0B6BAA", 0], DEFAULT_STATIC_FIELD: ["0B6BAA", 2],
    DEFAULT_CONSTANT: ["B5287A", 2], DEFAULT_METADATA: ["0E8A63", 0], DEFAULT_LABEL: ["B5287A", 0],
    DEFAULT_PREDEFINED_SYMBOL: ["B5287A", 0], DEFAULT_TAG: ["C8244A", 0], DEFAULT_ATTRIBUTE: ["4B36E8", 0],
    DEFAULT_ENTITY: ["C2410C", 0],
  },
};
// Editor chrome: [scheme colour key, value].
const EDITOR_COLORS = {
  dark: { CARET_COLOR: "FF4F6D", LINE_NUMBER_ON_CARET_ROW_COLOR: "FFB02E", SELECTION_BACKGROUND: "2E2870", CARET_ROW_COLOR: "17162A" },
  light: { CARET_COLOR: "E8365A", LINE_NUMBER_ON_CARET_ROW_COLOR: "E8365A", SELECTION_BACKGROUND: "D9D2FF", CARET_ROW_COLOR: "F7F5FF" },
};

// ---------------------------------------------------------------- colour maths
const clamp = (v, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, v));
function parseHex(v) {
  let h = String(v).replace(/^#/, "");
  if (!/^[0-9a-f]+$/i.test(h)) return null;
  let alpha = "";
  if (h.length === 8) { alpha = h.slice(6); h = h.slice(0, 6); } else if (h.length < 6) h = h.padStart(6, "0"); else if (h.length !== 6) return null;
  const n = parseInt(h, 16);
  return { r: (n >> 16) / 255, g: ((n >> 8) & 255) / 255, b: (n & 255) / 255, alpha };
}
const toHex = ({ r, g, b }) => [r, g, b].map(c => Math.round(clamp(c) * 255).toString(16).padStart(2, "0")).join("").toUpperCase();
function rgbToHsl({ r, g, b }) {
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2, d = max - min;
  if (!d) return { h: 0, s: 0, l };
  const s = d / (1 - Math.abs(2 * l - 1));
  const h = max === r ? ((g - b) / d + 6) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return { h: h * 60, s, l };
}
function hslToRgb({ h, s, l }) {
  const c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = l - c / 2;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return { r: r + m, g: g + m, b: b + m };
}

// JetBrains' colour families → Veyra hues. Lightness is kept, so JetBrains' carefully tuned contrast is kept too.
function family(h) {
  if (h >= 185 && h < 252) return 250;   // blue → indigo
  if (h >= 252 && h < 320) return 274;   // purple → violet
  if (h >= 320 || h < 12) return 350;    // red / pink → coral
  if (h < 32) return 18;                 // orange → flame
  if (h < 65) return 38;                 // yellow → amber
  if (h < 165) return 158;               // green → mint
  return 182;                            // teal
}
function recolor(value, mode) {
  const c = parseHex(value);
  if (!c) return value;
  const hsl = rgbToHsl(c);
  let out;
  if (hsl.s < 0.16 || hsl.l > 0.96 || hsl.l < 0.03) {
    // Neutrals: an ink-violet tint, a little deeper at the dark end in the dark theme.
    const l = mode === "dark" && hsl.l < 0.35 ? hsl.l - 0.03 * (1 - hsl.l / 0.35) : hsl.l;
    const s = mode === "dark" ? clamp(0.32 - 0.3 * hsl.l, 0.05, 0.3) : (hsl.l > 0.6 ? 0.3 : 0.14);
    out = hslToRgb({ h: 246, s: hsl.l > 0.985 ? 0 : s, l });
  } else {
    out = hslToRgb({ h: family(hsl.h), s: clamp(hsl.s * 1.08), l: hsl.l });
  }
  const hex = toHex(out) + c.alpha.toUpperCase();
  return String(value).startsWith("#") ? "#" + hex : hex;
}

// ---------------------------------------------------------------- UI theme
const readJson = f => JSON.parse(fs.readFileSync(f, "utf8"));
const findRef = name => refs.map(d => path.join(d, name)).filter(f => fs.existsSync(f));
function deepMerge(into, from) {
  for (const [k, v] of Object.entries(from)) {
    if (v && typeof v === "object" && !Array.isArray(v) && into[k] && typeof into[k] === "object") deepMerge(into[k], v);
    else into[k] = structuredClone(v);
  }
  return into;
}
const mapDeep = (o, fn) => Array.isArray(o) ? o.map(v => mapDeep(v, fn))
  : o && typeof o === "object" ? Object.fromEntries(Object.entries(o).map(([k, v]) => [k, mapDeep(v, fn)])) : fn(o);

function buildTheme(mode) {
  const sources = [...findRef(`expUI_${mode}.theme.json`), ...findRef(`ManyIslands${mode === "dark" ? "Dark" : "Light"}.theme.json`)];
  if (!sources.length) throw new Error(`no ${mode} theme sources in ${refs.join(", ")}`);
  const merged = { colors: {}, ui: {}, palette: {} };
  for (const f of sources) {
    const t = readJson(f);
    deepMerge(merged.colors, t.colors || {});
    deepMerge(merged.ui, t.ui || {});
    deepMerge(merged.palette, t.icons?.ColorPalette || {});   // icon path replacements are JetBrains-internal; colours only
  }
  const hex = v => (typeof v === "string" && /^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(v) ? recolor(v, mode) : v);
  const named = { ...Object.fromEntries(Object.entries(BRAND).map(([k, v]) => [`veyra-${k}`, v])), ...mapDeep(merged.colors, hex), ...UI[mode].colors };
  // IDEs before 2025.3 can't follow a colour that names another colour ("tool-window-bg": "gray-10"), so resolve
  // every chain to its hex value here. UI keys may still use the names.
  const resolve = (v, seen = new Set()) => (typeof v === "string" && named[v] !== undefined && !seen.has(v) ? resolve(named[v], seen.add(v)) : v);
  const colors = Object.fromEntries(Object.entries(named).map(([k, v]) => [k, resolve(v)]));
  const unresolved = Object.entries(colors).filter(([, v]) => typeof v !== "string" || !/^#[0-9A-F]{6}([0-9A-F]{2})?$/i.test(v));
  if (unresolved.length) throw new Error(`${mode}: colours without a hex value: ${unresolved.map(([k, v]) => `${k}=${v}`).join(", ")}`);
  const ui = deepMerge(mapDeep(merged.ui, hex), UI[mode].ui);
  return {
    name: mode === "dark" ? "Veyra Dark" : "Veyra Light",
    dark: mode === "dark",
    author: "FlightCoders",
    parentTheme: mode === "dark" ? "ExperimentalDark" : "ExperimentalLightWithLightHeader",
    editorScheme: `/themes/veyra-${mode}.xml`,
    colors,
    ui,
    icons: { ColorPalette: mapDeep(merged.palette, hex) },
  };
}

// ---------------------------------------------------------------- editor colour scheme
// A tiny reader for JetBrains' scheme XML: <colors><option name value/></colors> and
// <attributes><option name [baseAttributes]><value><option name value/>…</value></option></attributes>.
function readScheme(xml, schemeName) {
  if (schemeName) {
    const start = xml.indexOf(`<scheme name="${schemeName}"`);
    xml = xml.slice(start, xml.indexOf("</scheme>", start));
  }
  const colors = {}, attributes = {};
  const colorBlock = xml.match(/<colors>([\s\S]*?)<\/colors>/)?.[1] || "";
  for (const m of colorBlock.matchAll(/<option name="([^"]+)" value="([^"]*)"/g)) colors[m[1]] = m[2];
  const attrBlock = xml.match(/<attributes>([\s\S]*)<\/attributes>/)?.[1] || "";
  let current = null;
  for (const line of attrBlock.split(/\r?\n/)) {
    const open = line.match(/^\s*<option name="([^"]+)"(?: baseAttributes="([^"]+)")?\s*(\/?)>/);
    if (!current && open) {
      if (open[2]) attributes[open[1]] = { base: open[2] };
      else if (open[3]) attributes[open[1]] = { value: {} };
      else current = attributes[open[1]] = { value: {} };
      continue;
    }
    if (!current) continue;
    const opt = line.match(/<option name="([A-Z_]+)"(?: value="([^"]*)")?/);
    if (opt && opt[2] !== undefined) current.value[opt[1]] = opt[2];
    else if (/^\s*<\/option>/.test(line)) current = null;
  }
  return { colors, attributes };
}

function buildScheme(mode) {
  const parentName = mode === "dark" ? "Darcula" : "Default";
  const managers = findRef("DefaultColorSchemesManager.xml");
  const overlays = [...findRef(`expUI_${mode}Scheme.xml`), ...(mode === "dark" ? findRef("IslandSchemeDark.xml") : [])];
  if (!managers.length) throw new Error("DefaultColorSchemesManager.xml not found");
  const base = { colors: {}, attributes: {} };
  for (const f of managers) { const s = readScheme(fs.readFileSync(f, "utf8"), parentName); Object.assign(base.colors, s.colors); Object.assign(base.attributes, s.attributes); }
  for (const f of overlays) { const s = readScheme(fs.readFileSync(f, "utf8")); Object.assign(base.colors, s.colors); Object.assign(base.attributes, s.attributes); }

  // Every JetBrains syntax colour that one of our defaults replaces, everywhere it appears.
  const syntax = SYNTAX[mode], swap = {};
  for (const [key, [fg]] of Object.entries(syntax)) {
    const old = base.attributes[key]?.value?.FOREGROUND?.toUpperCase();
    if (old && !swap[old]) swap[old] = fg;
  }
  const colorOf = v => swap[v.toUpperCase().padStart(6, "0")] || recolor(v.padStart(6, "0"), mode);

  const attributes = {};
  for (const [key, a] of Object.entries(base.attributes)) {
    if (a.base) { attributes[key] = a; continue; }
    const value = {};
    for (const [k, v] of Object.entries(a.value)) {
      if (k === "FOREGROUND" || k === "BACKGROUND" || k === "EFFECT_COLOR" || k === "ERROR_STRIPE_COLOR") value[k] = v === "" ? v : colorOf(v).toLowerCase();
      else value[k] = v;
    }
    attributes[key] = { value };
  }
  for (const [key, [fg, font]] of Object.entries(syntax)) {
    const value = { ...(attributes[key]?.value || {}), FOREGROUND: fg.toLowerCase() };
    if (font) value.FONT_TYPE = String(font); else delete value.FONT_TYPE;
    attributes[key] = { value };
  }
  // The editor background is the TEXT background: keep it in step with the IDE's editor surface.
  const colors = Object.fromEntries(Object.entries(base.colors).map(([k, v]) => [k, v === "" ? v : recolor(v.padStart(6, "0"), mode).toLowerCase()]));
  Object.assign(colors, Object.fromEntries(Object.entries(EDITOR_COLORS[mode]).map(([k, v]) => [k, v.toLowerCase()])));

  const name = mode === "dark" ? "Veyra Dark" : "Veyra Light";
  const esc = s => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
  const lines = [`<scheme name="${name}" version="142" parent_scheme="${parentName}">`, "  <metaInfo>",
    `    <property name="ide">idea</property>`, `    <property name="originalScheme">${name}</property>`, "  </metaInfo>", "  <colors>"];
  for (const k of Object.keys(colors).sort()) lines.push(`    <option name="${esc(k)}" value="${colors[k]}" />`);
  lines.push("  </colors>", "  <attributes>");
  for (const k of Object.keys(attributes).sort()) {
    const a = attributes[k];
    if (a.base) { lines.push(`    <option name="${esc(k)}" baseAttributes="${esc(a.base)}" />`); continue; }
    lines.push(`    <option name="${esc(k)}">`, "      <value>");
    for (const [o, v] of Object.entries(a.value)) lines.push(`        <option name="${o}" value="${esc(v)}" />`);
    lines.push("      </value>", "    </option>");
  }
  lines.push("  </attributes>", "</scheme>", "");
  return { xml: lines.join("\n"), count: Object.keys(attributes).length };
}

fs.mkdirSync(OUT, { recursive: true });
for (const mode of ["dark", "light"]) {
  const theme = buildTheme(mode);
  fs.writeFileSync(path.join(OUT, `veyra-${mode}.theme.json`), JSON.stringify(theme, null, 2) + "\n");
  const scheme = buildScheme(mode);
  fs.writeFileSync(path.join(OUT, `veyra-${mode}.xml`), scheme.xml);
  console.log(`veyra-${mode}: ${Object.keys(theme.colors).length} colours, ${JSON.stringify(theme.ui).match(/":/g).length} UI keys, ${scheme.count} editor attributes`);
}
