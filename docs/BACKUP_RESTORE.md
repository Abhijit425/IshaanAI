# Backing up and restoring Ishaan's records

**Why it matters:** records are stored only on this phone. If the phone is lost, broken, factory-reset, or the app is uninstalled, the records are gone — unless you have a backup.

## Make a backup
1. Settings → **Backup and restore → Create backup**.
2. Choose where to save the `.ishaanbackup` file (Downloads, a USB drive, or a cloud folder *you* choose such as Google Drive).
3. Enter a backup password (8+ characters) twice. **Write it down somewhere safe — without it the backup cannot be opened, and there is no recovery.**
4. Copy the file off the phone (e.g. to a computer or USB drive). Repeat regularly — the dashboard reminds you after 30 days.

What's inside: the whole database plus every document, photo and video, in one file encrypted with AES-256-GCM using a key derived from your password (PBKDF2-HMAC-SHA256, 150,000 iterations). It does not depend on this phone, so it can be restored on a new phone.

## Restore
1. Install Ishaan AI on the phone and set a PIN.
2. Settings → **Backup and restore → Restore**, pick the `.ishaanbackup` file, enter the password.
3. The app checks the whole file first. Only if everything decrypts correctly does it replace the records, then it restarts.

Restoring **replaces** all records currently on the phone with the backup's contents.

If you see "Wrong password or damaged backup file", nothing on the phone was changed.

## Other exports
- Settings → **Export complete history** creates a PDF of the complete medical summary (readable by anyone you share it with — handle it carefully).
- Every health report (AI tab) can be shared or saved as a PDF for the doctor.

## Updating the app safely
Install the new APK over the old one. Never uninstall first. Every build is signed with the same key, so updates keep all records. Make a backup before updating anyway.
