# Karoo Fuel

Extension für den **Hammerhead Karoo 3**: berechnet während der Fahrt den Kalorien- und Kohlenhydratverbrauch und erinnert dich rechtzeitig an das nächste Gel.

*English summary below.*

## Was die App macht

- Berechnet jede Sekunde deinen **Energieverbrauch**: aus der **Leistung** (Wattmesser), ohne Wattmesser aus der **Herzfrequenz**.
- Schätzt, welcher Anteil davon aus **Kohlenhydraten** stammt. Je intensiver du fährst, desto höher ist er.
- Rechnet daraus, **wann das nächste Gel fällig ist**, und meldet sich mit Hinweis und Signalton: *„Zeit für ein Gel! 25 g Kohlenhydrate · Gel Nr. 3“*.

### Datenfelder (im Ride-Profil hinzufügen)

| Feld | Bedeutung |
|---|---|
| **kcal/h** | aktueller Verbrauch (über ca. 30 s geglättet) |
| **kcal gesamt** | Verbrauch seit Fahrtbeginn |
| **KH g/h** | aktueller Kohlenhydratverbrauch |
| **Nächstes Gel (min)** | Minuten bis zum nächsten Gel |
| **Gels** | Anzahl der Gels in dieser Fahrt |

### Tasten-Aktionen (Bonus Actions)

Die App geht davon aus, dass du das Gel nach der Erinnerung nimmst. Zwei Aktionen kannst du auf eine Taste legen (Karoo-Einstellungen → Steuerung):

- **Extra-Gel genommen**: zählt ein zusätzliches Gel, die nächste Erinnerung kommt später.
- **Gel nicht genommen**: macht die letzte Erinnerung rückgängig, du wirst nach 10 Minuten erneut erinnert.

## Installation

1. Am Smartphone die Seite [Releases](https://github.com/aluetschen/karoo-nutrition/releases/latest) öffnen.
2. Den Link zu **karoo-fuel.apk** lange drücken → **Teilen** → **Hammerhead Companion App** wählen.
3. Die App wird auf den Karoo übertragen. Updates meldet der Karoo danach selbst.
4. Am Karoo **Karoo Fuel** öffnen und Alter, Geschlecht und Gel-Größe eintragen.
5. Die Datenfelder in einem Ride-Profil hinzufügen: Profil bearbeiten → Feld → **Karoo Fuel**.

**Gewicht, FTP, Ruhe- und Maximalpuls** kommen aus deinem Karoo-Fahrerprofil. Halte sie dort aktuell.

## So wird gerechnet

**1. Energie aus der Leistung** (bevorzugt)
Die Arbeit an den Pedalen geteilt durch den Wirkungsgrad des Körpers beim Radfahren (ca. 24 %):

```
kcal/h = Watt × 3600 / 4184 / 0,24   →   200 W ≈ 717 kcal/h
```

Als Untergrenze gilt der Ruheumsatz (ca. 1 kcal pro kg Körpergewicht und Stunde), z. B. beim Rollen bergab.

**2. Energie aus der Herzfrequenz** (ohne Wattmesser)
Formel nach Keytel et al. (2005). Sie berücksichtigt Puls, Gewicht, Alter und Geschlecht. Sie ist ungenauer als die Wattmessung, weil der Puls auch von Hitze, Müdigkeit und Koffein abhängt.

**3. Kohlenhydrat-Anteil**
Je höher die Intensität (Leistung im Verhältnis zur FTP, sonst Anteil an der Herzfrequenzreserve), desto mehr Energie kommt aus Kohlenhydraten statt aus Fett:

| Intensität | 30 % | 65 % | 100 % (Schwelle) |
|---|---|---|---|
| KH-Anteil | 25 % | 60 % | 95 % |

**4. Gel-Bedarf**
- In den ersten **30 Minuten** reichen die Glykogenspeicher, es gibt keine Erinnerung.
- Danach sammelt die App einen „Bedarf“: **60 % der verbrannten Kohlenhydrate**, höchstens **60 g pro Stunde**. Mehr nimmt der Darm ohne Training kaum auf.
- Sobald der Bedarf die Gel-Größe erreicht (Standard 25 g), kommt die Erinnerung. Zwischen zwei Gels liegen mindestens 10 Minuten.

Beispiel bei 75 kg, FTP 250 W und konstant 200 W: Gels nach 55, 80, 105 … Minuten, also eins alle 25 Minuten (60 g/h).

Alle Werte stellst du in der App ein. Die Fahrt wird zwischengespeichert, falls die App mitten in der Fahrt neu startet.

> ⚠️ Alle Werte sind **Schätzungen** und keine medizinische oder ernährungswissenschaftliche Beratung. Teste deine Verpflegung im Training.

## Für Entwickler

- `core/`: reine Kotlin-Rechenlogik mit Unit-Tests (`./gradlew :core:test`)
- `app/`: Android-App und Karoo-Extension ([karoo-ext](https://github.com/hammerheadnav/karoo-ext) 1.1.9)
- GitHub Actions baut bei jedem Push. Ein Tag `v*` erzeugt ein Release mit `karoo-fuel.apk` und `manifest.json`.

**Neue Version veröffentlichen:** In `app/build.gradle.kts` `versionCode` erhöhen und `versionName` anpassen, dann einen Tag pushen, z. B. `v0.2.0`.

**Lokal bauen:** karoo-ext liegt auf GitHub Packages. Dafür brauchst du ein GitHub-Token mit `read:packages` in `~/.gradle/gradle.properties`:
```
gpr.user=<github-user>
gpr.key=<token>
```

**Signatur:** Damit Updates über ältere Versionen installiert werden können, muss jede APK mit demselben Schlüssel signiert sein. Dafür legst du unter *Settings → Secrets and variables → Actions* vier Secrets an: `SIGNING_KEYSTORE_BASE64` (Keystore-Datei, base64-kodiert), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` und `SIGNING_KEY_PASSWORD`. Ohne diese Secrets wird mit einem temporären Debug-Schlüssel signiert. Das reicht zum Testen, aber Updates erfordern dann eine Neuinstallation.

---

## English summary

Karoo Fuel is a Hammerhead Karoo 3 extension. It estimates your energy expenditure live, from power (preferred) or from heart rate (Keytel formula) together with weight, age and sex. It splits the energy into carbohydrate and fat burn depending on intensity, and reminds you to take a gel. By default it waits 30 minutes, then replaces 60 % of burned carbohydrates, capped at 60 g/h, with 25 g gels. It provides five data fields and two bonus actions (extra gel taken / gel not taken). Install it by sharing the APK link from the [latest release](https://github.com/aluetschen/karoo-nutrition/releases/latest) to the Hammerhead Companion app. All values are estimates, not medical advice.

License: MIT
