const CONFIG = {
  // Spreadsheet ID is the value between /d/ and /edit in the spreadsheet URL.
  SPREADSHEET_ID: "PASTE_SPREADSHEET_ID_HERE",
  // Generate a secret in the app's Setup guide, then use Copy Code.gs.
  API_SECRET: "REPLACE_WITH_GENERATED_SECRET",
  TIMEZONE: "Asia/Kolkata"
};

const ACTIVITY_HEADERS = [
  "Activity_ID", "Date", "Activity", "Category", "Priority",
  "Planned_Start", "Planned_End", "Planned_Duration_Min",
  "Status", "Actual_Duration_Min", "Notes", "Created_At", "Updated_At",
  "Reminder_At"
];

const TIME_LOG_HEADERS = [
  "Log_ID", "Activity_ID", "Date", "Activity",
  "Start_Time", "End_Time", "Duration_Min", "Notes"
];

function setupWorkbook() {
  const ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  ensureSheet_(ss, "Activities", ACTIVITY_HEADERS);
  const activities = ss.getSheetByName("Activities");
  if (activities) ensureReminderColumn_(activities);
  ensureSheet_(ss, "Time_Log", TIME_LOG_HEADERS);
}

function ensureReminderColumn_(sheet) {
  const width = Math.max(sheet.getLastColumn(), 1);
  const headers = sheet.getRange(1, 1, 1, width).getValues()[0]
    .map(value => String(value).trim());
  if (!headers.includes("Reminder_At")) {
    const column = width + 1;
    sheet.getRange(1, column).setValue("Reminder_At");
    sheet.getRange(1, column).setFontWeight("bold");
    sheet.setColumnWidth(column, 22 * 7);
  }
}

function ensureSheet_(ss, name, headers) {
  let sheet = ss.getSheetByName(name);
  if (!sheet) sheet = ss.insertSheet(name);
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(headers);
    sheet.setFrozenRows(1);
  }
}

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents || "{}");
    if (!CONFIG.API_SECRET || body.secret !== CONFIG.API_SECRET) {
      throw new Error("Unauthorized");
    }
    const ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    const activities = ss.getSheetByName("Activities");
    if (activities) ensureReminderColumn_(activities);
    return jsonResponse({ success: true, result: handleAction(ss, body) });
  } catch (err) {
    return jsonResponse({ success: false, error: String(err.message || err) });
  }
}

