# LFM3Local — Android MVP

Assistente de IA **offline**, Android ARM64, dedicado ao **Liquid AI LFM3-1.7B-Q4_K_M.gguf**, com interface de chat, geração em streaming e cancelamento.

## Download do APK

**Build Debug v0.1.0**, compilado com sucesso em 23/11/2026:
- [Execução aprovada do GitHub Actions e artefato APK](https://github.com/pabloerlis/LFM3Local/actions/runs/45190108294)
- Na execução, abra **Artifacts** → **LFM3Local-MVP-debug-APK**. Baixe o ZIP e extraia `app-debug.apk`.
- APK: Android **11+ (API 30)**, arquitetura **ARM64 (arm64-v8a)**, ~67,4 MB.
- O build automatizado confirma a presença de `libai-chat.so` e das bibliotecas `libllama.so` / `libggml.so`. **Não houve teste de inferência com pesos reais em dispositivo físico; é um MVP experimental.**

## Instalação e primeiros passos

1. Instale o APK no smartphone Android ARM64.
2. Baixe o modelo oficial [LFM3-1.7B-GGUF — Q4_K_M](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/tree/main) (~1,05 GB).
3. Ao abrir o app, escolha **Importar arquivo GGUF** e selecione o modelo.
4. O arquivo será copiado para armazenamento privado do aplicativo, portanto reserve espaço livre suficiente.
5. Após `Modelo pronto`, converse normalmente. Use **Parar** para interromper a resposta e **Novo chat** para reiniciar o contexto.
6. Em aberturas posteriores, o app tenta carregar automaticamente o arquivo importado.

**Limitações da versão 0.1.0:** apenas texto; contexto de 4096 tokens; saída limitada inicialmente a 512 tokens por turno; sem skills, ferramentas, histórico persistente nem download interno de pesos. As mensagens anteriores permanecem no contexto enquanto a sessão estiver aberta e couberem no limite. O aparelho deve ter memória suficiente para carregar o modelo; desempenho e estabilidade ainda precisam de validação física.

## Código-fonte e build

```bash
bash scripts/bootstrap-llama.sh
gradle -p android :app:assembleDebug
```

O script fixa o `llama.cpp` no commit `4663f9ca8a97c525ba3d248711fa2b52050920ec`. A compilação automatizada está em [android-apk.yml](.github/workflows/android-apk.yml). Versões de Android SDK, NDK e Gradle estão definidas no projeto e no workflow.

## Arquitetura

- Kotlin / Jetpack Compose: interface e gerenciamento do GGUF.
- JNI / C++ / llama.cpp: inferência local com template de conversa do modelo.
- Posteriormente: harness extensível por **skills**, incluindo uma futura skill de recomendação de hinos, sem incorporar o domínio de hinos ao núcleo.

Leia [Planejamento](docs/PLANEJAMENTO.md) e [Skills](docs/SKILLS.md).

## Licenças e proveniência

O motor e a integração JNI foram adaptados do [exemplo Android de llama.cpp](https://github.com/ggml-org/llama.cpp/tree/4663f9ca8a97c525ba3d248711fa2b52050920ec/examples/llama.android), distribuído conforme [licença MIT](third_party/llama.cpp-LICENSE).

Os pesos do modelo não estão incluídos no APK; a licença da Liquid AI é independente e deve ser consultada no repositório do modelo.

## Feedback do MVP

Para investigar travamentos: anote o estado exibido, envie a mensagem usada, informe se a falha ocorreu ao carregar ou gerar e exporte o **Logcat** pelo Android Studio (`adb logcat -s LFM3Local llama.cpp ai-chat`) quando disponível.
