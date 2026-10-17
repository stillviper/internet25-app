# Internet25 Comercio (Android)

App del comercio: abre https://mi.internet25.online/panel/?_route=plugin/i25_fichas a pantalla completa e imprime
directo en la impresora interna de Sunmi (InnerPrinter, Bluetooth virtual de fábrica) o en la mini impresora Bluetooth emparejada.

- Sin dependencias externas (solo Android SDK).
- Compila en GitHub Actions (.github/workflows/build.yml) → artefacto `internet25-apk`.
- Firma: secretos del repo `I25_KEYSTORE_B64` (keystore en base64) e `I25_KS_PASS`. Sin secretos compila versión debug.
- El keystore NO va en git. Guárdalo aparte: sin él no se pueden publicar actualizaciones de la misma app.
