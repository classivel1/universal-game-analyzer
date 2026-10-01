# Universal Game Analyzer — Android nativo

Aplicativo Android para análise visual em tempo real usando a API oficial **MediaProjection** do Android.

## Recursos
- Captura autorizada da tela via MediaProjection
- Overlay flutuante e arrastável
- Perfis 3×5 e 5×6
- Histórico visual por sessão
- Comparação de contextos semelhantes
- Estados: **AGUARDE / OBSERVANDO PADRÃO / PADRÃO OBSERVADO**
- Não toca automaticamente no botão do jogo

## Gerar o APK
1. Abra a aba **Actions**
2. Entre em **Build Android APK**
3. Toque em **Run workflow**
4. Quando terminar, abra a execução e baixe o artefato **universal-game-analyzer-debug**
5. Extraia o ZIP do artefato e instale o `app-debug.apk`

## Uso
1. Abra o aplicativo
2. Toque em **Permitir sobreposição**
3. Escolha o perfil do jogo
4. Toque em **Iniciar captura e análise**
5. Autorize o compartilhamento/captura de tela do Android
6. Abra o jogo

O score mede semelhança com situações anteriores e não garante o resultado da próxima rodada.
