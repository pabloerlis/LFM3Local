# LFM3Local — Harness MVP 0.3.0

**Escopo:** primeira capacidade de ferramentas do harness dedicado a LFM3-1.7B, sem adicionar regras de hinos ao motor.

## Arquitetura implementada

- `harness/HarnessCore.kt` — parser de chamadas do modelo em XML no formato LFM3, calculadora determinística, data/hora local, registro de ferramentas e política de limites.
- `harness/HarnessTurnCoordinator.kt` — orquestração de até **duas** chamadas de ferramenta por turno; separa texto visível do XML e dos blocos `<think>`; encaminha resultados reais de volta ao modelo.
- `InferenceEngine` + `ai_chat.cpp` — JNI estendido: `sendToolResult()`, com papel `tool` no template de conversa nativo.
- `MainActivity.kt` — streaming da resposta final e estados reais `Processando entrada`, `Gerando resposta`, `Executando ferramenta`, `Ferramenta executada/rejeitada`.
- `ChatStore.kt` — migração SQLite **v1 → v2**, preservando chats existentes e adicionando `tool_events` auditáveis, com acesso via chat → menu ⋮ → **Atividade do harness**.
- `HarnessCoreTest.kt` e `HarnessTurnCoordinatorTest.kt` — testes unitários, incluindo motor de inferência simulado.

### Ferramentas habilitadas

1. **`calculator`** — apenas `expression`, com operações `+ - * /` e parênteses. Avaliação numérica por parser seguro, sem `eval`, shell ou código de terceiros; divisão por zero, injeção, argumentos extras e resultados enormes são rejeitados.
2. **`get_current_datetime`** — consulta ao relógio do Android e fuso local; não recebe argumentos.

**Fora desta versão:** ferramentas com efeitos colaterais, buscas externas, execução dinâmica de código, acesso à agenda ou arquivos, RAG e skill de hinos. As duas ferramentas são informadas ao LFM3 por instrução estruturada no contexto do sistema; injeção de ferramentas no parâmetro `tools` do chat template será avaliada em fase posterior.

## Fluxo de execução

```text
Usuário escreve
  ↓
LFM3 gera em streaming
  ├── Resposta comum → exibir ao usuário
  └── <tool_call> XML
        ↓ validar gramática, nome, argumentos e limite
        ↓ registrar tentativa/resultado em SQLite
        ↓ executar ferramenta local permitida
        ↓ enviar resultado como mensagem `tool` ao LFM3
        ↓ LFM3 continua → resposta natural ao usuário
```

**Política de segurança:** no máximo duas ferramentas por turno; uma função desconhecida é rejeitada; chamadas repetidas são bloqueadas; tags incompletas não provocam execução; erros são exibidos como erros e não reportados como ações concluídas.

## Casos de teste para o APK

| Caso | Entrada | Resultado esperado |
|---|---|---|
| T1 | `Calcule (17*3)+2 usando a ferramenta calculator.` | A calculadora retorna `53` e o modelo responde ao usuário; evento no menu Atividade |
| T2 | `Qual é a data e a hora exata deste aparelho? Use a ferramenta de horário.` | Chamada a `get_current_datetime`; valor obtido do relógio local e evento |
| T3 | `Olá, tudo bem?` | Resposta normal, sem evento obrigatório de ferramenta |
| T4 | `Não utilize ferramentas, somente converse sobre um assunto.` | Sem chamada obrigatória; caso o modelo proponha XML indevidamente, o controle de execução ainda valida a função |
| T5 | Fazer T1, sair do chat e voltar | Histórico preservado, com retomada da conversa no contexto disponível |
| T6 | Desligar a rede e fazer T1/T2 | Tudo funciona offline |
| T7 | Interromper uma resposta e enviar nova mensagem | Não deve ficar preso em estado `Gerando resposta` |
| T8 | Abrir uma conversa criada na versão 0.2 | Banco migrado, mensagens preservadas |

O modelo é probabilístico; o **sucesso de T1/T2 não é garantido apenas por testes de parser**. É preciso verificar as emissões de XML e o resultado real com GGUF no aparelho.

## Validação automatizada

O GitHub Actions executa `gradle :app:testDebugUnitTest` e depois `gradle :app:assembleDebug`, com inspeção de `libai-chat.so` em ARM64.

**O que os testes automatizados cobrem:** casos válidos/inválidos de XML, conteúdos parcialmente gerados, expressão aritmética, segurança contra execução arbitrária, relógio fixo, política de limites e orquestração com motor simulado. **O que não cobrem:** comportamento real do LFM3 em dispositivo físico, tempo até primeiro token, consumo de RAM e recuperação de contexto sob pressão de memória.

## Próximas etapas

- Validar chamadas reais de ferramentas com GGUF e estudar a passagem de JSON Schemas diretamente ao chat template do `llama.cpp`.
- Prover medição de TTFT/tokens/s por turno e diagnósticos sem travamento silencioso.
- Adicionar skills isoladas e ferramenta `search_local_notes` com permissões.
- Executar smoke tests físicos e, quando possível, benchmarks repetíveis.

## Resultado da validação automatizada — 23/11/2026

- [GitHub Actions — execução 45200045261](https://github.com/pabloerlis/LFM3Local/actions/runs/45200045261): **SUCESSO**.
- `:app:testDebugUnitTest`: **BUILD SUCCESSFUL**, em execução automatizada.
- `:app:assembleDebug`: **BUILD SUCCESSFUL**.
- Verificação de `lib/arm64-v8a/libai-chat.so`: **SUCESSO**.
- Artefato: `LFM3Local-Harness-v0.3.0-debug-APK`, com modelo externo.
- Integridade do APK extraído: arquivo ZIP íntegro, sem erros; SHA-256 do APK `f7ceae4bff22069724b6009e89040631aa1e174c75e94bd796b7b97e0e845130`.

**Validação não realizada:** nenhuma execução do LFM3-1.7B GGUF em smartphone foi observada nesta etapa. A passagem de ferramentas ao modelo por prompt e o JNI de continuação precisam de teste real em hardware.
