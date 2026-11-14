# 📱 PHPlayer Android (Cliente API Nativo Kotlin) — Guia para Gerar o APK

Este pacote já está **100% organizado e pré-configurado** para gerar o arquivo instalável **`.apk`** do PHPlayer para Android.

---

## 🚀 Opção 1: Gerar o APK sem instalar nada no PC (via GitHub Actions na Nuvem)

Se você não quiser instalar o Android Studio no computador, este pacote já inclui o compilador automático na nuvem (`.github/workflows/build-apk.yml`):

1. Crie um repositório gratuito no [GitHub](https://github.com/new).
2. Envie (faça upload) de todos os arquivos desta pasta para o repositório.
3. Clique na aba **Actions** do seu repositório no GitHub.
4. O workflow **"Gerar APK Android (PHPlayer API)"** irá rodar sozinho em ~2 minutos e disponibilizar o arquivo **`PHPlayer-Android-APK.zip`** (contendo o `app-debug.apk` e `app-release.apk` prontos para instalar no celular)!

---

## 💻 Opção 2: Gerar o APK no Android Studio (1 Clique)

1. Extraia o arquivo `.zip` em uma pasta no seu computador.
2. Abra o **Android Studio** e clique em **Open** (Abrir), selecionando a pasta extraída.
3. Aguarde o Gradle sincronizar automaticamente.
4. No menu superior, clique em **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Clique em **locate** na notificação que aparecerá no canto inferior direito — o seu **`app-debug.apk`** estará em:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## 🔗 Recursos do Aplicativo Android (API):
- **Conexão direta com `api.php`**: Músicas, Álbuns, Artistas (com Logos/Fotos sincronizadas e separação por tipo: Álbuns de Estúdio, EPs, Singles, Ao Vivo, Compilações), Favoritos (`♥`), Playlists, Rádios Online e Podcasts.
- **Botão "Artista Aleatório" e "Álbum Aleatório"** nativos.
- **Cache Offline Automático**: Baixa automaticamente em segundo plano as músicas reproduzidas para ouvir sem internet.
- **Equalizador Nativo de Hardware do Android** + Visualizador de Letras (`?route=lyrics`).
