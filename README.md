# NexDS PRO

Emulador Nintendo DS para Android. Frontend próprio (Java + JNI) que roda o core libretro **melonDS**.

- O core é baixado automaticamente pelo GitHub Actions (`.github/workflows/build.yml`) e embutido no APK como `libretro_core.so`.
- Baixe o APK em Actions → último build → artifact `NexDS-PRO-debug`.
- Recursos: tela dupla com toque, controles virtuais, controle Bluetooth/USB, som, save in-game, save state, acelerar (FF), importar BIOS/firmware (opcional).
- Build local: coloque o core em `app/src/main/jniLibs/arm64-v8a/libretro_core.so` e rode `gradle assembleDebug`.
