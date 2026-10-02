# Aviator Analyzer — Android nativo

Aplicativo Android que usa **MediaProjection** para ler a tela do Aviator e extrair os multiplicadores visíveis com OCR.

## O que esta versão faz
- Lê automaticamente os multiplicadores do histórico do Aviator
- Mantém histórico local da sessão
- Calcula, nas últimas rodadas:
  - percentual abaixo de 2x
  - percentual de 2x+, 3x+, 5x+ e 10x+
  - sequência atual abaixo de 2x
  - volatilidade observada
- Exibe tudo em overlay flutuante e arrastável
- Não toca no botão de aposta
- Não trata histórico como previsão do próximo resultado

## Uso
1. Instale o APK
2. Abra o **Aviator Analyzer**
3. Permita sobreposição
4. Inicie a leitura
5. Autorize compartilhar a tela inteira
6. Abra o Aviator e mantenha a faixa de histórico de multiplicadores visível

O OCR foi calibrado para a faixa superior do layout mostrado no vídeo de referência.

## Gerar o APK
Abra **Actions > Build Android APK** e baixe o artefato **universal-game-analyzer-debug** do build mais recente concluído com sucesso.

## Observação
As porcentagens e sequências são estatísticas do histórico observado. Elas não garantem nem preveem o multiplicador da próxima rodada.
