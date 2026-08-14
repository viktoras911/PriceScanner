# PriceScanner 2.0

Android kainos skeneris, naudojantis CameraX ir ML Kit Text Recognition.

## Kas pakeista 2.0 versijoje

- OCR gauna tik žalio skenavimo rėmelio ROI, o ne visą lentynos vaizdą.
- `Preview` ir `ImageAnalysis` susieti per bendrą CameraX `ViewPort` / `UseCaseGroup`.
- ROI koordinatės perskaičiuojamos pagal `ImageProxy.cropRect` ir kameros rotaciją.
- OCR analizuojamas `Text.Element` lygiu.
- Atpažįstami formatai: `4,99`, `4.99`, `4:99`, `4 99`, atskirai išdėstyti eurai ir centai.
- Toleruojamos dažnos OCR klaidos: `O -> 0`, `I/l -> 1`, superscript centų skaitmenys.
- Kandidatai vertinami pagal formatą, dydį, vietą rėmelio centre ir `€` artumą.
- Nuvertinamos / atmetamos vieneto kainos (`€/kg`, `€/l`), procentai, barkodai ir senos kainos.
- Rezultatas stabilizuojamas per kelių kadrų svertinį balsavimą ir pateikiamas patikimumas.
- Aptikta kaina užfiksuojama iki mygtuko **Skenuoti kitą**.
- Fokusas ir ekspozicijos matavimas nukreipiami į skenavimo rėmelį; rėmelį galima paliesti pakartotiniam fokusavimui.
- Pridėtas žibintuvėlio valdymas.
- Pageidaujama analizės raiška: 1280x960 su CameraX fallback strategija.
- Parseris ir stabilizatorius atskirti nuo Android UI bei padengti JVM testais.

## Struktūra

- `MainActivity.kt` – CameraX, UI, fokusas, torch, skenavimo būsena.
- `scanner/PriceImageAnalyzer.kt` – ROI crop, ML Kit OCR ir OCR elementų konvertavimas.
- `scanner/PriceParser.kt` – kainos kandidatų paieška ir scoring.
- `scanner/PriceStabilizer.kt` – kelių kadrų stabilizavimas.
- `scanner/PriceModels.kt` – bendri modeliai.
- `app/src/test/...` – parserio ir stabilizatoriaus testai.

## Build

Reikalinga JDK 17. Projekte naudojamas `compileSdk 36`. GitHub Actions workflow paleidžia:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

Sėkmingo workflow APK artefaktas: `app-debug.apk`.

## Naudojimas

1. Nukreipkite kamerą į prekės kainų etiketę.
2. Pagrindinę kainą laikykite žalio rėmelio centre.
3. Palaukite, kol rodoma būsena **Kaina užfiksuota**.
4. Pasirinkite nuolaidą arba įveskite savo procentą.
5. Kitai etiketei paspauskite **Skenuoti kitą**.
