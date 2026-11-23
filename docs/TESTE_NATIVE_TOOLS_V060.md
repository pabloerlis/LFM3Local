# LFM3Local Harness v0.6.0 — teste A/B de schemas nativos

## Objetivo técnico

Verificar, em dispositivo Android real, se registrar schemas pelo chat template Jinja do LFM3 melhora a execução de ferramentas em relação às descrições em texto da versão anterior.

**Referência oficial:** [Liquid AI — LFM3-1.7B, Tool Calling](https://huggingface.co/LiquidAI/LFM3-1.7B/blob/main/README.md#tool-calling) e [chat_template.jinja](https://huggingface.co/LiquidAI/LFM3-1.7B/blob/main/chat_template.jinja).

O APK tem o identificador `br.com.lfm3local.preview` e instala ao lado do aplicativo principal. Ele mantém seu próprio SQLite/arquivo GGUF, sem migrar dados do aplicativo principal.

## Estados exibidos

- **Template LFM3 nativo:** o GGUF possui o template com tags necessárias, o aplicativo validou a renderização das funções e habilitou Jinja para prompts e respostas de ferramentas.
- **Compatibilidade (GGUF sem suporte):** não foi possível validar o template nativo. O app continua com o mecanismo anterior.
- **Compatibilidade (manual):** o usuário desligou a opção experimental em Skills; nenhum schema nativo é enviado.
- **Reconfiguração pendente:** após mudar uma opção, abra ou retorne a uma conversa para reconstruir o contexto com a nova configuração.

## Procedimento mínimo A/B (realizar no mesmo dispositivo/modelo)

1. Abra o **LFM3Local Skills Beta v0.6.0**, com o GGUF carregado.
2. Em **Skills**, deixe **Ferramentas nativas (experimental)** ativada. Se aparecer `Template LFM3 nativo`, crie **nova conversa**.
3. Pergunte `Calcule (17*3)+2 usando a ferramenta calculator; depois explique o resultado.`.
4. Em **⋮ → Atividade do harness**, confira se ocorreu chamada `calculator`, parâmetros `(17*3)+2`, e resultado `53`.
5. Crie uma nota **Bicicleta** com conteúdo **Verificar pressão dos pneus no sábado**; ative **Notas locais**.
6. Abra outra conversa e solicite `Consulte minhas notas locais usando search_local_notes: o que anotei sobre a bicicleta?`. Confira a chamada e o conteúdo retornado.
7. Volte a **Skills** e desligue **Ferramentas nativas (experimental)**. Crie um novo chat e repita os passos 3 e 6. O status deve ser `Compatibilidade (manual)`.
8. Compare os resultados: número de chamadas corretas, respostas completas, erros, tempo para o primeiro texto e repetição de perguntas. Testar no mínimo **cinco vezes por modo** é mais informativo do que uma única resposta.
9. Com **Notas locais desativada**, a tentativa de usar `search_local_notes` não deve ler dados, em nenhum modo.
10. Teste também um chat sem ferramentas, para procurar regressões de fluidez.

## Critérios

| Propriedade | Esperado |
|---|---|
| A interface não trava | Enviar e parar continuam funcionais |
| Carregamento GGUF | App identifica o modo real em uso |
| Exatidão da calculadora | O host calcula 53 e grava execução |
| Permissionamento da skill | Notas não são lidas quando a skill está desligada |
| Reversibilidade | Alternar Jinja/compatibilidade não exige reinstalação |
| Histórico persistente | Conversas e notas permanecem no SQLite próprio do Preview |

## Testes automatizados e limites

O pipeline roda Kotlin/JUnit, compila o motor C++ e inspeciona as bibliotecas ARM64. Os testes Kotlin verificam os prompts com/sem schemas redundantes, a lista de permissões e o fluxo de ferramentas com um backend simulado.

**Não verificado sem acesso a smartphone/GGUF:** fluidez real, compatibilidade da instância Jinja com seu arquivo específico e se o modelo segue a estrutura correta de tool calls após várias rodadas. A fallback automática não garante que toda divergência de prefixo durante inferência seja recuperável; se houver erro no modo experimental, desligue a opção e registre a mensagem exibida no aplicativo.
