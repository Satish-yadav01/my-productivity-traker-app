/**
 * TrackerApi.gs  —  REFERENCE ADAPTER for "My Productivity Tracker" (Android).
 *
 * You said you already have an Apps Script API on your Productivity-tracker
 * workbook. I could not see it, so I did not guess its action names or column
 * layout. This file shows EXACTLY what the Android app sends and expects.
 *
 *  - If your script already has equivalent actions, change the constants in
 *    app/.../SheetsApi.kt (ApiContract) to match yours and skip this file.
 *  - Otherwise, paste this into the SAME Apps Script project as an extra file
 *    (it only ADDS a doPost; it never creates sheets or edits headers).
 *    If you already have a doPost, call handleTrackerRequest_(body) from it.
 *
 * Nothing here creates a spreadsheet, renames headers or deletes rows.
 * Columns are located BY HEADER NAME, so your existing column order is respected.
 *
 * SETUP: Project Settings -> Script properties -> add  TRACKER_TOKEN = <long random string>
 */

var TZ = 'Asia/Kolkata';
var ACTIVITIES_SHEET = 'Activities';
var TIMELOG_SHEET = 'Time_Log';

// App field -> header text in YOUR sheet. EDIT the right-hand side to match your headers.
// Headers that don't exist in the sheet are skipped (never created).
var ACTIVITIES_MAP = {
  clientId:   'Client ID',        // recommended: add this column once, used for duplicate protection
  date:       'Date',
  name:       'Activity',
  category:   'Category',
  priority:   'Priority',
  status:     'Status',
  plannedMin: 'Planned Duration (min)',
  actualMin:  'Actual Duration (min)'
};
var TIMELOG_MAP = {
  clientId: 'Client ID',
  date:     'Date',
  name:     'Activity',
  category: 'Category',
  minutes:  'Duration (min)'
};

// Named doPostTracker (not doPost) so it cannot clash with a doPost you already have.
// Call it from your existing doPost: see the wiring note at the top of this answer.
function doPostTracker(e) {
  try {
    return handleTrackerRequest_(JSON.parse(e.postData.contents));
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

function handleTrackerRequest_(body) {
  var expected = PropertiesService.getScriptProperties().getProperty('TRACKER_TOKEN');
  if (!expected || body.token !== expected) {
    return json_({ ok: false, error: 'Unauthorized: bad token' });
  }
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var p = body.payload || {};
    switch (body.action) {
      case 'ping':             return json_({ ok: true, data: { message: 'Productivity tracker API ready' } });
      case 'getToday':         return json_({ ok: true, data: { activities: getToday_(p.date) } });
      case 'addActivity':      addRow_(ACTIVITIES_SHEET, ACTIVITIES_MAP, p, p.clientId); return json_({ ok: true, data: {} });
      case 'logTime':          addRow_(TIMELOG_SHEET, TIMELOG_MAP,
                                 { clientId: p.activityClientId, date: p.date, name: p.activityName,
                                   category: p.category, minutes: p.minutes }, p.activityClientId);
                               return json_({ ok: true, data: {} });
      case 'refreshDashboard': refreshDashboard_(); return json_({ ok: true, data: {} });
      default:                 return json_({ ok: false, error: 'Unknown action: ' + body.action });
    }
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

// If this script is NOT opened from inside the sheet (Extensions -> Apps Script), paste your
// spreadsheet ID here (the long text between /d/ and /edit in the sheet's URL). Otherwise leave ''.
var SPREADSHEET_ID = '';

function sheet_(name) {
  var ss = SPREADSHEET_ID ? SpreadsheetApp.openById(SPREADSHEET_ID) : SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) throw new Error('No spreadsheet: set SPREADSHEET_ID at the top of TrackerApi.gs');
  var s = ss.getSheetByName(name);
  if (!s) throw new Error('Sheet not found: ' + name);
  return s;
}

function headerIndex_(sheet) {
  var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  var idx = {};
  headers.forEach(function (h, i) { idx[String(h).trim()] = i; });
  return { idx: idx, width: headers.length };
}

/** Appends one row; ignores a repeat of the same clientId (idempotent). */
function addRow_(sheetName, map, data, clientId) {
  var sh = sheet_(sheetName);
  var h = headerIndex_(sh);
  var idCol = h.idx[map.clientId];
  if (clientId && idCol !== undefined && sh.getLastRow() > 1) {
    var ids = sh.getRange(2, idCol + 1, sh.getLastRow() - 1, 1).getValues();
    for (var i = 0; i < ids.length; i++) if (ids[i][0] === clientId) return;   // already saved
  }
  var row = new Array(h.width).fill('');
  Object.keys(map).forEach(function (field) {
    var col = h.idx[map[field]];
    if (col !== undefined && data[field] !== undefined && data[field] !== null) row[col] = data[field];
  });
  if (idCol !== undefined && clientId) row[idCol] = clientId;
  sh.appendRow(row);
}

function getToday_(dateStr) {
  var sh = sheet_(ACTIVITIES_SHEET);
  if (sh.getLastRow() < 2) return [];
  var h = headerIndex_(sh);
  var values = sh.getRange(2, 1, sh.getLastRow() - 1, h.width).getValues();
  var out = [];
  values.forEach(function (r) {
    var d = r[h.idx[ACTIVITIES_MAP.date]];
    var ds = (d instanceof Date) ? Utilities.formatDate(d, TZ, 'yyyy-MM-dd') : String(d).slice(0, 10);
    if (ds !== dateStr) return;
    function get(f) { var c = h.idx[ACTIVITIES_MAP[f]]; return c === undefined ? '' : r[c]; }
    out.push({
      clientId: String(get('clientId') || ''), name: String(get('name')), category: String(get('category')),
      date: ds, plannedMin: get('plannedMin') === '' ? null : Number(get('plannedMin')),
      actualMin: get('actualMin') === '' ? null : Number(get('actualMin')),
      priority: String(get('priority') || 'Medium'), status: String(get('status') || 'Planned')
    });
  });
  return out;
}

/** Recalculates formulas/pivots so the dashboard shows the new rows. Add your own refresh here if needed. */
function refreshDashboard_() {
  SpreadsheetApp.flush();
}
