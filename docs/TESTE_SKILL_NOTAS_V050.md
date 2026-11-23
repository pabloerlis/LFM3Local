# LFM3Local v0.5.0 — validação da skill Notas locais

## Alteração demonstrável

A tela **Skills** agora permite criar, editar e excluir notas no banco SQLite privado. A skill de consulta é **desativada por padrão** e só pode pesquisar notas depois de ser ativada explicitamente.

**Ferramenta adicionada:** `search_local_notes(query)`. Ela é somente leitura, retorna no máximo três registros e trechos limitados. Nenhuma skill executa comandos, instala código ou acessa a rede.

## Roteiro em aparelho

1. Instale o APK v0.5.0 em Android ARM64.
2. Na lista de conversas, toque em **Skills**.
3. Crie uma nota intitulada **Bicicleta** com o texto **Verificar a pressão dos pneus no sábado**.
4. Ative o controle **Notas locais**. O próprio botão constitui autorização para o modelo consultar suas notas.
5. Abra uma conversa com LFM3 carregado e pergunte **Consulte minhas notas locais. O que anotei sobre a bicicleta? Use search_local_notes.**
6. Abra o menu da conversa **⋮ → Atividade do harness**. Verifique se foi registrada a chamada de `search_local_notes`, seus argumentos e o resultado.
7. Desative a skill e repita o pedido. Não deve haver acesso a notas desativadas. O modelo pode deixar de chamar a ferramenta, mas nunca deverá receber notas sem autorização.
8. Edite o texto da nota, reative a skill e peça nova consulta. O resultado deve refletir o novo texto.
9. Feche e reabra o aplicativo. A nota, o estado de habilitação e as conversas devem persistir.
10. Teste novamente em modo avião.

## Casos automatizados

- Busca de notas por palavra-chave, ordenação determinística e ausência de resultados.
- Bloqueio do acesso com a skill desabilitada.
- Limites de tamanho e de quantidade de trechos devolvidos ao modelo.
- Não execução de comandos escritos nas notas (notas são dados, nunca código).
- Ciclo `LFM3 (simulado) → search_local_notes → resultado → LFM3`.
- Limite compartilhado de ferramentas internas e skills.

**Atenção:** os testes JVM não executam o LFM3 real e não verificam a tela em dispositivo Android físico. O comportamento com o GGUF deve ser inspecionado no aplicativo.

## Limitações

- Não há backup/exportação das notas nesta versão.
- Desinstalar o APK apaga o SQLite local; a atualização precisa preservar a assinatura digital.
- Apenas o modelo LFM3-1.7B GGUF selecionado pelo usuário é suportado no objetivo do MVP.
- O mecanismo de seleção de ferramentas ainda recebe as definições por prompt textual com schemas. Suporte nativo integral do parâmetro `tools` no template LFM3 é uma próxima etapa de melhoria.
