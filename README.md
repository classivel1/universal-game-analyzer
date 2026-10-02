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


## Limite de risco por rodada
A versão atual calcula um alvo conservador para a próxima rodada usando apenas o histórico recente da sessão. O alvo fica fixo durante a rodada e pode variar entre 1.30x, 1.40x e 1.50x conforme sequência abaixo de 2x, proporção de resultados abaixo de 2x e volatilidade observada.

Durante o voo, o overlay tenta ler também o multiplicador atual e mostra:
- **ABAIXO DO LIMITE**
- **PRÓXIMO DO LIMITE**
- **SAIR AGORA — LIMITE ATINGIDO**

Esse alvo é uma regra de gestão de risco baseada no histórico observado. Ele não prevê o ponto de crash nem garante que o avião chegará ao alvo.


## Atualizações
As versões atuais usam uma assinatura estável explícita no pipeline de build para permitir atualização por cima da instalação existente. Instalações antigas assinadas antes dessa configuração podem exigir uma reinstalação única.
