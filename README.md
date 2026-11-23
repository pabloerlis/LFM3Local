# LFM3Local — Android MVP 0.2.0

Aplicativo **offline** dedicado ao **Liquid AI LFM3-1.7B-Q4_K_M.gguf**, com interface de mensagens inspirada em aplicativos de conversação, histórico local e inferência via `llama.cpp`.

## Instalar o APK

- Android **11 ou mais recente** (API 30+), processador ARM64.
- [Builds e artefatos de APK no GitHub Actions](https://github.com/pabloerlis/LFM3Local/actions/workflows/android-apk.yml). Abra a execução aprovada mais recente, baixe o ZIP **LFM3Local-MVP-debug-APK** e extraia `app-debug.apk`.
- O modelo é **baixado separadamente**: [LFM3-1.7B-GGUF — Q4_K_M](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/tree/main) (~1,05 GB). Não está dentro do APK.
- Ao instalar pela primeira vez, pressione **Modelo** e importe o GGUF. O app faz uma cópia para seu armazenamento privado e tenta carregá-lo automaticamente na próxima abertura.

> **Importante para quem instalou o antigo APK Debug 0.1.0:** builds feitos em runners diferentes do GitHub podem ser assinados com chaves de depuração diferentes. Se a atualização falhar por incompatibilidade de assinatura, será necessário desinstalar o APK anterior antes de instalar este. **Desinstalar ou limpar dados remove o arquivo GGUF importado e o histórico local.** Guarde uma cópia do GGUF original fora do aplicativo. O v0.1.0 não armazenava conversas em banco, portanto não há histórico anterior a migrar.

## O que há no MVP 0.2.0

- **Tela de conversas**, com título, trecho da última mensagem, horário, busca por título/trecho e botão para criar conversa.
- **Chat estilo mensageiro**, com bolhas distintas para usuário e LFM3, horário e campo de digitação fixo.
- **Histórico de conversas local**, gravado em SQLite no armazenamento privado do Android; continua disponível ao fechar e abrir o aplicativo.
- **Múltiplas conversas** independentes, renomeação e exclusão com confirmação.
- **Nome automático** do chat a partir da primeira mensagem, editável.
- **Reconstrução do contexto** pelo template LFM3 ao reabrir uma conversa: mensagens anteriores são reenviadas para o contexto do motor, respeitando a janela de 4.096 tokens. Mensagens mais antigas podem sair do contexto de inferência, mas continuam visíveis no histórico salvo.
- **Streaming de tokens**, cancelamento da geração e recuperação de respostas incompletas depois de encerramento inesperado.
- Uso offline; sem sincronização e sem permissões de internet necessárias ao chat.

### Limitações conhecidas

- Sem teste de inferência com o GGUF real em aparelho físico até o momento: a compilação não comprova velocidade ou qualidade de geração.
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
