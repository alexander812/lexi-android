# lexi-android

Android-приложение Lexi: WebView-обёртка веб-приложения (`https://alexander812.github.io/elemental/`) с мостом в нативный код. Создано из шаблона `android-webview-template`. Дополнительно к вибрации и информации об устройстве реализован метод моста `scanText` — распознавание текста с камеры через Tesseract4Android.

## Стек

- Kotlin, Gradle 8.5, AGP 8.2.2, версии — в `gradle/libs.versions.toml`
- compileSdk/targetSdk 34, minSdk 26
- `androidx.webkit` (WebViewAssetLoader), `androidx.activity`, `androidx.exifinterface`
- `cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0` (JitPack), класс API — `com.googlecode.tesseract.android.TessBaseAPI`
- APK собирается только под `arm64-v8a` и `armeabi-v7a` (эмулятор x86 не поддерживается)

## Быстрый старт

Android Studio: открыть папку проекта и нажать Run.

CLI (JDK 17 из Android Studio):

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug
```

`sdk.dir` для CLI — в `local.properties` (в git не попадает). Тестировать нужно на устройстве: сканирование использует камеру.

## URL веб-приложения

Настраивается в `app/webview.properties`:

```properties
webview.prodUrl=https://alexander812.github.io/elemental/
webview.devUrl=
```

- debug-сборка берёт `webview.devUrl`, release — `webview.prodUrl`; пустое значение → локальная демо-страница из `assets`.
- Для разработки укажи в `webview.devUrl` адрес Vite-сервера (`npm run dev -- --host`) и собери debug.
- HTTP разрешён только в debug (`app/src/debug/res/xml/network_security_config.xml`); release — только https.

## Метод моста scanText

```js
const result = await window.nativeBridge.call("scanText", { lang: "ru" });
// result: { text: "...", confidence: 87, cancelled: false }
```

- `lang` — код языка из приложения: `ru`, `en`, `es`, `fr`, `it`, `de`, `zh` (внутри маппится на код модели Tesseract, `zh` → `chi_sim`).
- Открывается системная камера (`ACTION_IMAGE_CAPTURE`, без разрешения `CAMERA`), снимок кладётся в `cacheDir/scans` через FileProvider, после распознавания удаляется.
- Отмена съёмки — успешный ответ `{ text: "", cancelled: true }`; ошибки приходят как reject с кодом: `unsupported_language`, `busy`, `no_camera_app`, `decode_failed`, `model_download_failed`, `tesseract_init_failed`.
- Перед распознаванием фото уменьшается до 2000px по большей стороне и разворачивается по EXIF; PSM — `AUTO`.

## Модели Tesseract

- `ru` (`rus.traineddata`) и `en` (`eng.traineddata`) вшиты в `app/src/main/assets/tessdata` — работают офлайн сразу.
- Остальные языки скачиваются при первом использовании с `tesseract-ocr/tessdata_fast@4.0.0` в `filesDir/tesseract/tessdata` и дальше работают офлайн. Скачивание требует интернет один раз на язык.
- В коде — `TextScanner.ensureModel`; URL и список языков (`MODEL_ALIASES`) — в конце `TextScanner.kt`.

## Структура

```
app/src/main/java/dev/alexander812/lexi/
  MainActivity.kt              WebView, asset loader, back-навигация, wiring сканера
  bridge/NativeBridge.kt       @JavascriptInterface-мост: vibrate, deviceInfo, scanText
  ocr/TextScanner.kt           камера → препроцесс → Tesseract → результат
app/src/main/assets/www/       демо-страница моста (кнопки вибрации, инфо, сканирование)
app/src/main/assets/tessdata/  вшитые модели ru и en
app/webview.properties         URL веб-приложения: prod и dev
```

## Добавление метода моста

1. Kotlin, `NativeBridge.call`: добавить ветку метода и `respond(requestId, ...)`. Для асинхронных операций (как `scanText`) — сохранить `requestId` и ответить позже, ничего больше не требуется.
2. JS — вызвать `window.nativeBridge.call("method", params)`; клиент моста в веб-приложении — `app/src/lib/nativeBridge.ts` (elemental).

## Отладка

В debug-сборке включён `WebView.setWebContentsDebuggingEnabled(true)`: страница видна в Chrome через `chrome://inspect`. Если `webview.devUrl` пуст, приложение покажет демо-страницу с кнопкой «Сканировать текст (ru)».
