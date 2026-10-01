# Universal Game Analyzer — Android nativo

Aplicativo Android para análise visual em tempo real usando a API oficial **MediaProjection** do Android.

## Recursos
- Captura autorizada da tela via MediaProjection
- Overlay flutuante e arrastável
- Perfis 3×5 e 5×6
- Histórico visual por sessão
- Comparação de contextos semelhantes
- Estados: **COLETANDO / SEM EVIDÊNCIA / SINAL EM FORMAÇÃO / SINAL DE TESTE — PRÓXIMO GIRO**
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


## Como interpretar os estados
- **COLETANDO**: os giros estão alimentando a análise.
- **SEM EVIDÊNCIA**: o último resultado não formou um contexto repetitivo relevante.
- **SINAL EM FORMAÇÃO**: o contexto atual começou a se parecer com situações anteriores.
- **SINAL DE TESTE — PRÓXIMO GIRO**: o próximo giro é destacado como teste experimental com base em repetição visual histórica.

O estado permanece na tela até o próximo resultado e é recalculado somente quando uma nova rodada termina.
