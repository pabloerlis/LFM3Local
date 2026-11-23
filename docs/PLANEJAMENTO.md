# Planejamento técnico — LFM3Local

**Data:** 2026-11-23  
**Estado:** Planejamento inicial; implementação ainda não iniciada  
**Repositório:** https://github.com/pabloerlis/LFM3Local

## 1. Objetivo

Construir um aplicativo Android nativo (APK) de inferência **100% local**, com um **harness dedicado ao Liquid AI LFM3-1.7B**, priorizando o arquivo **LFM3-1.7B-Q4_K_M.gguf**. A interface deve oferecer chat responsivo, orquestração de ferramentas locais, memória persistente opcional, telemetria e diagnóstico transparente dos estados de execução.

Importante: a extensão de arquivo correta é **.gguf**, não **.ggfu**. O modelo de referência é o checkpoint pós-treinado/instruct, **não** o checkpoint Base.

## 2. Evidências técnicas

- Modelo: **1,73 bilhão de parâmetros**, arquitetura tri-híbrida com 24 camadas (13 convoluções curtas com gates, 5 sliding-window attention, 6 global attention); suporte declarado a 256K tokens de contexto, mas esse limite não equivale à memória disponível no Android.
- GGUF oficial Q4_K_M: **~1,05 GB em disco**. O uso total de RAM depende de runtime, buffers e KV cache e deve ser medido.
- O GGUF exige **llama.cpp v0.8.0 / b11960 ou posterior**, de acordo com a documentação oficial do modelo.
- O LFM3 utiliza ChatML com prefixos, marcadores de raciocínio `<think>` e ferramentas em `<tool_call>` XML. Nunca montar o prompt como simples concatenação de strings.
- Sampler de referência para conversação/ferramentas: **temperature 0.1**, **top_k 50**, **repeat_penalty 1.1**. Perfil para matemática/ciências deve ser opcional (temperature 1.0, top_p 0.95, top_k 20).
- Liquid AI recomenda retrieval/ferramentas para conhecimento factual e desaconselha atribuir autonomamente ao modelo tarefas extensas de agentic coding ou raciocínio complexo.
- Base Android inicial: **arm64-v8a**. O guia upstream de llama.cpp demonstra Android Studio, JNI, importação GGUF por ContentResolver e geração em Kotlin Flow.
- Aceleração inicial: CPU, kernels ARM/KleidiAI conforme suporte. OpenCL/Vulkan somente após benchmarks e checagem por dispositivo; nunca presumir GPU compatível.
- Licença dos pesos: **LFM Open License v1.0**. Confirmar condições de distribuição/aviso antes de redistribuir pesos ou publicar na loja.

Fontes (consultadas em 2026-11-23):
- https://huggingface.co/LiquidAI/LFM3-1.7B
- https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF
- https://docs.liquid.ai/examples/agent-harnesses
- https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md
- https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md

## 3. Princípios inegociáveis

1. **Offline real**: chat e ferramentas locais funcionam sem rede. A rede só é usada para baixar o modelo mediante ação do usuário e, futuramente, ferramentas externas habilitadas explicitamente.
2. **Um modelo-alvo**: reconhecer metadados/arquitetura LFM3; falhar com mensagem clara para GGUF incompatível. Suporte genérico a outros LLMs não é prioridade.
3. **Sem bloqueio indefinido**: todo carregamento ou inferência passa por estados observáveis, mensagem de erro, cancelamento e watchdog com diagnósticos.
4. **Sem ações implícitas**: ferramentas com efeito colateral exigem confirmação; nenhuma ferramenta acessa shell arbitrário.
5. **Primeiro funcional, depois otimizado**: APK com resposta textual e medições precede RAG, memória e aceleração GPU.
6. **Sem promessas de desempenho não medido**: documentar hardware, build do motor, tokens/s, TTFT, memória de pico e temperatura.
7. **Reprodutibilidade**: fixar commit/versão do llama.cpp, NDK e versão de dependências; gerar APK por CI.

## 4. Arquitetura de referência

```text
Android App (Kotlin + Jetpack Compose)
├── UI
│   ├── Chat: streaming, cancelar, histórico, erros
│   ├── Atividade: estados e eventos das ferramentas (sem ocultar travas)
│   ├── Modelo: importar/baixar/verificar GGUF e carregar automaticamente
│   └── Diagnóstico: RAM, tokens/s, TTFT, logs exportáveis
├── Application/Domain
│   ├── ChatController / StateMachine
│   ├── LFM3PromptFormatter + tokenizer/template upstream
│   ├── ContextBudgetManager
│   ├── HarnessOrchestrator
│   │   ├── ToolRegistry (schema, permissões, validator)
│   │   ├── ToolCallParser (XML robusto, streaming)
│   │   ├── ToolExecutor (resultado estruturado + timeout)
│   │   └── TurnCoordinator (limites, retomada, tratamento de erro)
│   ├── ConversationRepository (Room)
│   └── MemoryRepository (Room, com aprovação do usuário)
└── Infraestrutura
    ├── Lfm3InferenceEngine (JNI/C++ para llama.cpp fixado)
    ├── LocalModelRepository (SAF, SHA-256, app-private storage)
    ├── DeviceCapabilityProbe
    └── Instrumentation/Telemetry
```

