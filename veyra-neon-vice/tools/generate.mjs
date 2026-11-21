#!/usr/bin/env node
// Veyra Neon Vice generator.
//
// Builds the four files in src/main/resources/themes from JetBrains' own New UI and Islands themes, re-coloured in
// the Neon Vice palette. Starting from JetBrains' themes means every part of the IDE is covered (600+ UI keys and
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

// ---------------------------------------------------------------- the Neon Vice palette
// An 80s beach city at night: hot-pink and electric-cyan neon signs, a sunset of yellow and orange, violet haze and
// mint, over a deep purple night. Every surface is midnight purple.
const BRAND = {
  pink: "#FF3EA5", cyan: "#22E6FF", yellow: "#FFD23F", orange: "#FF8A3D", violet: "#B96BFF", mint: "#72F1B8",
  night: "#120B24", haze: "#2A1747",
};
const PREFIX = "neonvice";
const NAMES = { dark: "Veyra Neon Vice", light: "Veyra Neon Vice Daylight" };
const FILES = { dark: "veyra-neon-vice-dark", light: "veyra-neon-vice-light" };

// Hand-picked colours. UI keys use the theme.json key names; semantic names are the Islands colour roles.
const UI = {
  dark: {
    colors: {
      "accent-brand-bg": "#C2307F", "accent-brand-border": "#C2307F",
      "text-link": "#5FEFFF", "popup-completion-match-text": BRAND.yellow,
      "tab-selected-border-active": BRAND.pink, "got-it-bg": "#8A2BB8", "got-it-border": "#8A2BB8",
      "presentation-assistant-bg": "#8A2BB8",
    },
    ui: {
      // The default button is a neon sunset: hot pink into orange.
      "Button.default": { startBackground: "#D4247F", endBackground: "#D9541E", startBorderColor: "#D4247F",
        endBorderColor: "#D9541E", focusedBorderColor: BRAND.cyan, foreground: "#FFFFFF" },
      "ProgressBar": { progressColor: BRAND.cyan, indeterminateStartColor: BRAND.pink, indeterminateEndColor: BRAND.cyan },
      "EditorTabs": { underlineColor: BRAND.pink, underlineHeight: 3 },
      "ToolWindow.HeaderTab.underlineColor": BRAND.pink,
      "TabbedPane.underlineColor": BRAND.pink,
      "Counter": { background: BRAND.pink, foreground: "#FFFFFF" },
      "Link": { activeForeground: "#5FEFFF", hoverForeground: "#A6F7FF", visitedForeground: "#D2A6FF", pressedForeground: "#A6F7FF" },
    },
  },
  light: {
    colors: {
      "accent-brand-bg": "#C2307F", "accent-brand-border": "#C2307F",
      "text-link": "#0B6E8C", "popup-completion-match-text": "#B4480F",
      "tab-selected-border-active": "#C8137A", "got-it-bg": "#7A2BD0", "got-it-border": "#7A2BD0",
      "presentation-assistant-bg": "#7A2BD0",
    },
    ui: {
      "Button.default": { startBackground: "#C8137A", endBackground: "#C2410C", startBorderColor: "#C8137A",
        endBorderColor: "#C2410C", focusedBorderColor: "#0B7A99", foreground: "#FFFFFF" },
      "ProgressBar": { progressColor: "#0B7A99", indeterminateStartColor: "#C8137A", indeterminateEndColor: "#0B7A99" },
      "EditorTabs": { underlineColor: "#C8137A", underlineHeight: 3 },
      "ToolWindow.HeaderTab.underlineColor": "#C8137A",
      "TabbedPane.underlineColor": "#C8137A",
      "Counter": { background: "#C8137A", foreground: "#FFFFFF" },
      "Link": { activeForeground: "#0B6E8C", hoverForeground: "#08566E", visitedForeground: "#7A2BD0", pressedForeground: "#08566E" },
    },
  },
};

