# Edelmetall Tracker – Version 0.3 (Test)

Android-App zum Verfolgen von Gold, Silber, Platin und Palladium. Ohne Konto, ohne eigenen Server, kostenlos.

## Was die App kann

- **Kurse:** Live-Spotpreise in € je Unze und je Gramm (Quelle: gold-api.com, jede Minute aktualisiert)
- **Charts:** 1 Std, 1 Tag, 1 Woche, 1/3/6 Monate, 1/5 Jahre, Maximum – Finger auflegen zeigt den Wert
- **Kurshistorie ab Euro-Einführung (1999):** wird beim Bauen automatisch aus freien Quellen geladen
  (Stooq bzw. Yahoo Finance, Umrechnung mit EZB-Kursen) – kein Schlüssel nötig, wöchentlich aktualisiert
- **Mehrere Portfolios:** z. B. „Gold“ oder „Käufe 2022“, jedes mit eigenem Wert und Gewinn/Verlust, dazu eine Gesamtansicht
- **Positionen** aus der Produktdatenbank wählen (≈ 250 Barren und Münzen) oder **eigene Produkte anlegen** (mit Foto)
- **Produktbilder** mit Prägung: Hersteller, Gewicht, Feinheit
- **Design:** Hell, Dunkel oder automatisch wie am Handy
- **Neu in 0.3:** Kupfer, Währung wählbar (EUR, USD, CHF, GBP), Kurse in g/oz/kg, Metall-Verteilung und
  Wertentwicklung je Portfolio, Sortierung der Positionen, Preisvorschlag beim Anlegen,
  Erkennung per Foto (Barren, Münze oder Rechnung – läuft offline auf dem Handy)
- **Haltefrist:** „steuerfrei ab …“ pro Position (§ 23 EStG, keine Steuerberatung)
- **Bewertung:** Spotpreis oder geschätzter Ankaufspreis (Spot minus einstellbarer Abschlag)
- **Sicherheit:** Entsperren mit Displaysperre (Fingerabdruck/Gesicht/PIN), verschlüsselte Speicherung
  (Android Keystore, AES-256), Screenshot-Schutz, kein Cloud-Backup
- **Diskret-Modus**, **verschlüsseltes Backup** mit eigenem Passwort

## So kommst du an die APK (kostenlos, ohne Installation)

1. Auf **github.com** kostenlos registrieren.
2. Oben rechts **+ → New repository**, Name z. B. `edelmetall-tracker`, **Private** wählen, erstellen.
3. Auf der leeren Repository-Seite **„uploading an existing file“** anklicken und den **Inhalt** des
   entpackten Ordners `edelmetall-tracker` hineinziehen (alle Dateien und Ordner, auch `.github`).
   Unten **Commit changes** klicken.
   *Tipp: Wird der Ordner `.github` nicht mit hochgeladen (Windows blendet ihn manchmal aus),
   im Explorer unter „Anzeigen“ → „Ausgeblendete Elemente“ aktivieren.*
4. Reiter **Actions** öffnen. Der Lauf „APK bauen“ startet automatisch (sonst: „APK bauen“ → **Run workflow**).
   Dauer: ca. 5–8 Minuten.
5. Wenn der Lauf grün ist: anklicken → unten unter **Artifacts** „EdelmetallTracker-APK“ herunterladen.
   Das ist eine ZIP-Datei, darin liegt `EdelmetallTracker.apk`.
6. APK aufs Handy kopieren, antippen, Installation aus dieser Quelle erlauben.

Ist ein Lauf **rot**, den Fehlertext aus dem Schritt „APK bauen“ kopieren und mir schicken – ich korrigiere es.

## Kurshistorie

Die lange Historie wird bei jedem Build auf GitHub automatisch geladen und ins Repository gespeichert
(`app/src/main/assets/history.json`, Protokoll in `data/history-log.txt`). Jeden Montag läuft der Build
automatisch und erstellt eine neue APK mit aktuellen Daten. Optional kann weiterhin ein gold-api.com-Schlüssel
als Secret `GOLDAPI_KEY` hinterlegt werden – nötig ist er nicht mehr.

## Alternative: Android Studio

Android Studio (kostenlos) installieren → **Open** → Ordner `edelmetall-tracker` → warten, bis Gradle fertig ist →
**Build → Build App Bundle(s)/APK(s) → Build APK(s)**.

## Hinweise zur Testversion

- Signiert mit einem **Test-Schlüssel** (`keystore/test.keystore`). Neue Testversionen lassen sich darüber
  installieren. Für den Play Store erstellen wir später einen eigenen, geheimen Upload-Schlüssel.
- Neue Version installieren: einfach die neue APK über die alte installieren – die Daten bleiben erhalten.
- Vor dem Deinstallieren ein Backup machen: Die Daten sind an dieses Gerät gebunden.