**Regra de separação:** o motor gera tokens; o harness interpreta, escolhe ferramentas, valida e coordena; a UI apresenta estados. Não colocar regras de negócio no JNI nem fazer o modelo simular a execução de ferramenta por texto.

## 5. Harness dedicado ao LFM3

### 5.1 Ciclo de turno
1. Receber mensagem e carregar histórico relevante.
2. Montar mensagens estruturadas (system/user/assistant/tool) pelo template oficial LFM3, com orçamento de tokens.
3. Produzir tokens em streaming; separar conteúdo, marcadores de raciocínio e possíveis blocos de tool call.
4. Detectar conclusão de chamada de ferramenta por parser de XML incremental e validá-la contra schemas registrados.
5. Se for ferramenta habilitada, executar no host com timeout, registrando consentimento quando preciso.
6. Inserir o resultado na conversa como **tool message** e solicitar continuação à IA.
7. Encerrar com resposta final, erro estruturado ou pedido de intervenção humana.

**Limites iniciais configuráveis:** 3 chamadas de ferramenta por turno; 2 tentativas de reparo de formato; 1 execução de cada ação com efeitos colaterais sem repetição automática; máximo de 512 tokens de resposta por padrão, ajustável.

### 5.2 Ferramentas do primeiro ciclo
- `calculator`: avaliação matemática em parser seguro, determinístico.
- `get_current_datetime`: data/hora local com timezone.
- `search_local_notes`: busca textual em notas permitidas, sem enviar dados para fora do telefone.
- `save_note`: gravar nota **após confirmação explícita**.
- `search_tools` (posterior): descoberta adiada do catálogo de ferramentas, conforme template do modelo.

**Fora do MVP:** acesso remoto a web, execução de comandos, plugins arbitrários, automação irrestrita do aparelho e ações silenciosas.

### 5.3 Qualidade de conversação
- Evitar repetir perguntas respondidas: preservar slots explícitos do diálogo, sumário rastreável e as últimas mensagens.
- Quando ferramenta falhar: apresentar evento e resultado; nunca inventar que a tarefa foi concluída.
- Resposta e status são canais distintos: `Carregando modelo`, `Pré-processando`, `Gerando resposta`, `Executando ferramenta X`, `Concluído` e `Falhou`.
- Exibir métricas de TTFT e tokens/s em tela técnica sem transformar a interface principal em painel de debug.
- Raciocínio do modelo (`<think>`) não deve ser confundido com status real de execução ou com ação efetivamente realizada.

## 6. Ciclo de vida, recursos e arquivos

- Importação via Storage Access Framework; validar magic/header GGUF, metadados de arquitetura, quantização e integridade.
- Guardar o modelo selecionado em preferência persistida; tentar carregá-lo automaticamente ao iniciar, com falha recuperável e sem nova seleção manual.
- Distribuir **APK sem o GGUF** por padrão; modelo obtido via importação ou download oficial com consentimento, progresso, retomada e SHA-256.
- Começar com `n_ctx=4096`; expor perfis `8192` e `16384` depois de qualificados em dispositivo real. O máximo teórico 256K não é meta inicial para celular.
- Carregamento/decodificação fora da main thread; nenhuma operação custosa no compositor UI.
- Estado explícito: `UNCONFIGURED -> VERIFYING -> LOADING -> READY -> PREFILL -> DECODING -> TOOL_CALL -> READY`; transições para `FAILED` e `CANCELLED`.
- Eventos do engine devem ter timeouts e códigos de erro; watchdog reporta ausência de progresso, não mascara falha como "interpretando" indefinidamente.
- Definir limites de memória do aparelho e tratamento de `OOM`, temperatura e ciclo de vida; fechar/liberar contexto no encerramento.
- Offline comprovado por modo avião em teste físico.

## 7. Design de UX

Quatro abas principais, com visual limpo:
1. **Chat** — bolhas, streaming, cancelar, anexar nota local futuramente; campo de digitação fixo.
2. **Atividade** — trilha verificável do turno e chamadas de ferramenta; execução real vs proposta do modelo.
3. **Modelo** — status, arquivo, importação, download, memória necessária estimada, configuração.
4. **Diagnóstico** — versão do motor, CPU/ABI, contexto, logs, TTFT, tokens/s e RAM.

Não mostrar ações fictícias nem pedir novamente configurações concluídas.

## 8. Fases, agentes especializados e entregáveis

