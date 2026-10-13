// CSV helpers: a small RFC 4180 reader for the bulk-invite paste/upload, row validation that mirrors the hub's own limits, and a writer
// for the exports. Spreadsheet formula injection is neutralised when WRITING a cell (a leading = + - @ tab or CR gets a quote prefix).

export const MAX_BULK_ROWS = 200;
export const MAX_NAME = 64;

/** Parses CSV text into rows of cells. Handles quotes, doubled quotes, CRLF, and a trailing newline. */
export function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  let any = false;
  const s = String(text ?? '').replace(/^﻿/, '');
  for (let i = 0; i < s.length; i += 1) {
    const c = s[i];
    if (quoted) {
      if (c === '"') {
        if (s[i + 1] === '"') {
          cell += '"';
          i += 1;
        } else quoted = false;
      } else cell += c;
    } else if (c === '"') {
      quoted = true;
      any = true;
    } else if (c === ',') {
      row.push(cell);
      cell = '';
      any = true;
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && s[i + 1] === '\n') i += 1;
      if (any || cell !== '') {
        row.push(cell);
        rows.push(row);
      }
      row = [];
      cell = '';
      any = false;
    } else {
      cell += c;
      any = true;
    }
  }
  if (any || cell !== '') {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

/**
 * Turns pasted or uploaded text into bulk-invite rows `{line, name, department, errors[]}`. A first row whose first cell is "name" is a
 * header. Department names are checked against [knownDepartments] (ids or names, case-insensitive) when that list is given.
 * Returns `{rows, tooMany}`; the hub accepts at most MAX_BULK_ROWS rows.
 */
export function validateBulk(text, knownDepartments) {
  const parsed = parseCsv(text);
  const rows = [];
  const lookup = knownDepartments
    ? new Map(knownDepartments.flatMap((d) => [[d.id.toLowerCase(), d.id], [d.name.toLowerCase(), d.id]]))
    : null;
  const seen = new Set();
  parsed.forEach((cells, index) => {
    if (index === 0 && String(cells[0] ?? '').trim().toLowerCase() === 'name') return;
    const name = String(cells[0] ?? '').trim();
    const departmentRaw = String(cells[1] ?? '').trim();
    if (!name && !departmentRaw) return;
    const errors = [];
    if (!name) errors.push('name-missing');
    else if (name.length > MAX_NAME) errors.push('name-too-long');
    // eslint-disable-next-line no-control-regex
    if (/[\u0000-\u001f\u007f]/.test(name)) errors.push('name-control');
    if (name && seen.has(name.toLowerCase())) errors.push('duplicate');
    seen.add(name.toLowerCase());
    let department = null;
    if (departmentRaw) {
      if (lookup) {
        department = lookup.get(departmentRaw.toLowerCase()) ?? null;
        if (!department) errors.push('department-unknown');
      } else department = departmentRaw;
    }
    rows.push({ line: index + 1, name, department, errors });
  });
  return { rows, tooMany: rows.length > MAX_BULK_ROWS };
}

/** The CSV the hub expects for [rows] (valid rows only): `name,department` per line. */
export function toBulkCsv(rows) {
  return rows.map((r) => csvLine([r.name, r.department ?? ''])).join('\n');
}

/** One CSV cell, quoted when needed, with spreadsheet formula injection neutralised. */
export function csvCell(value) {
  let s = value === null || value === undefined ? '' : String(value);
  if (typeof value !== 'number' && /^[=+\-@\t\r]/.test(s)) s = "'" + s;
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

export function csvLine(cells) {
  return cells.map(csvCell).join(',');
}

export function toCsv(header, rows) {
  return [csvLine(header), ...rows.map(csvLine)].join('\r\n') + '\r\n';
}
