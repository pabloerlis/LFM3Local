# LFM3Local Native Beta v0.7.0 — contexto rápido

## Causa e correção

A reconstrução anterior de uma conversa fazia novamente o **prefill** de até 2.560 tokens com os pesos do LFM3-1.7B, mesmo após o usuário ter visitado aquela conversa durante a mesma sessão. A inferência nativa de um histórico longo consome CPU e pode levar muitos segundos no celular.

Na v0.7.0, o harness guarda até **dois estados KV por conversa** em RAM, utilizando APIs oficiais da revisão fixada do llama.cpp:

- `llama_state_seq_get_size`
- `llama_state_seq_get_data`
- `llama_state_seq_set_data`

O orçamento é de até **160 MiB para todos os snapshots** e no máximo **120 MiB por snapshot**. Se um estado exceder o orçamento ou não puder ser restaurado, o aplicativo reprocessa o histórico normalmente. Os estados são descartados ao liberar/recarregar o modelo, encerrar o processo ou mudar de versão do mecanismo.

O snapshot também inclui mensagens formatadas do motor, as posições de tokens e informações da sessão; seu identificador inclui chat, hash das mensagens e hash das configurações/skills. Nenhuma informação vai à rede.

### Outras melhorias

- Primeira conversa vazia aproveita o **prompt de sistema já processado** na abertura do aplicativo.
- Restauração fria limita o histórico ativo a aproximadamente **1.792 tokens**, deixando mais espaço para novas mensagens. Antes, até **2.560 tokens** eram reprocessados.
- As mensagens completas seguem no SQLite local. **Contexto do modelo ≠ histórico armazenado**.
- A interface exibe `Contexto: cache KV (RAM) · X ms`, `Contexto: histórico SQLite · X ms` ou `Contexto: sistema reutilizado · X ms`.

## Teste prático sugerido

1. Abra o **Native Beta v0.7.0** com o GGUF local carregado.
2. Abra a conversa A, faça pelo menos cinco perguntas e registre a latência indicada para o contexto quando houver.
3. Volte à lista e crie/abra a conversa B. Faça duas perguntas.
4. Volte à conversa A. **Se o snapshot couber na RAM**, a tela deve mostrar `cache KV (RAM)`, não `histórico SQLite`, e tempo menor que a restauração fria.
5. Repita A → B → A três vezes e compare os milissegundos.
6. Feche o processo e abra o app novamente. O cache RAM terá sido perdido; a restauração fria ainda deverá funcionar.
7. Continue uma conversa longa até a janela de contexto se esgotar. O app deve reprocessar apenas mensagens recentes e continuar, sem loop de tentativas.
8. Desligue a skill Notas locais, reabra uma conversa e confirme que não é possível obter notas com a skill desativada.
9. Interrompa uma geração e faça uma pergunta nova. O app deve reconstruir o contexto com dados persistidos, em vez de usar um KV potencialmente incompleto.

## Critérios de validação

| Verificação | Esperado |
|---|---|
| Reabrir chat ativo | Sem prefill |
| Reabrir chat com snapshot válido | Cache RAM e tempo inferior ao cold replay |
| Snapshot grande demais | Fallback normal, sem erro de memória |
| Editar mensagens ou alternar skills | Snapshot desatualizado não reutilizado |
| Reiniciar app | Histórico SQLite permanece; cache RAM não |
| Trocar de conversa | A IA não mistura informações de chats diferentes |

## Limites da medição

O valor em milissegundos registrado pelo aplicativo abrange acesso ao SQLite, cálculo de hashes, serialização/deserialização do cache, preparação do template e eventual prefill. Não é, isoladamente, o tempo de `llama_decode`.

A melhoria é **uma hipótese de desempenho para confirmar no hardware**. Builds CI e testes JUnit validam a lógica de identificação dos snapshots; sem rodar o GGUF no smartphone, não é possível declarar ganho de velocidade quantitativo nem garantia de cache hit.

## Compatibilidade do APK

O Android ID é `br.com.lfm3local.nativepreview`, igual ao Native Beta v0.6.1. Ele usa a assinatura Debug de teste guardada na CI. O aplicativo principal e o Skills Beta antigo têm IDs próprios e permanecem instalados. Se ocorrer incompatibilidade de assinatura, **não desinstale sem preservar os dados do Native Beta**.

## Validação v0.7.0 — 23/11/2026

- [GitHub Actions 45223427255](https://github.com/pabloerlis/LFM3Local/actions/runs/45223427255): **SUCCESS**.
- **38 testes JUnit** no projeto; `app:testDebugUnitTest` aprovado.
- `app:assembleDebug` aprovado (Kotlin/C++/JNI), incluindo 8 bibliotecas ARM64.
- Identificador Android **`br.com.lfm3local.nativepreview`** verificado no CI.
- APK extraído do artefato, ZIP e CRC íntegros; tamanho **68.012.755 bytes**, SHA-256 `a4d6740b244bf5b2e790eaa010d1346389b1e674a313c2fd7d237bb0d70f7c52`.
- **Chave Debug de teste restaurada do cache anterior com sucesso** (`lfm3local-nativepreview-debug-keystore-v1`, confirmado no log), permitindo atualização do Native Beta 0.6.1 sem alteração planejada da assinatura.
- O tempo de restauração e a proporção de acertos no cache **não foram medidos no dispositivo**; o ganho esperado deve ser validado por testes A → B → A no smartphone.
