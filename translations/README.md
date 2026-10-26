# Display text catalog

`en.json` maps existing Korean display keys to English. `ko.json` contains Korean display overrides for older bilingual keys. Other Korean keys display unchanged. The generated `UiText.java` is shared by both apps; it has no Android dependency and does not call `Locale.setDefault()`.

After editing a catalog:

```sh
python3 scripts/generate_translations.py
python3 scripts/generate_translations.py --check
python3 scripts/test.py --java /path/to/jdk17/bin/java
python3 -m unittest discover -s tests -p 'test_*.py'
```

Translation is applied only at presentation boundaries. Keep telemetry label arrays, signal IDs, units, service command strings and preference keys stable. Media/user/device strings must not be translated. New languages need an explicit resolver/selector addition and complete coverage; adding a JSON filename alone does not enable a language.

Application labels also live in each app's Android `res/values` (English fallback) and `res/values-ko` resources. UI language overrides are persisted separately in each package's `ui_language` preferences; the system locale is not changed.
