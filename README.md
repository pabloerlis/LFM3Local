# LFM3Local — Android Harness MVP 0.6.1

Aplicativo **offline** dedicado ao **Liquid AI LFM3-1.7B-Q4_K_M.gguf**, com chat estilo mensageiro, histórico local e **harness inicial de ferramentas** via `llama.cpp`.


## Aviso de instalação da versão 0.6.1

A versão 0.6.1 usa um **pacote Android diferente**: `br.com.lfm3local.nativepreview`, identificado como **LFM3Local Native Beta**. Ela não substitui a versão anterior `br.com.lfm3local.preview`. Ambas podem coexistir e o banco de mensagens/notas antigo permanece intacto no outro aplicativo.

A v0.6.1 possui armazenamento próprio. Para testar com LFM3, importe o GGUF novamente nesta versão, com espaço para a cópia. Não desinstale seu aplicativo anterior para realizar o teste.

**Motivo:** o pipeline das versões 0.5/0.6 não recuperou a chave Debug anterior (confirmado nos logs do GitHub Actions), então atualizar diretamente poderia falhar pela diferença de assinaturas. A partir desta versão, o CI cria uma chave de teste no local esperado e a salva no cache do workflow para os builds experimentais seguintes. Chaves de Debug cacheadas **não garantem estabilidade permanente** e não substituem chave de release protegida em Secrets.

## Harness v0.6.0 — JSON Schemas nativos do LFM3

**Alteração testável:** o harness registra as ferramentas `calculator`, `get_current_datetime` e, quando habilitada, `search_local_notes`, diretamente no **chat template Jinja do GGUF**. É o mecanismo previsto pela Liquid AI para apresentar os JSON Schemas ao modelo, em vez de duplicá-los no prompt de sistema.

- A aplicação verifica se o GGUF suporta o template LFM3 com `<tools>`, `<additional_tools>` e `<tool_response>`, e se ele renderiza todos os nomes de ferramentas.
- Se compatível, mostra **Ferramentas: Template LFM3 nativo** e usa o Jinja no sistema, nas mensagens e nas respostas de ferramentas.
- Se incompatível, mostra **Ferramentas: Compatibilidade (prompt)** e conserva o mecanismo anterior sem falhar no carregamento.
- As duas opções preservam a mesma lista de permissões/skills, registro de atividades e limite de duas ferramentas por turno.
- Quando a skill Notas locais está desativada, seu schema **não é incluído** no template e a execução continua negada.
- Testes unitários reforçados para evitar duplicar schemas no prompt e garantir que a skill só apareça quando habilitada.

**Teste prático v0.6.0:** confira em Conversas o indicador **Ferramentas: ...**. Peça `Use calculator para calcular (17*3)+2` e, com Notas locais ligada, consulte uma nota criada por você. Em ambos, verifique a atividade do harness e a resposta final. Desative a skill, retorne à conversa e confirme que o acesso não é concedido.

**Limitação:** compilação e testes JVM não provam a compatibilidade do seu GGUF específico; é necessário confirmar o status no aplicativo. O modo nativo usa um formato incremental da conversa; a ferramenta será enviada pelo papel `tool`, e a captura/representação de chamadas ainda é validada pelo parser do Android.

## Harness v0.5.0 — primeira skill independente com dados locais

A versão 0.5 acrescenta **Skills → Notas locais**, com criação, edição e exclusão de notas armazenadas em SQLite privado no aparelho.

- **Skill desativada por padrão**. A chave de ativação permite explicitamente que a IA consulte suas notas pelo tool `search_local_notes(query)`.
- **Controle de acesso real:** quando a skill está desativada, seu código não fica no catálogo de ferramentas autorizadas e nenhuma nota é lida por uma solicitação do modelo.
- O modelo **somente consulta**: jamais altera ou apaga notas; essas operações são feitas exclusivamente pela interface.
- Notas limitadas a 250 registros, com título de até 80 caracteres e texto de até 2000. A busca retorna até três trechos relevantes; não existe acesso à rede.
- Banco migrado de v2 para **v3**, preservando conversas e registros de ferramentas. Dados permanecem apenas no dispositivo.
- **Testes adicionais:** ativação/desativação, busca, limites, injeção de instruções em notas tratada como conteúdo não confiável, limite conjunto entre ferramentas internas e skills, ida e volta do resultado por motor simulado.

**Teste prático:** vá em Skills, crie uma nota `Revisão da bicicleta` com o conteúdo `Verificar a pressão dos pneus no sábado.`, ative **Notas locais**, volte para uma conversa e pergunte: `Consulte minhas notas locais. O que anotei sobre a bicicleta?`. Confira em **⋮ → Atividade do harness** se o `search_local_notes` foi executado. Desative a skill e repita para testar o bloqueio.

**Limitação:** a execução com o GGUF real em smartphone precisa ser confirmada. O LFM3 pode não escolher chamar a ferramenta em todos os turnos; o acesso aos dados, entretanto, permanece restrito ao catálogo habilitado.