function handleAction(ss, body) {
  const action = body.action;
  const activities = ss.getSheetByName("Activities");
  if (!activities) throw new Error('Sheet "Activities" was not found.');

  if (action === "list_day") {
    if (activities.getLastRow() < 2) return [];
    const headers = activities.getRange(1, 1, 1, activities.getLastColumn()).getValues()[0]
      .map(value => String(value).trim());
    return activities.getDataRange().getValues().slice(1)
      .filter(row => normalizeDate(row[1]) === String(body.date))
      .map(row => rowToObject(row, headers));
  }

  if (action === "add") {
    validateActivity(body);
    const now = new Date();
    const id = Utilities.getUuid();
    const headers = activities.getRange(1, 1, 1, activities.getLastColumn()).getValues()[0]
      .map(value => String(value).trim());
    const newRow = new Array(headers.length).fill("");
    [
      id, body.date, body.activity, body.category, body.priority || "Medium",
      "", "", Number(body.planned_duration_min || 0), "Planned", 0,
      body.notes || "", now, now
    ].forEach((value, index) => newRow[index] = value);
    newRow[headers.indexOf("Reminder_At")] = body.reminder_at || "";
    activities.appendRow(newRow);
    return { activity_id: id, status: "Planned" };
  }

  if (action === "update" || action === "complete") {
    const row = findActivityRow(activities, body.activity_id);
    if (!row) throw new Error("Activity ID not found");
    if (action === "complete") {
      activities.getRange(row, 9).setValue("Completed");
    } else {
      const columns = {
        date: 2,
        activity: 3,
        category: 4,
        priority: 5,
        planned_duration_min: 8,
        status: 9,
        actual_duration_min: 10,
        notes: 11
      };
      columns.reminder_at = activities.getRange(1, 1, 1, activities.getLastColumn())
        .getValues()[0].map(value => String(value).trim()).indexOf("Reminder_At") + 1;
      Object.keys(columns).forEach(key => {
        if (body[key] !== undefined) activities.getRange(row, columns[key]).setValue(body[key]);
      });
    }
    activities.getRange(row, 13).setValue(new Date());
    return { activity_id: body.activity_id, updated: true };
  }

  if (action === "delete") {
    const row = findActivityRow(activities, body.activity_id);
    if (!row) throw new Error("Activity ID not found");
    const timeLog = ss.getSheetByName("Time_Log");
    if (timeLog && timeLog.getLastRow() > 1) {
      const ids = timeLog.getRange(2, 2, timeLog.getLastRow() - 1, 1).getValues().flat();
      for (let i = ids.length - 1; i >= 0; i--) {
        if (String(ids[i]) === String(body.activity_id)) timeLog.deleteRow(i + 2);
      }
    }
    activities.deleteRow(row);
    return { activity_id: body.activity_id, deleted: true };
  }

  if (action === "log_time") {
    const row = findActivityRow(activities, body.activity_id);
    if (!row) throw new Error("Activity ID not found");
    const minutes = Number(body.duration_min);
    if (!Number.isFinite(minutes) || minutes <= 0) {
      throw new Error("A positive duration_min is required");
    }
    let timeLog = ss.getSheetByName("Time_Log");
    if (!timeLog) {
      timeLog = ss.insertSheet("Time_Log");
      timeLog.appendRow(TIME_LOG_HEADERS);
    }
    const activityName = activities.getRange(row, 3).getValue();
    const date = body.date || activities.getRange(row, 2).getValue();
    timeLog.appendRow([
      Utilities.getUuid(), body.activity_id, date, activityName,
      "", "", minutes, body.notes || ""
    ]);
    const actualCell = activities.getRange(row, 10);
    actualCell.setValue(Number(actualCell.getValue() || 0) + minutes);
    activities.getRange(row, 13).setValue(new Date());
    return { activity_id: body.activity_id, logged_minutes: minutes };
  }

  throw new Error("Unsupported action: " + action);
}

function validateActivity(body) {
  if (!body.date || !/^\d{4}-\d{2}-\d{2}$/.test(body.date)) {
    throw new Error("Date must be YYYY-MM-DD");
  }
  if (!body.activity || !String(body.activity).trim()) {
    throw new Error("Activity is required");
  }
  if (!body.category) throw new Error("Category is required");
  if (Number(body.planned_duration_min || 0) < 0) {
    throw new Error("Planned duration cannot be negative");
  }
}

function findActivityRow(sheet, id) {
  if (!id) throw new Error("activity_id is required");
  const rowCount = sheet.getLastRow() - 1;
  if (rowCount < 1) return null;
  const ids = sheet.getRange(2, 1, rowCount, 1).getValues().flat();
  const index = ids.findIndex(value => String(value) === String(id));
  return index < 0 ? null : index + 2;
}

function rowToObject(row, headers) {
  const reminderColumn = headers ? headers.indexOf("Reminder_At") : 13;
  return {
    activity_id: String(row[0] || ""),
    date: normalizeDate(row[1]),
    activity: String(row[2] || ""),
    category: String(row[3] || ""),
    priority: String(row[4] || "Medium"),
    planned_duration_min: Number(row[7] || 0),
    status: String(row[8] || "Planned"),
    actual_duration_min: Number(row[9] || 0),
    notes: String(row[10] || ""),
    reminder_at: String(row[reminderColumn] || "")
  };
}

function normalizeDate(value) {
  return value instanceof Date
    ? Utilities.formatDate(value, CONFIG.TIMEZONE, "yyyy-MM-dd")
    : String(value).slice(0, 10);
}

function jsonResponse(data) {
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}