// Editor syntax colours. [foreground, font] where font is 0 plain, 1 bold, 2 italic, 3 bold italic.
// Every other language's colours follow these: wherever JetBrains used the same colour as one of these defaults,
// Neon Vice uses the replacement below, so Kotlin, Java, Python, JS/TS, HTML, SQL… all match.
const SYNTAX = {
  dark: {
    TEXT: ["EDE6FF", 0], DEFAULT_IDENTIFIER: ["EDE6FF", 0],
    DEFAULT_KEYWORD: ["FF3EA5", 0], DEFAULT_STRING: ["22E6FF", 0], DEFAULT_VALID_STRING_ESCAPE: ["FFD23F", 0],
    DEFAULT_NUMBER: ["FF8A3D", 0], DEFAULT_LINE_COMMENT: ["8A78B8", 2], DEFAULT_BLOCK_COMMENT: ["8A78B8", 2],
    DEFAULT_DOC_COMMENT: ["9483C0", 2], DEFAULT_DOC_COMMENT_TAG: ["22E6FF", 2], DEFAULT_DOC_COMMENT_TAG_VALUE: ["B96BFF", 2],
    DEFAULT_FUNCTION_DECLARATION: ["FFD23F", 0], DEFAULT_CLASS_NAME: ["C58AFF", 0], DEFAULT_CLASS_REFERENCE: ["C58AFF", 0],
    DEFAULT_INTERFACE_NAME: ["C58AFF", 2], DEFAULT_INSTANCE_FIELD: ["72F1B8", 0], DEFAULT_STATIC_FIELD: ["72F1B8", 2],
    DEFAULT_CONSTANT: ["FFA26B", 2], DEFAULT_METADATA: ["F97E72", 0], DEFAULT_LABEL: ["72F1B8", 0],
    DEFAULT_PREDEFINED_SYMBOL: ["72F1B8", 0], DEFAULT_TAG: ["FF3EA5", 0], DEFAULT_ATTRIBUTE: ["FFD23F", 0],
    DEFAULT_ENTITY: ["FF8A3D", 0],
  },
  light: {
    TEXT: ["241A3A", 0], DEFAULT_IDENTIFIER: ["241A3A", 0],
    DEFAULT_KEYWORD: ["C8137A", 0], DEFAULT_STRING: ["0B7493", 0], DEFAULT_VALID_STRING_ESCAPE: ["8A6100", 0],
    DEFAULT_NUMBER: ["B4480F", 0], DEFAULT_LINE_COMMENT: ["65597D", 2], DEFAULT_BLOCK_COMMENT: ["65597D", 2],
    DEFAULT_DOC_COMMENT: ["62567A", 2], DEFAULT_DOC_COMMENT_TAG: ["0B7493", 2], DEFAULT_DOC_COMMENT_TAG_VALUE: ["7A2BD0", 2],
    DEFAULT_FUNCTION_DECLARATION: ["8A6100", 0], DEFAULT_CLASS_NAME: ["7A2BD0", 0], DEFAULT_CLASS_REFERENCE: ["7A2BD0", 0],
    DEFAULT_INTERFACE_NAME: ["7A2BD0", 2], DEFAULT_INSTANCE_FIELD: ["0B7F5C", 0], DEFAULT_STATIC_FIELD: ["0B7F5C", 2],
    DEFAULT_CONSTANT: ["B4480F", 2], DEFAULT_METADATA: ["B43C30", 0], DEFAULT_LABEL: ["0B7F5C", 0],
    DEFAULT_PREDEFINED_SYMBOL: ["0B7F5C", 0], DEFAULT_TAG: ["C8137A", 0], DEFAULT_ATTRIBUTE: ["8A6100", 0],
    DEFAULT_ENTITY: ["B4480F", 0],
  },
};
// Editor chrome: [scheme colour key, value].
const EDITOR_COLORS = {
  dark: { CARET_COLOR: "22E6FF", LINE_NUMBER_ON_CARET_ROW_COLOR: "FF3EA5", SELECTION_BACKGROUND: "3B1E5F", CARET_ROW_COLOR: "1B1033" },
  light: { CARET_COLOR: "C8137A", LINE_NUMBER_ON_CARET_ROW_COLOR: "C8137A", SELECTION_BACKGROUND: "FFD6EE", CARET_ROW_COLOR: "FFF3FA" },
};
// Neutrals (backgrounds, borders, grey text) take this hue: midnight purple.
const NEUTRAL_HUE = 266;

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

