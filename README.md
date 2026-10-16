# lexi-android

Android-приложение Lexi: WebView-обёртка веб-приложения (`https://app.lexi.casa/`) с мостом в нативный код. Создано из шаблона `android-webview-template`. Дополнительно к вибрации и информации об устройстве реализованы методы моста `keepScreenOn` — удержание экрана включённым (`FLAG_KEEP_SCREEN_ON`), `scanText` — распознавание текста с камеры через Tesseract4Android, `speak` — озвучка: скачанный офлайн-нейроголос (sherpa-onnx + Piper) или системный TextToSpeech, и `recognizeSpeech` — распознавание речи на заданном языке (системный `SpeechRecognizer` или офлайн-модель Whisper на sherpa-onnx). Плюс методы управления голосами `ttsVoices`/`downloadVoice`/`deleteVoice` и распознаванием `asrStatus`/`downloadAsr`/`deleteAsr`.

## Стек

- Kotlin, Gradle 8.5, AGP 8.2.2, версии — в `gradle/libs.versions.toml`
- compileSdk/targetSdk 34, minSdk 26
- `androidx.webkit` (WebViewAssetLoader), `androidx.activity`, `androidx.exifinterface`
- `cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0` (JitPack), класс API — `com.googlecode.tesseract.android.TessBaseAPI`
- `org.apache.commons:commons-compress:1.28.0` — распаковка tar.bz2 с голосами
- `app/libs/sherpa-onnx-1.13.8.aar` (Apache-2.0, из GitHub-релизов k2-fsa/sherpa-onnx) — офлайн-синтез и распознавание речи; APK ~95 МБ
- APK собирается только под `arm64-v8a` и `armeabi-v7a` (эмулятор x86 не поддерживается)

## Быстрый старт

Android Studio: открыть папку проекта и нажать Run.

CLI (JDK 17 из Android Studio):

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug
```

`sdk.dir` для CLI — в `local.properties` (в git не попадает). Тестировать нужно на устройстве: сканирование использует камеру.

## Установка на телефон

Debug-APK ставится без ключей:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

По USB: `./gradlew :app:installDebug` или `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Либо скинуть APK на телефон и открыть файл (нужно разрешение «Установка неизвестных приложений»).

## Подписанный release APK

1. Создать ключ один раз (файл хранить отдельно от репозитория):

```bash
keytool -genkeypair -v -keystore lexi-release.jks -alias lexi -keyalg RSA -keysize 2048 -validity 10000
```

2. Создать в корне репозитория `keystore.properties` (в git не коммитится):

```properties
storeFile=lexi-release.jks
storePassword=пароль
keyAlias=lexi
keyPassword=пароль
```

Путь в `storeFile` — относительно корня репозитория или абсолютный.

3. Собрать:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Без `keystore.properties` release собирается неподписанным (`app-release-unsigned.apk`) — на телефон его не установить. Ключ и пароли не терять: обновления приложения подписываются тем же ключом.

## URL веб-приложения

Настраивается в `app/webview.properties`:

```properties
webview.prodUrl=https://app.lexi.casa/
webview.devUrl=
```

- debug-сборка берёт `webview.devUrl`, если пуст — `webview.prodUrl`; release — `webview.prodUrl`; если пусто — локальная демо-страница из `assets`.
- Для разработки укажи в `webview.devUrl` адрес Vite-сервера (`npm run dev -- --host`) и собери debug.
- HTTP разрешён только в debug (`app/src/debug/res/xml/network_security_config.xml`); release — только https.

## Метод моста scanText

```js
const result = await window.nativeBridge.call("scanText", { lang: "ru" });
// result: { text: "...", confidence: 87, cancelled: false }
```

- `lang` — код языка из приложения: `ru`, `en`, `es`, `fr` (внутри маппится на код модели Tesseract: `rus`, `eng`, `spa`, `fra`).
- Открывается системная камера (`ACTION_IMAGE_CAPTURE`, без разрешения `CAMERA`), снимок кладётся в `cacheDir/scans` через FileProvider, после распознавания удаляется.
- Отмена съёмки — успешный ответ `{ text: "", cancelled: true }`; ошибки приходят как reject с кодом: `unsupported_language`, `busy`, `no_camera_app`, `decode_failed`, `model_download_failed`, `tesseract_init_failed`.
- Перед распознаванием фото уменьшается до 2000px по большей стороне, разворачивается по EXIF и приводится к серой шкале с растяжением контраста по гистограмме (1%–99%); PSM — `AUTO`.

## Метод моста speak

```js
const result = await window.nativeBridge.call("speak", { text: "Понедельник", lang: "ru" });
// result: { spoken: true, utteranceId: "lexi-1" }
```

- `lang` — код языка из приложения: `ru`, `en`, `es`, `fr`.
- Порядок выбора (`speech/SpeechService.kt`): скачанный офлайн-голос (sherpa-onnx) → системный `TextToSpeech` (`android.speech.tts.TextToSpeech`, `QUEUE_FLUSH`) → ошибка `voice_missing`.
- В манифесте объявлен `<queries>` с `android.intent.action.TTS_SERVICE` — без него на Android 11+ (`targetSdk 30+`) TTS-движок не виден и инициализация падает (`speech_unavailable`).
- Диагностика — логи `LexiSpeech` (`adb logcat -s LexiSpeech`).