## Harness v0.4.0 — confiabilidade e observabilidade

A versão 0.4 mantém o chat, o histórico SQLite e as ferramentas da 0.3, e acrescenta:

- **Recuperação automática da janela de contexto:** o motor interrompe de forma segura um prompt que não cabe, reconstrói os últimos turnos do histórico local e tenta a mesma pergunta mais uma vez. Não fica em loop infinito. Conversas antigas permanecem armazenadas, mas turnos mais antigos podem sair dos 4096 tokens ativos.
- **Correção de estados nativos:** erros de preparo de prompt passam a ser sinalizados; não deixam o motor silenciosamente preso em "Processando prompt". Falhas recuperáveis e fatais são distintas.
- **Cancelamento seguro:** depois de interromper uma geração, o aplicativo reconstrói o contexto antes da próxima mensagem.
- **Recuperação manual do modelo:** botão "Recarregar" em caso de erro; limpeza nativa protegida quando o carregamento falha parcialmente.
- **Diagnósticos visíveis:** estados reais do motor (processando prompt, decodificando tokens, carregando GGUF); aviso quando não há progresso; tempo até o primeiro texto, taxa de caracteres/s e quantidade de ferramentas executadas.
- **Testes de confiabilidade:** reconstrução permitida uma só vez, propagação de erros, medição monotônica, mantendo os testes anteriores do parser, da calculadora e do coordenador.

**Limite:** a taxa em caracteres/s não deve ser confundida com tokens/s; não foi feita medição de desempenho físico nem execução com o GGUF real neste ambiente. Um aviso de ausência de progresso não comprova travamento.

## Harness 0.3.0 — primeira etapa

O modelo pode chamar ferramentas locais usando o formato XML do LFM3: `<tool_call>...`. O Android valida os parâmetros, executa a função permitida e devolve o resultado ao modelo como uma mensagem de papel `tool`, sem simular a execução apenas no texto.

- `calculator(expression)`: expressão aritmética com `+ - * /` e parênteses, parser limitado e sem eval/shell.
- `get_current_datetime()`: data e hora reais do aparelho com fuso.
- Limite de **duas chamadas de ferramenta por turno**, bloqueio de chamadas desconhecidas e repetidas.
- Auditoria em SQLite: dentro do chat, toque em **⋮ → Atividade do harness** para conferir o que foi realmente executado ou rejeitado.
- **Testes automatizados** de parser, cálculo e continuidade com motor simulado executados antes de cada compilação.
- Leia [arquitetura do harness e roteiro de teste no aparelho](docs/HARNESS_V030.md).

**Observação:** o comportamento de chamadas emitidas pelo GGUF ainda exige validação num smartphone; o conjunto de ferramentas é apresentado ao modelo no prompt de sistema nesta versão. Não há download de rede, execução de scripts, manipulação de arquivos, skills de terceiros ou acesso ao sistema operacional.

## Instalação de teste sem apagar a versão anterior

A versão 0.5.0 foi distribuída como **LFM3Local Skills Beta** com identificador Android
`br.com.lfm3local.preview`. Ela pode ser instalada **ao lado do LFM3Local antigo**
(`br.com.lfm3local`), sem exigir desinstalação.

**Atenção:** o Preview possui armazenamento próprio. Suas conversas, notas e GGUF **não**
são importados automaticamente do app antigo. Para testar o modelo, importe o GGUF novamente
no Preview e reserve espaço de armazenamento para a cópia. O histórico do aplicativo
antigo permanece intacto.

O pipeline utiliza cache de chave de assinatura **Debug**, exclusivamente para builds
experimentais do Preview. Não substitui uma assinatura de release segura e permanente.

## Instalar o APK

