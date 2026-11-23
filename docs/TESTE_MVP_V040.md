# Plano de validação física — LFM3Local v0.4.0

## Escopo

Verificar o harness dedicado ao `LFM3-1.7B-Q4_K_M.gguf` em um celular **Android 11+, ARM64**, usando o APK Debug v0.4 e o modelo original da Liquid AI. Os testes de compilação e JUnit são complementares; **não substituem** a execução no aparelho.

### Preparação e preservação de dados

- Antes de substituir o APK Debug, confira se a atualização mantém os dados da aplicação. Builds assinados por chaves Debug diferentes podem ser incompatíveis.
- **Não desinstalar sem backup:** a desinstalação apaga o SQLite local e a cópia do GGUF.
- O modelo fica separado do APK; caso a instalação seja limpa, importe novamente o GGUF.
- Na primeira abertura, observe as fases `Carregando GGUF`, `Preparando contexto`, `Aguardando mensagem`.

## Matriz prática

| ID | Teste | Critério |
|---|---|---|
| H01 | Abrir conversa existente da v0.3 | Histórico permanece; motor restaura o contexto sem erro |
| H02 | Criar conversa, fazer 5 perguntas sequenciais | Modelo aproveita respostas anteriores; não fica preso em `Processando prompt` |
| H03 | Perguntar `Calcule (17*3)+2 usando calculator` | Resultado 53; menu ⋮ → Atividade do harness registra `calculator` |
| H04 | Perguntar data e hora atual usando ferramenta | `get_current_datetime` retorna horário/fuso reais do dispositivo |
| H05 | Perguntar sem ferramenta | Geração normal; nenhuma ferramenta precisa ser executada |
| H06 | Interromper resposta longa e enviar outra pergunta | Estado retorna a pronto; reconstrução de contexto; sem duplicação indefinida |
| H07 | Fazer 20–30 mensagens de conteúdo longo | Quando a janela de 4096 tokens lotar, o app tenta reconstruir histórico uma vez, sem travar nem repetir indefinidamente |
| H08 | Mudar de chat e voltar | Contexto ativo pertence só à conversa selecionada |
| H09 | Fechar e reabrir o app | Histórico preservado; GGUF selecionado carrega automaticamente |
| H10 | Modo avião | Chat, calculadora e horário continuam disponíveis sem rede |
| H11 | Consultar a telemetria no chat | Exibe estado real do mecanismo e tempo até o primeiro texto/velocidade de caracteres |
| H12 | Deixar geração em andamento sem saída por mais de 15 s | A interface avisa sobre falta de progresso e informa a etapa do motor; não afirma que travou |
| H13 | Simular erro recuperável e tocar `Recarregar` | Tenta liberar/recarregar o modelo, preservando o banco de conversas |
| H14 | Tentar chamada a ferramenta indisponível | O harness não a executa; informa rejeição no registro, sem executar scripts |

### Evidências a registrar

Para cada defeito: versão do APK, nome exato do GGUF, captura da tela, status mostrado, tempo aproximado até a primeira resposta e sequência de mensagens que reproduz o problema. Se tiver ADB, registrar Logcat filtrando `LFM3Local`, `InferenceEngineImpl` e `llama.cpp`.

## Validação automatizada

`GitHub Actions` executa testes JUnit `app:testDebugUnitTest`, compilação `app:assembleDebug` e inspeção da biblioteca `lib/arm64-v8a/libai-chat.so`.

### Limites honestos

- O pipeline **não** confirma funcionamento com o modelo real em dispositivo Android; ainda é necessário completar H01–H14.
- `caracteres/s` não é `tokens/s`; medições e unidades são explicitamente distintas.
- Uma janela de contexto cheia reduz quantas mensagens antigas o modelo pode usar, mas **não remove** mensagens armazenadas em SQLite.
- O aviso por 15 segundos indica ausência de progresso visível no harness, não comprova um travamento nativo.
- Nenhum recurso de terceiros recebe permissão para executar código arbitrário. As ferramentas atuais são restritas à calculadora e à data/hora local.