## Метод моста recognizeSpeech

```js
const result = await window.nativeBridge.call("recognizeSpeech", { lang: "en" });
// result: { transcript: "mother", confidence: null, alternatives: [] }
```

- `lang` — код языка из приложения: `ru`, `en`, `es`, `fr`; язык всегда передаётся движку, ответ приходит на нём же.
- Порядок выбора (`speech/RecognitionService.kt`): системный `SpeechRecognizer` (`ACTION_RECOGNIZE_SPEECH`, локаль по языку, до 3 вариантов и confidence) → on-device (`createOnDeviceSpeechRecognizer`, Android 12+) → офлайн-модель. Если системный движок вернул ошибку «язык недоступен» (Android 13+) или результат в чужой письменности (например, кириллицу для английского) — распознавание автоматически повторяется офлайн-моделью.
- Офлайн-модели (`speech/OfflineAsr.kt`): для выбранного языка используется самая специфичная скачанная модель — для английского `nemo-en-conformer-small` (NeMo Conformer CTC, int8 ~46 МБ, лучше на одиночных словах), для остальных языков `whisper-tiny-multilingual` (Whisper tiny int8 ~104 МБ, язык передаётся в декодер); мультиязычная модель также служит фолбэком для английского. Распознаватель кешируется по паре «модель+язык», конфиг собирается по движку (`nemo_ctc`/`whisper`), старые неизвестные модели при распаковке удаляются.
- Артефакты распознавания (`speech/AsrCatalog.kt`): `asrStatus` отдаёт `{ artifacts: [{ id, title, description, sizeBytes, languages, installed, downloading, progress, error }] }`; моделей две — английская NeMo CTC small (76 МБ) и мультиязычная Whisper tiny (116 МБ, 4 языка, фолбэк для английского); `downloadAsr { id }` скачивает модель (резолвится по завершении, прогресс — в `asrStatus`, во время загрузки приложение опрашивает статус раз в секунду), `deleteAsr { id }` удаляет и выгружает распознаватель. Веб-приложение показывает список артефактов на экране «Распознавание речи» (ручная кнопка «Загрузить»), а при клике на микрофон без подходящей модели для языка открывает шит и запускает загрузку самой специфичной модели автоматически с прогрессом.
- Запись — `AudioRecord` (16 кГц, mono) с простым VAD: тишина до и после речи отсекается, максимум 12 с.
- Загрузка модели идёт в foreground-сервисе `speech/DownloadService.kt` (`foregroundServiceType="dataSync"`): уведомление «Загрузка: …» с процентами и переходом в приложение, поэтому процесс не замораживается и загрузка продолжается при автоблокировке экрана и в фоне. `AsrManager` — синглтон (`AsrManager.get(context)`), `release()` не выгружает менеджер, пока идёт загрузка. В манифесте — `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` и `POST_NOTIFICATIONS` (разрешение на уведомления спрашивается при старте первой загрузки, без него сервис работает, но прогресс не показывается).
- Отмена — `cancelRecognizeSpeech`; статус — `asrStatus`; синхронный `hasRecognition()` — доступность.
- Ошибки приходят как reject с кодом: `permission_denied`, `no_speech`, `network`, `busy`, `language_not_supported`, `asr_download_failed`, `recognition_failed`.

## Офлайн-голоса (ttsVoices / downloadVoice / deleteVoice)

```js
const { voices } = await window.nativeBridge.call("ttsVoices", {});
// voices: [{ lang, id, title, sizeBytes, installed, downloading, progress, error }]

await window.nativeBridge.call("downloadVoice", { lang: "ru" }); // резолвится по завершении
await window.nativeBridge.call("deleteVoice", { lang: "ru" });
```

