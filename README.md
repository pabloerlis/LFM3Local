# LFM3Local

Aplicativo Android nativo de inferência **offline** e **harness especializado no Liquid AI LFM3-1.7B**, inicialmente na quantização **Q4_K_M GGUF**.

## Status

**Fase de planejamento (23/11/2026).** O projeto Android, o motor nativo e o APK ainda não foram implementados.

- [Planejamento técnico, arquitetura, fases e critérios de aceite](docs/PLANEJAMENTO.md)
- [Checkpoint oficial Q4_K_M GGUF](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF)
- [Documentação oficial do LFM3](https://huggingface.co/LiquidAI/LFM3-1.7B)
- [llama.cpp para Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)

## Diretriz

Primeiro entregar um APK que carregue o **LFM3-1.7B-Q4_K_M.gguf** e gere respostas locais com streaming, cancelamento, observabilidade e reabertura automática do modelo; depois evoluir o harness de ferramentas e memória.

O arquivo do modelo (~1,05 GB) não será incorporado ao APK inicial. Será selecionado pelo usuário ou baixado separadamente, com validação de integridade.

> Extensão correta: `.gguf`, não `.ggfu`.