// JetBrains' colour families → neon hues. Lightness is kept, so JetBrains' carefully tuned contrast is kept too.
function family(h) {
  if (h >= 185 && h < 252) return 196;   // blue → electric cyan-blue
  if (h >= 252 && h < 320) return 276;   // purple → neon violet
  if (h >= 320 || h < 12) return 338;    // red / pink → hot pink (errors stay red-pink)
  if (h < 32) return 22;                 // orange → sunset orange
  if (h < 65) return 46;                 // yellow → sunset yellow
  if (h < 165) return 156;               // green → mint
  return 188;                            // teal → electric cyan
}
function recolor(value, mode) {
  const c = parseHex(value);
  if (!c) return value;
  const hsl = rgbToHsl(c);
  let out;
  if (hsl.s < 0.16 || hsl.l > 0.96 || hsl.l < 0.03) {
    // Neutrals: a midnight-blue tint, a little deeper at the dark end in the dark theme.
    const l = mode === "dark" && hsl.l < 0.35 ? hsl.l - 0.035 * (1 - hsl.l / 0.35) : hsl.l;
    const s = mode === "dark" ? clamp(0.55 - 0.45 * hsl.l, 0.08, 0.5) : (hsl.l > 0.6 ? 0.34 : 0.16);
    out = hslToRgb({ h: NEUTRAL_HUE, s: hsl.l > 0.985 ? 0 : s, l });
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
  const named = { ...Object.fromEntries(Object.entries(BRAND).map(([k, v]) => [`${PREFIX}-${k}`, v])), ...mapDeep(merged.colors, hex), ...UI[mode].colors };
  // IDEs before 2025.3 can't follow a colour that names another colour ("tool-window-bg": "gray-10"), so resolve
  // every chain to its hex value here. UI keys may still use the names.
  const resolve = (v, seen = new Set()) => (typeof v === "string" && named[v] !== undefined && !seen.has(v) ? resolve(named[v], seen.add(v)) : v);
  const colors = Object.fromEntries(Object.entries(named).map(([k, v]) => [k, resolve(v)]));
  const unresolved = Object.entries(colors).filter(([, v]) => typeof v !== "string" || !/^#[0-9A-F]{6}([0-9A-F]{2})?$/i.test(v));
  if (unresolved.length) throw new Error(`${mode}: colours without a hex value: ${unresolved.map(([k, v]) => `${k}=${v}`).join(", ")}`);
  const ui = deepMerge(mapDeep(merged.ui, hex), UI[mode].ui);
  return {
    name: NAMES[mode],
    dark: mode === "dark",
    author: "FlightCoders",
    parentTheme: mode === "dark" ? "ExperimentalDark" : "ExperimentalLightWithLightHeader",
    editorScheme: `/themes/${FILES[mode]}.xml`,
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

  const name = NAMES[mode];
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
  fs.writeFileSync(path.join(OUT, `${FILES[mode]}.theme.json`), JSON.stringify(theme, null, 2) + "\n");
  const scheme = buildScheme(mode);
  fs.writeFileSync(path.join(OUT, `${FILES[mode]}.xml`), scheme.xml);
  console.log(`${FILES[mode]}: ${Object.keys(theme.colors).length} colours, ${JSON.stringify(theme.ui).match(/":/g).length} UI keys, ${scheme.count} editor attributes`);
}