- Каталог — `speech/VoiceCatalog.kt` (Piper medium): ru `ru_RU-ruslan-medium` (Руслан), en `en_US-lessac-medium`, es `es_ES-sharvard-medium`, fr `fr_FR-siwis-medium`, it `it_IT-paola-medium`, de `de_DE-thorsten-medium`, zh `zh_CN-huayan-medium`; 64–80 МБ.
- Источники (по порядку, при ошибке — следующий): HuggingFace `csukuangfj/vits-piper-<id>` (файлы `.onnx` + `tokens.txt`) → GitHub-релиз `k2-fsa/sherpa-onnx@tts-models` (tar.bz2, распаковка commons-compress). Файлы кладутся в `filesDir/tts/<id>`.
- `espeak-ng-data` (18 МБ, общий для всех голосов) вшит в APK (`assets/tts/espeak-ng-data.zip`) и распаковывается один раз в `filesDir/tts/espeak-ng-data`.
- Пока идёт загрузка, `ttsVoices` возвращает `downloading: true` и `progress` (0..1) — веб опрашивает раз в секунду.
- Скачивание идёт через общий foreground-сервис `speech/DownloadService.kt` (`foregroundServiceType="dataSync"`): уведомление «Загрузка голоса: …» с процентами и переходом в приложение, поэтому загрузка продолжается при автоблокировке экрана и в фоне. `VoiceManager` — синглтон (`VoiceManager.get(context)`), `release()` не выгружает менеджер, пока идёт загрузка; разрешение на уведомления (Android 13+) спрашивается при старте загрузки.
- Синтез и воспроизведение — `speech/LocalTts.kt` (`OfflineTts` + `AudioTrack`); загруженная модель живёт в памяти до смены языка или удаления голоса.
- При ошибке загрузки в `ttsVoices` приходит `error` с деталями (`huggingface:http_403; github:...`, `storage_unavailable` и т.п.).
- Пока TTS инициализируется, последняя фраза ждёт готовности и озвучивается после `onInit`.
- Ошибки приходят как reject с кодом: `text_required`, `speech_unavailable`, `language_not_supported`, `speak_failed`, `superseded` (фраза вытеснена более новой).
- В веб-приложении озвучка карточек сама выбирает способ: нативный `speak` при наличии моста, иначе Web Speech API браузера (`app/src/transport/speech.ts` в elemental).

## Модели Tesseract

- `ru` (`rus.traineddata`, точная модель `tessdata_best`) и `en` (`eng.traineddata`, быстрая `tessdata_fast`) вшиты в `app/src/main/assets/tessdata` — работают офлайн сразу.
- Остальные языки скачиваются при первом использовании с `tesseract-ocr/tessdata_fast@4.0.0` в `filesDir/tesseract/tessdata` и дальше работают офлайн. Скачивание требует интернет один раз на язык.
- Вшитые модели копируются в `filesDir` один раз; при обновлении приложения копии обновляются по ревизии (`MODEL_REVISION` в `TextScanner.kt`).
- В коде — `TextScanner.ensureModel`; URL и список языков (`MODEL_ALIASES`) — в конце `TextScanner.kt`.

## Структура

```
app/src/main/java/dev/alexander812/lexi/
  MainActivity.kt              WebView, asset loader, back-навигация, wiring сканера, озвучки и распознавания
  bridge/NativeBridge.kt       @JavascriptInterface-мост: vibrate, deviceInfo, keepScreenOn, scanText, speak, recognizeSpeech, cancelRecognizeSpeech, asrStatus, downloadAsr, deleteAsr, hasRecognition, ttsVoices, downloadVoice, deleteVoice
  ocr/TextScanner.kt           камера → препроцесс → Tesseract → результат
  speech/RecognitionService.kt системный SpeechRecognizer → офлайн-модель: выбор, разрешение, отмена
  speech/OfflineAsr.kt         sherpa-onnx Whisper: запись с VAD, декод на заданном языке
  speech/AsrManager.kt         скачивание tar.bz2 модели распознавания, распаковка, удаление, статусы
  speech/DownloadService.kt    foreground-сервис загрузок (dataSync) с прогрессом в уведомлении
  speech/AsrCatalog.kt         модель распознавания: id, URL GitHub-релиза, языки, размер
  speech/AsrStorage.kt         filesDir/asr: encoder/decoder/tokens
  speech/SpeechService.kt      выбор: локальный голос → системный TTS → voice_missing
  speech/SpeechSynthesizer.kt  системный TextToSpeech: языки, очередь до init, release
  speech/LocalTts.kt           sherpa-onnx: загрузка модели, синтез, воспроизведение (AudioTrack)
  speech/VoiceManager.kt       скачивание tar.bz2, распаковка, удаление, статусы
  speech/VoiceCatalog.kt       каталог Piper-голосов: id, URL GitHub-релиза, размеры
  speech/VoiceStorage.kt       filesDir/tts
app/libs/sherpa-onnx-1.13.8.aar  офлайн-движок (Apache-2.0, все ABI)
app/src/main/assets/www/       демо-страница моста (кнопки вибрации, инфо, сканирование, озвучка)
app/src/main/assets/tts/       espeak-ng-data.zip (общий фонетический словарь для Piper-голосов)
app/src/main/assets/tessdata/  вшитые модели ru и en
app/src/main/res/mipmap-*/     иконка: эмблема на #050505 (адаптивная + legacy, округлённая и квадратная)
app/webview.properties         URL веб-приложения: prod и dev
```

## Добавление метода моста

1. Kotlin, `NativeBridge.call`: добавить ветку метода и `respond(requestId, ...)`. Для асинхронных операций (как `scanText`) — сохранить `requestId` и ответить позже, ничего больше не требуется.
2. JS — вызвать `window.nativeBridge.call("method", params)`; клиент моста в веб-приложении — `app/src/lib/nativeBridge.ts` (elemental).

## Отладка

В debug-сборке включён `WebView.setWebContentsDebuggingEnabled(true)`: страница видна в Chrome через `chrome://inspect`. Если `webview.devUrl` пуст, приложение покажет демо-страницу с кнопкой «Сканировать текст (ru)».