- Android **11 ou mais recente** (API 30+), processador ARM64.
- [Build do harness 0.3.0 no GitHub Actions](https://github.com/pabloerlis/LFM3Local/actions/workflows/android-apk.yml). Abra a execução aprovada mais recente, baixe o ZIP **LFM3Local-Harness-v0.6.1-nativepreview-APK** e extraia `app-debug.apk`.
- O modelo é **baixado separadamente**: [LFM3-1.7B-GGUF — Q4_K_M](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/tree/main) (~1,05 GB). Não está dentro do APK.
- Ao instalar pela primeira vez, pressione **Modelo** e importe o GGUF. O app faz uma cópia para seu armazenamento privado e tenta carregá-lo automaticamente na próxima abertura.

> **Importante para quem instalou o antigo APK Debug 0.1.0:** builds feitos em runners diferentes do GitHub podem ser assinados com chaves de depuração diferentes. Se a atualização falhar por incompatibilidade de assinatura, será necessário desinstalar o APK anterior antes de instalar este. **Desinstalar ou limpar dados remove o arquivo GGUF importado e o histórico local.** Guarde uma cópia do GGUF original fora do aplicativo. O v0.1.0 não armazenava conversas em banco, portanto não há histórico anterior a migrar.

## Interface e histórico do MVP 0.2.0 preservados na versão 0.3.0

- **Tela de conversas**, com título, trecho da última mensagem, horário, busca por título/trecho e botão para criar conversa.
- **Chat estilo mensageiro**, com bolhas distintas para usuário e LFM3, horário e campo de digitação fixo.
- **Histórico de conversas local**, gravado em SQLite no armazenamento privado do Android; continua disponível ao fechar e abrir o aplicativo.
- **Múltiplas conversas** independentes, renomeação e exclusão com confirmação.
- **Nome automático** do chat a partir da primeira mensagem, editável.
- **Reconstrução do contexto** pelo template LFM3 ao reabrir uma conversa: mensagens anteriores são reenviadas para o contexto do motor, respeitando a janela de 4.096 tokens. Mensagens mais antigas podem sair do contexto de inferência, mas continuam visíveis no histórico salvo.
- **Streaming de tokens**, cancelamento da geração e recuperação de respostas incompletas depois de encerramento inesperado.
- Uso offline; sem sincronização e sem permissões de internet necessárias ao chat.

### Limitações conhecidas

- Sem teste de inferência com o GGUF real em aparelho físico até o momento: a compilação e os testes com motor simulado não comprovam velocidade ou qualidade de geração, nem compatibilidade prática do fluxo de ferramentas.
- Um texto longo pode exceder o contexto máximo de 4.096 tokens; o motor mantém os turnos recentes ao restaurar uma conversa. O histórico no banco não tem esse limite.
- Resposta padrão limitada a até 512 tokens por turno. Sem anexos, voz, skills ou ferramentas nesta versão.
- O histórico está armazenado **somente no aparelho**. Não existe backup, exportação, sincronização ou recuperação após apagar os dados do aplicativo.
- Na mudança de conversa, o modelo precisa reconstruir o contexto local e isso pode causar uma pausa. Não há instâncias simultâneas do modelo.

## Arquitetura resumida

- `android/app/` — Kotlin, Jetpack Compose, interface e persistência SQLite (`ChatStore`).
- `android/lib/` — biblioteca JNI e motor nativo.
- `scripts/bootstrap-llama.sh` — checkout fixo de `llama.cpp` no commit `4663f9ca8a97c525ba3d248711fa2b52050920ec`.
- `.github/workflows/android-apk.yml` — build Android automatizado.

Para compilar localmente, com SDK/NDK configurados:

```bash
bash scripts/bootstrap-llama.sh
gradle -p android :app:assembleDebug
```

Consulte [planejamento técnico](docs/PLANEJAMENTO.md) e [plataforma de skills futura](docs/SKILLS.md). A recomendação de hinos permanece uma skill futura, não uma regra codificada no motor principal.

## Licenças

O runtime e o exemplo de integração foram adaptados do [llama.cpp](https://github.com/ggml-org/llama.cpp) sob [licença MIT](third_party/llama.cpp-LICENSE). O modelo GGUF tem licença independente fornecida pela Liquid AI.

## Diagnóstico

Registre a mensagem de status ao ocorrer um erro. Para logs de depuração: `adb logcat -s LFM3Local llama.cpp ai-chat`. Inclua versão do aparelho, variante do GGUF e sequência que reproduz a falha.

## Resultado verificável do build v0.4.0 (23/11/2026)

- [GitHub Actions 45203997728](https://github.com/pabloerlis/LFM3Local/actions/runs/45203997728): **success**.
- **19 testes JUnit declarados** no projeto; tarefa `app:testDebugUnitTest` executada com sucesso.
- `app:assembleDebug` concluída; `lib/arm64-v8a/libai-chat.so` verificada no APK.
- Arquivo `app-debug.apk`: **67.658.783 bytes**; SHA-256 `0508249cc4ebd861fb38ed3aca1c8bb7055a7caaba8a914d9dd90db58e4c05aa`.
- A análise ZIP do APK não encontrou erros de CRC. O binário **não foi testado com o modelo real** em um dispositivo físico.

## Build Preview v0.5.0 — evidências de CI (23/11/2026)

- GitHub Actions: [execução 45209048089](https://github.com/pabloerlis/LFM3Local/actions/runs/45209048089).
- Suíte `app:testDebugUnitTest`: **aprovada**. O projeto contém 29 métodos de teste JUnit `@Test`.
- Compilação nativa Android ARM64 `app:assembleDebug`: **aprovada**.
- Biblioteca `libai-chat.so` e identificador `br.com.lfm3local.preview` conferidos na CI.
- O APK foi extraído do artefato e testado como ZIP: **nenhum erro de integridade/CRC**.
- Tamanho do APK: **67.768.795 bytes**.
- SHA-256: `28cc964ff277ba54b3ef981c8e0fdd5f01a145d895b8501418775463a5a86dcf`.
- **Não** houve execução com o GGUF real em um smartphone na infraestrutura de build.
