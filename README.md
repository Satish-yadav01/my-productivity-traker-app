# My Productivity Tracker

An Android app that turns a quick voice note or a few typed words about your day into organized productivity data. Review and edit the details, save them to your own Google Sheet, and use the same data to understand how you spend your time.

## Features

- **Speak or type naturally:** Record one activity or describe several in a single sentence. The rule-based parser understands English and common Hinglish phrases.
- **Review before saving:** Check and edit the activity name, date, category, priority, status, planned time, and actual time. Nothing is sent to your sheet until you tap **Save to Google Sheets**.
- **Track time, not just tasks:** Record what you planned and how long it actually took. Completed work can be logged in a separate time log.
- **See your progress:** Browse Today, custom date ranges, weekly reports, and monthly reports. Reports include activity counts, planned and actual time, status summaries, charts, and category totals.
- **Find the data you need:** Filter reports by date range, category, status, priority, and planned minutes.
- **Keep working through connection problems:** Confirmed entries are saved in a phone-side queue and retried when the app is opened or refreshed. Recent duplicates are flagged, and retries are designed to avoid creating duplicate rows.
- **Stay on top of activities:** Choose a reminder delay for each planned or in-progress activity (priority defaults are one hour, or two hours for low priority). Set a bedtime in Settings to get a daily summary two hours beforehand.
- **Allow Android notifications:** On Android 12 and newer, grant the app **Alarms & reminders** access when prompted for activity reminders. Notifications must also be enabled for the app. Without exact-alarm access, Android may deliver a reminder late.
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
2. Select the **Build and release Android app** workflow and open a successful run.
3. Under **Artifacts**, download `my-productivity-tracker-debug-apk`.
4. Extract the downloaded archive and copy `Daily Tracker.apk` to your Android phone.
5. Open the APK on your phone. If Android asks, allow your browser or file manager to install unknown apps, then continue the installation.
6. Launch **My Productivity Tracker** from your app list.

The workflow builds an APK when code is pushed to `main`; it can also be started manually from the workflow page. This is a debug APK for testing, not a Play Store release. Android 8.0 (API 26) or newer is required.

### Build and open from Android Studio

1. Install Android Studio and open this repository folder.
2. Let Gradle sync, then choose **Build → Build APK(s)**, or connect an Android device and press **Run**.
3. Run `./gradlew assembleDebug` from the repository folder. The APK is automatically saved as `app/build/outputs/daily-tracker/Daily Tracker.apk`.

### Build a signed Google Play bundle

Create a release keystore outside this repository and keep a secure offline backup. Add these values as GitHub Actions repository secrets: `ANDROID_KEYSTORE_BASE64` (the keystore file encoded as one base64 string), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Never commit the keystore or its passwords.

Push a version tag such as `v1.1.0` to run the Play Store release job. The workflow verifies tests, release lint, and optimized APK packaging, then builds a signed Android App Bundle (`.aab`) with a CI-generated version code. Download `my-productivity-tracker-play-aab` from the workflow run and upload it to the Google Play Console. The workflow does not publish to Google Play automatically.

## Set up Google Sheets and Apps Script

The app's built-in setup guide walks through these steps. It generates a strong secret on your phone and inserts that same secret and your spreadsheet ID into the script you copy.

1. **Create your spreadsheet.** In the app, open **How to connect / Setup guide**. Tap **Download .xlsx template**, upload the file to Google Drive, open it with Google Sheets, and choose **File → Save as Google Sheets**. Alternatively, use an existing workbook with `Activities` and `Time_Log` sheets and the expected headers.
2. **Copy the spreadsheet URL.** In your browser, copy the full URL of the Google spreadsheet and paste it into the setup guide.
3. **Generate your API secret.** Tap **Generate secret** once. The app stores the key using Android's encrypted preferences backed by Android Keystore. Do not generate a different secret during setup; the generated value is not displayed on the guide.
4. **Build the configured script.** Tap **Build and copy Code.gs**. The app inserts the spreadsheet ID from the URL and the saved secret into its provided script, then copies the configured code to your clipboard.
5. **Add the script to the spreadsheet.** In the spreadsheet, choose **Extensions → Apps Script**. Replace the editor contents with the copied `Code.gs` and save. Select `setupWorkbook` in the function list, tap **Run**, and approve Google's requested permissions. This creates the `Activities` and `Time_Log` sheets and headers if they do not already exist.
6. **Deploy as a web app.** In Apps Script, choose **Deploy → New deployment**, select **Web app**, set **Execute as** to **Me**, and set **Who has access** to **Anyone**. Deploy and approve the permissions, then copy the web app URL ending in `/exec`.
7. **Connect the Android app.** Open **Settings** (or tap **Open connection settings** in the guide), paste the `/exec` URL, and confirm the generated API secret is already filled in. Tap **Save**, then **Test connection**.

If you already connected an older script, replace it with the current `Code.gs`, run `setupWorkbook` once, and deploy a **new version**. The script adds a `Reminder_At` column at the end of the Activities sheet if it is missing; new and edited activity reminder times are written there.

Keep the configured Apps Script private: it contains the API secret. The deployed endpoint requires the same secret for requests. The app stores the URL and secret encrypted on the phone, but a rooted or compromised device may expose local data. If you change the Apps Script code, deploy a **new version**. If you replace the secret, update the script and the app to use the matching value.

## Notes and limitations

- Speech recognition uses Google's recognizer (`en-IN`) and usually requires an internet connection. You can edit the transcript or type instead.
- Parsing is rule-based, so unusual phrases may need correction on the confirmation screen.
- Reports fetch activities from the spreadsheet and cache recent results on the phone for 10 minutes.
- All “today” date logic uses the `Asia/Kolkata` time zone.
- The app requires its Apps Script web app connection to read and sync spreadsheet data.

## Enjoy tracking your progress

Spend less effort maintaining a task list and more time understanding your day. Capture what you did, review your progress, and enjoy making steady progress—one activity at a time!
