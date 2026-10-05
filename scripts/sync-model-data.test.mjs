import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { test } from "node:test";

const script = readFileSync(new URL("./sync-model-data.mjs", import.meta.url), "utf8");
const helpers = script.slice(script.indexOf("function dataBody("), script.indexOf("function fallback("));
const decisions = script.slice(script.indexOf("const boxesStale ="), script.indexOf("if (!boxesStale"));
function compare(boxes, extra, nextBoxes = boxes, nextExtra = extra) {
  return runInNewContext(`${helpers}\n${decisions}\n({ boxesStale, extraStale });`, {
    BOXES: "boxes", EXTRA: "extra", boxesXml: nextBoxes, extraXml: nextExtra,
    existsSync: () => true,
    readFileSync: (path) => path === "boxes" ? boxes : extra,
  });
}
const boxes = '<maintenanceBoxes><model>TEST-A</model></maintenanceBoxes>';
const extra = '<models count="1"><model name="TEST" rkey="1" keyword="0x00"><pad kind="main" addresses="24" reset="0"/></model></models>';

test("unchanged data with a different prose header is not rewritten", () => {
  const result = compare(`<!-- old -->${boxes}`, `<!-- old -->${extra}`, `<!-- new -->${boxes}`, `<!-- new -->${extra}`);
  assert.equal(result.boxesStale, false);
  assert.equal(result.extraStale, false);
});
test("same model names with changed keys addresses reset values or pad kind are stale", () => {
  for (const [before, after] of [['rkey="1"', 'rkey="2"'], ['0x00', '0x01'], ['addresses="24"', 'addresses="25"'], ['reset="0"', 'reset="94"'], ['kind="main"', 'kind="platen"']]) {
    assert.equal(compare(boxes, extra, boxes, extra.replace(before, after)).extraStale, true);
  }
});
test("replacing a maintenance-box model at equal count is stale", () => {
  assert.equal(compare(boxes, extra, boxes.replace("TEST-A", "TEST-B")).boxesStale, true);
});
