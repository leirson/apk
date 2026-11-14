@echo off
chcp 65001 >nul
echo ========================================================
echo   GERADOR DE APK - PHPLAYER ANDROID (API NATIVO)
echo ========================================================
echo.

where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [OK] Gradle encontrado! Iniciando compilacao do APK...
    call gradle assembleDebug
    if %ERRORLEVEL% EQU 0 (
        echo.
        echo ========================================================
        echo   SUCESSO! SEU APK FOI GERADO EM:
        echo   app\build\outputs\apk\debug\app-debug.apk
        echo ========================================================
        explorer "app\build\outputs\apk\debug"
        pause
        exit /b 0
    )
)

echo [INFO] Caso nao tenha o Gradle no PATH, abra esta pasta no Android Studio:
echo   1. Abra o Android Studio -> File -> Open -> selecione esta pasta.
echo   2. No menu superior, clique em: Build -> Build Bundle(s) / APK(s) -> Build APK(s)
echo   3. O arquivo app-debug.apk sera gerado em app\build\outputs\apk\debug\app-debug.apk
echo.
pause
