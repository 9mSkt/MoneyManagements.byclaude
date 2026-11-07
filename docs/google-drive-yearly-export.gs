/**
 * Money Management – yearly copy to Google Drive.
 *
 * Runs in YOUR Google account (script.google.com), so the file is saved in your own Drive folder.
 * Every January it downloads last year's Excel file (a Summary sheet + one sheet per month) from the app
 * and saves it in the folder. Running it again replaces the file of that year.
 *
 * Setup (see the README, "Yearly copy to Google Drive"):
 *   Project Settings -> Script Properties, add:
 *     APP_URL       https://your-app.onrender.com   (no / at the end)
 *     EXPORT_TOKEN  the same value as EXPORT_TOKEN in Render
 *     FOLDER_ID     your Drive folder link as you copied it, or just its ID
 *     LANG          en or th (optional, default en)
 *   Then run "setup" once (it asks for permission) and "testNow" to try it.
 * Nothing secret is written in this file.
 */

/** Creates the monthly trigger (2nd of each month, about 06:00). Only January does the work. */
function setup() {
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'monthlyCheck') { ScriptApp.deleteTrigger(t); }
  });
  ScriptApp.newTrigger('monthlyCheck').timeBased().onMonthDay(2).atHour(6).create();
  Logger.log('Trigger created: monthlyCheck, every month on the 2nd. The yearly file is made in January.');
}

/** Called by the trigger. In January, saves the file of the year that just ended. */
function monthlyCheck() {
  var now = new Date();
  if (now.getMonth() !== 0) { return; }   // 0 = January
  exportYear(now.getFullYear() - 1);
}

/** Try it now: saves the file of THIS year so far (run it from the editor). */
function testNow() {
  exportYear(new Date().getFullYear());
}

/**
 * Accepts the folder link as copied from Drive (https://drive.google.com/drive/folders/<ID>?usp=sharing,
 * also .../drive/u/0/folders/<ID> or ...?id=<ID>) or the ID on its own.
 */
function folderIdFrom(value) {
  var v = (value || '').trim();
  var m = v.match(/\/folders\/([A-Za-z0-9_-]+)/) || v.match(/[?&]id=([A-Za-z0-9_-]+)/);
  return m ? m[1] : v;
}

function exportYear(year) {
  var p = PropertiesService.getScriptProperties();
  var appUrl = (p.getProperty('APP_URL') || '').replace(/\/+$/, '');
  var token = p.getProperty('EXPORT_TOKEN');
  var folderId = folderIdFrom(p.getProperty('FOLDER_ID'));
  var lang = p.getProperty('LANG') || 'en';
  if (!appUrl || !token || !folderId) {
    throw new Error('Set APP_URL, EXPORT_TOKEN and FOLDER_ID in Project Settings -> Script Properties.');
  }

  // The free app sleeps when idle: wake it up first, then ask for the file (a few tries).
  UrlFetchApp.fetch(appUrl + '/login', { muteHttpExceptions: true });
  var url = appUrl + '/api/export/year.xlsx?year=' + year + '&lang=' + encodeURIComponent(lang);
  var response = null;
  for (var attempt = 1; attempt <= 5; attempt++) {
    response = UrlFetchApp.fetch(url, {
      headers: { Authorization: 'Bearer ' + token },
      muteHttpExceptions: true
    });
    if (response.getResponseCode() === 200) { break; }
    if (response.getResponseCode() === 401 || response.getResponseCode() === 404) {
      throw new Error('The app refused the request (' + response.getResponseCode()
        + '). Check EXPORT_TOKEN here and in Render.');
    }
    Utilities.sleep(20000);   // still waking up: wait 20 s and try again
  }
  if (response.getResponseCode() !== 200) {
    throw new Error('Could not get the file: HTTP ' + response.getResponseCode());
  }

  var name = 'Money Management ' + year + ' (by month).xlsx';
  var folder = DriveApp.getFolderById(folderId);
  var old = folder.getFilesByName(name);
  while (old.hasNext()) { old.next().setTrashed(true); }   // keep one file per year
  var blob = response.getBlob().setName(name);
  var file = folder.createFile(blob);
  Logger.log('Saved ' + file.getName() + ' in ' + folder.getName());
}