| Fase | Responsável funcional | Atividades | Critério de saída |
|---|---|---|---|
| P0: Prova técnica | Agente Runtime | Fixar llama.cpp; obter GGUF; compilar Android arm64; JNI; gerar texto com template LFM3 | Teste real de uma pergunta gera tokens no aparelho |
| P1: APK de chat | Agente Android | Compose, importação, auto-load, streaming, cancelamento, persistência | APK instalável, modelo selecionado persistido, múltiplas rodadas sem loop |
| P2: Harness | Agente Orquestração | Parser XML, registry, resultado `tool`, timeouts, política de permissões | Chamadas a calculator e horário realizadas, com retorno rastreável |
| P3: Contexto | Agente Contexto | Orçamento de tokens, histórico, memória consentida, busca local | 10+ turnos sem esquecer instruções fixadas e sem repetir slots respondidos no teste |
| P4: Performance | Agente Benchmark | CPU/KleidiAI, parâmetros, GPU experimental, RAM/thermal | Relatório comparativo reproduzível; manter backend mais confiável |
| P5: Release | Agente QA/CI | Testes unitários/instrumentados, matriz de dispositivos, build GitHub Actions | APK Debug anexado ao CI e APK Release assinado quando houver chave segura |

**Revisor independente** cruza entregáveis de cada fase com os critérios, casos de falha e logs. Estes são papéis de trabalho planejados, não agentes autônomos já executados.

### Marco obrigatório: primeiro APK
O **primeiro APK** é liberado já em **P1**, antes da implementação integral do harness. Ele deve carregar o Q4_K_M e produzir respostas locais, com fallback de erro e logs.

## 9. Critérios de aceite verificáveis

**Teste de compatibilidade**
- [ ] Reconhecer e importar `LFM3-1.7B-Q4_K_M.gguf`.
- [ ] Rejeitar modelo incompatível e arquivo truncado, sem crash.
- [ ] Usar chat template nativo e obter saída não vazia.
- [ ] Fechar e abrir o app sem apontar o modelo novamente.

**Teste do runtime**
- [ ] Carregar e liberar modelo 3 vezes sem travar.
- [ ] 20 respostas consecutivas sem congelamento permanente da UI.
- [ ] Cancelar geração em progresso e responder à próxima mensagem.
- [ ] Em modo avião, conversar e executar ferramentas locais.
- [ ] Capturar TTFT, prefill/decode tokens/s, RAM máxima e erro final.

**Teste do harness**
- [ ] Chamar `calculator` e retornar resultado correto.
- [ ] Não relatar como executada uma ferramenta não chamada.
- [ ] Invalidar nome/argumentos de ferramenta desconhecida.
- [ ] Interromper loops de ferramentas no limite configurado.
- [ ] Tarefa que grava nota solicita confirmação.
- [ ] Não repetir pergunta cuja resposta já está no contexto disponível.

**Teste de distribuição**
- [ ] CI compila com dependências e versão de NDK fixadas.
- [ ] Unit tests, testes de parser/template e Android lint passam.
- [ ] Artefato `app-debug.apk` disponível no GitHub Actions.
- [ ] Testes físicos Android arm64 distinguidos dos testes em emulador.

Os limiares de desempenho serão definidos depois da primeira medição real, não inventados agora.

## 10. Riscos e mitigação

| Risco | Mitigação |
|---|---|
| Engine antigo gera saída incorreta | Pin >= llama.cpp v0.8.0, verificar compatibilidade da arquitetura e do chat template |
| Ausência aparente de resposta | Estados explícitos, streaming, watchdog, timeouts, logs de carregamento |
| Consumo excessivo de RAM pelo KV cache | n_ctx pequeno por padrão, monitorar memória, reduzir contexto ao identificar limite |
| Alucinação de ação | Executar apenas chamadas registradas, registrar eventos, confirmar side effects |
| XML parcial, truncado ou malformado | Parser incremental robusto, schema validation, limites, tentativa controlada de reparo |
| GPU incompatível | CPU ARM64 baseline garantido; acelerar apenas após teste |
| Modelo de ~1 GB torna APK inconveniente | APK leve, download/importação separados, checksum |
| Permissões ou licença inadequadas | Sem bundling de pesos na fase inicial, checagem de avisos/licença |

## 11. Estrutura proposta de repositório

```text
LFM3Local/
├── README.md
├── docs/
│   └── PLANEJAMENTO.md
├── android/
│   ├── app/
│   ├── core/
│   ├── inference/
│   ├── harness/
│   ├── data/
│   └── benchmark/
├── tests/
│   ├── fixtures/
│   └── harness-cases/
├── scripts/
│   ├── verify-model.*
│   └── benchmark-device.*
└── .github/workflows/
    ├── android-ci.yml
    └── android-release.yml
```

A estrutura final poderá consolidar módulos para evitar complexidade de build prematura.

## 12. Próxima ação concreta (P0)

1. Fixar commit de `ggml-org/llama.cpp` >= v0.8.0 e montar baseline `arm64-v8a`.
2. Criar projeto Kotlin/Compose e JNI com teste de geração em streaming.
3. Testar `LFM3-1.7B-Q4_K_M.gguf` real, incluindo metadados/templating e `<tool_call>`.
4. Configurar GitHub Actions para exportar `app-debug.apk`.
5. Executar smoke test em aparelho físico; publicar métricas e falhas.
6. Prosseguir para P1 apenas após satisfazer os critérios mínimos do motor.

**Não realizado nesta etapa:** implementação Android, compilação, teste de inferência ou publicação de APK.
