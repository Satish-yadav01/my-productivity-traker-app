# My Productivity Tracker

An Android app that turns a quick voice note or a few typed words about your day into organized productivity data. Review and edit the details, save them to your own Google Sheet, and use the same data to understand how you spend your time.

## Features

- **Speak or type naturally:** Record one activity or describe several in a single sentence. The rule-based parser understands English and common Hinglish phrases.
- **Review before saving:** Check and edit the activity name, date, category, priority, status, planned time, and actual time. Nothing is sent to your sheet until you tap **Save to Google Sheets**.
- **Track time, not just tasks:** Record what you planned and how long it actually took. Completed work can be logged in a separate time log.
- **See your progress:** Browse Today, custom date ranges, weekly reports, and monthly reports. Reports include activity counts, planned and actual time, status summaries, charts, and category totals.
- **Find the data you need:** Filter reports by date range, category, status, priority, and planned minutes.
- **Keep working through connection problems:** Confirmed entries are saved in a phone-side queue and retried when the app is opened or refreshed. Recent duplicates are flagged, and retries are designed to avoid creating duplicate rows.
- **Use your own data store:** The app connects directly to your Google Sheets workbook through Google Apps Script. There is no separate tracker account, hosted database, or paid app service.

## How it is different

This is more than a checklist of tasks to tick off. Many to-do apps focus on what you intend to do; My Productivity Tracker is built to capture what you actually did and how your time compared with your plan. A short voice or text description becomes structured, reviewable activity records, then weekly and monthly reports help you spot where your time went—with less manual entry.

Your records live in a Google Sheet you control. You can inspect and edit that data in Sheets, while the app provides a faster way to capture activities and review trends.

## How to use the app

1. Open the app and follow the first-launch prompt to **How to connect / Setup guide**.
2. Connect your Google Sheet using the setup steps below. You can finish setup later from **Settings**.
3. On **Today**, tap the microphone and describe what you did, or choose **Type instead**.
4. Tap **Understand** to create activity cards. Review the parsed details, correct anything needed, and resolve any highlighted ambiguity.
5. Tap **Save to Google Sheets**. The app uploads the confirmed activities and shows entries that are waiting to upload if the connection is unavailable.
6. Use **Activities**, **Weekly**, and **Monthly** to browse and filter your saved activity history.

Example: “Aaj 45 min gym kiya, then 1 hour Kotlin padha” can be turned into two separate activities for you to review. The parser is rule-based, so always check the confirmation screen—especially for unusual wording or ambiguous dates.

## Download and open

### Download a prebuilt APK from GitHub Actions

1. Open this repository on GitHub and select **Actions**.
2. Select the **Build APK** workflow and open a successful run.
3. Under **Artifacts**, download `my-productivity-tracker-debug-apk`.
4. Extract the downloaded archive and copy `app-debug.apk` to your Android phone.
5. Open the APK on your phone. If Android asks, allow your browser or file manager to install unknown apps, then continue the installation.
6. Launch **My Productivity Tracker** from your app list.

The workflow builds an APK when code is pushed to `main`; it can also be started manually from the workflow page. This is a debug APK for testing, not a Play Store release. Android 8.0 (API 26) or newer is required.

### Build and open from Android Studio

1. Install Android Studio and open this repository folder.
2. Let Gradle sync, then choose **Build → Build APK(s)**, or connect an Android device and press **Run**.
3. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Set up Google Sheets and Apps Script

The app's built-in setup guide walks through these steps. It generates a strong secret on your phone and inserts that same secret and your spreadsheet ID into the script you copy.

1. **Create your spreadsheet.** In the app, open **How to connect / Setup guide**. Tap **Download .xlsx template**, upload the file to Google Drive, open it with Google Sheets, and choose **File → Save as Google Sheets**. Alternatively, use an existing workbook with `Activities` and `Time_Log` sheets and the expected headers.
2. **Copy the spreadsheet URL.** In your browser, copy the full URL of the Google spreadsheet and paste it into the setup guide.
3. **Generate your API secret.** Tap **Generate secret** once. The app stores the key using Android's encrypted preferences backed by Android Keystore. Do not generate a different secret during setup; the generated value is not displayed on the guide.
4. **Build the configured script.** Tap **Build and copy Code.gs**. The app inserts the spreadsheet ID from the URL and the saved secret into its provided script, then copies the configured code to your clipboard.
5. **Add the script to the spreadsheet.** In the spreadsheet, choose **Extensions → Apps Script**. Replace the editor contents with the copied `Code.gs` and save. Select `setupWorkbook` in the function list, tap **Run**, and approve Google's requested permissions. This creates the `Activities` and `Time_Log` sheets and headers if they do not already exist.
6. **Deploy as a web app.** In Apps Script, choose **Deploy → New deployment**, select **Web app**, set **Execute as** to **Me**, and set **Who has access** to **Anyone**. Deploy and approve the permissions, then copy the web app URL ending in `/exec`.
7. **Connect the Android app.** Open **Settings** (or tap **Open connection settings** in the guide), paste the `/exec` URL, and confirm the generated API secret is already filled in. Tap **Save**, then **Test connection**.

Keep the configured Apps Script private: it contains the API secret. The deployed endpoint requires the same secret for requests. The app stores the URL and secret encrypted on the phone, but a rooted or compromised device may expose local data. If you change the Apps Script code, deploy a **new version**. If you replace the secret, update the script and the app to use the matching value.

## Notes and limitations

- Speech recognition uses Google's recognizer (`en-IN`) and usually requires an internet connection. You can edit the transcript or type instead.
- Parsing is rule-based, so unusual phrases may need correction on the confirmation screen.
- Reports fetch activities from the spreadsheet and cache recent results on the phone for 10 minutes.
- All “today” date logic uses the `Asia/Kolkata` time zone.
- The app requires its Apps Script web app connection to read and sync spreadsheet data.

## Enjoy tracking your progress

Spend less effort maintaining a task list and more time understanding your day. Capture what you did, review your progress, and enjoy making steady progress—one activity at a time!
