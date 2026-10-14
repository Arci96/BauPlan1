# BauPlan – Paper-Plugin für Minecraft 1.21.11

Bau-Pläne speichern/importieren und als Hologramm in der Welt anzeigen, um sie nachzubauen.

## Bauen über GitHub (ohne Maven auf dem eigenen PC)
1. Neues Repository auf github.com anlegen und den **Inhalt** dieses Ordners hochladen (nicht den Ordner selbst).
   Wichtig: der Ordner `.github` muss mit hoch.
2. Oben im Repository auf **Actions** klicken. Der Build startet automatisch (oder **Build BauPlan -> Run workflow**).
3. Wenn der Lauf grün ist: Lauf anklicken, unten bei **Artifacts** `BauPlan` herunterladen, ZIP entpacken -> `BauPlan.jar`.
4. `BauPlan.jar` in den `plugins`-Ordner des Servers legen, Server neu starten.
Wird der Lauf rot: den Fehlertext aus dem Log kopieren.

## Bauen (Jar erzeugen) lokal
Voraussetzung: Java 21 + Maven (https://maven.apache.org)

    mvn clean package

Die fertige Datei liegt dann in `target/BauPlan.jar` -> in den `plugins`-Ordner deines Paper-Servers kopieren, Server neu starten.

## Benutzen
1. Plan erstellen
   - Aus der Welt: `/bauplan pos1`, `/bauplan pos2` (Block ansehen), `/bauplan save haus1`
   - Aus Datei: `.schem`- oder `.litematic`-Datei in `plugins/BauPlan/import/` legen, dann `/bauplan import haus.schem` bzw. `haus.litematic`
2. Anzeigen: `/bauplan show haus1`
3. Platzieren: `/bauplan here` (an deine Position), `/bauplan move <dx> <dy> <dz>`, `/bauplan rotate`
4. Bauen: Hologramm-Blöcke haben keine Kollision – einfach durchbauen. Gebaute Blöcke verschwinden automatisch.
   Ebene für Ebene: `/bauplan layer 0`, `/bauplan layer up`, `/bauplan layer all`
5. Material: `/bauplan materials` (fehlend) oder `/bauplan materials haus1` (gesamt)
6. Ausblenden: `/bauplan hide`

Berechtigung: `bauplan.use` (Standard: OP)
Einstellungen: `plugins/BauPlan/config.yml`

## Eingebaute Pläne
Beim ersten Start legt das Plugin automatisch an:
- `huehnerfarm` – Boden aus Trichtern, alle Eier landen in einer Kiste in der Ostwand. 8-12 Hühner reinsetzen, fertig.

## Direkt einbauen
- `/bauplan show <name>`, positionieren, dann `/bauplan paste` baut den Plan sofort in die Welt.
- `/bauplan undo` nimmt den letzten Einbau zurück (Kisteninhalte gehen dabei verloren).
- Berechtigung: `bauplan.build` (Standard: OP)

## Fertige Farm-Schematics benutzen
- `/bauplan download <https-link-zur-.schem> <name>` und danach `/bauplan import <name>.schem`
  (oder die Datei selbst in `plugins/BauPlan/import/` legen)
