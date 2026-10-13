# AIS Track Master (Android)

Phone app for transporters/drivers to send a vehicle's live location to the
AIS Glass Vehicle Tracker while the vehicle is on the way to the customer.

## What it does
- Press **UPDATE** → all vehicles of your transporter currently in transit are listed with
  customer, destination, status, last known location and ETA (vehicles that need a
  location come first). Tap a vehicle to open its update screen.
- **START TRACKING** → the phone sends its GPS position to AIS automatically every
  5 / 10 / 15 / 30 / 60 minutes (foreground service, keeps running with the screen off)
  until the driver stops it, AIS marks the vehicle **REACHED**, or the gate pass is closed.
- **Send now (GPS)** for a one-off push, **Send place name** when GPS is not available.
- Every push is verified by AIS against the plant → customer route; the app shows
  Accepted / Rejected with the reason, and keeps a log of the last 100 updates.
- Settings: AIS server address, API key (both given by AIS), Test connection,
  "Allow background running" (battery optimisation exemption).

## Build the APK (no Android Studio needed)
1. Create a GitHub repository and upload this whole folder (keep the `.github` folder).
2. GitHub → **Actions** → the workflow *Build AIS Track Master APK* runs automatically
   on every push (or press **Run workflow**).
3. When it finishes, open the run → **Artifacts** → download **AIS_Track_Master_APK**
   (zip containing `AIS_Track_Master.apk`).
4. Send the APK to transporters; on the phone allow "Install unknown apps" and install.

## Server side
Requires AIS Vehicle Tracker v1.2 or later (Transporter API). AIS admin creates the
transporter in **Admin → Transporter API** and shares the API key. For phones outside
the AIS network, IT publishes `/api/ext/v1/*` on a public HTTPS address — that
address is what the driver enters as the server in the app's Settings.

Architected by DT · v1.1
